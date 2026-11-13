package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.bot.BotPolicy
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/** What a run does when somebody saves a page between the run reading it and saving it. */
@OptIn(ExperimentalAtomicApi::class)
class BotRunConflictTest {

    private val pages = FakePageService("volcano" to "old", "lava" to "old")

    /** Saves [text] to [ref] as somebody else would, which leaves the run's read behind. */
    private suspend fun somebodySaves(ref: PageRef, text: String) {
        pages.edit(ref) {
            this.text = text
            summary = "by hand"
        }
    }

    @Test
    fun `a conflicted save reads the page again and edits what it says now`() = runTest {
        val tries = AtomicInt(0)

        val report =
            botRun(pages, StopPolicy.NONE, null) {
                source(flowOf(pages.ref("volcano")))
                transform { page ->
                    if (tries.incrementAndFetch() == 1) somebodySaves(page.ref, "theirs")
                    Edit(page.text + " and ours", "s")
                }
                dryRun = false
            }

        tries.load() shouldBe 2
        report.saved shouldBe 1
        report.refused shouldBe 0
        pages.text("volcano") shouldBe "theirs and ours"
    }

    @Test
    fun `a page that keeps conflicting is refused once its tries are spent`() = runTest {
        val tries = AtomicInt(0)

        val report =
            botRun(pages, StopPolicy.NONE, null) {
                source(flowOf(pages.ref("volcano")))
                transform { page ->
                    somebodySaves(page.ref, "theirs ${tries.incrementAndFetch()}")
                    Edit("ours", "s")
                }
                dryRun = false
                conflictRetries = 2
            }

        tries.load() shouldBe 3
        report.refused shouldBe 1
        (report.problems.single() as PageOutcome.Refused).outcome.shouldBeInstanceOf<EditOutcome.Conflict>()
        pages.text("volcano") shouldBe "theirs 3"
    }

    @Test
    fun `no retries reports the first conflict as it was`() = runTest {
        val tries = AtomicInt(0)

        val report =
            botRun(pages, StopPolicy.NONE, null) {
                source(flowOf(pages.ref("volcano")))
                transform { page ->
                    if (tries.incrementAndFetch() == 1) somebodySaves(page.ref, "theirs")
                    Edit("ours", "s")
                }
                dryRun = false
                conflictRetries = 0
            }

        tries.load() shouldBe 1
        report.refused shouldBe 1
        pages.text("volcano") shouldBe "theirs"
    }

    @Test
    fun `a page given nobots before the retry is left alone`() = runTest {
        val report =
            botRun(pages, StopPolicy.NONE, BotPolicy("FenaBot")) {
                source(flowOf(pages.ref("volcano")))
                transform { page ->
                    if (page.text == "old") somebodySaves(page.ref, "{{nobots}} theirs")
                    Edit("ours", "s")
                }
                dryRun = false
            }

        report.saved shouldBe 0
        report.skipped shouldBe 1
        pages.text("volcano") shouldBe "{{nobots}} theirs"
    }

    @Test
    fun `a retry counts once toward the cap on edits`() = runTest {
        val tries = AtomicInt(0)

        val report =
            botRun(pages, StopPolicy.NONE, null) {
                source(flowOf(pages.ref("volcano"), pages.ref("lava")))
                transform { page ->
                    if (page.ref.title.text == "volcano" && tries.incrementAndFetch() == 1) {
                        somebodySaves(page.ref, "theirs")
                    }
                    Edit(page.text + " and ours", "s")
                }
                dryRun = false
                readConcurrency = 1
                maxEdits = 1
            }

        report.saved shouldBe 1
        pages.text("volcano") shouldBe "theirs and ours"
        pages.text("lava") shouldBe "old"
    }

    @Test
    fun `a negative number of retries is refused`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            botRun(pages, StopPolicy.NONE, null) {
                source(flowOf(pages.ref("volcano")))
                transform { Edit("ours", "s") }
                conflictRetries = -1
            }
        }
    }
}
