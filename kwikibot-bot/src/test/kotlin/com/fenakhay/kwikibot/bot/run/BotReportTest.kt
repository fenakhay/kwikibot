package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.collections.shouldHaveSize
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

    private fun refused(title: String) =
        PageOutcome.Refused(
            pages.ref(title),
            EditOutcome.Protected(pages.ref(title), "locked", "sysop"),
        )

    @Test
    fun `a report carries a count under each heading`() {
        val report =
            BotReport(
                processed = 7,
                saved = 2,
                pending = 1,
                skipped = 2,
                refused = 1,
                failed = 1,
                problems = listOf(refused("f")),
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
        BotReport(processed = 1, saved = 1).clean shouldBe true
        BotReport().clean shouldBe true
    }

    @Test
    fun `the problems it kept are the refusals and failures`() {
        val report = BotReport(processed = 2, refused = 1, failed = 1, problems = listOf(refused("f")))

        report.problems shouldHaveSize 1
        // Two went wrong and one was kept, so the list is not the whole of it.
        report.problemsTruncated shouldBe true
    }

    @Test
    fun `a report that kept every problem does not claim to be truncated`() {
        val report = BotReport(processed = 1, refused = 1, problems = listOf(refused("f")))

        report.problemsTruncated shouldBe false
    }

    @Test
    fun `a report prints as one line, which is what gets logged`() {
        val printed = BotReport(processed = 1, saved = 1, stopped = true).toString()

        printed shouldContain "processed=1"
        printed shouldContain "saved=1"
        printed shouldContain "stopped early"
        printed.lines().size shouldBe 1
    }

    @Test
    fun `a run that finished says nothing about stopping`() {
        BotReport().toString().contains("stopped") shouldBe false
    }
}
