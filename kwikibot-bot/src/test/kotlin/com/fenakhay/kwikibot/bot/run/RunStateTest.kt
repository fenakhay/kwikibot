package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest

class RunStateTest {

    private val directory = Files.createTempDirectory("kwikibot-run")
    private val pages = FakePageService("a" to "x", "b" to "y", "c" to "z")

    private suspend fun run(state: RunState, resume: Boolean, transform: (String) -> String?): BotReport =
        botRun(pages) {
            source(listOf("a", "b", "c", "missing").map { pages.ref(it) }.asFlow())
            transform { page -> transform(page.text)?.let { Edit(it, "s") } }
            this.state = state
            this.resume = resume
            dryRun = false
        }

    @Test
    fun `a run records what it finished, what it decided on, and what it saved`() = runTest {
        RunState(directory).use { state ->
            run(state, resume = false) { text -> if (text == "x") "X" else null }
        }

        val finished = directory.resolve("finished.tsv").readLines()
        finished.map { it.substringBefore('\t') }.toSet() shouldBe setOf("a", "b", "c", "missing")
        finished.single { it.startsWith("a\t") } shouldBe "a\tsaved"

        val pins =
            directory.resolve("pins.tsv").readLines().associate {
                it.substringBefore('\t') to it.substringAfter('\t')
            }
        pins["a"] shouldBe pages.revision("a").toString()
        pins["b"] shouldBe FakePageService.INITIAL_REVISION.toString()

        val saved = directory.resolve("saves.jsonl").readText()
        saved shouldContain "\"title\":\"a\""
        saved shouldContain "\"summary\":\"s\""
    }

    @Test
    fun `a resumed run skips what was finished, before it is read`() = runTest {
        RunState(directory).use { state ->
            run(state, resume = false) { text -> if (text == "x") "X" else null }
        }
        val before = pages.edits.size

        val report = RunState(directory).use { state -> run(state, resume = true) { "changed" } }

        report.processed shouldBe 0
        pages.edits.size shouldBe before
    }

    @Test
    fun `a page read at the revision it was decided on is not decided again`() = runTest {
        RunState(directory).use { state ->
            state.record(PageOutcome.Unchanged(pages.ref("b")), RevisionId(FakePageService.INITIAL_REVISION))
        }
        // Finished pages are skipped before a pin is consulted, so take "b" off the finished list.
        Files.writeString(directory.resolve("finished.tsv"), "")

        var transformed = 0
        RunState(directory).use { state ->
            botRun(pages) {
                source(listOf(pages.ref("b")).asFlow())
                transform {
                    transformed++
                    Edit("new", "s")
                }
                this.state = state
                resume = true
            }
        }

        transformed shouldBe 0
    }

    @Test
    fun `failures and pages never reached are left for the next run`() {
        RunState(directory).use { state ->
            state.record(PageOutcome.Failed(pages.ref("a"), IllegalStateException("x")), null)
            state.record(PageOutcome.NotAttempted(pages.ref("b"), StopReason.AUTH), null)
            state.record(PageOutcome.Pending(pages.ref("c"), Edit("t", "s"), "old"), RevisionId(1))
            state.record(
                PageOutcome.Refused(pages.ref("d"), EditOutcome.Protected(pages.ref("d"), "protected")),
                RevisionId(1),
            )

            state.isFinished(pages.ref("a")) shouldBe false
            state.isFinished(pages.ref("b")) shouldBe false
            state.isFinished(pages.ref("c")) shouldBe false
            state.isFinished(pages.ref("d")) shouldBe false
            state.finishedCount shouldBe 0
            state.pin(pages.ref("c")) shouldBe null
            state.pin(pages.ref("d")) shouldBe null
        }
    }

    @Test
    fun `a page recorded again is written once, and its pin only when the revision changed`() {
        RunState(directory).use { state ->
            state.record(PageOutcome.Skipped(pages.ref("a"), "no"), RevisionId(1))
            state.record(PageOutcome.Skipped(pages.ref("a"), "no"), RevisionId(1))
            state.record(PageOutcome.Unchanged(pages.ref("a")), RevisionId(2))
            state.record(PageOutcome.Missing(pages.ref("b")), null)
        }

        directory.resolve("finished.tsv").readLines() shouldBe listOf("a\tskipped", "b\tmissing")
        directory.resolve("pins.tsv").readLines() shouldBe listOf("a\t1", "a\t2")
        RunState(directory).use { it.pin(pages.ref("a")) } shouldBe RevisionId(2)
    }

    @Test
    fun `a save with no revision before it and no edit records only the revision it made`() {
        RunState(directory).use { state ->
            state.record(PageOutcome.Saved(pages.ref("a"), RevisionId(5)), null)
        }

        directory.resolve("saves.jsonl").readText() shouldBe "{\"title\":\"a\",\"revision\":5}\n"
    }

    @Test
    fun `a pin that cannot be read is passed over`() {
        Files.writeString(directory.resolve("pins.tsv"), "a\tnot a revision\nb\t7\n")

        RunState(directory).use { state ->
            state.pin(pages.ref("a")) shouldBe null
            state.pin(pages.ref("b")) shouldBe RevisionId(7)
        }
    }

    @Test
    fun `a page edited since it was pinned is decided again`() = runTest {
        RunState(directory).use { state ->
            state.record(PageOutcome.Unchanged(pages.ref("b")), RevisionId(FakePageService.INITIAL_REVISION))
        }
        Files.writeString(directory.resolve("finished.tsv"), "")
        pages.edit(pages.ref("b")) { text = "edited by someone" }

        val report = RunState(directory).use { state -> run(state, resume = true) { "changed" } }

        report.saved shouldBe 3
        pages.text("b") shouldBe "changed"
    }

    @Test
    fun `resuming with no state to resume from works through every page`() = runTest {
        val report =
            botRun(pages) {
                source(listOf("a", "b").map { pages.ref(it) }.asFlow())
                transform { Edit("new", "s") }
                resume = true
            }

        report.pending shouldBe 2
    }
}
