import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class FleetHostTest {

    private val login = BotPassword("TestBot", "task", "secret")
    private val mock = MockWiki(mapOf("Volcano" to "ash\n", "Stop" to "false"))

    private fun bot(id: String, run: (RunContext) -> Any?): BotSpec =
        optionsOf("id" to id, "description" to "$id does things", "run" to run)

    private fun accounts(): Accounts = createAccounts("c", optionsOf("login" to login), null, mock.engine)

    private fun hosted(
        accounts: Accounts,
        vararg more: Pair<String, Any?>,
    ): FleetHostOptions =
        optionsOf("accounts" to accounts, "endpoint" to endpointOf("test.example.org"), *more)

    @Test
    fun `a fleet can be told everything`() = runTest {
        val accounts = accounts()
        val lines = mutableListOf<String>()
        val questions = mutableListOf<Question>()
        var seenStop: Boolean? = null
        var seenOptions: CommonOptions? = null
        var directory: String? = null
        val fleet =
            createFleetWith(
                hosted(
                    accounts,
                    "stop" to stopPolicyOf { answering(true) },
                    "directory" to if (hasFileSystem()) "runs" else null,
                    "onLine" to { line: String -> lines += line },
                    "onQuestion" to
                        { _: String, question: Question ->
                            questions += question
                            Promise.resolve(true)
                        },
                    "now" to { Date(0.0) },
                    "heartbeat" to 1.0,
                ),
                arrayOf(
                    bot("full") { context ->
                        seenOptions = context.options
                        directory = context.directory
                        context.sayAt("saved one", literal("saved"))
                        context.stopPolicy.mayContinue().then { yes ->
                            seenStop = yes
                            context.askQuestion(
                                optionsOf("title" to "Go?", "summary" to "yes", "diff" to "+x")
                            )
                        }
                    }
                ),
            )

        val events = mutableListOf<FleetEvent>()
        val walking = collectEvents(fleet, events)
        fleet.startWith("full", createCommonOptions(optionsOf("limit" to 3)), arrayOf("x"))
        fleet.idle().await()
        walking.stop()

        val run = fleet.runs().single()
        run.status shouldBe "done"
        run.isFinished shouldBe true
        seenStop shouldBe true
        seenOptions?.limit shouldBe 3
        directory shouldBe if (hasFileSystem()) Path("runs", "full", "1").toString() else null
        lines shouldBe listOf("[full] saved one")
        listOf(questions.single().title, questions.single().summary, questions.single().diff) shouldBe
            listOf("Go?", "yes", "+x")

        val said = events.first { it.kind.toString() == "said" }
        said.level shouldBe "saved"
        said.steps?.toList() shouldBe emptyList()
        val changed = events.first { it.kind.toString() == "changed" }
        changed.level shouldBe null
        changed.steps shouldBe null

        fleet.registry.all.single().id shouldBe "full"
        fleet.close()
        accounts.close()
    }

    @Test
    fun `closing a fleet leaves its accounts open`() = runTest {
        val accounts = accounts()
        createFleetWith(hosted(accounts), emptyArray()).close()

        val wiki = accounts.wiki(endpointOf("test.example.org")).await()
        wiki.page("Volcano").await()?.text shouldBe "ash\n"
        accounts.close()
    }

    @Test
    fun `a directory for runs is refused where there is no filesystem`() {
        if (hasFileSystem()) return
        shouldThrow<IllegalStateException> {
            createFleetWith(hosted(accounts(), "directory" to "runs"), emptyArray())
        }
    }

    @Test
    fun `steps say where they are, and a level can be wrong`() = runTest {
        val events = mutableListOf<FleetEvent>()
        val fleet =
            createFleetWith(
                hosted(accounts()),
                arrayOf(
                    bot("stepper") { context ->
                        context.step("German") { inner ->
                            inner.sayAt("in it", literal("error"))
                            shouldThrow<IllegalArgumentException> { inner.sayAt("x", literal("nope")) }
                            answering<Any?>(null)
                        }
                    }
                ),
            )
        val walking = collectEvents(fleet, events)

        fleet.startWith("stepper")
        fleet.idle().await()
        walking.stop()

        val said = events.single { it.kind.toString() == "said" }
        said.steps?.toList() shouldBe listOf("German")
        said.level shouldBe "error"
        fleet.runs().single().status shouldBe "done"
        fleet.close()
    }

    @Test
    fun `a bot's run counts its pages through outcomes`() = runTest {
        val fleet =
            createFleetWith(
                hosted(accounts()),
                arrayOf(
                    bot("editor") { context ->
                        context.wiki.botRun(
                            optionsOf(
                                "source" to pageSourceOfTitles(arrayOf("Volcano")),
                                "stopPolicy" to context.stopPolicy,
                                "dryRun" to !context.save,
                                "onOutcome" to context.outcomes,
                                "transform" to
                                    { page: Page ->
                                        optionsOf<PageEdit>("text" to page.text + "more", "summary" to "why")
                                    },
                            )
                        )
                    }
                ),
            )

        fleet.startWith("editor", createCommonOptions(optionsOf("save" to true)))
        fleet.idle().await()

        val run = fleet.runs().single()
        run.seen shouldBe 1
        run.saved shouldBe 1
        fleet.close()
    }

    @Test
    fun `watching runs says how they change`() = runTest {
        val fleet = createFleetWith(hosted(accounts()), arrayOf(bot("quick") { _ -> null }))

        fleet.watchRuns().take(1).single().size shouldBe 0
        fleet.startWith("quick")
        fleet.idle().await()
        fleet.watchRuns().take(1).single().single().status shouldBe "done"
        fleet.close()
    }

    @Test
    fun `serving with the common flags runs what is scheduled`() = runTest {
        val seen = mutableListOf<Int?>()
        val spec: BotSpec =
            optionsOf(
                "id" to "due",
                "schedule" to every(1.0),
                "run" to { context: RunContext -> seen += context.options.limit },
            )
        val fleet = createFleetWith(hosted(accounts()), arrayOf(spec))

        val serving = fleet.serveWith(createCommonOptions(optionsOf("limit" to 2)))
        val _ = serving.then({}, {})
        repeat(WAITS) {
            if (seen.isEmpty()) {
                Promise<Unit> { resolve, _ -> js("setTimeout")({ resolve(Unit) }, 5) }.await()
            }
        }

        seen.first() shouldBe 2
        fleet.close()
    }

    @Test
    fun `a schedule says when it next fires`() {
        every(60_000.0).next(Date(0.0)).getTime() shouldBe 60_000.0
        cron("0 3 * * *").next(Date(0.0)).getTime() shouldBe 3 * 3_600_000.0
    }

    @Test
    fun `a registry finds its bots, and refuses two of a name`() {
        val registry = BotRegistry(arrayOf(bot("one") { null }, bot("two") { null }))

        registry.all.map { it.id } shouldBe listOf("one", "two")
        registry.find("two")?.id shouldBe "two"
        registry.find("three") shouldBe null
        registry.get("one").id shouldBe "one"
        shouldThrow<Throwable> { registry.get("three") }
        shouldThrow<Throwable> { BotRegistry(arrayOf(bot("one") { null }, bot("one") { null })) }
    }

    @Test
    fun `accounts open a session for each, with the credentials given`() = runTest {
        val asked = mutableListOf<String?>()
        val accounts =
            createAccounts(
                "c",
                null,
                { account ->
                    asked += account
                    if (account == "named") login else null
                },
                mock.engine,
            )
        val endpoint = endpointOf("test.example.org")

        accounts.wiki(endpoint).await().user.isAnonymous shouldBe true
        accounts.wiki(endpoint, "named").await().user.name shouldBe "TestBot"
        accounts.wiki(endpoint, "named").await()
        asked shouldBe listOf(null, "named")
        accounts.close()

        createAccounts("c").close()
    }

    @Test
    fun `a shared stop reads once for many, and fails closed`() = runTest {
        var reads = 0
        var now = 0.0
        var text: Any? = "false"
        val stop =
            sharedStop(
                {
                    reads++
                    val answer = text
                    if (answer == "throw") throw IllegalStateException("no") else untyped(answer)
                },
                optionsOf("every" to 1000.0, "staleAfter" to 3000.0, "now" to { Date(now) }),
            )

        stop.mayContinue().await() shouldBe true
        stop.mayContinue().await() shouldBe true
        reads shouldBe 1

        now = 1000.0
        text = Promise.resolve("true")
        stop.mayContinue().await() shouldBe false
        reads shouldBe 2

        now = 2000.0
        text = "false"
        stop.mayContinue().await() shouldBe true

        now = 3000.0
        text = "throw"
        stop.mayContinue().await() shouldBe true
        now = 6000.0
        stop.mayContinue().await() shouldBe false

        sharedStop({ answering("false") }).mayContinue().await() shouldBe true

        val wiki = mock.open(login)
        sharedStopOnPage(wiki, "Stop").mayContinue().await() shouldBe true
        wiki.close()
    }

    @Test
    fun `a recording context writes down what a bot does`() = runTest {
        val wiki = mock.open(login)
        val recording =
            RecordingContext(
                wiki,
                optionsOf(
                    "stopPolicy" to stopPolicyOf { answering(false) },
                    "options" to createCommonOptions(optionsOf("save" to true)),
                    "arguments" to arrayOf("x"),
                    "directory" to "here",
                    "answer" to { question: Question -> question.title == "Yes?" },
                ),
            )
        val context = recording.context

        context.say("hello")
        context.sayAt("saved", literal("saved"))
        context.expect(2)
        context.expect(1)
        context.stopped("first")
        context.stopped("second")
        context.ask("Yes?").await() shouldBe true
        context.askQuestion(optionsOf("title" to "No?", "diff" to "+x")).await() shouldBe false
        context.mayContinue().await() shouldBe false
        context.save shouldBe true
        context.arguments.toList() shouldBe listOf("x")
        context.directory shouldBe "here"
        context.step("inside") { inner -> answering(inner.say("in")) }.await()

        val report =
            wiki
                .botRun(
                    optionsOf(
                        "source" to pageSourceOfTitles(arrayOf("Volcano")),
                        "transform" to { _: Page -> null },
                        "onOutcome" to context.outcomes,
                    )
                )
                .await()
        report.processed shouldBe 1

        recording.said.toList() shouldBe listOf("hello", "saved", "in")
        recording.asked.map { it.title } shouldBe listOf("Yes?", "No?")
        recording.asked.last().diff shouldBe "+x"
        recording.pages.single().kind shouldBe "skipped"
        recording.expected shouldBe 3
        recording.stoppedBecause shouldBe "first"
        wiki.close()

        val plain = RecordingContext(mock.open())
        plain.context.ask("Anything?").await() shouldBe false
        plain.context.mayContinue().await() shouldBe true
        plain.context.openFamily("en", FAMILY_WIKTIONARY).await().id shouldBe plain.context.wiki.id
        plain.context.wiki.close()
    }

    private fun collectEvents(fleet: Fleet, into: MutableList<FleetEvent>): EventWalk {
        val iterator = fleet.events().asDynamic()[js("Symbol.asyncIterator")]()
        val walk = EventWalk(iterator)
        fun pull() {
            val next: Promise<dynamic> = iterator.next().unsafeCast<Promise<dynamic>>()
            val _ = next.then { step: dynamic ->
                if (step.done != true) {
                    into += step.value.unsafeCast<FleetEvent>()
                    pull()
                }
            }
        }
        pull()
        return walk
    }

    private class EventWalk(private val iterator: dynamic) {
        suspend fun stop() {
            Promise<Unit> { resolve, _ -> js("setTimeout")({ resolve(Unit) }, 20) }.await()
            iterator["return"]().unsafeCast<Promise<dynamic>>().await()
        }
    }

    private companion object {
        const val WAITS = 200
    }
}
