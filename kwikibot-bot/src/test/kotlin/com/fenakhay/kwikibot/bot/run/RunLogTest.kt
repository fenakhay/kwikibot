package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.bot.fix.Diffs
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

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

    @Test
    fun `progress counts what happened and rewrites its line`() {
        val out = StringBuilder()
        val progress = Progress(total = 3, sink = out)

        progress(PageOutcome.Pending(ref("a"), Edit("t", "s"), "old"))
        progress(PageOutcome.Saved(ref("b"), RevisionId(2)))
        progress(PageOutcome.Skipped(ref("c"), "nothing to do"))
        progress.finish()

        val last = out.toString().split("\r").last()
        last shouldContain "3/3"
        last shouldContain "changed=2"
        last shouldContain "saved=1"
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
        val progress = Progress(total = null, sink = out)

        progress(PageOutcome.Saved(ref("a"), RevisionId(2)))

        out.toString() shouldContain "1 pages"
    }
}
