package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Instant
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
    fun `a multi-line payload is one event not several`() = runTest {
        streams(stream).recentChanges().take(1).toList().single().comment shouldBe "typo"
    }

    @Test
    fun `the stream offset is kept so a stopped bot can resume`() = runTest {
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
    fun `an event with a field of the wrong shape does not end the stream`() = runTest {
        val misshapen =
            """
            data: {"wiki":"enwiktionary","title":"volcano","meta":"none","revision":7}

            data: {"wiki":"enwiktionary","title":"mountain","revision":{"old":1,"new":2}}
            """
                .trimIndent() + BLANK_LINE

        val events = streams(misshapen).recentChanges().toList()

        events.map { it.title } shouldBe listOf("volcano", "mountain")
        events.first().revisionId.shouldBeNull()
        events.last().revisionId shouldBe 2L
    }

    @Test
    fun `an event naming no wiki is filed under its domain and one with no time takes the time sent`() =
        runTest {
            val sparse =
                """
                event: message
                data: {"title":"volcano","meta":{"domain":"en.wiktionary.org","dt":"2026-10-01T12:00:00Z"}}

                data: {"wiki":"enwiki"}

                data: {"title":"lava"}
                """
                    .trimIndent() + BLANK_LINE

            val events = streams(sparse).recentChanges().toList()

            events.map { it.title } shouldBe listOf("volcano", "lava")
            events.map { it.wiki } shouldBe listOf("en.wiktionary.org", "")
            events.map { it.timestamp } shouldBe listOf(Instant.parse("2026-10-01T12:00:00Z"), null)
            events.last().type shouldBe ""
            events.last().namespace shouldBe 0
        }

    @Test
    fun `page creations are their own stream and a stream resumes from where it was asked to`() = runTest {
        val asked = mutableListOf<Url>()
        val streams =
            EventStreams(
                HttpClient(
                    MockEngine { request ->
                        asked += request.url
                        respond(
                            "",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    }
                ),
                userAgent,
            )

        streams.pageCreations(since = "2026-10-01T00:00:00Z").toList()
        streams.recentChanges().toList()

        asked.map { it.encodedPath } shouldBe listOf("/v2/stream/page-create", "/v2/stream/recentchange")
        asked.map { it.parameters["since"] } shouldBe listOf("2026-10-01T00:00:00Z", null)
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
    fun `the query is posted not put in the URL`() = runTest {
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
    fun `an error page is reported as a failed query not as no results`() = runTest {
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
    fun `giving up says the service was busy not that the query was wrong`() = runTest {
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

    @Test
    fun `entity ids are read from the variable asked for and a variable holding no entity yields none`() =
        runTest {
            val client = client { respondJson(results) }

            client.entityIds("SELECT ?item WHERE {}") shouldBe listOf("Q7889", "Q42")
            client.entityIds("SELECT ?item WHERE {}", variable = "itemLabel") shouldBe emptyList()
        }

    @Test
    fun `an answer with no results block or no bindings is a failed query`() = runTest {
        for (answer in listOf("""{"head":{"vars":[]}}""", """{"results":{}}""")) {
            assertFailsWith<WikiError.Api> { client { respondJson(answer) }.select("SELECT ?item WHERE {}") }
                .code shouldBe "sparqlfailed"
        }
    }

    @Test
    fun `a cell that is not a term is left out and a term that says nothing is empty`() = runTest {
        val rows = client {
            respondJson("""{"results":{"bindings":[{"item":"oops","label":{}},"not a row"]}}""")
        }
            .select("SELECT ?item WHERE {}")

        rows shouldBe listOf(mapOf("label" to SparqlValue(value = "", type = "")))
    }

    @Test
    fun `a wait the service names as a date rather than in seconds is not honoured`() = runTest {
        var attempts = 0
        val client = client {
            attempts++
            if (attempts == 1) {
                respond(
                    "",
                    HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.RetryAfter, "Wed, 21 Oct 2026 07:28:00 GMT"),
                )
            } else {
                respondJson(results)
            }
        }

        client.select("SELECT ?item WHERE {}")

        currentTime shouldBe RetryPolicy().initialDelay.inWholeMilliseconds
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

    private fun rows(vararg lines: String) = lines.joinToString("\n")

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

    /**
     * TSV is asked for by header alone.
     *
     * A `format` parameter the service does not recognise is not refused: it stops honouring the Accept
     * header and answers in its own default, which is XML. A reader that takes lines for rows then finds one
     * variable called `<?xml version='1.0'?>` and no bindings at all, so a query that works returns nothing.
     */
    @Test
    fun `the query is sent without a format parameter`() = runTest {
        var body = ""

        client {
                body = it.body.toByteArray().decodeToString()
                respondTsv(rows("?item", "<http://www.wikidata.org/entity/Q1>"))
            }
            .selectStreamed("SELECT ?item WHERE {}") { it.toList() }

        body shouldContain "query="
        body shouldNotContain "format="
    }

    @Test
    fun `a URI comes back as one and reduces to its entity id`() = runTest {
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
    fun `every row is read not only the first`() = runTest {
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
    fun `a row shorter than its header leaves the variables it does not reach unbound`() = runTest {
        val read = read(rows("?item${tab}?label", "<http://www.wikidata.org/entity/Q1>"))

        read.single().keys shouldBe setOf("item")
    }

    @Test
    fun `a bare number or an unclosed URI is a literal as it stands`() = runTest {
        val read = read(rows("?count${tab}?broken", "42$tab<http://example.org"))

        read.single() shouldBe
            mapOf(
                "count" to SparqlValue(value = "42", type = "literal"),
                "broken" to SparqlValue(value = "<http://example.org", type = "literal"),
            )
    }

    @Test
    fun `a failure in what reads the rows reaches the caller`() = runTest {
        val failure =
            shouldThrow<IllegalStateException> {
                client { respondTsv(rows("?item", "<http://www.wikidata.org/entity/Q1>")) }
                    .selectStreamed("SELECT ?item WHERE {}") { error("the bot stopped") }
            }

        failure.message shouldBe "the bot stopped"
    }

    @Test
    fun `the query is asked for as tsv by header`() = runTest {
        var accept = ""

        client {
                accept = it.headers[HttpHeaders.Accept].orEmpty()
                respondTsv(rows("?item"))
            }
            .selectStreamed("SELECT ?item WHERE {}") { it.toList() }

        accept shouldContain "text/tab-separated-values"
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
