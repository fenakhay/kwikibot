package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.protocol.PageDecoder
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration

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
                    "byteoffset":196,"anchor":"Etymology"}]}}""",
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
    fun `a link that does not exist is reported as red`() = runTest {
        val service = service {
            respondJson(
                """{"parse":{"title":"Sandbox","links":[
                   {"ns":0,"title":"lava","exists":true},
                   {"ns":0,"title":"no such page","exists":false}]}}""",
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
                   {"sortkey":"VOLCANO","category":"English_terms_borrowed_from_Italian"}]}}""",
            )
        }

        val category = service.resolve(ref("volcano"), setOf(ParseProperty.CATEGORIES))
            .categories
            .single()

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

    private fun TestScope.service(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): RenderService {
        val transport = KtorTransport(
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
