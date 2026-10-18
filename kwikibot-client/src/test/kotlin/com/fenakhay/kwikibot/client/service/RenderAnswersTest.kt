package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiRenderService
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.render.LimitReport
import com.fenakhay.kwikibot.model.render.ParsedPage
import com.fenakhay.kwikibot.model.render.RenderedSection
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.testkit.MockTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * What a render service makes of a parse that says little, and how a service without its own parse parses.
 */
class RenderAnswersTest {

    private val wiki = WikiId("enwiktionary")
    private val volcano = PageRef(wiki, Title.Local(Namespace.MAIN, "volcano"))

    @Test
    fun `a parse with no HTML renders as empty text`() = runTest {
        val service = render("""{"parse":{}}""")

        service.render(volcano) shouldBe ""
        service.renderText("x", context = volcano) shouldBe ""
    }

    @Test
    fun `a parse reports the revision it parsed, and an empty external link is not one`() = runTest {
        val parsed =
            render("""{"parse":{"revid":42,"externallinks":["","https://example.org"]}}""")
                .resolve(volcano, setOf(ParseProperty.EXTERNAL_LINKS))

        parsed.revision shouldBe RevisionId(42)
        parsed.externalLinks shouldBe listOf("https://example.org")
    }

    @Test
    fun `a limit report row with no name or an unreadable value keeps its place as empty text`() = runTest {
        val parsed =
            render(
                    """{"parse":{"limitreportdata":[
                       {"name":"limitreport-expensivefunctioncount","0":3,"1":500},
                       {"0":{"value":1},"x":"ignored"},"not a row"]}}"""
                )
                .resolve(volcano, setOf(ParseProperty.LIMIT_REPORT))

        parsed.limitReport shouldBe
            LimitReport(mapOf("limitreport-expensivefunctioncount" to listOf("3", "500"), "" to listOf("")))
        parsed.limitReport?.expensiveFunctions shouldBe 3
    }

    @Test
    fun `a heading that says nothing is an unnumbered heading at no level`() = runTest {
        val nothing =
            RenderedSection("", "", level = 0, tocLevel = 0, number = "", anchor = "", byteOffset = null)

        render("""{"parse":{"tocdata":{"sections":[{},{"hLevel":-1}]}}}""").sections(volcano) shouldBe
            listOf(nothing, nothing)
        render("""{"parse":{"sections":[{}]}}""").sections(volcano) shouldBe listOf(nothing)
        render("""{"parse":{"tocdata":{}}}""").sections(volcano) shouldBe emptyList()
    }

    @Test
    fun `a link, a category or a file the wiki gives no name is not one`() = runTest {
        val parsed =
            render(
                    """{"parse":{"links":[{"exists":true}],"templates":[{"ns":10}],
                       "categories":[{"category":""},{"sortkey":"x"}],"images":[""]}}"""
                )
                .resolve(volcano, ParseProperty.ALL)

        parsed.links shouldBe emptyList()
        parsed.templates shouldBe emptyList()
        parsed.categories shouldBe emptyList()
        parsed.images shouldBe emptyList()
    }

    @Test
    fun `a render service that does not parse for itself answers a request through resolve`() = runTest {
        val asked = mutableListOf<String>()
        val service =
            object : RenderService by render("{}") {
                override suspend fun resolve(page: PageRef, properties: Set<ParseProperty>): ParsedPage {
                    asked += "page ${page.title.text}"
                    return ParsedPage(html = "page")
                }

                override suspend fun resolveText(
                    wikitext: String,
                    context: PageRef,
                    properties: Set<ParseProperty>,
                ): ParsedPage {
                    asked += "text $wikitext at ${context.title.text}"
                    return ParsedPage(html = "text")
                }

                override suspend fun parse(request: RenderRequest): ParsedPage =
                    super<RenderService>.parse(request)
            }

        service.parse(RenderRequest.page(volcano)).html shouldBe "page"
        service.parse(RenderRequest.text("[[lava]]", volcano)).html shouldBe "text"
        shouldThrow<IllegalStateException> {
            service.parse(RenderRequest.text("x", volcano).copy(revision = RevisionId(1)))
        }

        asked shouldBe listOf("page volcano", "text [[lava]] at volcano")
    }

    private fun render(answer: String): RenderService =
        ApiRenderService(
            transport = MockTransport { MockTransport.json(answer) },
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
        )
}
