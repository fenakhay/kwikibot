import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.RegExp
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.runTest

class PageSourceTest {

    private val mock =
        MockWiki(mapOf("Volcano" to "ash", "Etna" to "lava", "Redirect" to "#REDIRECT [[Etna]]"))

    private fun openAnd(block: suspend (Wiki) -> Unit) = runTest {
        val wiki = mock.open()
        block(wiki)
        wiki.close()
    }

    private fun titles(vararg titles: String) = pageSourceOfTitles(arrayOf(*titles))

    @Test
    fun `titles, a list of sources and nothing name their pages`() = openAnd { wiki ->
        titles("Volcano", "Etna").pages(wiki).take() shouldBe listOf("Volcano", "Etna")
        concatPageSources(arrayOf(titles("Volcano"), titles("Etna"))).pages(wiki).take() shouldBe
            listOf("Volcano", "Etna")
        EMPTY_PAGE_SOURCE.pages(wiki).take() shouldBe emptyList()
    }

    @Test
    fun `an iterable of a listing, an array or a function will do`() = openAnd { wiki ->
        pageSourceOf(wiki.search("x")).pages(wiki).take() shouldBe listOf("Volcano", "Volcanic ash")

        val plain = arrayOf("Etna").unsafeCast<AsyncIterable<String>>()
        pageSourceOf(plain).pages(wiki).take() shouldBe listOf("Etna")

        pageSourceFrom { opened -> opened.embeddedIn("Infobox") }.pages(wiki).take() shouldBe
            listOf("Volcano")
    }

    @Test
    fun `ending a walk early ends the iterable it reads`() = openAnd { wiki ->
        var ended = false
        val iterable: dynamic = js("({})")
        iterable[js("Symbol.asyncIterator")] = {
            val iterator: dynamic = js("({})")
            iterator.next = {
                val step: dynamic = js("({})")
                step.value = "Etna"
                step.done = false
                step
            }
            iterator["return"] = { ended = true }
            iterator
        }

        pageSourceOf(iterable.unsafeCast<AsyncIterable<String>>()).pages(wiki).take(1) shouldBe listOf("Etna")
        ended shouldBe true
    }

    @Test
    fun `a spec names a source, and a wrong one says what there is`() = openAnd { wiki ->
        parsePageSource("page:Volcano").pages(wiki).take() shouldBe listOf("Volcano")
        parsePageSources(arrayOf("page:Volcano", "page:Etna")).pages(wiki).take() shouldBe
            listOf("Volcano", "Etna")

        parsePageSource("search:lava").pages(wiki).take() shouldBe listOf("Volcano", "Volcanic ash")
        mock.requests.last { it["list"] == "search" }["srsearch"] shouldBe "lava"

        pageSourceKinds().toList() shouldBe
            listOf(
                "page",
                "cat",
                "subcats",
                "catfiles",
                "links",
                "backlinks",
                "transcludes",
                "search",
                "prefix",
                "allpages",
                "special",
                "usercontribs",
                "recentchanges",
                "file",
            )
        shouldThrow<IllegalArgumentException> { parsePageSource("nope:x") }.message shouldContain "available"
    }

    @Test
    fun `sources narrow, and leave the one they came from as it was`() = openAnd { wiki ->
        val all = titles("Volcano", "Etna", "User:Me", "Volcano", "Volcano/sub/deep")

        all.inNamespaces(arrayOf(2)).pages(wiki).take() shouldBe listOf("User:Me")
        all.inNamespaces(emptyArray()).pages(wiki).take().size shouldBe 5
        all.titleMatching(RegExp("^Etna$")).pages(wiki).take() shouldBe listOf("Etna")
        all.titleNotMatching(RegExp("V|M")).pages(wiki).take() shouldBe listOf("Etna")
        all.distinctPages().pages(wiki).take().size shouldBe 4
        all.subpageDepthAtMost(1).pages(wiki).take().size shouldBe 4
        all.intersect(titles("Etna", "Other")).pages(wiki).take() shouldBe listOf("Etna")
        all.excluding(titles("Volcano", "Volcano/sub/deep", "User:Me")).pages(wiki).take() shouldBe
            listOf("Etna")
    }

    @Test
    fun `a repeating source names only what is new`() = openAnd { wiki ->
        titles("Etna", "Etna", "Volcano").repeating(1.0, 10).pages(wiki).take(2) shouldBe
            listOf("Etna", "Volcano")
        titles("Etna").repeating().pages(wiki).take(1) shouldBe listOf("Etna")
    }

    @Test
    fun `pages with their text come in batches, and narrow on it`() = openAnd { wiki ->
        val source = titles("Volcano", "Etna", "Nope")

        val read = source.withContent(wiki, batch = 2, concurrency = 2).pages().take()
        read.map { it.title to it.text } shouldBe listOf("Volcano" to "ash", "Etna" to "lava")
        mock.requests.filter { "content" in it["rvprop"].orEmpty() }.map { it["titles"] } shouldBe
            listOf("Volcano|Etna", "Nope")

        source.withContent(wiki).textMatching(RegExp("ash")).pages().take().map { it.title } shouldBe
            listOf("Volcano")
        source.withContent(wiki).textNotMatching(RegExp("ash")).pages().take().map { it.title } shouldBe
            listOf("Etna")
        source.withContent(wiki).redirects(false).pages().take().size shouldBe 2
        source.withContent(wiki).redirects().pages().take().size shouldBe 0
    }

    @Test
    fun `PetScan, PagePile and a query service are read for pages`() = runTest {
        val scope = this
        val external = MockWiki()
        external.canned[""] = "Volcano\n\nEtna\n"
        val wiki = external.open()
        val contact = "https://example.org/me"
        val query: Record<String, String> = optionsOf("categories" to "Volcanoes")

        petScanSource(query, contact, null, external.engine).pages(wiki).take() shouldBe
            listOf("Volcano", "Etna")
        external.bodies.last() shouldContain "categories=Volcanoes"
        external.bodies.last() shouldContain "format=plain"

        petScanSavedSource("42", contact, UserAgent("Mine", "1", "c"), external.engine)
            .pages(wiki)
            .take() shouldBe listOf("Volcano", "Etna")
        external.requests.last()["psid"] shouldBe "42"

        petScanTitles(query, contact, null, external.engine).await().toList() shouldBe
            listOf("Volcano", "Etna")

        val walked = mutableListOf<String>()
        val answer =
            withPetScanTitles(
                    query,
                    contact,
                    { titles ->
                        answering(
                            scope.promise {
                                walked += titles.take()
                                walked.size
                            }
                        )
                    },
                    null,
                    external.engine,
                )
                .await()
        answer shouldBe 2
        walked shouldBe listOf("Volcano", "Etna")

        external.canned["get_data"] = """{"pages":["Etna"]}"""
        pagePileSource("7", contact, null, external.engine).pages(wiki).take() shouldBe listOf("Etna")
        external.requests.last()["id"] shouldBe "7"

        external.canned[""] = "?title\n\"Volcano\"\n"
        sparqlSource(
                "SELECT ?title",
                contact,
                optionsOf("endpoint" to "https://q.example.org/sparql", "auth" to sparqlAuthNone()),
                external.engine,
            )
            .pages(wiki)
            .take() shouldBe listOf("Volcano")
        wiki.close()
    }
}
