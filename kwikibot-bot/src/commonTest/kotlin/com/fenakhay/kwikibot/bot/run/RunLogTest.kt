package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.bot.fix.Diffs
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceInfo
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.readString

class DiffsTest {

    @Test
    fun `a diff shows the changed lines with context`() {
        val before = "==English==\n\n===Noun===\ntext\n"
        val after = "==English==\n\n===Noun===\ntext\n\n====Derived terms====\n"

        val diff = Diffs.unified(before, after, "volcano")

        diff shouldContain "--- old/volcano"
        diff shouldContain "+++ new/volcano"
        diff shouldContain "+====Derived terms===="
    }

    @Test
    fun `identical text produces no diff`() {
        Diffs.unified("same", "same", "volcano") shouldBe ""
    }

    @Test
    fun `adding a trailing newline is not reported as a rewritten last line`() {
        val diff = Diffs.unified("text", "text\n", "volcano")

        diff.lines().none { it.startsWith("-text") && it.contains("+text") } shouldBe true
    }
}

class RunLogTest {

    private fun ref(title: String) = PageRef(WikiId("testwiki"), Title.Local(Namespace.MAIN, title))

    @Test
    fun `a pending edit is written as a diff`() {
        val diffs = Buffer()
        val log = RunLog(diffs = diffs)

        log.record(PageOutcome.Pending(ref("volcano"), Edit("new text", "s"), "old text"))

        diffs.text() shouldContain "=== diff: volcano ==="
        diffs.text() shouldContain "-old text"
        diffs.text() shouldContain "+new text"
    }

    @Test
    fun `a page outside the main namespace is logged with its prefix`() {
        val skips = Buffer()
        val appendix = PageRef(WikiId("testwiki"), Title.Local(Namespace(100), "Arabic roots/ك ت ب"))
        val template = PageRef(WikiId("testwiki"), Title.Local(Namespace.TEMPLATE, "l"))
        val namespaces =
            NamespaceMap(NamespaceMap.CANONICAL.all + NamespaceInfo(Namespace(100), "Appendix", "Appendix"))

        RunLog(skips = skips, namespaces = namespaces).record(PageOutcome.Skipped(appendix, "no roots"))
        RunLog(skips = skips).record(PageOutcome.Skipped(template, "nothing to do"))

        skips.text() shouldContain "\"title\":\"Appendix:Arabic roots/ك ت ب\""
        skips.text() shouldContain "\"title\":\"Template:l\""
    }

    @Test
    fun `a saved edit that carries its text is written as a diff marked as saved`() {
        val diffs = Buffer()

        RunLog(diffs = diffs)
            .record(
                PageOutcome.Saved(
                    ref("volcano"),
                    RevisionId(2),
                    RevisionId(1),
                    Edit("new text", "s"),
                    "old text",
                )
            )

        diffs.text() shouldContain "=== saved: volcano ==="
        diffs.text() shouldContain "+new text"
    }

    @Test
    fun `a page the run never got to and a section edit leave nothing to read`() {
        val diffs = Buffer()
        val skips = Buffer()
        val log = RunLog(diffs = diffs, skips = skips)

        log.record(PageOutcome.NotAttempted(ref("volcano"), StopReason.POLICY))
        log.record(PageOutcome.Pending(ref("vog"), Edit("== a ==\nx", "s", section = "1"), "whole page"))

        diffs.text() shouldBe ""
        skips.text() shouldBe ""
    }

    @Test
    fun `a saved page needs no diff since the wiki has the history`() {
        val diffs = Buffer()

        RunLog(diffs = diffs).record(PageOutcome.Saved(ref("volcano"), RevisionId(2)))

        diffs.text() shouldBe ""
    }

    @Test
    fun `skips are written as one JSON object per line`() {
        val skips = Buffer()
        val log = RunLog(skips = skips)

        log.record(PageOutcome.Skipped(ref("volcano"), "no English section"))
        log.record(PageOutcome.Missing(ref("nonexistent")))

        val lines = skips.text().trim().lines()
        lines.size shouldBe 2
        lines[0] shouldContain """"title":"volcano""""
        lines[0] shouldContain """"reason":"no English section""""
        lines[1] shouldContain """"kind":"missing""""
    }

    @Test
    fun `a refusal records what the wiki said`() {
        val skips = Buffer()

        RunLog(skips = skips)
            .record(
                PageOutcome.Refused(
                    ref("volcano"),
                    EditOutcome.Protected(ref("volcano"), "page is protected"),
                )
            )

        skips.text() shouldContain """"reason":"page is protected""""
    }

    @Test
    fun `a failure records what went wrong`() {
        val skips = Buffer()

        RunLog(skips = skips).record(PageOutcome.Failed(ref("volcano"), IllegalStateException("timed out")))

        skips.text() shouldContain """"kind":"failed""""
        skips.text() shouldContain """"reason":"timed out""""
    }

    @Test
    fun `every handler is told of each outcome in the order they were given`() {
        val heard = mutableListOf<String>()
        val report = reportTo({ heard += "log ${it.ref.title}" }, { heard += "count ${it.ref.title}" })

        report(PageOutcome.Missing(ref("volcano")))
        report(PageOutcome.Missing(ref("lava")))

        heard shouldBe listOf("log volcano", "count volcano", "log lava", "count lava")
    }

    @Test
    fun `a page the transform left as it was is not logged`() {
        val diffs = Buffer()
        val skips = Buffer()

        RunLog(diffs = diffs, skips = skips).record(PageOutcome.Unchanged(ref("volcano")))

        diffs.text() shouldBe ""
        skips.text() shouldBe ""
    }

    @Test
    fun `a saved page the edit created is diffed against nothing`() {
        val diffs = Buffer()

        RunLog(diffs = diffs)
            .record(PageOutcome.Saved(ref("lava"), RevisionId(2), edit = Edit("new page", "create")))

        diffs.text() shouldContain "=== saved: lava ==="
        diffs.text() shouldContain "+new page"
    }

    @Test
    fun `an edit that changes nothing writes no diff`() {
        val diffs = Buffer()

        RunLog(diffs = diffs).record(PageOutcome.Pending(ref("volcano"), Edit("same", "s"), "same"))

        diffs.text() shouldBe ""
    }

    @Test
    fun `a log with nowhere to put a record drops it and writes the other kind where it was asked`() {
        val diffs = Buffer()
        val skips = Buffer()

        RunLog(skips = skips).record(PageOutcome.Pending(ref("volcano"), Edit("new", "s"), "old"))
        RunLog(diffs = diffs).record(PageOutcome.Missing(ref("lava")))

        diffs.text() shouldBe ""
        skips.text() shouldBe ""
    }

    @Test
    fun `each record is flushed as it is written so an interrupted run keeps it`() {
        val diffs = FlushCounting()
        val skips = FlushCounting()
        val log = RunLog(diffs = diffs.buffered(), skips = skips.buffered())

        log.record(PageOutcome.Pending(ref("volcano"), Edit("new", "s"), "old"))
        log.record(PageOutcome.Missing(ref("lava")))

        diffs.flushes shouldBe 1
        skips.flushes shouldBe 1
        skips.text() shouldContain "\"kind\":\"missing\""
    }

    @Test
    fun `a skip reason containing quotes stays valid JSON`() {
        val skips = Buffer()

        RunLog(skips = skips).record(PageOutcome.Skipped(ref("volcano"), """ambiguous_pos:"Noun","Verb""""))

        skips.text() shouldContain """\"Noun\",\"Verb\""""
    }
}

/** A sink that keeps what was written and counts how often it was flushed. */
class FlushCounting : RawSink {
    private val written = Buffer()

    var flushes = 0
        private set

    override fun write(source: Buffer, byteCount: Long) {
        written.write(source, byteCount)
    }

    override fun flush() {
        flushes++
    }

    override fun close() = Unit

    fun text(): String = written.text()
}

/** What was written to this buffer, leaving it as it was. */
private fun Buffer.text(): String = copy().readString()

class ProgressTest {

    private fun ref(title: String) = PageRef(WikiId("testwiki"), Title.Local(Namespace.MAIN, title))

    private val clock = TestTimeSource()

    private fun progress(total: Int?, out: Sink, width: Int = 200) =
        Progress(total, sink = out, width = width, timeSource = clock)

    @Test
    fun `progress counts what happened and rewrites its line`() {
        val out = Buffer()
        val progress = progress(total = 3, out)

        progress.record(PageOutcome.Pending(ref("a"), Edit("t", "s"), "old"))
        progress.record(PageOutcome.Saved(ref("b"), RevisionId(2)))
        progress.record(PageOutcome.Skipped(ref("c"), "nothing to do"))
        progress.finish()

        val last = out.text().split("\r").last()
        last shouldContain "3/3"
        last shouldContain "would change 1"
        last shouldContain "saved 1"
        last shouldContain "skipped 1"
        last.endsWith("\n") shouldBe true
    }

    @Test
    fun `progress can be silenced`() {
        val out = Buffer()

        Progress(total = 1, sink = out, enabled = false).record(PageOutcome.Missing(ref("a")))

        out.text() shouldBe ""
    }

    @Test
    fun `a run of unknown length still reports how far it has come`() {
        val out = Buffer()

        progress(total = null, out).record(PageOutcome.Saved(ref("a"), RevisionId(2)))

        out.text() shouldContain "1 pages"
    }

    @Test
    fun `a fast run is redrawn a few times a second not once a page`() {
        val out = Buffer()
        val progress = progress(total = 1000, out)

        repeat(1000) { progress.record(PageOutcome.Unchanged(ref("p$it"))) }
        clock += 250.milliseconds
        progress.record(PageOutcome.Unchanged(ref("last")))
        progress.finish()

        out.text().count { it == '\r' } shouldBe 3
    }

    @Test
    fun `the rate and the time left follow the last few seconds`() {
        val out = Buffer()
        val progress = progress(total = 10, out)

        repeat(5) {
            clock += 1.seconds
            progress.record(PageOutcome.Unchanged(ref("p$it")))
        }

        val last = out.text().split("\r").last()
        last shouldContain "1.0/s"
        last shouldContain "5s left"
    }

    @Test
    fun `a narrow terminal loses the bar before anything else`() {
        val out = Buffer()
        val progress = progress(total = 10, out, width = 30)

        progress.record(PageOutcome.Unchanged(ref("a")))

        val line = out.text().removePrefix("\r")
        line.startsWith("1/10") shouldBe true
        (line.length < 30) shouldBe true
    }

    @Test
    fun `a terminal too narrow even for the counts gets as much of them as fits`() {
        val out = Buffer()

        progress(total = 10, out, width = 12).record(PageOutcome.Unchanged(ref("a")))

        // One column is left free, so the cursor does not wrap onto the next line.
        out.text().removePrefix("\r") shouldBe "1/10  unchanged 1".take(11)
    }

    @Test
    fun `a refusal is counted with the failures`() {
        val out = Buffer()
        val progress = progress(total = 1, out)

        progress.record(PageOutcome.Refused(ref("a"), EditOutcome.Protected(ref("a"), "page is protected")))

        out.text() shouldContain "failed 1"
    }

    @Test
    fun `each drawing is flushed so the terminal shows it at once`() {
        val out = FlushCounting()
        val progress = Progress(total = 1, sink = out.buffered(), width = 200, timeSource = clock)

        progress.record(PageOutcome.Unchanged(ref("a")))
        progress.finish()

        out.flushes shouldBe 2
    }

    @Test
    fun `the rate leaves out what happened more than ten seconds ago`() {
        val out = Buffer()
        val progress = progress(total = null, out)

        clock += 1.seconds
        repeat(20) { progress.record(PageOutcome.Unchanged(ref("burst$it"))) }
        repeat(12) {
            clock += 1.seconds
            progress.record(PageOutcome.Unchanged(ref("p$it")))
        }

        out.text().split("\r").last() shouldContain "32 pages  1.0/s"
    }

    @Test
    fun `a run over no pages shows a full bar`() {
        val out = Buffer()
        val progress = progress(total = 0, out)

        progress.finish()

        out.text() shouldBe "\r[####################] 0/0\n"
    }

    @Test
    fun `the time left is shown in minutes and seconds when it is under an hour`() {
        val out = Buffer()
        val progress = progress(total = 1000, out)

        repeat(5) {
            clock += 1.seconds
            progress.record(PageOutcome.Unchanged(ref("p$it")))
        }

        out.text().split("\r").last() shouldContain "16m35s left"
    }

    @Test
    fun `the width is the one the shell reports or the fallback when it reports none that makes sense`() {
        terminalWidth(otherwise = 100) { if (it == "COLUMNS") "80" else null } shouldBe 80
        listOf(null, "0", "-5", "wide").map { columns -> terminalWidth(otherwise = 100) { columns } } shouldBe
            listOf(100, 100, 100, 100)
    }

    @Test
    fun `pages a stopped run never reached are counted apart`() {
        val out = Buffer()
        val progress = progress(total = 2, out)

        progress.record(PageOutcome.Failed(ref("a"), IllegalStateException("x")))
        progress.record(PageOutcome.NotAttempted(ref("b"), StopReason.AUTH))
        progress.finish()

        val last = out.text().split("\r").last()
        last shouldContain "1/2"
        last shouldContain "failed 1"
        last shouldContain "not attempted 1"
    }
}
