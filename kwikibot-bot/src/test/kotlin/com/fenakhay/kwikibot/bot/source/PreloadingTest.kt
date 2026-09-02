package com.fenakhay.kwikibot.bot.source

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
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
    fun `redirects can be kept or dropped from a stream`() = runTest {
        val stream =
            listOf(
                content("volcano", "==English=="),
                content("vulcan", "#REDIRECT [[volcano]]", redirectTo = "volcano"),
            )

        stream.asFlow().redirects().toList().map { it.ref.title.text } shouldBe listOf("vulcan")
        stream.asFlow().redirects(keep = false).toList().map { it.ref.title.text } shouldBe listOf("volcano")
    }
}
