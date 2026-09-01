package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.protocol.PageDecoder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class PageActionsTest {

    private val wiki = WikiId("enwiktionary")

    private fun ref(text: String, namespace: Namespace = Namespace.MAIN) =
        PageRef(wiki, Title.Local(namespace, text))

    @Test
    fun `expanding text asks the wiki, because only the wiki knows what a template does`() = runTest {
        var asked = ""
        val service = service { request ->
            asked = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            respondJson("""{"expandtemplates":{"wikitext":"English"}}""")
        }

        service.expandText("{{lang|en}}", ref("volcano")) shouldBe "English"

        asked shouldContain "expandtemplates"
        asked shouldContain "volcano"
    }

    @Test
    fun `expanding is paced as a read, since it changes nothing`() = runTest {
        var asked = ""
        val service = service { request ->
            asked = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            respondJson("""{"expandtemplates":{"wikitext":"x"}}""")
        }

        service.expandText("x")

        asked.contains("meta=tokens") shouldBe false
    }

    @Test
    fun `a wiki that expands to nothing is an error, not an empty string`() = runTest {
        val service = service { respondJson("""{"expandtemplates":{}}""") }

        val error = assertFailsWith<WikiError.Api> { service.expandText("{{x}}") }
        error.code shouldBe "noexpansion"
    }

    @Test
    fun `exists answers from the page info, not from the title alone`() = runTest {
        val present = service {
            respondJson("""{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano"}]}}""")
        }
        val absent = service {
            respondJson("""{"query":{"pages":[{"ns":0,"title":"Nope","missing":true}]}}""")
        }

        present.exists(ref("volcano")) shouldBe true
        absent.exists(ref("Nope")) shouldBe false
    }

    @Test
    fun `a wiki that answers with no page at all reads as absent`() = runTest {
        val service = service { respondJson("""{"query":{"pages":[]}}""") }

        service.exists(ref("Nope")) shouldBe false
    }

    @Test
    fun `watching uses its own token type, which is not the edit token`() = runTest {
        val asked = mutableListOf<String>()
        val service = service { request ->
            val body = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            asked += body
            if ("meta=tokens" in body) {
                respondJson("""{"query":{"tokens":{"watchtoken":"WATCH"}}}""")
            } else {
                respondJson("""{"watch":[{"ns":0,"title":"volcano","watched":true}]}""")
            }
        }

        service.watch(listOf(ref("volcano")))

        asked.single { "meta=tokens" in it } shouldContain "watch"
        asked.single { "action=watch" in it && "meta=tokens" !in it } shouldContain "volcano"
    }

    @Test
    fun `unwatching says so explicitly, rather than omitting the flag`() = runTest {
        var write = ""
        val service = service { request ->
            val body = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            if ("meta=tokens" in body) {
                respondJson("""{"query":{"tokens":{"watchtoken":"WATCH"}}}""")
            } else {
                write = body
                respondJson("""{"watch":[{"ns":0,"title":"volcano","unwatched":true}]}""")
            }
        }

        service.watch(listOf(ref("volcano")), watch = false)

        write shouldContain "unwatch"
    }

    @Test
    fun `a watch expiry passes through, so a temporary watch stays temporary`() = runTest {
        var write = ""
        val service = service { request ->
            val body = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            if ("meta=tokens" in body) {
                respondJson("""{"query":{"tokens":{"watchtoken":"WATCH"}}}""")
            } else {
                write = body
                respondJson("""{"watch":[]}""")
            }
        }

        service.watch(listOf(ref("volcano")), expiry = "1 month")

        write shouldContain "expiry"
    }

    @Test
    fun `watching nothing asks the wiki nothing`() = runTest {
        val service = service { error("the wiki should not have been asked") }
        service.watch(emptyList())
    }

    @Test
    fun `watching many pages goes out in batches, not one request each`() = runTest {
        var writes = 0
        val service = service(batchSize = 2) { request ->
            val body = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            if ("meta=tokens" in body) {
                respondJson("""{"query":{"tokens":{"watchtoken":"WATCH"}}}""")
            } else {
                writes++
                respondJson("""{"watch":[]}""")
            }
        }

        service.watch(List(5) { ref("page$it") })

        writes shouldBe 3
    }

    @Test
    fun `an import carries the history and template flags it was asked for`() = runTest {
        var write = ""
        val service = service { request ->
            val body = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            if ("meta=tokens" in body) {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                write = body
                respondJson("""{"import":[{"ns":0,"title":"volcano","revisions":3}]}""")
            }
        }

        service.importPage(
            source = "enwiki",
            page = "Volcano",
            includeTemplates = true,
            rootPage = "Imported",
            summary = "moving a page across",
        )

        write shouldContain "interwikisource"
        write shouldContain "fullhistory"
        write shouldContain "templates"
        write shouldContain "rootpage"
    }

    @Test
    fun `an import without full history omits the flag rather than sending it off`() = runTest {
        var write = ""
        val service = service { request ->
            val body = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            if ("meta=tokens" in body) {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                write = body
                respondJson("""{"import":[]}""")
            }
        }

        service.importPage(source = "enwiki", page = "Volcano", fullHistory = false)

        write.contains("fullhistory") shouldBe false
        write.contains("templates") shouldBe false
    }

    @Test
    fun `an import the wiki refuses is raised, not swallowed`() = runTest {
        val service = service { request ->
            val body = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            if ("meta=tokens" in body) {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                respondJson(
                    """{"errors":[{"code":"cantimport","text":"Not permitted",
                       "module":"import"}]}""",
                )
            }
        }

        assertFailsWith<WikiError> { service.importPage("enwiki", "Volcano") }
    }

    private fun TestScope.service(
        batchSize: Int = 50,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PageService {
        val transport = KtorTransport(
            client = HttpClient(MockEngine(handler)),
            endpoint = ApiEndpoint("en.wiktionary.org"),
            userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
            throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
            retry = RetryPolicy.NONE,
        )
        return ApiPageService(
            transport = transport,
            tokens = TokenStore(transport),
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
            batchSize = batchSize,
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
