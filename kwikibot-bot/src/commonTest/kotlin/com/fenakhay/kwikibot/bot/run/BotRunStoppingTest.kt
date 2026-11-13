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
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Which errors stop a run, and what becomes of the pages a stopped run already had in hand. */
class BotRunStoppingTest {

    private val pages = FakePageService("a" to "t", "b" to "t", "c" to "t", "d" to "t")

    /** Pages whose first save runs [during] before it is sent, and which keeps count of what was saved. */
    private inner class FirstSaveHeld(private val during: suspend () -> Unit) : PageService by pages {
        var edits = 0

        override suspend fun edit(ref: PageRef, block: EditBuilder.() -> Unit): EditOutcome {
            if (++edits == 1) during()
            return pages.edit(ref, block)
        }
    }

    @Test
    fun `a page queued behind the write permit sees a stop set while it waits`() = runTest {
        var allowed = true
        var checks = 0
        val held = FirstSaveHeld {
            repeat(200) {
                if (checks < 3) withContext(Dispatchers.Default) { delay(1) }
            }
            allowed = false
        }

        val report =
            botRun(
                held,
                StopPolicy {
                    checks++
                    allowed
                },
                null,
            ) {
                source(listOf(pages.ref("a"), pages.ref("b")).asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                readConcurrency = 2
                writeConcurrency = 1
            }

        held.edits shouldBe 1
        report.saved shouldBe 1
        report.notAttempted shouldBe 1
        report.stopReason shouldBe StopReason.POLICY
    }

    @Test
    fun `reaching the cap does not let a lost session keep saving`() = runTest {
        val capReached = CompletableDeferred<Unit>()
        val held = FirstSaveHeld {
            capReached.await()
            throw WikiError.Auth.NotLoggedIn("editing")
        }

        val report =
            botRun(held, StopPolicy.NONE, null) {
                source(listOf(pages.ref("a"), pages.ref("b"), pages.ref("c")).asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                readConcurrency = 3
                writeConcurrency = 1
                maxEdits = 2
                onOutcome = { if (it is PageOutcome.NotAttempted) capReached.complete(Unit) }
            }

        held.edits shouldBe 1
        report.stopReason shouldBe StopReason.AUTH
    }

    @Test
    fun `reaching the cap alone reports the stop as the policy`() = runTest {
        val report =
            botRun(pages, StopPolicy.NONE, null) {
                source(listOf(pages.ref("a"), pages.ref("b")).asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                readConcurrency = 1
                maxEdits = 1
            }

        report.saved shouldBe 1
        report.stopped shouldBe true
        report.stopReason shouldBe StopReason.POLICY
    }

    @Test
    fun `a policy that times out stops the run as unreachable`() = runTest {
        val report =
            botRun(pages, StopPolicy.NONE, null) {
                source(flowOf(pages.ref("a")))
                transform { Edit("new", "s") }
                dryRun = false
                stopPolicy = StopPolicy { withTimeout(1.milliseconds) { awaitCancellation() } }
            }

        report.stopReason shouldBe StopReason.POLICY_UNREACHABLE
    }

    @Test
    fun `cancelling a run during its stop check ends it instead of reporting a stop`() = runTest {
        val checking = CompletableDeferred<Unit>()
        var finished = false
        val run = launch {
            botRun(pages, StopPolicy.NONE, null) {
                source(flowOf(pages.ref("a")))
                transform { Edit("new", "s") }
                dryRun = false
                stopPolicy = StopPolicy {
                    checking.complete(Unit)
                    awaitCancellation()
                }
            }
            finished = true
        }

        checking.await()
        run.cancelAndJoin()

        finished shouldBe false
    }

    @Test
    fun `an error about one page or the api or a passing transport failure fails only that page`() = runTest {
        val errors =
            mapOf(
                "a" to WikiError.Transport.Unreachable("https://example.org/w/api.php"),
                "b" to WikiError.Api("badvalue", "Unrecognized value"),
                "c" to WikiError.Page.Missing(pages.ref("c").title),
            )

        val report =
            botRun(pages, StopPolicy.NONE, null) {
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
            botRun(gated, StopPolicy.NONE, null) {
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
            botRun(pages, StopPolicy.NONE, null) {
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

            botRun(forgetful, StopPolicy.NONE, null) {
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
