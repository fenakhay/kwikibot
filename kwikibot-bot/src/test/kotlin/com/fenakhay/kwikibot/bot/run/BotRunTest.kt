package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.client.service.EditBuilder
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest

class BotRunTest {

    private val wiki = WikiId("testwiki")

    private fun ref(text: String) = PageRef(wiki, Title.Local(Namespace.MAIN, text))

    @Test
    fun `a dry run computes edits without sending them`() = runTest {
        val pages = FakePageService(mapOf("volcano" to "old text", "vog" to "old text"))

        val report =
            botRun(pages) {
                source(listOf(ref("volcano"), ref("vog")).asFlow())
                transform { Edit(it.text + " more", "adding") }
            }

        report.pending shouldBe 2
        report.saved shouldBe 0
        pages.edits.isEmpty() shouldBe true
    }

    @Test
    fun `a live run saves and reports the new revisions`() = runTest {
        val pages = FakePageService(mapOf("volcano" to "old text"))

        val report =
            botRun(pages) {
                source(listOf(ref("volcano")).asFlow())
                transform { Edit(it.text + " more", "adding") }
                dryRun = false
            }

        report.saved shouldBe 1
        report.clean shouldBe true
        pages.edits.single().first shouldBe ref("volcano")
    }

    @Test
    fun `an edit is sent with the revision it was computed from`() = runTest {
        val pages = FakePageService(mapOf("volcano" to "old text"))

        botRun(pages) {
            source(listOf(ref("volcano")).asFlow())
            transform { Edit("new text", "adding") }
            dryRun = false
        }

        pages.edits.single().second.baseRevision shouldBe RevisionId(1)
    }

    @Test
    fun `returning null from the transform skips the page`() = runTest {
        val pages = FakePageService(mapOf("volcano" to "text", "vog" to "text"))

        val report =
            botRun(pages) {
                source(listOf(ref("volcano"), ref("vog")).asFlow())
                transform { if (it.title.text == "vog") Edit("new", "s") else null }
                dryRun = false
            }

        report.saved shouldBe 1
        report.skipped shouldBe 1
    }

    @Test
    fun `skip records the reason it was given`() = runTest {
        val pages = FakePageService(mapOf("volcano" to "text"))

        val seen = mutableListOf<PageOutcome>()

        val report =
            botRun(pages) {
                source(listOf(ref("volcano")).asFlow())
                transform { skip("no English section") }
                onOutcome = { seen += it }
            }

        report.skipped shouldBe 1
        val skipped = seen.single().shouldBeInstanceOf<PageOutcome.Skipped>()
        skipped.reason shouldBe "no English section"
    }

    @Test
    fun `text identical to the page is a no-op rather than an edit`() = runTest {
        val pages = FakePageService(mapOf("volcano" to "same"))

        val seen = mutableListOf<PageOutcome>()

        val report =
            botRun(pages) {
                source(listOf(ref("volcano")).asFlow())
                transform { Edit("same", "no real change") }
                dryRun = false
                onOutcome = { seen += it }
            }

        report.processed shouldBe 1
        seen.single().shouldBeInstanceOf<PageOutcome.Unchanged>()
        pages.edits.isEmpty() shouldBe true
    }

    @Test
    fun `a missing page is reported rather than failing the run`() = runTest {
        val pages = FakePageService(mapOf("volcano" to "text"))

        val seen = mutableListOf<PageOutcome>()

        val report =
            botRun(pages) {
                source(listOf(ref("volcano"), ref("nonexistent")).asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                onOutcome = { seen += it }
            }

        report.saved shouldBe 1
        seen.filterIsInstance<PageOutcome.Missing>().size shouldBe 1
    }

    @Test
    fun `a refusal is recorded against the page and the run continues`() = runTest {
        val pages =
            FakePageService(
                mapOf("volcano" to "text", "vog" to "text"),
                refuse = { ref ->
                    if (ref.title.text == "volcano") EditOutcome.Filtered(ref, "refused by test") else null
                },
            )

        val report =
            botRun(pages) {
                source(listOf(ref("volcano"), ref("vog")).asFlow())
                transform { Edit("new", "s") }
                dryRun = false
            }

        report.refused shouldBe 1
        report.saved shouldBe 1
        report.clean shouldBe false
    }

    @Test
    fun `a dead session stops the run instead of failing every page the same way`() = runTest {
        val pages =
            FakePageService(
                (1..20).associate { "page$it" to "text" },
                failWith = { WikiError.Auth.NotLoggedIn("editing") },
            )

        val seen = mutableListOf<PageOutcome>()
        var pulled = 0

        val report =
            botRun(pages) {
                source((1..20).map { ref("page$it") }.asFlow().onEach { pulled++ })
                transform { Edit("new", "s") }
                dryRun = false
                readConcurrency = 1
                onOutcome = { seen += it }
            }

        report.failed shouldBe 1
        report.skipped shouldBe 0
        report.stopReason shouldBe StopReason.AUTH
        // Only what was already in hand is reported, and the source is not drained to report the rest.
        (report.notAttempted <= 1) shouldBe true
        seen.count { it is PageOutcome.NotAttempted } shouldBe report.notAttempted
        (pulled <= 3) shouldBe true
    }

    @Test
    fun `a server that refuses the bot stops the run`() = runTest {
        val pages =
            FakePageService(
                (1..20).associate { "page$it" to "text" },
                failWith = { WikiError.Transport.Refused(403, "https://example.org/w/api.php", "blocked") },
            )

        val report =
            botRun(pages) {
                source((1..20).map { ref("page$it") }.asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                readConcurrency = 1
            }

        report.failed shouldBe 1
        report.stopReason shouldBe StopReason.REFUSED
        (report.notAttempted <= 1) shouldBe true
    }

    @Test
    fun `the stop policy is checked before every save, not just at the start`() = runTest {
        val pages = FakePageService(mapOf("a" to "t", "b" to "t", "c" to "t"))
        var allowed = 3

        val report =
            botRun(pages) {
                source(listOf(ref("a"), ref("b"), ref("c")).asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                readConcurrency = 1
                stopPolicy = StopPolicy { allowed-- > 0 }
            }

        report.saved shouldBe 2
        report.stopped shouldBe true
    }

    @Test
    fun `a stop by the stop policy takes no more pages from the source`() = runTest {
        val pages = FakePageService((1..20).associate { "page$it" to "text" })
        var allowed = 2
        var pulled = 0
        val seen = mutableListOf<PageOutcome>()

        val report =
            botRun(pages) {
                source((1..20).map { ref("page$it") }.asFlow().onEach { pulled++ })
                transform { Edit("new", "s") }
                dryRun = false
                readConcurrency = 1
                // Yes before the run starts and before the first save, then no.
                stopPolicy = StopPolicy { allowed-- > 0 }
                onOutcome = { seen += it }
            }

        report.saved shouldBe 1
        report.stopReason shouldBe StopReason.POLICY
        pages.edits.size shouldBe 1
        // The page refused its save and what was already in hand are reported; the rest are never asked for.
        (report.notAttempted <= 2) shouldBe true
        seen.count { it is PageOutcome.NotAttempted } shouldBe report.notAttempted
        (pulled <= 4) shouldBe true
    }

    @Test
    fun `a wiki gone read-only or not set up for the run stops it, as a dead session does`() = runTest {
        val stops =
            listOf(WikiError.ReadOnly("maintenance"), WikiError.Configuration.MissingExtension("Echo")).map {
                error ->
                botRun(FakePageService(mapOf("a" to "t", "b" to "t"), failWith = { error })) {
                        source(listOf(ref("a"), ref("b")).asFlow())
                        transform { Edit("new", "s") }
                        dryRun = false
                        readConcurrency = 1
                    }
                    .stopReason
            }

        stops shouldBe listOf(StopReason.READ_ONLY, StopReason.CONFIGURATION)
    }

    @Test
    fun `a save the wiki finds changes nothing is unchanged, not saved`() = runTest {
        val fake = FakePageService(mapOf("a" to "==One==\nx"))
        val pages =
            object : PageService by fake {
                override suspend fun edit(ref: PageRef, block: EditBuilder.() -> Unit): EditOutcome =
                    EditOutcome.NoChange(ref, RevisionId(1))
            }
        val seen = mutableListOf<PageOutcome>()

        val report =
            botRun(pages) {
                source(listOf(ref("a")).asFlow())
                // A section edit is always sent, since only the wiki can say whether it changes the page.
                transform { Edit("==One==\nx", "s", section = "1") }
                dryRun = false
                onOutcome = { seen += it }
            }

        seen shouldBe listOf(PageOutcome.Unchanged(ref("a")))
        report.processed shouldBe 1
        report.saved shouldBe 0
        report.clean shouldBe true
    }

    @Test
    fun `a run on a wiki works on that wiki's pages`() = runTest {
        val pages = FakePageService(mapOf("volcano" to "old"))

        val report =
            FakeWiki(pages).botRun {
                source(listOf(ref("volcano")).asFlow())
                transform { Edit("new", "s") }
                dryRun = false
            }

        report.saved shouldBe 1
        pages.text("volcano") shouldBe "new"
    }

    @Test
    fun `a stop policy that cannot be checked stops the run before anything is read`() = runTest {
        val pages = FakePageService(mapOf("a" to "t"))
        var pulled = 0

        val report =
            botRun(pages) {
                source(listOf(ref("a")).asFlow().onEach { pulled++ })
                transform { Edit("new", "s") }
                dryRun = false
                stopPolicy = StopPolicy { error("stop service unreachable") }
            }

        report shouldBe BotReport(stopped = true, stopReason = StopReason.POLICY_UNREACHABLE)
        pulled shouldBe 0
        pages.edits.isEmpty() shouldBe true
    }

    @Test
    fun `a stop page that does not say false stops the run before anything is read`() = runTest {
        val pages = FakePageService(mapOf("a" to "t"))

        val report =
            botRun(pages) {
                source(listOf(ref("a")).asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                stopPolicy = StopPolicy { false }
            }

        report.stopped shouldBe true
        report.stopReason shouldBe StopReason.POLICY
        report.processed shouldBe 0
        pages.edits.isEmpty() shouldBe true
    }

    @Test
    fun `a dry run needs no stop policy`() = runTest {
        val pages = FakePageService(mapOf("a" to "t"))

        val report =
            botRun(pages) {
                source(listOf(ref("a")).asFlow())
                transform { Edit("new", "s") }
                stopPolicy = StopPolicy { error("stop service unreachable") }
            }

        report.pending shouldBe 1
    }

    @Test
    fun `outcomes are reported as they happen`() = runTest {
        val pages = FakePageService(mapOf("a" to "t", "b" to "t"))
        val seen = mutableListOf<PageOutcome>()

        botRun(pages) {
            source(listOf(ref("a"), ref("b")).asFlow())
            transform { Edit("new", "s") }
            onOutcome = { seen += it }
        }

        seen.size shouldBe 2
    }

    @Test
    fun `a run needs a source and a transform`() = runTest {
        val pages = FakePageService(emptyMap())

        assertFailsWith<IllegalArgumentException> {
            botRun(pages) { transform { null } }
        }
        assertFailsWith<IllegalArgumentException> {
            botRun(pages) { source(emptyList<PageRef>().asFlow()) }
        }
    }

    @Test
    fun `by default a page is asked for one at a time`() = runTest {
        val pages = Counting(FakePageService(mapOf("a" to "x", "b" to "x", "c" to "x")))

        botRun(pages) {
            source(listOf(ref("a"), ref("b"), ref("c")).asFlow())
            transform { null }
        }

        pages.singles shouldBe 3
        pages.batches shouldBe 0
    }

    @Test
    fun `a batch reads the whole group in one request`() = runTest {
        val pages = Counting(FakePageService(mapOf("a" to "x", "b" to "x", "c" to "x")))

        botRun(pages) {
            source(listOf(ref("a"), ref("b"), ref("c")).asFlow())
            transform { null }
            readBatch = 50
        }

        pages.batches shouldBe 1
        pages.singles shouldBe 0
    }

    @Test
    fun `batching reaches the same verdict on every page`() = runTest {
        val texts = (1..7).associate { "p$it" to "old text" }
        val refs = texts.keys.map { ref(it) }

        val singly = mutableListOf<PageOutcome>()
        val batched = mutableListOf<PageOutcome>()

        val one =
            botRun(FakePageService(texts)) {
                source(refs.asFlow())
                transform { Edit(it.text + "!", "adding") }
                onOutcome = { singly += it }
            }
        val many =
            botRun(FakePageService(texts)) {
                source(refs.asFlow())
                transform { Edit(it.text + "!", "adding") }
                readBatch = 3
                onOutcome = { batched += it }
            }

        // Pages in a batch are worked on side by side, so they are reported as they finish.
        batched.map { it.ref }.toSet() shouldBe singly.map { it.ref }.toSet()
        batched.size shouldBe singly.size
        many.pending shouldBe one.pending
        many.processed shouldBe one.processed
    }

    @Test
    fun `a batch reports each page as soon as it is saved, before the next save is sent`() = runTest {
        val titles = listOf("a", "b", "c", "d")
        val reported = mutableListOf<PageRef>()
        val reportedAtSave = mutableListOf<Int>()
        val pages =
            FakePageService(
                titles.associateWith { "x" },
                refuse = {
                    reportedAtSave += reported.size
                    null
                },
            )

        botRun(pages) {
            source(titles.map { ref(it) }.asFlow())
            transform { Edit(it.text + "y", "s") }
            dryRun = false
            readBatch = 50
            writeConcurrency = 1
            onOutcome = { reported += it.ref }
        }

        reportedAtSave shouldBe listOf(0, 1, 2, 3)
        reported.toSet() shouldBe titles.map { ref(it) }.toSet()
    }

    @Test
    fun `a page that is not there is still reported missing when batching`() = runTest {
        val pages = FakePageService(mapOf("a" to "x"))

        val seen = mutableListOf<PageOutcome>()

        val report =
            botRun(pages) {
                source(listOf(ref("a"), ref("gone")).asFlow())
                transform { Edit(it.text + "!", "adding") }
                readBatch = 10
                onOutcome = { seen += it }
            }

        report.skipped shouldBe 1
        seen.filterIsInstance<PageOutcome.Missing>().single().ref shouldBe ref("gone")
    }

    @Test
    fun `a limit still counts pages, not batches`() = runTest {
        val pages = FakePageService((1..20).associate { "p$it" to "x" })

        val report =
            botRun(pages) {
                source((1..20).map { ref("p$it") }.asFlow())
                transform { null }
                readBatch = 6
                limit = 4
            }

        report.processed shouldBe 4
    }

    @Test
    fun `a batch that cannot be read fails its pages rather than the run`() = runTest {
        val pages =
            object : Counting(FakePageService(mapOf("a" to "x", "b" to "x"))) {
                override suspend fun contents(refs: Collection<PageRef>) =
                    throw WikiError.Transport.RateLimited(null)
            }

        val seen = mutableListOf<PageOutcome>()

        val report =
            botRun(pages) {
                source(listOf(ref("a"), ref("b")).asFlow())
                transform { Edit("new", "adding") }
                readBatch = 10
                onOutcome = { seen += it }
            }

        report.failed shouldBe 2
        seen.all { it is PageOutcome.Failed } shouldBe true
    }

    /** A page service that says how it was asked. */
    private open class Counting(private val real: FakePageService) : PageService by real {
        var singles: Int = 0
        var batches: Int = 0

        override suspend fun content(ref: PageRef): PageContent? {
            singles++
            return real.content(ref)
        }

        override suspend fun contents(refs: Collection<PageRef>): Map<PageRef, PageContent> {
            batches++
            return real.contents(refs)
        }
    }
}
