package com.fenakhay.kwikibot.bot.run

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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.asFlow
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

        val report =
            botRun(pages) {
                source((1..20).map { ref("page$it") }.asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                readConcurrency = 1
                onOutcome = { seen += it }
            }

        report.failed shouldBe 1
        report.skipped shouldBe 19
        seen.count { it is PageOutcome.Skipped } shouldBe 19
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
    fun `a stop policy that cannot be checked stops the run`() = runTest {
        val pages = FakePageService(mapOf("a" to "t"))

        val failure =
            assertFailsWith<IllegalStateException> {
                botRun(pages) {
                    source(listOf(ref("a")).asFlow())
                    transform { Edit("new", "s") }
                    dryRun = false
                    stopPolicy = StopPolicy { error("stop service unreachable") }
                }
            }

        failure.message?.contains("refused") shouldBe true
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

        batched.map { it.ref } shouldBe singly.map { it.ref }
        many.pending shouldBe one.pending
        many.processed shouldBe one.processed
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
