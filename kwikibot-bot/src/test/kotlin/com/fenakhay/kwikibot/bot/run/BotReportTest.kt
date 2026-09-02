package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class BotReportTest {
    private val pages =
        FakePageService(
            "volcano" to "==English==",
            "vulcan" to "#REDIRECT [[volcano]]",
            "geyser" to "==English==",
        )

    private fun content(title: String, text: String, redirectTo: String? = null) =
        PageContent(
            pages.ref(title),
            RevisionId(1),
            text,
            redirectTarget = redirectTo?.let { pages.ref(it).title },
        )

    @Test
    fun `a report counts each outcome under its own heading`() {
        val report =
            BotReport(
                outcomes =
                    listOf(
                        PageOutcome.Saved(pages.ref("a"), RevisionId(2)),
                        PageOutcome.Saved(pages.ref("b"), RevisionId(3)),
                        PageOutcome.Pending(pages.ref("c"), Edit("new", "a summary"), before = "old"),
                        PageOutcome.Skipped(pages.ref("d"), "nothing to do"),
                        PageOutcome.Missing(pages.ref("e")),
                        PageOutcome.Refused(
                            pages.ref("f"),
                            EditOutcome.Protected(pages.ref("f"), "locked", "sysop"),
                        ),
                        PageOutcome.Failed(pages.ref("g"), IllegalStateException("network")),
                    )
            )

        report.processed shouldBe 7
        report.saved shouldBe 2
        report.pending shouldBe 1
        report.skipped shouldBe 2
        report.refused shouldBe 1
        report.failed shouldBe 1
        report.clean shouldBe false
    }

    @Test
    fun `a run with nothing refused or failed is clean`() {
        BotReport(listOf(PageOutcome.Saved(pages.ref("a"), RevisionId(2)))).clean shouldBe true
        BotReport(emptyList()).clean shouldBe true
    }

    @Test
    fun `a report prints as one line, which is what gets logged`() {
        val report =
            BotReport(
                outcomes = listOf(PageOutcome.Saved(pages.ref("a"), RevisionId(2))),
                stopped = true,
            )

        val printed = report.toString()

        printed shouldContain "processed=1"
        printed shouldContain "saved=1"
        printed shouldContain "stopped early"
        printed.lines().size shouldBe 1
    }

    @Test
    fun `a run that finished says nothing about stopping`() {
        BotReport(emptyList()).toString().contains("stopped") shouldBe false
    }
}
