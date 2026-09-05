package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
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
import javax.xml.stream.XMLStreamException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

private val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")

private const val BLANK_LINE = "\n\n"

class EventStreamsTest {

    private val stream =
        """
        :ok

        id: [{"topic":"codfw.mediawiki.recentchange","offset":42}]
        data: {"wiki":"enwiktionary","type":"edit","title":"volcano","namespace":0,
        data: "user":"Someone","comment":"typo","timestamp":1756684800,
        data: "bot":false,"minor":false,"revision":{"old":1,"new":2}}

        data: {"wiki":"enwiki","type":"new","title":"Volcano","namespace":0,"user":"Other"}
        """
            .trimIndent() + BLANK_LINE

    @Test
    fun `events are read off the stream and decoded`() = runTest {
        val events = streams(stream).recentChanges().take(2).toList()

        events.first().wiki shouldBe "enwiktionary"
        events.first().title shouldBe "volcano"
        events.first().revisionId shouldBe 2L
        events.first().previousRevisionId shouldBe 1L
        events.last().type shouldBe "new"
    }

    @Test
    fun `a multi-line payload is one event, not several`() = runTest {
        streams(stream).recentChanges().take(1).toList().single().comment shouldBe "typo"
    }

    @Test
    fun `the stream offset is kept, so a stopped bot can resume`() = runTest {
        val first = streams(stream).recentChanges().take(1).toList().single()

        first.offset?.contains("offset") shouldBe true
    }

    @Test
    fun `a keep-alive comment is not an event`() = runTest {
        streams(stream).recentChanges().take(2).toList().size shouldBe 2
    }

    @Test
    fun `an unreadable payload is skipped rather than ending the run`() = runTest {
        val withGarbage =
            """
            data: {not json at all

            data: {"wiki":"enwiktionary","type":"edit","title":"volcano","namespace":0}
            """
                .trimIndent() + BLANK_LINE

        val events = streams(withGarbage).recentChanges().toList()

        events.single().title shouldBe "volcano"
    }

    @Test
    fun `matching filters on wiki and namespace together`() = runTest {
        val events = streams(stream).recentChanges().take(2).toList()

        events.count { it.matches("enwiktionary") } shouldBe 1
        events.count { it.matches("enwiktionary", setOf(4)) } shouldBe 0
    }

    @Test
    fun `an event with no blank line after it is not delivered`() = runTest {
        val cutOff =
            """
            data: {"wiki":"enwiktionary","type":"edit","title":"volcano","namespace":0}

            data: {"wiki":"enwiktionary","type":"edit","title":"half
            """
                .trimIndent()

        streams(cutOff).recentChanges().toList().size shouldBe 1
    }

    private fun streams(body: String) =
        EventStreams(
            HttpClient(
                MockEngine {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
            ),
            userAgent,
        )
}

class SparqlClientTest {

    private val results =
        """
        {"head":{"vars":["item","itemLabel"]},
         "results":{"bindings":[
           {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q7889"},
            "itemLabel":{"type":"literal","xml:lang":"en","value":"video game"}},
           {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q42"}}]}}
        """
            .trimIndent()

    @Test
    fun `rows are returned by variable name`() = runTest {
        val rows = client { respondJson(results) }.select("SELECT ?item WHERE {}")

        rows.size shouldBe 2
        rows.first().getValue("itemLabel").value shouldBe "video game"
        rows.first().getValue("itemLabel").language shouldBe "en"
    }

    @Test
    fun `an entity URI is reduced to the id every other part of the library wants`() = runTest {
        val rows = client { respondJson(results) }.select("SELECT ?item WHERE {}")

        rows.map { it.getValue("item").entityId } shouldBe listOf("Q7889", "Q42")
    }

    @Test
    fun `a literal is not mistaken for an entity`() = runTest {
        val rows = client { respondJson(results) }.select("SELECT ?item WHERE {}")

        rows.first().getValue("itemLabel").entityId.shouldBeNull()
    }

    @Test
    fun `the query is posted, not put in the URL`() = runTest {
        var method = ""
        var body = ""
        val client = client { request ->
            method = request.method.value
            body = request.body.toByteArray().decodeToString()
            respondJson(results)
        }

        client.select("SELECT ?item WHERE { ?item wdt:P31 wd:Q7889 }")

        method shouldBe "POST"
        body.contains("wdt%3AP31") shouldBe true
    }

    @Test
    fun `an error page is reported as a failed query, not as no results`() = runTest {
        val client = client {
            respond(
                "<html><body>MalformedQueryException: Encountered \" \"WHERE\"</body></html>",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }

        val error = assertFailsWith<WikiError.Api> { client.select("SELECT nonsense") }

        error.info.contains("MalformedQueryException") shouldBe true
    }

    @Test
    fun `a token is sent as the cookie the query service reads`() = runTest {
        var cookie: String? = null
        val client =
            client(auth = SparqlAuth.wcqs("secret-token")) { request ->
                cookie = request.headers[HttpHeaders.Cookie]
                respondJson(results)
            }

        client.select("SELECT ?item WHERE {}")

        cookie shouldBe "wcqsOauth=secret-token"
    }

    @Test
    fun `a public service is asked without credentials`() = runTest {
        var cookie: String? = "set"
        val client = client { request ->
            cookie = request.headers[HttpHeaders.Cookie]
            respondJson(results)
        }

        client.select("SELECT ?item WHERE {}")

        cookie.shouldBeNull()
    }

    @Test
    fun `a busy service is asked again rather than reported as empty`() = runTest {
        var attempts = 0
        val client = client {
            attempts++
            if (attempts == 1) respond("", HttpStatusCode.TooManyRequests) else respondJson(results)
        }

        val rows = client.select("SELECT ?item WHERE {}")

        attempts shouldBe 2
        rows.size shouldBe 2
    }

    @Test
    fun `the wait the service asks for is honoured`() = runTest {
        var attempts = 0
        val client = client {
            attempts++
            if (attempts == 1) {
                respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30"))
            } else {
                respondJson(results)
            }
        }

        client.select("SELECT ?item WHERE {}")

        currentTime shouldBe 30_000L
    }

    @Test
    fun `a server error is retried too`() = runTest {
        var attempts = 0
        val client = client {
            attempts++
            if (attempts == 1) respond("", HttpStatusCode.ServiceUnavailable) else respondJson(results)
        }

        client.select("SELECT ?item WHERE {}")

        attempts shouldBe 2
    }

    @Test
    fun `giving up says the service was busy, not that the query was wrong`() = runTest {
        var attempts = 0
        val client =
            client(retry = RetryPolicy(maxRetries = 2)) {
                attempts++
                respond("", HttpStatusCode.TooManyRequests)
            }

        val error = assertFailsWith<WikiError.Api> { client.select("SELECT ?item WHERE {}") }

        attempts shouldBe 3
        error.info.contains("429") shouldBe true
    }

    private fun client(
        auth: SparqlAuth = SparqlAuth.None,
        retry: RetryPolicy = RetryPolicy(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = SparqlClient(HttpClient(MockEngine(handler)), userAgent, SparqlClient.WIKIDATA, auth, retry)

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}

/**
 * The streaming form of the same queries.
 *
 * These check that the streamed form reads the same things out of the TSV the service writes for
 * `format=tsv`, where a term is written the way Turtle writes it.
 */
class SparqlStreamingTest {

    private val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")

    private val tab = "\t"

    private fun rows(vararg lines: String) = lines.joinToString(System.lineSeparator())

    private fun client(
        retry: RetryPolicy = RetryPolicy(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) =
        SparqlClient(
            HttpClient(MockEngine(handler)),
            userAgent,
            SparqlClient.WIKIDATA,
            SparqlAuth.None,
            retry,
        )

    private fun MockRequestHandleScope.respondTsv(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/tab-separated-values"))

    private suspend fun read(body: String): List<SparqlRow> = client {
        respondTsv(body)
    }
        .selectStreamed("SELECT ?item WHERE {}") { it.toList() }

    @Test
    fun `a URI comes back as one, and reduces to its entity id`() = runTest {
        val read = read(rows("?item", "<http://www.wikidata.org/entity/Q7889>"))

        read.single().getValue("item").type shouldBe "uri"
        read.single().getValue("item").entityId shouldBe "Q7889"
    }

    @Test
    fun `a literal with a language keeps the language`() = runTest {
        val read = read(rows("?item${tab}?label", "<http://www.wikidata.org/entity/Q1>$tab\"video game\"@en"))

        read.single().getValue("label").value shouldBe "video game"
        read.single().getValue("label").language shouldBe "en"
        read.single().getValue("label").type shouldBe "literal"
    }

    @Test
    fun `a typed literal keeps its datatype`() = runTest {
        val typed = """"42"^^<http://www.w3.org/2001/XMLSchema#integer>"""
        val read = read(rows("?count", typed))

        read.single().getValue("count").value shouldBe "42"
        read.single().getValue("count").dataType shouldBe "http://www.w3.org/2001/XMLSchema#integer"
    }

    @Test
    fun `a blank node is recognised rather than read as a literal`() = runTest {
        read(rows("?node", "_:b0")).single().getValue("node").type shouldBe "bnode"
    }

    /** An unbound variable is absent, the same as the JSON form leaves it out of the binding. */
    @Test
    fun `an unbound variable is absent rather than empty`() = runTest {
        val read = read(rows("?item${tab}?label", "<http://www.wikidata.org/entity/Q1>$tab"))

        read.single().containsKey("label") shouldBe false
        read.single().getValue("item").entityId shouldBe "Q1"
    }

    /** The service escapes a tab and a newline inside a literal, so neither ends the cell or the row. */
    @Test
    fun `an escaped tab or newline inside a literal does not end the cell or the row`() = runTest {
        val read = read(rows("?text", """"one\ttwo\nthree""""))

        read.single().getValue("text").value shouldBe "one\ttwo\nthree"
    }

    @Test
    fun `an empty answer is no rows rather than a failure`() = runTest {
        read(rows("?item")) shouldBe emptyList()
        read("") shouldBe emptyList()
    }

    @Test
    fun `every row is read, not only the first`() = runTest {
        val read =
            read(
                rows(
                    "?item",
                    "<http://www.wikidata.org/entity/Q1>",
                    "<http://www.wikidata.org/entity/Q2>",
                    "<http://www.wikidata.org/entity/Q3>",
                )
            )

        read.mapNotNull { it["item"]?.entityId } shouldBe listOf("Q1", "Q2", "Q3")
    }

    @Test
    fun `the query is asked for as tsv`() = runTest {
        var asked = ""

        client {
                asked = it.body.toByteArray().decodeToString()
                respondTsv(rows("?item"))
            }
            .selectStreamed("SELECT ?item WHERE {}") { it.toList() }

        asked shouldContain "format=tsv"
    }

    @Test
    fun `a service that says it is busy is asked again`() = runTest {
        var attempts = 0

        val read =
            client(RetryPolicy(maxRetries = 2, initialDelay = Duration.ZERO)) {
                    attempts++
                    if (attempts == 1) {
                        respond("", HttpStatusCode.TooManyRequests)
                    } else {
                        respondTsv(rows("?item", "<http://www.wikidata.org/entity/Q1>"))
                    }
                }
                .selectStreamed("SELECT ?item WHERE {}") { it.toList() }

        attempts shouldBe 2
        read.single().getValue("item").entityId shouldBe "Q1"
    }

    @Test
    fun `a service that stays busy gives up and says so`() = runTest {
        val refusal =
            shouldThrow<WikiError.Api> {
                client(RetryPolicy(maxRetries = 1, initialDelay = Duration.ZERO)) {
                        respond("", HttpStatusCode.TooManyRequests)
                    }
                    .selectStreamed("SELECT ?item WHERE {}") { it.toList() }
            }

        refusal.message shouldContain "busy"
    }
}

class XmlDumpTest {

    private val dump =
        """
        <mediawiki xmlns="http://www.mediawiki.org/xml/export-0.11/" version="0.11">
          <siteinfo><sitename>Wiktionary</sitename></siteinfo>
          <page>
            <title>volcano</title>
            <ns>0</ns>
            <id>1234</id>
            <revision>
              <id>9001</id>
              <timestamp>2026-08-01T12:00:00Z</timestamp>
              <contributor><username>Someone</username></contributor>
              <comment>a change</comment>
              <text xml:space="preserve">==English==
        A volcano.</text>
            </revision>
          </page>
          <page>
            <title>Talk:volcano</title>
            <ns>1</ns>
            <id>1235</id>
            <redirect title="Talk:mountain" />
            <revision>
              <id>9002</id>
              <text xml:space="preserve">#REDIRECT [[Talk:mountain]]</text>
            </revision>
          </page>
        </mediawiki>
        """
            .trimIndent()

    @Test
    fun `pages are read one at a time with their text`() {
        val pages = XmlDump.pages(dump.byteInputStream()).toList()

        pages.size shouldBe 2
        pages.first().title shouldBe "volcano"
        pages.first().pageId shouldBe 1234L
        pages.first().revisionId shouldBe 9001L
        pages.first().text.startsWith("==English==") shouldBe true
        pages.first().contributor shouldBe "Someone"
    }

    @Test
    fun `a page id and a revision id are not the same id`() {
        val page = XmlDump.pages(dump.byteInputStream()).first()

        (page.pageId != page.revisionId) shouldBe true
    }

    @Test
    fun `a redirect is marked as one`() {
        val pages = XmlDump.pages(dump.byteInputStream()).toList()

        pages.last().isRedirect shouldBe true
        pages.first().isRedirect shouldBe false
    }

    @Test
    fun `namespaces can be filtered before anything else is done with a page`() {
        val main = XmlDump.pages(dump.byteInputStream()).filter { it.inNamespaces(setOf(0)) }

        main.toList().map { it.title } shouldBe listOf("volcano")
    }

    @Test
    fun `a history dump keeps the first revision and skips the rest`() {
        val history =
            """
            <mediawiki>
              <page>
                <title>volcano</title><ns>0</ns><id>1</id>
                <revision><id>1</id><text>oldest</text></revision>
                <revision><id>2</id><text>newer</text></revision>
                <revision><id>3</id><text>newest</text></revision>
              </page>
            </mediawiki>
            """
                .trimIndent()

        val page = XmlDump.pages(history.byteInputStream()).single()

        page.revisionId shouldBe 1L
        page.text shouldBe "oldest"
    }

    @Test
    fun `a truncated dump yields what it contained and then says it was truncated`() {
        val truncated = dump.substringBefore("  <page>\n    <title>Talk:volcano")
        val pages = XmlDump.pages(truncated.byteInputStream()).iterator()

        pages.next().title shouldBe "volcano"

        assertFailsWith<XMLStreamException> { pages.hasNext() }
    }

    private companion object {
        const val BLANK_LINE = "\n\n"
    }
}
