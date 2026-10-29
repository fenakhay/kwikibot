import com.fenakhay.kwikibot.bot.fix.Diffs
import io.kotest.matchers.shouldBe
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFails
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class RunTest {

    private val login = BotPassword("TestBot", "task", "secret")
    private val pages = mapOf("Volcano" to "ash\n", "Etna" to "lava\n")

    private val tephra: (String, String) -> Any? = { text, _ ->
        if ("ash" in text) text.replace("ash", "tephra") else null
    }

    @Test
    fun `a dry run computes each change and saves nothing`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)
        val changes = mutableListOf<Change>()

        val report =
            wiki
                .run(arrayOf("Volcano", "Etna", "Nope"), "naming it", tephra, onChange = { changes += it })
                .await()

        listOf(report.processed, report.pending, report.saved, report.skipped, report.failed) shouldBe
            listOf(3, 1, 0, 2, 0)
        report.stopReason shouldBe null
        changes.map { Triple(it.title, it.diff, it.revision) } shouldBe
            listOf(Triple("Volcano", Diffs.unified("ash\n", "tephra\n", "Volcano"), null))
        mock.saved shouldBe emptyMap()
        wiki.close()
    }

    @Test
    fun `a run that saves saves each change with its summary`() = runTest {
        val mock = MockWiki(pages)
        val wiki = mock.open(login)
        val changes = mutableListOf<Change>()

        val report =
            wiki
                .run(
                    arrayOf("Volcano", "Etna"),
                    "naming it",
                    tephra,
                    save = true,
                    onChange = { changes += it },
                )
                .await()

        report.saved shouldBe 1
        mock.saved shouldBe mapOf("Volcano" to "tephra\n")
        mock.requests.single { it["action"] == "edit" }["summary"] shouldBe "naming it"
        changes.single().revision shouldBe 9002.0
        changes.single().diff shouldBe Diffs.unified("ash\n", "tephra\n", "Volcano")
        wiki.close()
    }

    @Test
    fun `a transform may answer with a promise`() = runTest {
        val wiki = MockWiki(pages).open(login)

        val report =
            wiki
                .run(arrayOf("Etna"), "adding", { text, title -> Promise.resolve("$text[[$title]]\n") })
                .await()

        report.pending shouldBe 1
        wiki.close()
    }

    @Test
    fun `a transform answering with neither text nor null stops the run with an error`() = runTest {
        val wiki = MockWiki(pages).open(login)

        val failure = assertFails { wiki.run(arrayOf("Etna"), "naming it", { _, _ -> 42 }).await() }

        failure.message shouldBe "the transform for Etna returned neither text nor null"
        wiki.close()
    }
}
