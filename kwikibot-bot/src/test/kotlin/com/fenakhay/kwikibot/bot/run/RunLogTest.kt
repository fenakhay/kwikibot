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
        val diffs = StringBuilder()
        val log = RunLog(diffs = diffs)

        log(PageOutcome.Pending(ref("volcano"), Edit("new text", "s"), "old text"))

        diffs.toString() shouldContain "=== diff: volcano ==="
        diffs.toString() shouldContain "-old text"
        diffs.toString() shouldContain "+new text"
    }

    @Test
    fun `a page outside the main namespace is logged with its prefix`() {
        val skips = StringBuilder()
        val appendix = PageRef(WikiId("testwiki"), Title.Local(Namespace(100), "Arabic roots/ك ت ب"))
        val template = PageRef(WikiId("testwiki"), Title.Local(Namespace.TEMPLATE, "l"))
        val namespaces =
            NamespaceMap(NamespaceMap.CANONICAL.all + NamespaceInfo(Namespace(100), "Appendix", "Appendix"))

        RunLog(skips = skips, namespaces = namespaces)(PageOutcome.Skipped(appendix, "no roots"))
        RunLog(skips = skips)(PageOutcome.Skipped(template, "nothing to do"))

        skips.toString() shouldContain "\"title\":\"Appendix:Arabic roots/ك ت ب\""
        skips.toString() shouldContain "\"title\":\"Template:l\""
    }

    @Test
    fun `a saved edit that carries its text is written as a diff, marked as saved`() {
        val diffs = StringBuilder()

        RunLog(diffs = diffs)(
            PageOutcome.Saved(ref("volcano"), RevisionId(2), RevisionId(1), Edit("new text", "s"), "old text")
        )

        diffs.toString() shouldContain "=== saved: volcano ==="
        diffs.toString() shouldContain "+new text"
    }

    @Test
    fun `a page the run never got to, and a section edit, leave nothing to read`() {
        val diffs = StringBuilder()
        val skips = StringBuilder()
        val log = RunLog(diffs = diffs, skips = skips)

        log(PageOutcome.NotAttempted(ref("volcano"), StopReason.POLICY))
        log(PageOutcome.Pending(ref("vog"), Edit("== a ==\nx", "s", section = "1"), "whole page"))

        diffs.toString() shouldBe ""
        skips.toString() shouldBe ""
    }

    @Test
    fun `a saved page needs no diff, since the wiki has the history`() {
        val diffs = StringBuilder()

        RunLog(diffs = diffs)(PageOutcome.Saved(ref("volcano"), RevisionId(2)))

        diffs.toString() shouldBe ""
    }

    @Test
    fun `skips are written as one JSON object per line`() {
        val skips = StringBuilder()
        val log = RunLog(skips = skips)

        log(PageOutcome.Skipped(ref("volcano"), "no English section"))
        log(PageOutcome.Missing(ref("nonexistent")))

        val lines = skips.toString().trim().lines()
        lines.size shouldBe 2
        lines[0] shouldContain """"title":"volcano""""
        lines[0] shouldContain """"reason":"no English section""""
        lines[1] shouldContain """"kind":"missing""""
    }

    @Test
    fun `a refusal records what the wiki said`() {
        val skips = StringBuilder()

        RunLog(skips = skips)(
            PageOutcome.Refused(
                ref("volcano"),
                EditOutcome.Protected(ref("volcano"), "page is protected"),
            )
        )

        skips.toString() shouldContain """"reason":"page is protected""""
    }

    @Test
    fun `a skip reason containing quotes stays valid JSON`() {
        val skips = StringBuilder()

        RunLog(skips = skips)(PageOutcome.Skipped(ref("volcano"), """ambiguous_pos:"Noun","Verb""""))

        skips.toString() shouldContain """\"Noun\",\"Verb\""""
    }
}

class ProgressTest {

    private fun ref(title: String) = PageRef(WikiId("testwiki"), Title.Local(Namespace.MAIN, title))

    private val clock = TestTimeSource()

    private fun progress(total: Int?, out: StringBuilder, width: Int = 200) =
        Progress(total, sink = out, width = width, timeSource = clock)

    @Test
    fun `progress counts what happened and rewrites its line`() {
        val out = StringBuilder()
        val progress = progress(total = 3, out)

        progress(PageOutcome.Pending(ref("a"), Edit("t", "s"), "old"))
        progress(PageOutcome.Saved(ref("b"), RevisionId(2)))
        progress(PageOutcome.Skipped(ref("c"), "nothing to do"))
        progress.finish()

        val last = out.toString().split("\r").last()
        last shouldContain "3/3"
        last shouldContain "would change 1"
        last shouldContain "saved 1"
        last shouldContain "skipped 1"
        last.endsWith("\n") shouldBe true
    }

    @Test
    fun `progress can be silenced`() {
        val out = StringBuilder()

        Progress(total = 1, sink = out, enabled = false)(PageOutcome.Missing(ref("a")))

        out.toString() shouldBe ""
    }

    @Test
    fun `a run of unknown length still reports how far it has come`() {
        val out = StringBuilder()

        progress(total = null, out)(PageOutcome.Saved(ref("a"), RevisionId(2)))

        out.toString() shouldContain "1 pages"
    }

    @Test
    fun `a fast run is redrawn a few times a second, not once a page`() {
        val out = StringBuilder()
        val progress = progress(total = 1000, out)

        repeat(1000) { progress(PageOutcome.Unchanged(ref("p$it"))) }
        clock += 250.milliseconds
        progress(PageOutcome.Unchanged(ref("last")))
        progress.finish()

        out.count { it == '\r' } shouldBe 3
    }

    @Test
    fun `the rate and the time left follow the last few seconds`() {
        val out = StringBuilder()
        val progress = progress(total = 10, out)

        repeat(5) {
            clock += 1.seconds
            progress(PageOutcome.Unchanged(ref("p$it")))
        }

        val last = out.toString().split("\r").last()
        last shouldContain "1.0/s"
        last shouldContain "5s left"
    }

    @Test
    fun `a narrow terminal loses the bar before anything else`() {
        val out = StringBuilder()
        val progress = progress(total = 10, out, width = 30)

        progress(PageOutcome.Unchanged(ref("a")))

        val line = out.toString().removePrefix("\r")
        line.startsWith("1/10") shouldBe true
        (line.length < 30) shouldBe true
    }

    @Test
    fun `pages a stopped run never reached are counted apart`() {
        val out = StringBuilder()
        val progress = progress(total = 2, out)

        progress(PageOutcome.Failed(ref("a"), IllegalStateException("x")))
        progress(PageOutcome.NotAttempted(ref("b"), StopReason.AUTH))
        progress.finish()

        val last = out.toString().split("\r").last()
        last shouldContain "1/2"
        last shouldContain "failed 1"
        last shouldContain "not attempted 1"
    }
}
