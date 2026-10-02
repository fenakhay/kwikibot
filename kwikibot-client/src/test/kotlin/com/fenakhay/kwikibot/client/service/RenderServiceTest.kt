package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiRenderService
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class RenderServiceTest {

    private val wiki = WikiId("enwiktionary")

    private fun ref(text: String, namespace: Namespace = Namespace.MAIN) =
        PageRef(wiki, Title.Local(namespace, text))

    @Test
    fun `a section carries the index an edit will accept`() = runTest {
        val service = service {
            respondJson(
                """{"parse":{"title":"volcano","sections":[
                   {"toclevel":1,"level":"2","line":"English","number":"1","index":"1",
                    "byteoffset":17,"anchor":"English"},
                   {"toclevel":2,"level":"3","line":"Etymology","number":"1.2","index":"3",
                    "byteoffset":196,"anchor":"Etymology"}]}}"""
            )
        }

        val sections = service.sections(ref("volcano"))

        sections[1].index shouldBe "3"
        sections[1].heading shouldBe "Etymology"
        sections[1].level shouldBe 3
        sections[1].tocLevel shouldBe 2
        sections[0].byteOffset shouldBe 17
    }

    @Test
    fun `a current wiki is asked for its table of contents, and the headings read the same`() = runTest {
        val sent = mutableListOf<String>()
        val service = service { request ->
            if (request.url.parameters["action"] == "paraminfo") {
                respondJson(paramInfo("""["sections","text","tocdata"]""", deprecated = """["sections"]"""))
            } else {
                sent += request.url.parameters["prop"].orEmpty()
                // tocdata leaves out every key at its default: no offset for a heading a template
                // produced, and no linkAnchor when it equals the anchor. The third heading leaves out
                // more than a wiki would, to check the defaults.
                respondJson(
                    """{"parse":{"title":"volcano","tocdata":{"sections":[
                       {"tocLevel":1,"hLevel":2,"line":"English","number":"1","index":"1",
                        "fromTitle":"Volcano","codepointOffset":17,"anchor":"English"},
                       {"tocLevel":2,"hLevel":3,"line":"Etymology","number":"1.2","index":"3",
                        "fromTitle":"Volcano","codepointOffset":196,"anchor":"Etymology",
                        "linkAnchor":"Etymology_2"},
                       {"line":"From a template","number":"1.3","index":"T-1","anchor":"From_a_template"}],
                       "extensionData":{}}}}"""
                )
            }
        }

        val sections = service.sections(ref("volcano"))

        sent shouldBe listOf("tocdata")
        sections[1].index shouldBe "3"
        sections[1].heading shouldBe "Etymology"
        sections[1].level shouldBe 3
        sections[1].tocLevel shouldBe 2
        sections[0].byteOffset shouldBe 17
        sections[2].index shouldBe "T-1"
        sections[2].level shouldBe 0
        sections[2].byteOffset shouldBe null
    }

    @Test
    fun `a wiki too old for the table of contents is asked for sections instead`() = runTest {
        val sent = mutableListOf<String>()
        val service = service { request ->
            if (request.url.parameters["action"] == "paraminfo") {
                respondJson(paramInfo("""["sections","text"]"""))
            } else {
                sent += request.url.parameters["prop"].orEmpty()
                respondJson(
                    """{"parse":{"sections":[{"toclevel":1,"level":"2","line":"English","number":"1",
                       "index":"1","byteoffset":17,"anchor":"English"}]}}"""
                )
            }
        }

        service.sections(ref("volcano")).single().heading shouldBe "English"
        sent shouldBe listOf("sections")
    }

    @Test
    @Suppress("DEPRECATION")
    fun `the deprecated entry is sent the way its replacement is, and only once beside it`() = runTest {
        val sent = mutableListOf<String>()
        val service = service { request ->
            if (request.url.parameters["action"] == "paraminfo") {
                respondJson(paramInfo("""["links","sections","tocdata"]""", deprecated = """["sections"]"""))
            } else {
                sent += request.url.parameters["prop"].orEmpty()
                respondJson("""{"parse":{}}""")
            }
        }

        service.resolve(
            ref("volcano"),
            setOf(ParseProperty.SECTIONS, ParseProperty.TOC_DATA, ParseProperty.LINKS),
        )

        sent shouldBe listOf("tocdata|links")
    }

    @Test
    @Suppress("DEPRECATION")
    fun `everything the service models leaves out what is kept only for compatibility`() {
        (ParseProperty.SECTIONS in ParseProperty.ALL) shouldBe false
        (ParseProperty.TOC_DATA in ParseProperty.ALL) shouldBe true
    }

    @Test
    fun `a link that does not exist is reported as red`() = runTest {
        val service = service {
            respondJson(
                """{"parse":{"title":"Sandbox","links":[
                   {"ns":0,"title":"lava","exists":true},
                   {"ns":0,"title":"no such page","exists":false}]}}"""
            )
        }

        val links = service.resolve(ref("Sandbox"), setOf(ParseProperty.LINKS)).links

        links.single { it.page.title.text == "lava" }.exists shouldBe true
        links.single { it.page.title.text == "no such page" }.exists shouldBe false
    }

    @Test
    fun `a category comes back as a title, not as a database key`() = runTest {
        val service = service {
            respondJson(
                """{"parse":{"title":"volcano","categories":[
                   {"sortkey":"VOLCANO","category":"English_terms_borrowed_from_Italian"}]}}"""
            )
        }

        val category = service.resolve(ref("volcano"), setOf(ParseProperty.CATEGORIES)).categories.single()

        category.title.text shouldBe "English terms borrowed from Italian"
        category.title.namespace shouldBe Namespace.CATEGORY
    }

    @Test
    fun `unsaved wikitext is parsed as a title, so magic words have an answer`() = runTest {
        var url = ""
        val service = service { request ->
            url = request.url.toString()
            respondJson("""{"parse":{"title":"Sandbox","text":"<p>x</p>"}}""")
        }

        service.renderText("hello", context = ref("Sandbox")) shouldContain "<p>x</p>"

        url shouldContain "text=hello"
        url shouldContain "title=Sandbox"
        url shouldContain "contentmodel=wikitext"
    }

    @Test
    fun `asking for nothing is refused rather than sent`() = runTest {
        var calls = 0
        val service = service {
            calls++
            respondJson("{}")
        }

        shouldThrow<IllegalArgumentException> { service.resolve(ref("volcano"), emptySet()) }
        calls shouldBe 0
    }

    @Test
    fun `a response with no parse block reads as empty rather than failing`() = runTest {
        val service = service { respondJson("{}") }

        service.resolve(ref("volcano")).links shouldBe emptyList()
    }

    private fun paramInfo(values: String, deprecated: String = "[]"): String =
        """{"paraminfo":{"modules":[{"name":"parse","path":"parse","prefix":"","source":"MediaWiki",
           "parameters":[{"name":"prop","type":$values,"multi":true,"deprecatedvalues":$deprecated}]}]}}"""

    private fun TestScope.service(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): RenderService {
        val transport =
            KtorTransport(
                client = HttpClient(MockEngine(handler)),
                endpoint = ApiEndpoint("en.wiktionary.org"),
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            )
        return ApiRenderService(
            transport = transport,
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
