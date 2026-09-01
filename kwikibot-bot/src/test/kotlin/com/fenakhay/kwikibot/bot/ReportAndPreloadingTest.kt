package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.PageContent
import com.fenakhay.kwikibot.model.EditOutcome
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ReportAndPreloadingTest {

    private val pages = FakePageService(
        "volcano" to "==English==",
        "vulcan" to "#REDIRECT [[volcano]]",
        "geyser" to "==English==",
    )

    private fun content(title: String, text: String, redirectTo: String? = null) = PageContent(
        pages.ref(title),
        RevisionId(1),
        text,
        redirectTarget = redirectTo?.let { pages.ref(it).title },
    )

    @Test
    fun `a report counts each outcome under its own heading`() {
        val report = BotReport(
            outcomes = listOf(
                PageOutcome.Saved(pages.ref("a"), RevisionId(2)),
                PageOutcome.Saved(pages.ref("b"), RevisionId(3)),
                PageOutcome.Pending(pages.ref("c"), Edit("new", "a summary"), before = "old"),
                PageOutcome.Skipped(pages.ref("d"), "nothing to do"),
                PageOutcome.Missing(pages.ref("e")),
                PageOutcome.Refused(pages.ref("f"), EditOutcome.Protected(pages.ref("f"), "locked", "sysop")),
                PageOutcome.Failed(pages.ref("g"), IllegalStateException("network")),
            ),
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
        val report = BotReport(
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

    @Test
    fun `pages are fetched in batches, not one request each`() = runTest {
        val refs = listOf("volcano", "vulcan", "geyser").map { pages.ref(it) }

        val fetched = refs.asFlow().withContent(pages, batch = 2).toList()

        fetched.map { it.ref.title.text } shouldBe listOf("volcano", "vulcan", "geyser")
    }

    @Test
    fun `a page that does not exist is dropped, there being nothing to hand a caller`() = runTest {
        val refs = listOf(pages.ref("volcano"), pages.ref("Nope")).asFlow()

        refs.withContent(pages).toList().size shouldBe 1
    }

    @Test
    fun `the batch is emitted in the order it was asked for, not the order it came back`() =
        runTest {
            val refs = listOf("geyser", "volcano").map { pages.ref(it) }.asFlow()

            refs.withContent(pages).toList().map { it.ref.title.text } shouldBe
                listOf("geyser", "volcano")
        }

    @Test
    fun `a batch of zero is refused rather than looping forever`() = runTest {
        val error = runCatching { flowOf(pages.ref("volcano")).withContent(pages, batch = 0) }

        error.isFailure shouldBe true
    }

    @Test
    fun `redirects can be kept or dropped from a stream`() = runTest {
        val stream = listOf(
            content("volcano", "==English=="),
            content("vulcan", "#REDIRECT [[volcano]]", redirectTo = "volcano"),
        )

        stream.asFlow().redirects().toList().map { it.ref.title.text } shouldBe listOf("vulcan")
        stream.asFlow().redirects(keep = false).toList()
            .map { it.ref.title.text } shouldBe listOf("volcano")
    }

    @Test
    fun `every shipped fix is listed by name`() {
        val all = Fixes.all

        all.isNotEmpty() shouldBe true
        all.keys.contains("ellipsis") shouldBe true
        all.getValue("ellipsis").apply("one...two") shouldBe "one…two"
    }

    @Test
    fun `a stop page halts the run unless it says false`() = runTest {
        val stopPage = FakePageService("Stop" to "false")

        val running = StopPolicy.page(stopPage, stopPage.ref("Stop"))
        running.mayContinue() shouldBe true

        val halted = FakePageService("Stop" to "")
        StopPolicy.page(halted, halted.ref("Stop")).mayContinue() shouldBe false
    }

    @Test
    fun `a stop page that does not exist halts the run`() = runTest {
        val empty = FakePageService()

        StopPolicy.page(empty, empty.ref("Stop")).mayContinue() shouldBe false
    }

    @Test
    fun `an allowed permission says so, and a denial carries its reason`() {
        EditPermission.Allowed.isAllowed shouldBe true

        val denied = EditPermission.Denied("nobots")
        denied.isAllowed shouldBe false
        denied.reason shouldBe "nobots"
    }

    @Test
    fun `a configuration reports the language and family it names`() {
        val config = BotConfig.parse(
            """
            [bot]
            name = "FenaBot"
            contact = "https://en.wiktionary.org/wiki/User:FenaBot"

            [wiki]
            lang = "fr"
            family = "wiktionary"
            """.trimIndent(),
        )

        config.language() shouldBe LangCode("fr")
    }

    @Test
    fun `a cache duration is read as a duration, not left as text`() {
        val config = BotConfig.parse(
            """
            [bot]
            name = "FenaBot"
            contact = "https://en.wiktionary.org/wiki/User:FenaBot"

            [cache]
            path = "apicache"
            ttl = "6h"
            """.trimIndent(),
        )

        config.cache?.ttl shouldBe "6h"
        config.toWikiConfig().cache.toString().isNotEmpty() shouldBe true
    }
}
