import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class StreamsTest {

    private val contact = "https://example.org/TestBot"

    private val asked = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    private val tsvRows = "?item\t?label\n<http://www.wikidata.org/entity/Q42>\t\"Douglas Adams\"@en\n"

    private val jsonRows =
        """{"results":{"bindings":[{"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q42"}}]}}"""

    private val sparqlEngine = MockEngine { request ->
        asked += request
        bodies += request.body.toByteArray().decodeToString()
        val tsv = request.headers[HttpHeaders.Accept] == "text/tab-separated-values"
        respond(if (tsv) tsvRows else jsonRows, HttpStatusCode.OK)
    }

    private val events =
        """
        : keep-alive
        id: [{"topic":"a","offset":1}]
        data: {"wiki":"enwiktionary","type":"edit","title":"Talk:Lava","namespace":1,"user":"Alice",
        data: "comment":"c","timestamp":1788000000,"revision":{"new":9,"old":8},"bot":true,"minor":true}

        id: [{"topic":"a","offset":2}]
        data: {"wiki":"frwiki","type":"new","title":"Lave","namespace":0}


        """
            .trimIndent()

    private fun eventEngine() = MockEngine { request ->
        asked += request
        respond(events, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }

    private fun quickRetry() =
        optionsOf<RetryOptions>("maxRetries" to 0, "initialDelay" to 1.0, "maxDelay" to 1.0)

    @Test
    fun `a query client selects rows and entity ids with its endpoint and agent`() = runTest {
        val client =
            sparqlClient(
                contact,
                optionsOf(
                    "endpoint" to COMMONS_QUERY_SERVICE,
                    "userAgent" to UserAgent("Mine", "2.0", contact),
                    "retry" to quickRetry(),
                ),
                sparqlEngine,
            )

        client.select("SELECT ?item WHERE {}").await().single().get("item")?.entityId shouldBe "Q42"
        client.entityIds("SELECT ?item WHERE {}").await().toList() shouldBe listOf("Q42")
        client.entityIds("SELECT ?item WHERE {}", "none").await().size shouldBe 0
        asked.first().url.toString() shouldBe COMMONS_QUERY_SERVICE
        asked.first().headers[HttpHeaders.UserAgent].orEmpty() shouldContain "Mine/2.0"
        bodies.first() shouldContain "query=SELECT"
        client.close()
    }

    @Test
    fun `a query's rows stream one at a time, with their types`() = runTest {
        val client = sparqlClient(contact, null, sparqlEngine)

        val row = client.selectStreamed("SELECT ?item ?label WHERE {}").take().single()

        row.get("item")?.entityId shouldBe "Q42"
        row.get("label")?.value shouldBe "Douglas Adams"
        row.get("label")?.language shouldBe "en"
        asked.single().url.toString() shouldBe WIKIDATA_QUERY_SERVICE
        client.close()
    }

    @Test
    fun `a service that stays busy rejects once its retries are spent`() = runTest {
        val busy = MockEngine { respond("", HttpStatusCode.TooManyRequests) }
        val client = sparqlClient(contact, optionsOf("retry" to quickRetry()), busy)

        val refused = shouldThrow<Throwable> { client.select("SELECT").await() }

        describeError(refused)?.code shouldBe "sparqlfailed"
        client.close()
    }

    @Test
    fun `authentication goes as a cookie and says what it is`() = runTest {
        sparqlAuthNone().kind shouldBe "none"
        sparqlAuthNone().name shouldBe null
        val cookie = sparqlAuthCookie("c", "v")
        listOf(cookie.kind, cookie.name, cookie.value) shouldBe listOf("cookie", "c", "v")
        sparqlAuthWcqs("t").name shouldBe "wcqsOauth"
        LINGUA_LIBRE_QUERY_SERVICE shouldContain "lingualibre.org"

        val client =
            sparqlClient(
                contact,
                optionsOf("endpoint" to COMMONS_QUERY_SERVICE, "auth" to sparqlAuthWcqs("secret-token")),
                sparqlEngine,
            )
        client.select("SELECT").await()
        asked.single().headers[HttpHeaders.Cookie].orEmpty() shouldContain "wcqsOauth=secret-token"
        client.close()
    }

    @Test
    fun `recent changes walk as events and leaving the walk ends it`() = runTest {
        val streams = eventStreams(contact, optionsOf("retry" to quickRetry()), eventEngine())

        val (first, second) = streams.recentChanges().take(2)

        listOf(first.wiki, first.type, first.title, first.namespace) shouldBe
            listOf("enwiktionary", "edit", "Talk:Lava", 1)
        listOf(first.user, first.comment, first.revisionId, first.previousRevisionId) shouldBe
            listOf("Alice", "c", 9.0, 8.0)
        listOf(first.isBot, first.isMinor) shouldBe listOf(true, true)
        first.timestamp?.getTime() shouldBe 1788000000000.0
        first.offset shouldBe """[{"topic":"a","offset":1}]"""
        first.matches("enwiktionary") shouldBe true
        first.matches("enwiktionary", arrayOf(0)) shouldBe false
        first.matches("frwiki") shouldBe false
        second.title shouldBe "Lave"
        second.user shouldBe null
        asked.first().url.toString() shouldBe "$WIKIMEDIA_EVENT_STREAMS/v2/stream/recentchange"
        asked.first().headers[HttpHeaders.Accept] shouldBe "text/event-stream"
        streams.close()
    }

    @Test
    fun `a stream resumes from an offset or a time, and any stream can be named`() = runTest {
        val streams =
            eventStreams(
                contact,
                optionsOf(
                    "baseUrl" to "https://streams.example.org",
                    "userAgent" to UserAgent("Mine", "2", contact),
                ),
                eventEngine(),
            )

        streams.pageCreations("[offset]").take(1)
        asked.last().url.toString() shouldBe "https://streams.example.org/v2/stream/page-create"
        asked.last().headers["Last-Event-ID"] shouldBe "[offset]"
        asked.last().headers[HttpHeaders.UserAgent].orEmpty() shouldContain "Mine/2"

        streams.events("mediawiki.page-move", "2026-01-01T00:00:00Z").take(1)
        asked.last().url.encodedPath shouldBe "/v2/stream/mediawiki.page-move"
        asked.last().url.parameters["since"] shouldBe "2026-01-01T00:00:00Z"
        streams.close()
    }

    @Test
    fun `a refused stream rejects the walk at once`() = runTest {
        val refusing = MockEngine { respond("no", HttpStatusCode.Forbidden) }
        val streams = eventStreams(contact, null, refusing)

        val refused = shouldThrow<Throwable> { streams.recentChanges().take(1) }

        describeError(refused)?.kind shouldBe "refused"
        streams.close()
    }

    @Test
    fun `the site matrix lists and finds wikis`() = runTest {
        val mock = MockWiki()
        mock.canned["sitematrix"] =
            """{"sitematrix":{"count":3,
              "0":{"code":"en","name":"English","site":[
                {"url":"https://en.wiktionary.org","dbname":"enwiktionary","code":"wiktionary"},
                {"url":"https://en.wikipedia.org","dbname":"enwiki","code":"wikipedia","closed":true}]},
              "specials":[{"url":"https://secret.wikimedia.org","dbname":"secretwiki","code":"secret","private":true}]}}"""
        val wiki = mock.open()

        val matrix = wiki.siteMatrix().await()

        matrix.wikis.map { it.id } shouldBe listOf("enwiktionary", "enwiki", "secretwiki")
        matrix.of("wikipedia").single().isClosed shouldBe true
        matrix.get("en", "wiktionary")?.url shouldBe "https://en.wiktionary.org"
        matrix.get("fr", "wiktionary") shouldBe null
        matrix.byId("secretwiki")?.isPrivate shouldBe true
        matrix.open().map { it.id } shouldBe listOf("enwiktionary")
        matrix.languagesOf("wiktionary").toList() shouldBe listOf("en")
        val found = matrix.wikis.first()
        listOf(found.language, found.project, found.endpoint.server) shouldBe
            listOf("en", "wiktionary", "en.wiktionary.org")
        familyOf(found).name shouldBe "wiktionary"
        decodeSiteMatrix(JSON.parse("""{"sitematrix":{"specials":[]}}""")).wikis.size shouldBe 0
        wiki.close()
    }
}
