import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BotRunTest {

    private val login = BotPassword("TestBot", "task", "secret")
    private val pages = mapOf("Volcano" to "ash\n", "Etna" to "lava\n")

    private fun edit(text: String, summary: String = "naming it", vararg more: Pair<String, Any?>): PageEdit =
        optionsOf("text" to text, "summary" to summary, *more)

    private val tephra: (Page) -> Any? = { page ->
        if ("ash" in page.text) edit(page.text.replace("ash", "tephra")) else null
    }

    private fun source(vararg titles: String): PageSource = pageSourceOfTitles(arrayOf(*titles))

    private fun run(vararg options: Pair<String, Any?>): BotRunOptions = optionsOf(*options)

    @Test
    fun `a dry run computes each edit and saves nothing`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)
        val outcomes = mutableListOf<PageOutcome>()

        val report =
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano", "Etna", "Nope"),
                        "transform" to tephra,
                        "onOutcome" to { outcome: PageOutcome -> outcomes += outcome },
                    )
                )
                .await()

        listOf(report.processed, report.pending, report.saved, report.skipped, report.failed) shouldBe
            listOf(3, 1, 0, 2, 0)
        listOf(report.stopped, report.clean, report.problemsTruncated, report.notAttempted) shouldBe
            listOf(false, true, false, 0)
        report.stopReason shouldBe null
        report.problems.size shouldBe 0
        report.toString() shouldContain "pending=1"
        mock.saved shouldBe emptyMap()

        val byTitle = outcomes.associateBy { it.title }
        val volcano = byTitle.getValue("Volcano")
        volcano.kind shouldBe "pending"
        volcano.edit?.text shouldBe "tephra\n"
        volcano.before shouldBe "ash\n"
        volcano.baseRevision shouldBe 9001.0
        byTitle.getValue("Etna").kind shouldBe "skipped"
        byTitle.getValue("Etna").reason shouldBe "no change needed"
        byTitle.getValue("Nope").kind shouldBe "missing"
        wiki.close()
    }

    @Test
    fun `a run that saves sends the edit as it was said`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)
        val outcomes = mutableListOf<PageOutcome>()

        val report =
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano"),
                        "dryRun" to false,
                        "stopPolicy" to STOP_POLICY_NONE,
                        "transform" to
                            { _: Page ->
                                Promise.resolve(
                                    edit("new\n", "why", "minor" to true, "tags" to arrayOf("bot-tag"))
                                )
                            },
                        "onOutcome" to { outcome: PageOutcome -> outcomes += outcome },
                    )
                )
                .await()

        report.saved shouldBe 1
        mock.saved shouldBe mapOf("Volcano" to "new\n")
        val sent = mock.requests.single { it["action"] == "edit" }
        listOf(sent["summary"], sent["minor"], sent["tags"], sent["baserevid"]) shouldBe
            listOf("why", "1", "bot-tag", "9001")

        val saved = outcomes.single()
        saved.kind shouldBe "saved"
        saved.revision shouldBe 9002.0
        saved.previousRevision shouldBe 9001.0
        saved.before shouldBe "ash\n"
        saved.edit?.summary shouldBe "why"
        saved.edit?.tags?.toList() shouldBe listOf("bot-tag")
        wiki.close()
    }

    @Test
    fun `an edit of no text and a section are told apart`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)
        val kinds = mutableListOf<String>()

        wiki
            .botRun(
                run(
                    "source" to source("Volcano", "Etna"),
                    "dryRun" to false,
                    "transform" to
                        { page: Page ->
                            if (page.title == "Volcano") edit(page.text)
                            else edit("more", "heading", "section" to "new")
                        },
                    "onOutcome" to { outcome: PageOutcome -> kinds += "${outcome.title} ${outcome.kind}" },
                )
            )
            .await()

        kinds.toSet() shouldBe setOf("Volcano unchanged", "Etna saved")
        mock.requests.single { it["action"] == "edit" }["section"] shouldBe "new"
        wiki.close()
    }

    @Test
    fun `skipPage leaves a page with its reason`() = runTest {
        val wiki = MockWiki(pages).open(login)
        val reasons = mutableListOf<String?>()

        val report =
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano"),
                        "transform" to { _: Page -> skipPage("not today") },
                        "onOutcome" to { outcome: PageOutcome -> reasons += outcome.reason },
                    )
                )
                .await()

        report.skipped shouldBe 1
        reasons shouldBe listOf("not today")
        SkipPageError("why").reason shouldBe "why"
        wiki.close()
    }

    @Test
    fun `a promise that rejects with a skip skips too`() = runTest {
        val wiki = MockWiki(pages).open(login)
        val transform = { _: Page -> Promise.reject(SkipPageError("later")) }

        val report = wiki.botRun(run("source" to source("Volcano"), "transform" to transform)).await()

        report.skipped shouldBe 1
        wiki.close()
    }

    @Test
    fun `a transform that throws rejects the run, and one that answers nonsense too`() = runTest {
        val wiki = MockWiki(pages).open(login)

        shouldThrow<Throwable> {
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano"),
                        "transform" to { _: Page -> throw IllegalStateException("it broke") },
                    )
                )
                .await()
        }
        wiki.close()

        val other = MockWiki(pages).open(login)
        shouldThrow<IllegalArgumentException> {
                other
                    .botRun(run("source" to source("Volcano"), "transform" to { _: Page -> "just text" }))
                    .await()
            }
            .message shouldContain "text and a summary"
        other.close()
    }

    @Test
    fun `createMissing makes a page that must not exist yet`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)

        val dry =
            wiki
                .botRun(
                    run(
                        "source" to source("Nope"),
                        "transform" to tephra,
                        "createMissing" to { title: String -> edit("about $title", "creating") },
                    )
                )
                .await()
        dry.pending shouldBe 1

        val report =
            wiki
                .botRun(
                    run(
                        "source" to source("Nope", "Other"),
                        "dryRun" to false,
                        "transform" to tephra,
                        "createMissing" to
                            { title: String ->
                                if (title == "Nope") Promise.resolve(edit("about $title", "creating"))
                                else skipPage("not that one")
                            },
                    )
                )
                .await()

        report.saved shouldBe 1
        report.skipped shouldBe 1
        mock.saved shouldBe mapOf("Nope" to "about Nope")
        mock.requests.single { it["action"] == "edit" }["createonly"] shouldBe "1"
        wiki.close()
    }

    @Test
    fun `limit caps the pages and readBatch asks for them together`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)

        val report =
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano", "Etna", "Nope"),
                        "transform" to tephra,
                        "limit" to 2,
                        "readBatch" to 2,
                        "readConcurrency" to 1,
                        "writeConcurrency" to 1,
                    )
                )
                .await()

        report.processed shouldBe 2
        mock.requests.filter { "content" in it["rvprop"].orEmpty() }.map { it["titles"] } shouldBe
            listOf("Volcano|Etna")
        wiki.close()
    }

    @Test
    fun `a conflicted save is tried again on the page as it is now unless told not to`() = runTest {
        for ((retries, saved) in listOf(null to "theirs tephra", 0 to null)) {
            val mock = MockWiki(mapOf("A" to "ash"))
            val wiki = mock.open(login)
            var tries = 0
            val transform: (Page) -> Any? = { page ->
                if (++tries == 1) mock.saveAsSomebodyElse("A", "theirs ash")
                tephra(page)
            }

            val report =
                wiki
                    .botRun(
                        run(
                            "source" to source("A"),
                            "dryRun" to false,
                            "transform" to transform,
                            "conflictRetries" to retries,
                        )
                    )
                    .await()

            tries shouldBe if (retries == 0) 1 else 2
            mock.saved["A"] shouldBe saved
            report.refused shouldBe if (retries == 0) 1 else 0
            wiki.close()
        }
    }

    @Test
    fun `maxEdits stops the run with the pages in hand not attempted`() = runTest {
        val mock = MockWiki(mapOf("A" to "ash", "B" to "ash", "C" to "ash"))
        val wiki = mock.open(login)

        val report =
            wiki
                .botRun(
                    run(
                        "source" to source("A", "B", "C"),
                        "dryRun" to false,
                        "readConcurrency" to 1,
                        "transform" to tephra,
                        "maxEdits" to 1,
                    )
                )
                .await()

        report.saved shouldBe 1
        report.stopped shouldBe true
        report.stopReason shouldBe "policy"
        report.notAttempted shouldBe 2
        wiki.close()
    }

    @Test
    fun `a stop policy stops a saving run before it reads anything`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)

        val report =
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano"),
                        "dryRun" to false,
                        "transform" to tephra,
                        "stopPolicy" to stopPolicyOf { answering(false) },
                    )
                )
                .await()

        report.stopped shouldBe true
        report.stopReason shouldBe "policy"
        report.processed shouldBe 0

        val failing =
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano"),
                        "dryRun" to false,
                        "transform" to tephra,
                        "stopPolicy" to
                            stopPolicyOf { answering(Promise.reject(IllegalStateException("no"))) },
                    )
                )
                .await()
        failing.stopReason shouldBe "policy-unreachable"
        mock.saved shouldBe emptyMap()
        wiki.close()
    }

    @Test
    fun `stop policies combine and read a page`() = runTest {
        val mock = MockWiki(mapOf("Stop" to "false"))
        val wiki = mock.open(login)

        stopPolicyOf { answering(true) }.mayContinue().await() shouldBe true
        stopPolicyOf { untyped("yes") }.mayContinue().await() shouldBe false
        stopPolicyOf { answering(Promise.resolve(true)) }.mayContinue().await() shouldBe true
        STOP_POLICY_NONE.mayContinue().await() shouldBe true
        STOP_POLICY_NONE.check().await()
        shouldThrow<Throwable> { stopPolicyOf { answering(false) }.check().await() }

        allStopPolicies(arrayOf(STOP_POLICY_NONE, stopPolicyOf { answering(true) }))
            .mayContinue()
            .await() shouldBe true
        allStopPolicies(arrayOf(STOP_POLICY_NONE, stopPolicyOf { answering(false) }))
            .mayContinue()
            .await() shouldBe false

        stopPolicyOnPage(wiki, "Stop").mayContinue().await() shouldBe true
        wiki.stopPage("Stop").mayContinue().await() shouldBe true
        mock.requests.last()["titles"] shouldBe "Stop"
        wiki.stopPage("Absent").mayContinue().await() shouldBe false
        wiki.stopPage().mayContinue().await() shouldBe false
        mock.requests.last()["titles"] shouldBe "User:TestBot/Stop"
        wiki.close()
    }

    @Test
    fun `a page the wiki refuses is a problem in the report`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)
        mock.canned["edit"] = """{"error":{"code":"protectedpage","info":"It is protected."}}"""

        val report =
            wiki.botRun(run("source" to source("Volcano"), "dryRun" to false, "transform" to tephra)).await()

        report.refused shouldBe 1
        report.clean shouldBe false
        val problem = report.problems.single()
        problem.kind shouldBe "refused"
        problem.title shouldBe "Volcano"
        problem.refusal?.status shouldBe "protected"
        problem.refusal?.detail shouldBe "It is protected."
        wiki.close()
    }

    @Test
    fun `a page the wiki cannot serve is a failure in the report`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)
        mock.canned["info|revisions"] = """{"error":{"code":"internal_api_error_x","info":"it broke"}}"""

        val report = wiki.botRun(run("source" to source("Volcano"), "transform" to tephra)).await()

        report.failed shouldBe 1
        val problem = report.problems.single()
        problem.kind shouldBe "failed"
        problem.error?.message.orEmpty() shouldContain "it broke"
        describeError(problem.error)?.kind shouldBe "api"
        wiki.close()
    }

    @Test
    fun `an exclusion is honoured as the account unless told otherwise`() = runTest {
        val mock = MockWiki(mapOf("Volcano" to "{{nobots}} ash"))
        val wiki = mock.open(login)

        val excluded = wiki.botRun(run("source" to source("Volcano"), "transform" to tephra)).await()
        excluded.pending shouldBe 0
        excluded.skipped shouldBe 1

        val optedOut =
            wiki
                .botRun(run("source" to source("Volcano"), "transform" to tephra, "exclusionPolicy" to null))
                .await()
        optedOut.pending shouldBe 1

        val other =
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano"),
                        "transform" to tephra,
                        "exclusionPolicy" to BotPolicy("Other"),
                    )
                )
                .await()
        other.skipped shouldBe 1
        wiki.close()
    }

    @Test
    fun `the common flags and a configuration set what the options do not`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)

        val capped =
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano", "Etna"),
                        "transform" to tephra,
                        "common" to createCommonOptions(optionsOf("limit" to 1)),
                        "config" to
                            parseBotConfig("[bot]\nname = \"B\"\ncontact = \"c\"\n[run]\nreadBatch = 2\n"),
                    )
                )
                .await()
        capped.processed shouldBe 1

        val overridden =
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano", "Etna"),
                        "transform" to tephra,
                        "common" to createCommonOptions(optionsOf("limit" to 1)),
                        "limit" to 2,
                    )
                )
                .await()
        overridden.processed shouldBe 2

        val saving =
            wiki
                .botRun(
                    run(
                        "source" to source("Volcano"),
                        "transform" to tephra,
                        "common" to createCommonOptions(optionsOf("save" to true)),
                    )
                )
                .await()
        saving.saved shouldBe 1
        wiki.close()
    }

    @Test
    fun `reportTo hands each outcome to every handler`() = runTest {
        val wiki = MockWiki(pages).open(login)
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()

        wiki
            .botRun(
                run(
                    "source" to source("Volcano"),
                    "transform" to tephra,
                    "onOutcome" to
                        reportTo(
                            arrayOf(
                                { outcome -> first += outcome.title },
                                { outcome -> second += outcome.kind.toString() },
                            )
                        ),
                )
            )
            .await()

        first shouldBe listOf("Volcano")
        second shouldBe listOf("pending")
        wiki.close()
    }

    @Test
    fun `the problem limit is the library's own`() {
        BOT_REPORT_PROBLEM_LIMIT shouldBe 100
    }
}
