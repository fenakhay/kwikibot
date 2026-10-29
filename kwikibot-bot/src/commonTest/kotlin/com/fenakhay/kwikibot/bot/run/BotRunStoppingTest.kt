package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.client.service.EditBuilder
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest

/** Which errors stop a run, and what becomes of the pages a stopped run already had in hand. */
class BotRunStoppingTest {

    private val pages = FakePageService("a" to "t", "b" to "t", "c" to "t", "d" to "t")

    @Test
    fun `an error about one page or the api or a passing transport failure fails only that page`() = runTest {
        val errors =
            mapOf(
                "a" to WikiError.Transport.Unreachable("https://example.org/w/api.php"),
                "b" to WikiError.Api("badvalue", "Unrecognized value"),
                "c" to WikiError.Page.Missing(pages.ref("c").title),
            )

        val report =
            botRun(pages) {
                source(listOf("a", "b", "c", "d").map { pages.ref(it) }.asFlow())
                transform { page -> errors[page.ref.title.text]?.let { throw it } ?: Edit("new", "s") }
                readConcurrency = 1
            }

        report.failed shouldBe 3
        report.pending shouldBe 1
        report.stopped shouldBe false
        report.problems.map { (it as PageOutcome.Failed).error } shouldBe errors.values.toList()
    }

    @Test
    fun `a page being read when another page stops the run is not attempted`() = runTest {
        val reported = CompletableDeferred<Unit>()
        val gated =
            object : PageService by pages {
                override suspend fun content(ref: PageRef): PageContent? {
                    if (ref == pages.ref("b")) reported.await()
                    return pages.content(ref)
                }
            }
        val seen = mutableListOf<PageOutcome>()

        val report =
            botRun(gated) {
                source(listOf(pages.ref("a"), pages.ref("b")).asFlow())
                transform { throw WikiError.Auth.NotLoggedIn("editing") }
                readConcurrency = 2
                onOutcome = {
                    seen += it
                    reported.complete(Unit)
                }
            }

        report.stopReason shouldBe StopReason.AUTH
        seen.last() shouldBe PageOutcome.NotAttempted(pages.ref("b"), StopReason.AUTH)
    }

    @Test
    fun `a page that reaches its save after another page stopped the run is not saved`() = runTest {
        val reported = CompletableDeferred<Unit>()

        val report =
            botRun(pages) {
                source(listOf(pages.ref("a"), pages.ref("b")).asFlow())
                transform { page ->
                    if (page.ref == pages.ref("a")) throw WikiError.Auth.NotLoggedIn("editing")
                    reported.await()
                    Edit("new", "s")
                }
                readBatch = 2
                dryRun = false
                onOutcome = { reported.complete(Unit) }
            }

        report.failed shouldBe 1
        report.notAttempted shouldBe 1
        report.stopReason shouldBe StopReason.AUTH
        pages.edits shouldBe emptyList()
    }

    @Test
    fun `a save the wiki reports without the revision it replaced keeps the one the page was read at`() =
        runTest {
            val forgetful =
                object : PageService by pages {
                    override suspend fun edit(ref: PageRef, block: EditBuilder.() -> Unit): EditOutcome {
                        val saved = pages.edit(ref, block).shouldBeInstanceOf<EditOutcome.Saved>()
                        return EditOutcome.Saved(saved.ref, saved.revision, previousRevision = null)
                    }
                }
            val seen = mutableListOf<PageOutcome>()

            botRun(forgetful) {
                source(listOf(pages.ref("a")).asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                onOutcome = { seen += it }
            }

            seen.single().shouldBeInstanceOf<PageOutcome.Saved>().previousRevision shouldBe
                RevisionId(FakePageService.INITIAL_REVISION)
        }

    @Test
    fun `a stop check that is cancelled is cancelled not a reason to stop`() = runTest {
        assertFailsWith<CancellationException> { StopPolicy { throw CancellationException("gone") }.check() }
    }
}
