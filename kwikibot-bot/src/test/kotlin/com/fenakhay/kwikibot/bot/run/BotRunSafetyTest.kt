package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * What a run must not do to a wiki: recreate a page somebody deleted, create one nobody asked for, decide an
 * edit from text a cache kept, or carry on after it was told to stop.
 */
class BotRunSafetyTest {

    @Test
    fun `a stop policy can be checked before any work starts, and an unreadable one says stop`() = runTest {
        StopPolicy.NONE.check()
        assertFailsWith<IllegalStateException> { StopPolicy { false }.check() }
        assertFailsWith<IllegalStateException> { StopPolicy { error("the page could not be read") }.check() }
    }

    @Test
    fun `a page deleted after it was read is refused, not recreated`() = runTest {
        val pages = FakePageService("volcano" to "old")

        val report =
            botRun(pages) {
                source(flowOf(pages.ref("volcano")))
                transform { page ->
                    // Somebody deletes the page while the bot is working on it.
                    pages.delete(page.ref, reason = "")
                    Edit("new", "s")
                }
                dryRun = false
            }

        report.refused shouldBe 1
        pages.exists(pages.ref("volcano")) shouldBe false
        (report.problems.single() as PageOutcome.Refused).outcome.shouldBeInstanceOf<EditOutcome.Rejected>()
    }

    @Test
    fun `an update is sent from the revision it was read at, and asks the wiki not to create`() = runTest {
        val pages = FakePageService("volcano" to "old")

        botRun(pages) {
            source(flowOf(pages.ref("volcano")))
            transform { Edit("new", "s", tags = listOf("bot-tag")) }
            dryRun = false
        }

        val sent = pages.edits.single().second
        sent.noCreate shouldBe true
        sent.createOnly shouldBe false
        sent.baseRevision shouldBe RevisionId(FakePageService.INITIAL_REVISION)
        sent.tags shouldBe listOf("bot-tag")
    }

    @Test
    fun `a missing page is created only when the run asks for that, and only if nobody beat it to it`() =
        runTest {
            val pages = FakePageService("volcano" to "old")

            val report =
                botRun(pages) {
                    source(listOf("Fresh", "Gone", "Raced").map { pages.ref(it) }.asFlow())
                    transform { null }
                    createMissing { ref ->
                        when (ref.title.text) {
                            "Fresh" -> Edit("created", "creating")
                            "Raced" -> {
                                // Somebody else creates it first.
                                pages.edit(ref) { text = "theirs" }
                                Edit("mine", "creating")
                            }
                            else -> null
                        }
                    }
                    dryRun = false
                }

            pages.text("Fresh") shouldBe "created"
            pages.text("Raced") shouldBe "theirs"
            pages.exists(pages.ref("Gone")) shouldBe false
            report.saved shouldBe 1
            report.refused shouldBe 1
            report.skipped shouldBe 1
            pages.edits.first { it.first.title.text == "Fresh" }.second.createOnly shouldBe true
        }

    @Test
    fun `a dry run shows the page it would create`() = runTest {
        val pages = FakePageService()
        val seen = mutableListOf<PageOutcome>()

        botRun(pages) {
            source(flowOf(pages.ref("Fresh")))
            transform { null }
            createMissing { Edit("created", "creating") }
            onOutcome = { seen += it }
        }

        val pending = seen.single().shouldBeInstanceOf<PageOutcome.Pending>()
        pending.before shouldBe ""
        pending.baseRevision.shouldBeNull()
        pending.edit.createOnly shouldBe true
        pages.edits.isEmpty() shouldBe true
    }

    @Test
    fun `outcomes carry what a log needs to show the edit`() = runTest {
        val pages = FakePageService("volcano" to "old")
        val dry = mutableListOf<PageOutcome>()
        val live = mutableListOf<PageOutcome>()

        botRun(pages) {
            source(flowOf(pages.ref("volcano")))
            transform { Edit("new", "s") }
            onOutcome = { dry += it }
        }
        botRun(pages) {
            source(flowOf(pages.ref("volcano")))
            transform { Edit("new", "s") }
            dryRun = false
            onOutcome = { live += it }
        }

        dry.single().shouldBeInstanceOf<PageOutcome.Pending>().baseRevision shouldBe
            RevisionId(FakePageService.INITIAL_REVISION)
        val saved = live.single().shouldBeInstanceOf<PageOutcome.Saved>()
        saved.previousRevision shouldBe RevisionId(FakePageService.INITIAL_REVISION)
        saved.before shouldBe "old"
        saved.edit?.text shouldBe "new"
    }

    @Test
    fun `a run that saves reads the wiki as it is now, and a dry run may use a cache`() = runTest {
        val reads = mutableListOf<String>()
        val fake = FakePageService("volcano" to "old", "vog" to "old", "User:Bot/Stop" to "false")
        val pages = ReadCounting(fake, reads)
        val refs = listOf(fake.ref("volcano"), fake.ref("vog"))

        botRun(pages) {
            source(refs.asFlow())
            transform { Edit("new", "s") }
        }
        val dry = reads.toList()
        reads.clear()
        botRun(pages) {
            source(refs.asFlow())
            transform { Edit("new", "s") }
            dryRun = false
            stopPolicy = StopPolicy.page(pages, fake.ref("Bot/Stop", Namespace.USER))
        }

        dry.all { it != "fresh" } shouldBe true
        reads.all { it == "fresh" } shouldBe true
    }

    @Test
    fun `a stop policy that says stop, and one that cannot be read, are told apart`() = runTest {
        val pages = FakePageService("a" to "x", "b" to "x")
        var allow = true

        val stopped =
            botRun(pages) {
                source(listOf(pages.ref("a"), pages.ref("b")).asFlow())
                transform { Edit("y", "s") }
                dryRun = false
                readConcurrency = 1
                stopPolicy = StopPolicy { allow.also { allow = false } }
            }
        allow = true
        var checks = 0
        val unreachable =
            botRun(FakePageService("a" to "x")) {
                source(flowOf(pages.ref("a")))
                transform { Edit("y", "s") }
                dryRun = false
                stopPolicy = StopPolicy { if (checks++ == 0) true else error("stop page unreachable") }
            }

        stopped.stopReason shouldBe StopReason.POLICY
        stopped.stopped shouldBe true
        stopped.toString().contains("stopped early: policy") shouldBe true
        unreachable.stopReason shouldBe StopReason.POLICY_UNREACHABLE
        unreachable.notAttempted shouldBe 1
    }

    @Test
    fun `a batch reports each page as it finishes rather than when the whole batch has`() = runTest {
        val pages = FakePageService("slow" to "x", "fast" to "x")
        val fastDone = CompletableDeferred<Unit>()
        val seen = mutableListOf<PageOutcome>()

        botRun(pages) {
            source(listOf(pages.ref("slow"), pages.ref("fast")).asFlow())
            readBatch = 2
            transform { page ->
                // The slow page waits until the fast one has been reported, which only happens if a
                // batch reports each page as it finishes.
                if (page.ref.title.text == "slow") fastDone.await()
                Edit("y", "s")
            }
            onOutcome = { outcome ->
                seen += outcome
                if (outcome.ref.title.text == "fast") fastDone.complete(Unit)
            }
        }

        seen.map { it.ref.title.text } shouldBe listOf("fast", "slow")
    }

    /** A page service that records whether each read was allowed to come from a cache. */
    private class ReadCounting(private val inner: FakePageService, private val reads: MutableList<String>) :
        PageService by inner {
        override suspend fun content(ref: PageRef): PageContent? {
            reads += "cached"
            return inner.content(ref)
        }

        override suspend fun contents(refs: Collection<PageRef>): Map<PageRef, PageContent> {
            reads += "cached"
            return inner.contents(refs)
        }

        override suspend fun freshContents(refs: Collection<PageRef>): Map<PageRef, PageContent> {
            reads += "fresh"
            return inner.freshContents(refs)
        }
    }
}
