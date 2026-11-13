package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.bot.BotConfig
import com.fenakhay.kwikibot.bot.BotStart
import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.bot.NonInteractive
import com.fenakhay.kwikibot.bot.Prompt
import com.fenakhay.kwikibot.bot.ScriptedPrompt
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

internal val testEndpoint = ApiEndpoint("en.wiktionary.org")

/** Accounts on a mock wiki that sees the account as being in [groups], after [opening] lets it answer. */
internal fun accountsOnMockWiki(
    groups: String = """"*","bot"""",
    id: Int = 5,
    configs: MutableList<WikiConfig> = mutableListOf(),
    opening: suspend (Credentials) -> Unit = {},
): Accounts =
    Accounts(
        WikiConfig(
            userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
            throttle = Throttle(Duration.ZERO, Duration.ZERO),
            retry = RetryPolicy.NONE,
        ),
        credentials = { account ->
            if (account == null) Credentials.Anonymous else Credentials.OAuth2("token-$account", account)
        },
        clients = { config, login ->
            configs += config
            WikiClient(
                config,
                login,
                engine =
                    MockEngine { request ->
                        opening(login)
                        val body =
                            request.body.toByteArray().decodeToString() + "&" + request.url.encodedQuery
                        val answer =
                            if ("userinfo" in body) {
                                """{"query":{"userinfo":{"id":$id,"name":"FleetBot","groups":[$groups],""" +
                                    """"rights":[]}}}"""
                            } else {
                                SITE_INFO
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

private const val SITE_INFO =
    """{"query":{"general":{"wikiid":"testwiki","sitename":"Test Wiki","lang":"en",
    "server":"//test.example.org","articlepath":"/wiki/${'$'}1","mainpage":"Main Page",
    "generator":"MediaWiki 1.47.0"},"namespaces":{"0":{"id":0,"name":"","case":"first-letter"}},
    "interwikimap":[]}}"""

internal class FleetTestBot(
    override val id: String,
    override val account: String? = null,
    val work: suspend (RunContext) -> Unit = {},
) : Bot {
    override val description: String = id

    override suspend fun run(context: RunContext) = work(context)
}

/** What a dispatcher on another thread can do: run the block before the code that dispatched it goes on. */
private object RunsAtOnce : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
}

@OptIn(ExperimentalCoroutinesApi::class)
class FleetSafetyTest {

    private val config =
        BotConfig.parse(
            """
            [bot]
            name = "T"
            version = "1"
            contact = "https://example.org"

            [login]
            account = "Main"
            botName = "main"
            passwordEnv = "MY_SECRET"
            """
                .trimIndent()
        )

    private fun start(vararg arguments: String, pages: FakePageService? = null): BotStart =
        BotStart(
            FakeWiki(pages ?: FakePageService("User:FakeBot/Stop" to "false")),
            CommonOptions(),
            arguments.toList(),
            config,
        )

    private suspend fun BotStart.command(
        bots: List<Bot>,
        prompt: Prompt = NonInteractive(),
        out: MutableList<String> = mutableListOf(),
    ): Int = runCommand(bots, prompt, { out += it }, accountsOnMockWiki())

    @Test
    fun `a run cancelled before its body begins ends cancelled and says so`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var ran = false
        val fleet =
            Fleet(
                listOf(FleetTestBot("a", work = { ran = true })),
                accountsOnMockWiki(),
                testEndpoint,
                StopPolicy.NONE,
                scope,
            )
        val events = mutableListOf<FleetEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fleet.events.collect { events += it }
        }
        runCurrent()

        val run = fleet.start("a")
        yield()
        fleet.cancel(run) shouldBe true
        runCurrent()

        fleet.runs.value.single().status shouldBe RunStatus.CANCELLED
        ran shouldBe false
        events shouldContain FleetEvent.Changed(run, "a", RunStatus.CANCELLED)
        fleet.awaitIdle()
        scope.cancel()
    }

    @Test
    fun `cancelling the fleet ends the runs waiting in a lane`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val fleet =
            Fleet(
                listOf(FleetTestBot("a", work = { awaitCancellation() })),
                accountsOnMockWiki(),
                testEndpoint,
                StopPolicy.NONE,
                scope,
            )
        fleet.start("a")
        fleet.start("a")
        runCurrent()

        scope.cancel()
        runCurrent()

        fleet.awaitIdle()
        fleet.runs.value.map { it.status } shouldBe List(2) { RunStatus.CANCELLED }
    }

    @Test
    fun `a cancel landing as a run starts on another thread reaches the run`() = runTest {
        val accounts = accountsOnMockWiki()
        withContext(Dispatchers.Default) { accounts.wiki(testEndpoint) }
        val scope = CoroutineScope(RunsAtOnce + SupervisorJob())
        var ended = false
        lateinit var fleet: Fleet
        val bot =
            FleetTestBot(
                "a",
                work = {
                    fleet.cancel(fleet.runs.value.last().id) shouldBe true
                    try {
                        awaitCancellation()
                    } finally {
                        ended = true
                    }
                },
            )
        fleet = Fleet(listOf(bot), accounts, testEndpoint, StopPolicy.NONE, scope)

        fleet.start("a")

        ended shouldBe true
        fleet.runs.value.single().status shouldBe RunStatus.CANCELLED
        scope.cancel()
    }

    @Test
    fun `runs started on many threads each have an id of their own and all end`() =
        runTest(timeout = 3.minutes) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val fleet =
                Fleet(
                    List(8) { FleetTestBot("bot$it") },
                    accountsOnMockWiki(),
                    testEndpoint,
                    StopPolicy.NONE,
                    scope,
                )

            val ids =
                withContext(Dispatchers.Default) {
                    List(8) { bot -> async { List(150) { fleet.start("bot$bot") } } }
                        .awaitAll()
                        .flatten()
                        .also { fleet.awaitIdle() }
                }

            ids.toSet().size shouldBe ids.size
            fleet.runs.value.size shouldBe ids.size
            fleet.runs.value.all { it.status == RunStatus.DONE } shouldBe true
            scope.cancel()
        }

    @Test
    fun `a run that says it stopped early is reported and exits with one`() = runTest {
        val out = mutableListOf<String>()
        val bots = listOf(FleetTestBot("a", work = { it.stopped("out of time") }))

        start("run", "a").command(bots, out = out) shouldBe 1

        out.last() shouldBe "[a] ended DONE: out of time"
    }

    @Test
    fun `a bot ignoring its report still exits with one when the stop page stopped it`() = runTest {
        val pages = FakePageService("a" to "text")
        val out = mutableListOf<String>()
        val bots =
            listOf(
                FleetTestBot(
                    "a",
                    work = { run ->
                        botRun(pages, run.stopPolicy, null) {
                            source(flowOf(pages.ref("a")))
                            transform { Edit("new", "s") }
                            dryRun = false
                        }
                    },
                )
            )

        start("run", "a", pages = pages).command(bots, out = out) shouldBe 1

        out.last() shouldContain "the stop policy says to stop"
    }

    @Test
    fun `a page not attempted counts as stopping early`() = runTest {
        val pages = FakePageService("a" to "text")
        val out = mutableListOf<String>()
        val bots =
            listOf(
                FleetTestBot(
                    "a",
                    work = { it.outcomes(PageOutcome.NotAttempted(pages.ref("a"), StopReason.AUTH)) },
                )
            )

        start("run", "a").command(bots, out = out) shouldBe 1

        out.last() shouldContain "auth"
    }

    @Test
    fun `a clean run exits with nothing to say and a declined one says who said no`() = runTest {
        val out = mutableListOf<String>()

        start("run", "a").command(listOf(FleetTestBot("a")), out = out) shouldBe 0
        out shouldBe emptyList()

        val declining = listOf(FleetTestBot("a", work = { throw Declined("they said no") }))
        start("run", "a").command(declining, out = out) shouldBe 1
        out.single() shouldBe "[a] ended DECLINED: they said no"
    }

    @Test
    fun `all is every bot only straight after run and on its own`() = runTest {
        val ran = mutableListOf<String>()
        val bots =
            listOf(
                FleetTestBot("a", work = { ran += "a ${it.arguments}" }),
                FleetTestBot("b", work = { ran += "b ${it.arguments}" }),
            )

        start("run", "a", "--all").command(bots) shouldBe 0
        ran shouldBe listOf("a [--all]")

        ran.clear()
        for (bad in
            listOf(
                listOf("run", "--all", "a"),
                listOf("--all"),
                listOf("--all", "run"),
                listOf("serve", "--all"),
                listOf("list", "--all"),
            )) {
            start(*bad.toTypedArray()).command(bots) shouldBe 2
        }
        ran shouldBe emptyList()

        start("run", "--all").command(bots) shouldBe 0
        ran.size shouldBe 2
    }

    @Test
    fun `two lanes asking at once are shown one question at a time`() = runTest {
        var showing = 0
        var most = 0
        val release = CompletableDeferred<Unit>()
        val prompt =
            object : Prompt by ScriptedPrompt(emptyList()) {
                override suspend fun confirm(question: String, default: Boolean): Boolean {
                    most = maxOf(most, ++showing)
                    release.await()
                    showing--
                    return true
                }
            }
        var arrived = 0
        val asks: suspend (RunContext) -> Unit = {
            arrived++
            it.ask(Question("Go on?"))
        }
        val bots =
            listOf(
                FleetTestBot("a", work = asks),
                FleetTestBot("b", work = asks),
                FleetTestBot(
                    "c",
                    work = {
                        while (arrived < 2) withContext(Dispatchers.Default) { delay(1) }
                        release.complete(Unit)
                    },
                ),
            )

        start("run", "--all").command(bots, prompt) shouldBe 0

        most shouldBe 1
    }

    @Test
    fun `a named account reads its own variables and never the default account`() {
        val secrets =
            mapOf(
                "MY_SECRET" to "main-secret",
                "KWIKIBOT_PASSWORD" to "default-secret",
                "KWIKIBOT_NEWS_ACCOUNT" to "NewsBot",
                "KWIKIBOT_NEWS_BOT_NAME" to "news",
                "KWIKIBOT_NEWS_PASSWORD" to "news-secret",
                "KWIKIBOT_HALF_PASSWORD" to "half-secret",
                "KWIKIBOT_TOKEN_OAUTH_TOKEN" to "the-token",
            )

        credentialsFor(config, null, secrets::get) shouldBe
            Credentials.BotPassword("Main", "main", "main-secret")
        credentialsFor(config, "news", secrets::get) shouldBe
            Credentials.BotPassword("NewsBot", "news", "news-secret")
        credentialsFor(config, "token", secrets::get) shouldBe Credentials.OAuth2("the-token")

        val missing = assertFailsWith<IllegalStateException> { credentialsFor(config, "gone", secrets::get) }
        missing.message!! shouldContain "KWIKIBOT_GONE_PASSWORD"
        missing.message!! shouldContain "KWIKIBOT_GONE_OAUTH_TOKEN"
        missing.message!! shouldNotContain "secret"

        val half = assertFailsWith<IllegalStateException> { credentialsFor(config, "half", secrets::get) }
        half.message!! shouldContain "KWIKIBOT_HALF_BOT_NAME"
        half.message!! shouldNotContain "half-secret"
    }

    @Test
    fun `an account with no credentials fails its own run and names what to set`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val accounts =
            Accounts(
                WikiConfig(UserAgent("TestBot", "1.0", "https://example.org/TestBot")),
                credentials = { account -> credentialsFor(config, account) { null } },
                clients = { _, _ -> error("nobody to log in as") },
            )
        val fleet =
            Fleet(
                listOf(FleetTestBot("named", "gone")),
                accounts,
                testEndpoint,
                StopPolicy.NONE,
                scope,
            )

        fleet.start("named")
        fleet.awaitIdle()

        fleet.runs.value.single().let {
            it.status shouldBe RunStatus.FAILED
            it.failure.shouldNotBeNull() shouldContain "KWIKIBOT_GONE_PASSWORD"
        }
        scope.cancel()
    }

    @Test
    fun `a saving run needs its account in the bot group unless on trial`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val ran = mutableListOf<CommonOptions>()
        val fleet =
            Fleet(
                listOf(FleetTestBot("a", "person", work = { ran += it.options })),
                accountsOnMockWiki(groups = """"*""""),
                testEndpoint,
                StopPolicy.NONE,
                scope,
            )

        val saving = fleet.start("a", CommonOptions(save = true))
        val trying = fleet.start("a", CommonOptions(save = true, limit = 1, trial = true))
        val looking = fleet.start("a")
        fleet.awaitIdle()

        val runs = fleet.runs.value.associateBy { it.id }
        runs.getValue(saving).status shouldBe RunStatus.FAILED
        runs.getValue(saving).failure.shouldNotBeNull() shouldContain "not in the bot group"
        runs.getValue(trying).status shouldBe RunStatus.DONE
        runs.getValue(looking).status shouldBe RunStatus.DONE
        ran.size shouldBe 2
        scope.cancel()
    }

    @Test
    fun `a saving run cannot be anonymous and open checks the other wiki too`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val nobody =
            Fleet(
                listOf(FleetTestBot("a")),
                accountsOnMockWiki(id = 0),
                testEndpoint,
                StopPolicy.NONE,
                scope,
            )
        nobody.start("a", CommonOptions(save = true))
        nobody.awaitIdle()
        nobody.runs.value.single().failure.shouldNotBeNull() shouldContain "anonymous"

        val configs = mutableListOf<WikiConfig>()
        val accounts = accountsOnMockWiki(configs = configs)
        val opening =
            Fleet(
                listOf(FleetTestBot("a", "one", work = { it.open(LangCode("de"), Family.WIKTIONARY) })),
                accounts,
                testEndpoint,
                StopPolicy.NONE,
                scope,
            )
        opening.start("a", CommonOptions(save = true))
        opening.awaitIdle()
        opening.runs.value.single().status shouldBe RunStatus.DONE
        configs.size shouldBe 1
        scope.cancel()
    }

    @Test
    fun `a stop policy that throws stops the run says so and a cancellation passes through`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val fleet =
            Fleet(
                listOf(
                    FleetTestBot(
                        "a",
                        work = { run ->
                            assertFailsWith<IllegalStateException> { run.stopPolicy.mayContinue() }
                            run.mayContinue() shouldBe false
                        },
                    )
                ),
                accountsOnMockWiki(),
                testEndpoint,
                StopPolicy { error("down") },
                scope,
            )
        fleet.start("a")
        fleet.awaitIdle()
        fleet.runs.value.single().let {
            it.status shouldBe RunStatus.DONE
            it.stoppedBecause shouldBe "the stop policy could not be read"
        }

        val gone =
            Fleet(
                listOf(FleetTestBot("a", work = { it.mayContinue() })),
                accountsOnMockWiki(),
                testEndpoint,
                StopPolicy { throw CancellationException("gone") },
                scope,
            )
        gone.start("a")
        gone.awaitIdle()
        gone.runs.value.single().status shouldBe RunStatus.CANCELLED
        scope.cancel()
    }

    @Test
    fun `a run meeting something worse than an exception ends failed and the lane goes on`() = runTest {
        val ignoring = CoroutineExceptionHandler { _, _ -> }
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + ignoring)
        val fleet =
            Fleet(
                listOf(
                    FleetTestBot("a", work = { if (it.arguments.isEmpty()) throw NotImplementedError("no") })
                ),
                accountsOnMockWiki(),
                testEndpoint,
                StopPolicy.NONE,
                scope,
            )

        fleet.start("a")
        fleet.start("a", arguments = listOf("x"))
        fleet.awaitIdle()

        fleet.runs.value.map { it.status } shouldBe listOf(RunStatus.FAILED, RunStatus.DONE)
        fleet.runs.value.first().failure.shouldNotBeNull() shouldContain "no"
        scope.cancel()
    }

    @Test
    fun `a failure with no message is reported by its type and a cancelled run with no reason alone`() =
        runTest {
            val out = mutableListOf<String>()

            start("run", "a")
                .command(
                    listOf(FleetTestBot("a", work = { throw IllegalArgumentException() })),
                    out = out,
                ) shouldBe 1
            out.last() shouldContain "IllegalArgumentException"

            start("run", "a")
                .command(
                    listOf(FleetTestBot("a", work = { throw CancellationException("gone") })),
                    out = out,
                ) shouldBe 1
            out.last() shouldBe "[a] ended CANCELLED"
        }

    @Test
    fun `the stop page asked for is the one read`() = runTest {
        val allowed = mutableListOf<Boolean>()
        val bots = listOf(FleetTestBot("a", work = { allowed += it.mayContinue() }))
        val pages = FakePageService("Mine" to "false")

        BotStart(FakeWiki(pages), CommonOptions(stopPage = "Mine"), listOf("run", "a"), config)
            .command(bots) shouldBe 0

        allowed.single() shouldBe true
    }

    @Test
    fun `a heartbeat with no total says only how far it has got`() = runTest {
        val lines = mutableListOf<String>()
        val pages = FakePageService("a" to "text")
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val fleet =
            Fleet(
                listOf(
                    FleetTestBot(
                        "a",
                        work = { run ->
                            withContext(Dispatchers.Default) { delay(5) }
                            run.outcomes(PageOutcome.Unchanged(pages.ref("a")))
                        },
                    )
                ),
                accountsOnMockWiki(),
                testEndpoint,
                StopPolicy.NONE,
                scope,
                out = { lines += it },
                heartbeat = Duration.ZERO,
            )

        fleet.start("a")
        fleet.awaitIdle()

        lines.single() shouldBe "[a] 1 pages, 0 saved"
        scope.cancel()
    }

    @Test
    fun `accounts open each wiki once on one client and close what they have`() = runTest {
        val configs = mutableListOf<WikiConfig>()
        val accounts = accountsOnMockWiki(configs = configs)

        val first = accounts.wiki(testEndpoint, "one")
        accounts.wiki(ApiEndpoint("de.wiktionary.org"), "one")

        accounts.wiki(testEndpoint, "one") shouldBe first
        configs.size shouldBe 1
        accounts.close()

        val refusing =
            Accounts(
                WikiConfig(UserAgent("TestBot", "1.0", "https://example.org/TestBot")),
                credentials = { error("no") },
                clients = { _, _ -> error("not made") },
            )
        assertFailsWith<IllegalStateException> { refusing.wiki(testEndpoint) }
        refusing.close()

        val fromEnvironment =
            Accounts(
                WikiConfig(UserAgent("TestBot", "1.0", "https://example.org/TestBot")),
                clients = { _, login -> error("would log in as $login") },
            )
        assertFailsWith<IllegalStateException> { fromEnvironment.wiki(testEndpoint) }.message!! shouldContain
            "Anonymous"
    }

    @Test
    fun `a cron step that is no number is refused`() {
        for (bad in listOf("1/ * * * *", "1/x * * * *", "*/x * * * *", "1-x * * * *")) {
            assertFailsWith<IllegalArgumentException> { Schedule.cron(bad) }
        }
    }

    @Test
    fun `a slow login holds back neither another account nor a second login`() = runTest {
        val release = CompletableDeferred<Unit>()
        val configs = mutableListOf<WikiConfig>()
        val accounts =
            accountsOnMockWiki(configs = configs) { login ->
                if (login.username == "slow") release.await()
            }

        withContext(Dispatchers.Default) {
            val slow = List(2) { async { accounts.wiki(testEndpoint, "slow") } }
            withTimeout(60.seconds) {
                while (configs.isEmpty()) yield()
                accounts.wiki(testEndpoint, "fast")
            }
            release.complete(Unit)
            val opened = slow.awaitAll()
            opened[0] shouldBe opened[1]
        }

        configs.size shouldBe 2
        accounts.close()
    }

    @Test
    fun `an account has a throttle of its own at the pace asked for`() = runTest {
        val configs = mutableListOf<WikiConfig>()
        val accounts = accountsOnMockWiki(configs = configs)

        accounts.wiki(testEndpoint, "one")
        accounts.wiki(testEndpoint, "two")
        accounts.wiki(testEndpoint, "one")

        configs.size shouldBe 2
        (configs[0].throttle === configs[1].throttle) shouldBe false
        configs[0].throttle.write shouldBe Duration.ZERO
        accounts.close()
    }

    @Test
    fun `many askers share one read`() = runTest {
        var reads = 0
        val answer = CompletableDeferred<String?>()
        val stop =
            SharedStop({
                reads++
                answer.await()
            })

        val asking = List(5) { async { stop.mayContinue() } }
        runCurrent()
        answer.complete("false")

        asking.awaitAll() shouldBe List(5) { true }
        reads shouldBe 1
    }

    @Test
    fun `an answer is aged to when it is asked after a read that failed slowly`() = runTest {
        val time = TestTimeSource()
        var fail = false
        val stop =
            SharedStop(
                {
                    if (fail) {
                        time += 6.minutes
                        error("timed out")
                    }
                    "false"
                },
                timeSource = time,
            )
        stop.mayContinue() shouldBe true

        time += 2.minutes
        fail = true

        stop.mayContinue() shouldBe false
    }

    @Test
    fun `a read that took long is aged from when it arrived`() = runTest {
        val time = TestTimeSource()
        val stop =
            SharedStop(
                {
                    time += 6.minutes
                    "false"
                },
                timeSource = time,
            )

        stop.mayContinue() shouldBe true
    }

    @Test
    fun `when the one reading is cancelled another reads instead`() = runTest {
        val cancelling = CompletableDeferred<Unit>()
        var reads = 0
        val stop =
            SharedStop({
                if (++reads == 1) {
                    cancelling.complete(Unit)
                    awaitCancellation()
                }
                "false"
            })

        val first = launch { stop.mayContinue() }
        cancelling.await()
        val second = async { stop.mayContinue() }
        runCurrent()
        first.cancelAndJoin()

        second.await() shouldBe true
        reads shouldBe 2
    }

    @Test
    fun `a read that times out by itself is a failed read and the asker is not cancelled`() = runTest {
        val stop = SharedStop({ withTimeout(1.seconds) { awaitCancellation() } })

        stop.mayContinue() shouldBe false
    }
}
