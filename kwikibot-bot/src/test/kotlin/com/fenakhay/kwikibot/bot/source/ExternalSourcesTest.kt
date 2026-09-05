package com.fenakhay.kwikibot.bot.source

import com.fenakhay.kwikibot.client.model.SparqlAuth
import com.fenakhay.kwikibot.client.model.SparqlClient
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class ExternalSourcesTest {

    private val userAgent = UserAgent("test-bot", "1.0", "https://example.org/User:TestBot")

    @Test
    fun `a pagepile is a plain list of titles`() {
        val body = """{"status":"OK","pages":["Volcano","Mount Etna"],"pages_returned":2}"""

        ExternalSources.titlesFromPagePile(body) shouldBe listOf("Volcano", "Mount Etna")
    }

    @Test
    fun `an empty result is an empty list rather than a failure`() {
        ExternalSources.titlesFromPagePile("""{"status":"OK"}""") shouldBe emptyList()
    }

    @Test
    fun `a petscan answer is read a line at a time, whatever its size`() = runTest {
        val many = 50_000
        var peak = 0

        val client =
            HttpClient(
                MockEngine {
                    respond(
                        (1..many).joinToString(System.lineSeparator()) { "Page$it" },
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            )

        // Counted rather than collected, so the test never holds them all.
        ExternalSources.withPetScanTitles(mapOf("psid" to "7"), client, userAgent) { titles ->
            titles.forEach { peak++ }
        }

        peak shouldBe many
    }

    @Test
    fun `a pagepile source fetches its list and resolves the titles`() = runTest {
        var asked = ""
        val client =
            HttpClient(
                MockEngine { request ->
                    asked = request.url.toString()
                    respondJson("""{"status":"OK","pages":["Volcano","Mount Etna"],"pages_returned":2}""")
                }
            )

        val pages = ExternalSources.pagePile("42", client, userAgent).pages(FakeWiki()).toList()

        pages.map { it.title.text } shouldBe listOf("Volcano", "Mount Etna")
        asked.contains("id=42") shouldBe true
        asked.contains("action=get_data") shouldBe true
    }

    @Test
    fun `a petscan source fetches its list and resolves the titles`() = runTest {
        var asked = ""
        val client =
            HttpClient(
                MockEngine { request ->
                    asked = request.url.toString()
                    respondPlain("Volcano")
                }
            )

        val pages = ExternalSources.petScan("7", client, userAgent).pages(FakeWiki()).toList()

        pages.map { it.title.text } shouldBe listOf("Volcano")
        asked.contains("psid=7") shouldBe true
    }

    @Test
    fun `an external tool is told who is asking`() = runTest {
        var sent: String? = null
        val client =
            HttpClient(
                MockEngine { request ->
                    sent = request.headers[HttpHeaders.UserAgent]
                    respondJson("""{"status":"OK","pages":[]}""")
                }
            )

        ExternalSources.pagePile("42", client, userAgent).pages(FakeWiki()).toList()

        sent shouldBe userAgent.headerValue
    }

    @Test
    fun `a petscan query given directly is posted with its own parameters`() = runTest {
        var method = ""
        var body = ""
        val client =
            HttpClient(
                MockEngine { request ->
                    method = request.method.value
                    body = request.body.toByteArray().decodeToString()
                    respondPlain("Volcano")
                }
            )

        val source =
            ExternalSources.petScan(
                mapOf("language" to "en", "project" to "wiktionary", "categories" to "German lemmas"),
                client,
                userAgent,
            )
        val pages = source.pages(FakeWiki()).toList()

        method shouldBe "POST"
        body.contains("project=wiktionary") shouldBe true
        body.contains("German+lemmas") shouldBe true
        body.contains("doit=1") shouldBe true
        pages.map { it.title.text } shouldBe listOf("Volcano")
    }

    @Test
    fun `a sparql source resolves the titles it selects`() = runTest {
        val client =
            HttpClient(
                MockEngine {
                    respondTsv("?title", "\"Volcano\"", "\"Mount Etna\"")
                }
            )

        val source = ExternalSources.sparql("SELECT ?title WHERE {}", client, userAgent)

        source.pages(FakeWiki()).toList().map { it.title.text } shouldBe listOf("Volcano", "Mount Etna")
    }

    @Test
    fun `a sparql source can name its endpoint, variable and credentials`() = runTest {
        var url = ""
        var cookie: String? = null
        val client =
            HttpClient(
                MockEngine { request ->
                    url = request.url.toString()
                    cookie = request.headers[HttpHeaders.Cookie]
                    respondTsv("?page", "\"Volcano\"")
                }
            )

        val source =
            ExternalSources.sparql(
                query = "SELECT ?page WHERE {}",
                client = client,
                userAgent = userAgent,
                endpoint = SparqlClient.COMMONS,
                variable = "page",
                auth = SparqlAuth.wcqs("token"),
            )

        source.pages(FakeWiki()).toList().map { it.title.text } shouldBe listOf("Volcano")
        url shouldBe SparqlClient.COMMONS
        cookie shouldBe "wcqsOauth=token"
    }

    /** What PetScan answers with `format=plain`: one title per line. */
    private fun MockRequestHandleScope.respondPlain(vararg titles: String): HttpResponseData =
        respond(
            titles.joinToString(System.lineSeparator()),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "text/plain"),
        )

    /** What a query service answers with `format=tsv`: a header of variables, then a term per cell. */
    private fun MockRequestHandleScope.respondTsv(vararg lines: String): HttpResponseData =
        respond(
            lines.joinToString(System.lineSeparator()),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "text/tab-separated-values"),
        )

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
