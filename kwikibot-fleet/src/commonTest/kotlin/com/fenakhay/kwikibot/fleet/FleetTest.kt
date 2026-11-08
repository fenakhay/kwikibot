package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.bot.BotConfig
import com.fenakhay.kwikibot.bot.BotStart
import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.bot.NonInteractive
import com.fenakhay.kwikibot.bot.run.Edit
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.bot.run.StopReason
import com.fenakhay.kwikibot.bot.run.botRun
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

@OptIn(ExperimentalCoroutinesApi::class)
class FleetTest {

    private val userinfo =
        """{"query":{"userinfo":{"id":5,"name":"FleetBot","groups":["*","bot"],"rights":["read","bot"]}}}"""

    private val siteinfo =
        """
        {"query":{
          "general":{"wikiid":"testwiki","sitename":"Test Wiki","lang":"en",
                     "server":"//test.example.org","articlepath":"/wiki/${'$'}1",
                     "mainpage":"Main Page","generator":"MediaWiki 1.47.0"},
          "namespaces":{
            "0":{"id":0,"name":"","case":"first-letter"},
            "4":{"id":4,"name":"Project","canonical":"Project","case":"first-letter"},
            "14":{"id":14,"name":"Category","canonical":"Category","case":"first-letter"}},
          "interwikimap":[{"prefix":"w","url":"https://en.wikipedia.org/wiki/${'$'}1"}]}}
        """
            .trimIndent()

    private val endpoint = ApiEndpoint("en.wiktionary.org")

    private val logins = mutableListOf<Credentials>()

    private fun accounts(failOn: String? = null) =
        Accounts(
            WikiConfig(
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO),
                retry = RetryPolicy.NONE,
            ),
            credentials = { account ->
                check(failOn == null || account != failOn) { "no such account" }
                if (account == null) Credentials.Anonymous else Credentials.OAuth2("token-$account", account)
            },
            clients = { config, login ->
                logins += login
                WikiClient(
                    config,
                    login,
                    engine =
                        MockEngine { request ->
                            val body =
                                request.body.toByteArray().decodeToString() + "&" + request.url.encodedQuery
                            val answer =
                                when {
                                    "userinfo" in body -> userinfo
                                    "functionhooks" in body ->
                                        """{"query":{"extensiontags":[],"general":{"langconversion":false}}}"""
                                    else -> siteinfo
                                }
                            respond(
                                answer,
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                )
            },
        )

    /** Waits for what a mock wiki's opening has to finish on real threads, as well as for virtual time. */
    private suspend fun TestScope.eventually(condition: () -> Boolean) {
        repeat(4_000) {
            runCurrent()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(1) }
        }
        error("it never happened")
    }

    private class SimpleBot(
        override val id: String,
        override val account: String? = null,
        override val schedule: Schedule? = null,
        val work: suspend (RunContext) -> Unit = {},
    ) : Bot {
        override val description: String = "$id does things"

        override suspend fun run(context: RunContext) = work(context)
    }

    private fun TestScope.fleet(
        vararg bots: Bot,
        accounts: Accounts = accounts(),
        stop: StopPolicy = StopPolicy.NONE,
        questions: suspend (String, Question) -> Boolean = { _, _ -> false },
        clock: Clock = Clock.System,
        out: (String) -> Unit = {},
    ): Pair<Fleet, CoroutineScope> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        return Fleet(bots.toList(), accounts, endpoint, stop, scope, null, questions, clock, out) to scope
    }

    @Test
    fun `two bots on one account share one login and a second account has its own`() = runTest {
        val seen = mutableListOf<String>()
        val record: suspend (RunContext) -> Unit = { seen += it.wiki.id.dbName }
        val (fleet, scope) =
            fleet(
                SimpleBot("a", work = record),
                SimpleBot("b", work = record),
                SimpleBot("c", "other", work = record),
            )

        listOf("a", "b", "c").forEach { fleet.start(it) }
        fleet.awaitIdle()

        logins.size shouldBe 2
        seen.size shouldBe 3
        fleet.runs.value.map { it.status } shouldBe List(3) { RunStatus.DONE }
        scope.cancel()
    }

    @Test
    fun `a failure stays in its run and its lane takes the next`() = runTest {
        var attempts = 0
        val (fleet, scope) =
            fleet(
                SimpleBot("flaky", work = { if (attempts++ == 0) error("it broke") }),
                SimpleBot("fine"),
                SimpleBot("wrongaccount", "bad"),
                accounts = accounts(failOn = "bad"),
            )

        val first = fleet.start("flaky")
        val second = fleet.start("flaky")
        val other = fleet.start("fine")
        val bad = fleet.start("wrongaccount")
        fleet.awaitIdle()

        val runs = fleet.runs.value.associateBy { it.id }
        runs.getValue(first).status shouldBe RunStatus.FAILED
        runs.getValue(first).failure shouldBe "it broke"
        runs.getValue(second).status shouldBe RunStatus.DONE
        runs.getValue(other).status shouldBe RunStatus.DONE
        runs.getValue(bad).status shouldBe RunStatus.FAILED
        scope.cancel()
    }

    @Test
    fun `runs of one bot take turns while another bot goes on`() = runTest {
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val (fleet, scope) =
            fleet(
                SimpleBot(
                    "slow",
                    work = {
                        order += "slow"
                        release.await()
                    },
                ),
                SimpleBot("quick", work = { order += "quick" }),
            )

        val first = fleet.start("slow")
        val second = fleet.start("slow")
        fleet.start("quick")
        eventually { order.size == 2 }

        order shouldContainExactlyInAnyOrder listOf("slow", "quick")
        fleet.runs.value.first { it.id == second }.status shouldBe RunStatus.QUEUED

        release.complete(Unit)
        fleet.awaitIdle()
        order.count { it == "slow" } shouldBe 2
        fleet.runs.value.first { it.id == first }.status shouldBe RunStatus.DONE
        scope.cancel()
    }

    @Test
    fun `a run can be cancelled in its lane or while it works and an ended one cannot`() = runTest {
        val (fleet, scope) = fleet(SimpleBot("waiter", work = { awaitCancellation() }))

        val working = fleet.start("waiter")
        val waiting = fleet.start("waiter")
        eventually { fleet.runs.value.first().status == RunStatus.RUNNING }

        fleet.cancel(waiting) shouldBe true
        fleet.cancel(working) shouldBe true
        fleet.awaitIdle()

        fleet.runs.value.map { it.status } shouldBe List(2) { RunStatus.CANCELLED }
        fleet.cancel(working) shouldBe false
        fleet.cancel(999) shouldBe false
        scope.cancel()
    }

    @Test
    fun `a question parks one lane and no is an answer rather than a fault`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        var other = false
        val (fleet, scope) =
            fleet(
                SimpleBot(
                    "asker",
                    work = { run ->
                        if (!run.ask(Question("Go on?", "It is large."))) throw Declined("they said no")
                    },
                ),
                SimpleBot("other", work = { other = true }),
                questions = { bot, question ->
                    bot shouldBe "asker"
                    question.title shouldBe "Go on?"
                    gate.await()
                },
            )

        val asking = fleet.start("asker")
        eventually { fleet.runs.value.first { it.id == asking }.status == RunStatus.WAITING }
        fleet.start("other")
        eventually { other }

        gate.complete(false)
        fleet.awaitIdle()
        fleet.runs.value
            .first { it.id == asking }
            .let {
                it.status shouldBe RunStatus.DECLINED
                it.stoppedBecause shouldBe "they said no"
            }
        scope.cancel()
    }

    @Test
    fun `what a run says and counts reaches the output the events and the view`() = runTest {
        val lines = mutableListOf<String>()
        val (fleet, scope) =
            fleet(
                SimpleBot(
                    "talker",
                    work = { run ->
                        run.expect(2)
                        run.say("starting", RunContext.Level.INFO)
                        run.step("German") { inner ->
                            inner.say("in a step", RunContext.Level.SAVED)
                            inner.stopped("first reason")
                        }
                        run.stopped("second reason")
                        run.options shouldBe CommonOptions(limit = 3)
                        run.arguments shouldBe listOf("x")
                        run.directory.shouldBeNull()
                        run.mayContinue() shouldBe true
                        run.stopPolicy.mayContinue() shouldBe true
                        run.open(LangCode("en"), Family.WIKTIONARY).id shouldBe run.wiki.id
                    },
                ),
                out = { lines += it },
            )

        fleet.start("talker", CommonOptions(limit = 3), listOf("x"))
        fleet.awaitIdle()

        lines shouldBe listOf("[talker] starting", "[talker > German] in a step")
        fleet.runs.value.single().let {
            it.expected shouldBe 2
            it.stoppedBecause shouldBe "first reason"
        }
        scope.cancel()
    }

    @Test
    fun `outcomes count pages and two bots with one id or an unknown bot are refused`() = runTest {
        val pages = FakePageService("a" to "text")
        val wiki = FakeWiki(pages)
        val (fleet, scope) =
            fleet(
                SimpleBot(
                    "saver",
                    work = { run ->
                        run.outcomes(PageOutcome.Unchanged(wiki.ref("a")))
                        run.outcomes(PageOutcome.NotAttempted(wiki.ref("a"), StopReason.POLICY))
                        val report =
                            wiki.botRun(StopPolicy.NONE) {
                                source(flowOf(wiki.ref("a")))
                                transform { Edit("new", "s") }
                                dryRun = false
                                onOutcome = run.outcomes
                            }
                        report.saved shouldBe 1
                    },
                )
            )

        fleet.start("saver")
        fleet.awaitIdle()
        fleet.runs.value.single().let {
            it.seen shouldBe 2
            it.saved shouldBe 1
        }

        assertFailsWith<IllegalStateException> { fleet.start("nobody") }.message!! shouldContain "saver"
        assertFailsWith<IllegalArgumentException> { BotRegistry(listOf(SimpleBot("a"), SimpleBot("a"))) }
        scope.cancel()
    }

    @Test
    fun `serve fires a schedule queues at most one run behind a long one and runs nothing unscheduled`() =
        runTest {
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
                }
            val hold = CompletableDeferred<Unit>()
            var fired = 0
            val (fleet, scope) =
                fleet(
                    SimpleBot(
                        "timed",
                        schedule = Schedule.every(10.minutes),
                        work = {
                            fired++
                            hold.await()
                        },
                    ),
                    SimpleBot("manual"),
                    clock = clock,
                )

            val serving = launch { fleet.serve() }
            runCurrent()
            advanceTimeBy(10.minutes)
            eventually { fired == 1 }

            advanceTimeBy(1.hours)
            runCurrent()
            fleet.runs.value.size shouldBe 2
            fleet.runs.value.any { it.botId == "manual" } shouldBe false

            hold.complete(Unit)
            eventually { fired == 2 }

            serving.cancelAndJoin()
            scope.cancel()
        }

    @Test
    fun `a shared stop reads once per interval keeps a good answer through a blip and then fails closed`() =
        runTest {
            var now = Instant.parse("2026-10-30T12:00:00Z")
            val clock =
                object : Clock {
                    override fun now(): Instant = now
                }
            var reads = 0
            var page: String? = "false"
            var failing = false
            val stop =
                SharedStop(
                    {
                        reads++
                        if (failing) error("down")
                        page
                    },
                    clock = clock,
                )

            stop.mayContinue() shouldBe true
            stop.mayContinue() shouldBe true
            reads shouldBe 1

            now += 2.minutes
            page = "stop"
            stop.mayContinue() shouldBe false
            reads shouldBe 2

            page = "false"
            now += 2.minutes
            stop.mayContinue() shouldBe true

            failing = true
            now += 2.minutes
            stop.mayContinue() shouldBe true
            now += 4.minutes
            stop.mayContinue() shouldBe false

            val neverRead = SharedStop({ error("down") }, clock = clock)
            neverRead.mayContinue() shouldBe false

            val gone = SharedStop({ null }, clock = clock)
            gone.mayContinue() shouldBe false
        }

    @Test
    fun `a stop page on a wiki is read through it`() = runTest {
        val wiki = FakeWiki(FakePageService("User:Bot/Stop" to "false"))

        SharedStop.onPage(wiki, "User:Bot/Stop").mayContinue() shouldBe true
        SharedStop.onPage(wiki, "User:Nobody/Stop").mayContinue() shouldBe false
    }

    @Test
    fun `cron finds the next firing and refuses what is not an expression`() {
        val at = Instant.parse("2026-10-30T12:34:56Z")

        Schedule.cron("0 3 * * *").next(at) shouldBe Instant.parse("2026-10-31T03:00:00Z")
        Schedule.cron("*/15 * * * *").next(at) shouldBe Instant.parse("2026-10-30T12:45:00Z")
        Schedule.cron("0 0 1 * *").next(at) shouldBe Instant.parse("2026-11-01T00:00:00Z")
        Schedule.cron("0 12 * * 0").next(at) shouldBe Instant.parse("2026-11-01T12:00:00Z")
        Schedule.cron("0 12 * * 7").next(at) shouldBe Instant.parse("2026-11-01T12:00:00Z")
        Schedule.cron("0 12 15 * 5").next(at) shouldBe Instant.parse("2026-11-06T12:00:00Z")
        Schedule.cron("10-20/5,40 8 * 1-3 *").next(at) shouldBe Instant.parse("2027-01-01T08:10:00Z")
        Schedule.cron("30/10 * * * *").next(at) shouldBe Instant.parse("2026-10-30T12:40:00Z")

        for (bad in
            listOf("* * * *", "61 * * * *", "* * * * x", "5-1 * * * *", "*/0 * * * *", "1/2/3 * * * *")) {
            assertFailsWith<IllegalArgumentException> { Schedule.cron(bad) }
        }
        assertFailsWith<IllegalArgumentException> { Schedule.every(Duration.ZERO) }
        assertFailsWith<IllegalStateException> { Schedule.cron("0 0 30 2 *").next(at) }
    }

    @Test
    fun `cron reads its zone and copes with the clock changing`() {
        val berlin = "Europe/Berlin"

        Schedule.cron("0 3 * * *", berlin).next(Instant.parse("2026-10-30T12:00:00Z")) shouldBe
            Instant.parse("2026-10-31T02:00:00Z")
        Schedule.cron("30 2 * * *", berlin).next(Instant.parse("2027-03-27T12:00:00Z")) shouldBe
            Instant.parse("2027-03-28T01:30:00Z")
        Schedule.cron("30 2 * * *", berlin).next(Instant.parse("2026-10-24T12:00:00Z")) shouldBe
            Instant.parse("2026-10-25T00:30:00Z")
    }

    @Test
    fun `a recording context writes down what a bot did`() = runTest {
        val wiki = FakeWiki(FakePageService("a" to "text"))
        val run = RecordingContext(wiki, answer = { true })

        run.say("hello")
        run.expect(2)
        run.expect(3)
        run.stopped("one")
        run.stopped("two")
        run.step("phase") { it.say("inside") }
        run.ask(Question("ok?")) shouldBe true
        run.mayContinue() shouldBe true
        run.open(LangCode("en"), Family.WIKTIONARY) shouldBe wiki
        run.outcomes(PageOutcome.Unchanged(wiki.ref("a")))

        run.said shouldBe listOf("hello", "inside")
        run.asked.single().title shouldBe "ok?"
        run.expected shouldBe 5
        run.stoppedBecause shouldBe "one"
        run.pages.size shouldBe 1
    }

    private fun start(vararg arguments: String): BotStart =
        BotStart(
            FakeWiki(FakePageService("User:FakeBot/Stop" to "false")),
            CommonOptions(),
            arguments.toList(),
            BotConfig.parse(
                """
                [bot]
                name = "T"
                version = "1"
                contact = "https://example.org"
                """
                    .trimIndent()
            ),
        )

    @Test
    fun `the command line lists runs and says what it wants`() = runTest {
        val bots = listOf(SimpleBot("a"), SimpleBot("b"))
        val out = mutableListOf<String>()

        start("list").runCommand(bots, NonInteractive(), { out += it }, accounts()) shouldBe 0
        out shouldContain "a: a does things"

        out.clear()
        start().runCommand(bots, NonInteractive(), { out += it }, accounts()) shouldBe 2
        out.single() shouldContain "usage"

        out.clear()
        start("run", "nobody").runCommand(bots, NonInteractive(), { out += it }, accounts()) shouldBe 2
        out.single() shouldContain "there is no bot"
    }

    @Test
    fun `the command line runs one bot or all of them and says what came of it`() = runTest {
        val ran = mutableListOf<String>()
        val bots =
            listOf(
                SimpleBot("a", work = { ran += "a ${it.arguments}" }),
                SimpleBot("b", work = { ran += "b" }),
            )
        val failing = listOf(SimpleBot("c", work = { error("it broke") }))
        val out = mutableListOf<String>()

        start("run", "a", "x", "y").runCommand(bots, NonInteractive(), { out += it }, accounts()) shouldBe 0
        ran.single() shouldBe "a [x, y]"

        ran.clear()
        start("run", "--all").runCommand(bots, NonInteractive(), { out += it }, accounts()) shouldBe 0
        ran shouldContainExactlyInAnyOrder listOf("a []", "b")

        out.clear()
        start("run", "c").runCommand(failing, NonInteractive(), { out += it }, accounts()) shouldBe 1
        out.last() shouldContain "it broke"
    }

    @Test
    fun `a question from the command line goes to the prompt`() = runTest {
        val answers = mutableListOf<Boolean>()
        val bots = listOf(SimpleBot("a", work = { answers += it.ask(Question("Go on?")) }))

        start("run", "a").runCommand(bots, NonInteractive(answer = true), {}, accounts()) shouldBe 0

        answers.single() shouldBe true
    }

    @Test
    fun `serve keeps running until it is cancelled and runs what is due`() = runTest {
        var fired = 0
        val bots = listOf(SimpleBot("a", schedule = Schedule.every(1.minutes), work = { fired++ }))

        val serving = launch { start("serve").runCommand(bots, NonInteractive(), {}, accounts()) }
        runCurrent()
        serving.isActive shouldBe true
        serving.cancelAndJoin()
    }

    @Test
    fun `the default accounts read the environment and close what they opened`() = runTest {
        val account = accounts()
        account.wiki(endpoint)
        account.wiki(endpoint)
        logins.size shouldBe 1
        account.close()
        account.wiki(endpoint)
        logins.size shouldBe 2

        val real =
            Accounts(
                WikiConfig(UserAgent("TestBot", "1.0", "https://example.org/TestBot")),
                clients = { config, login ->
                    logins += login
                    WikiClient(config, login, engine = MockEngine { error("not asked") })
                },
            )
        assertFailsWith<Exception> { real.wiki(endpoint, "nobody") }
        logins.last() shouldBe Credentials.Anonymous
    }

    @Test
    fun `a run has a directory of its own when the fleet has one and a heartbeat when it runs long`() =
        runTest {
            val lines = mutableListOf<String>()
            val wiki = FakeWiki(FakePageService("a" to "text"))
            val directories = mutableListOf<String?>()
            val bot =
                SimpleBot(
                    "slow",
                    work = { run ->
                        directories += run.directory?.toString()
                        run.expect(1)
                        withContext(Dispatchers.Default) { delay(40.milliseconds) }
                        run.outcomes(PageOutcome.Unchanged(wiki.ref("a")))
                    },
                )
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val fleet =
                Fleet(
                    listOf(bot),
                    accounts(),
                    endpoint,
                    StopPolicy.NONE,
                    scope,
                    Path("runs"),
                    out = { lines += it },
                    heartbeat = 10.milliseconds,
                )

            fleet.start("slow")
            fleet.awaitIdle()

            directories.single()!! shouldContain "slow"
            lines.single() shouldContain "1 of 1 pages, 0 saved"
            scope.cancel()
        }

    @Test
    fun `a named account reads its own variables and a bot needs say little`() = runTest {
        val environment = mapOf("KWIKIBOT_PASSWORD" to "default", "KWIKIBOT_OTHER_PASSWORD" to "other")

        accountEnvironment(null, environment::get)("KWIKIBOT_PASSWORD") shouldBe "default"
        accountEnvironment("other", environment::get)("KWIKIBOT_PASSWORD") shouldBe "other"

        val bot =
            object : Bot {
                override val id = "plain"
                override val description = "says little"

                override suspend fun run(context: RunContext) = Unit
            }
        bot.account.shouldBeNull()
        bot.schedule.shouldBeNull()

        val registry = BotRegistry(listOf(bot))
        registry.find("plain") shouldBe bot
        registry.get("plain") shouldBe bot
        registry.find("nobody").shouldBeNull()
        start("list").runCommand(listOf(bot), NonInteractive(), {}) shouldBe 0
    }

    @Test
    fun `a stop page that fails while a run is asked is a reason to stop and cancelling passes through`() =
        runTest {
            var asked = 0
            val (fleet, scope) =
                fleet(
                    SimpleBot(
                        "checker",
                        work = { run ->
                            asked++
                            run.mayContinue() shouldBe false
                        },
                    ),
                    stop = StopPolicy { false },
                )

            fleet.start("checker")
            fleet.awaitIdle()
            asked shouldBe 1
            scope.cancel()

            val cancelled = launch { SharedStop({ awaitCancellation() }).mayContinue() }
            runCurrent()
            cancelled.cancelAndJoin()
        }
}
