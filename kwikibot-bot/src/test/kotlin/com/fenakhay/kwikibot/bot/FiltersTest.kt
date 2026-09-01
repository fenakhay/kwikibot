package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.client.PageService
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.PageContent
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class FiltersTest {

    private val wiki = WikiId("testwiki")

    private class CountingPages(private val delegate: PageService) : PageService by delegate {
        var requests: Int = 0
            private set

        override suspend fun contents(refs: Collection<PageRef>): Map<PageRef, PageContent> {
            requests++
            return delegate.contents(refs)
        }
    }

    private fun counting(texts: Map<String, String>) =
        CountingPages(FakePageService(texts, wiki))

    private fun ref(text: String, namespace: Namespace = Namespace.MAIN) =
        PageRef(wiki, Title.Local(namespace, text))

    private fun refs(vararg titles: String) = titles.map { ref(it) }.asFlow()

    @Test
    fun `namespaces narrow the stream, and an empty set lets everything through`() = runTest {
        val pages = listOf(
            ref("volcano"),
            ref("volcano", Namespace.TALK),
            ref("English lemmas", Namespace.CATEGORY),
        ).asFlow()

        pages.inNamespaces(setOf(Namespace.MAIN)).toList().map { it.namespace } shouldBe
            listOf(Namespace.MAIN)
        pages.inNamespaces(emptySet()).toList().size shouldBe 3
    }

    @Test
    fun `a page named twice is edited once`() = runTest {
        val pages = refs("a", "b", "a", "c", "b")

        pages.distinctPages().toList().map { it.title.text } shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `subpage depth counts slashes`() = runTest {
        val pages = refs("Talk", "Talk/Archive", "Talk/Archive/1")

        pages.subpageDepthAtMost(0).toList().map { it.title.text } shouldBe listOf("Talk")
        pages.subpageDepthAtMost(1).toList().size shouldBe 2
    }

    @Test
    fun `intersecting keeps what both streams name, and excluding keeps what only one does`() =
        runTest {
            refs("a", "b", "c").intersect(refs("b", "c", "d")).toList()
                .map { it.title.text } shouldBe listOf("b", "c")

            refs("a", "b", "c").excluding(refs("b")).toList()
                .map { it.title.text } shouldBe listOf("a", "c")
        }

    @Test
    fun `titles are filtered by pattern in both directions`() = runTest {
        val pages = refs("volcano", "volcanic", "mountain")

        pages.titleMatching(Regex("^volcan")).toList().size shouldBe 2
        pages.titleNotMatching(Regex("^volcan")).toList()
            .map { it.title.text } shouldBe listOf("mountain")
    }

    @Test
    fun `content is fetched in batches rather than one page at a time`() = runTest {
        val letters = ('a'..'f').map { it.toString() }
        val pages = counting(letters.associateWith { "text of $it" })

        val contents = letters.map { ref(it) }.asFlow().withContent(pages, batch = 2).toList()

        contents.map { it.text } shouldBe letters.map { "text of $it" }
        pages.requests shouldBe 3
    }

    @Test
    fun `a partial last batch is still fetched`() = runTest {
        val pages = counting(mapOf("a" to "1", "b" to "2", "c" to "3"))

        val contents = refs("a", "b", "c").withContent(pages, batch = 2).toList()

        contents.size shouldBe 3
        pages.requests shouldBe 2
    }

    @Test
    fun `a page that does not exist is dropped rather than faked`() = runTest {
        val pages = counting(mapOf("a" to "1", "c" to "3"))

        val contents = refs("a", "b", "c").withContent(pages).toList()

        contents.map { it.ref.title.text } shouldBe listOf("a", "c")
    }

    @Test
    fun `fetching stops when the collector does`() = runTest {
        var produced = 0
        val source = flow {
            repeat(TOO_MANY) {
                produced++
                emit(ref("page$it"))
            }
        }
        val pages = counting((0 until TOO_MANY).associate { "page$it" to "text" })

        val first = source.withContent(pages, batch = 5).take(3).toList()

        first.size shouldBe 3
        pages.requests shouldBe 1
        (produced <= 5) shouldBe true
    }

    @Test
    fun `text filters look at content, not titles`() = runTest {
        val contents = listOf(
            PageContent(ref("a"), RevisionId(1), "==English==\n{{col|en}}"),
            PageContent(ref("b"), RevisionId(1), "==French=="),
        ).asFlow()

        contents.textMatching(Regex("\\{\\{col")).toList()
            .map { it.ref.title.text } shouldBe listOf("a")
        contents.textNotMatching(Regex("\\{\\{col")).toList()
            .map { it.ref.title.text } shouldBe listOf("b")
    }

    private companion object {
        const val TOO_MANY = 100
    }
}
