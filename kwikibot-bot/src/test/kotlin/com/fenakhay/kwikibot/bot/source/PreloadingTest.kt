package com.fenakhay.kwikibot.bot.source

import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

class PreloadingTest {
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
    fun `the batch is emitted in the order it was asked for, not the order it came back`() = runTest {
        val refs = listOf("geyser", "volcano").map { pages.ref(it) }.asFlow()

        refs.withContent(pages).toList().map { it.ref.title.text } shouldBe listOf("geyser", "volcano")
    }

    @Test
    fun `a batch of zero is refused rather than looping forever`() = runTest {
        val error = runCatching { flowOf(pages.ref("volcano")).withContent(pages, batch = 0) }

        error.isFailure shouldBe true
    }

    @Test
    fun `batches are fetched side by side and still come out in the order they went in`() = runTest {
        val letters = listOf("a", "b", "c", "d", "e", "f")
        val seeded = FakePageService(letters.associateWith { "text" })
        // The first batch is the slowest, so anything that emitted in completion order would show it.
        val recording =
            Recording(seeded) { refs ->
                if (refs.first().title.text == "a") 300.milliseconds else 100.milliseconds
            }

        val fetched =
            letters
                .map { seeded.ref(it) }
                .asFlow()
                .withContent(recording, batch = 1, concurrency = 3)
                .toList()

        fetched.map { it.ref.title.text } shouldBe letters
        recording.most shouldBe 3
        (currentTime < 600) shouldBe true
    }

    @Test
    fun `a collector that stops early stops the fetching too`() = runTest {
        val letters = List(20) { "p$it" }
        val seeded = FakePageService(letters.associateWith { "text" })
        val recording = Recording(seeded) { 100.milliseconds }

        letters
            .map { seeded.ref(it) }
            .asFlow()
            .withContent(recording, batch = 2, concurrency = 2)
            .take(3)
            .toList()

        // Two batches cover the three pages taken, and no more than two may be held at once.
        (recording.batches.size <= 4) shouldBe true
    }

    @Test
    fun `a wiki fetches as many pages per request as the account may ask for`() = runTest {
        val refs = List(600) { pages.ref("p$it") }
        val bot = Recording(pages)
        val plain = Recording(pages)
        val highLimits = Identity("Bot", id = 1, groups = setOf("bot"), rights = setOf("apihighlimits"))

        refs.asFlow().withContent(FakeWiki(bot, identity = highLimits)).toList()
        refs.asFlow().withContent(FakeWiki(plain)).toList()

        bot.batches shouldBe listOf(500, 100)
        plain.batches shouldBe List(12) { 50 }
    }

    @Test
    fun `a concurrency of zero is refused`() = runTest {
        val error = runCatching { flowOf(pages.ref("volcano")).withContent(pages, concurrency = 0) }

        error.isFailure shouldBe true
    }

    @Test
    fun `redirects can be kept or dropped from a stream`() = runTest {
        val stream =
            listOf(
                content("volcano", "==English=="),
                content("vulcan", "#REDIRECT [[volcano]]", redirectTo = "volcano"),
            )

        stream.asFlow().redirects().toList().map { it.ref.title.text } shouldBe listOf("vulcan")
        stream.asFlow().redirects(keep = false).toList().map { it.ref.title.text } shouldBe listOf("volcano")
    }

    /** Counts the batches asked for and how many were in flight at once, each taking [pause]. */
    private class Recording(
        private val delegate: PageService,
        private val pause: (Collection<PageRef>) -> Duration = { Duration.ZERO },
    ) : PageService by delegate {
        val batches = mutableListOf<Int>()
        var most = 0
        private var inFlight = 0

        override suspend fun contents(refs: Collection<PageRef>): Map<PageRef, PageContent> {
            batches += refs.size
            most = maxOf(most, ++inFlight)
            delay(pause(refs))
            inFlight--
            return delegate.contents(refs)
        }
    }
}
