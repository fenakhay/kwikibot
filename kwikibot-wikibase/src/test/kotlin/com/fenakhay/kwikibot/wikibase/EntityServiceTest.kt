package com.fenakhay.kwikibot.wikibase

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.net.UserAgent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class EntityServiceTest {

    @Test
    fun `entities are fetched and decoded`() = runTest {
        val service = service {
            respondJson(
                """{"entities":{"Q42":{"type":"item","id":"Q42","lastrevid":7,
                   "labels":{"en":{"language":"en","value":"Douglas Adams"}}}}}""",
            )
        }

        val entity = service.entity(EntityId("Q42"))

        entity.shouldBeInstanceOf<Entity.Item>().label("en") shouldBe "Douglas Adams"
    }

    @Test
    fun `an id that does not exist comes back absent rather than empty`() = runTest {
        val service = service {
            respondJson("""{"entities":{"Q0":{"id":"Q0","missing":""}}}""")
        }

        service.entity(EntityId("Q0")).shouldBeNull()
    }

    @Test
    fun `reads are batched at the limit the API allows`() = runTest {
        var requests = 0
        val service = service {
            requests++
            respondJson("""{"entities":{}}""")
        }

        service.entities((1..120).map { EntityId("Q$it") })

        requests shouldBe 3
    }

    @Test
    fun `pages are matched to entities through their sitelinks`() = runTest {
        val service = service {
            respondJson(
                """{"entities":{"Q42":{"type":"item","id":"Q42",
                   "sitelinks":{"enwiki":{"site":"enwiki","title":"Douglas Adams"}}}}}""",
            )
        }

        val found = service.forPages("enwiki", listOf("douglas Adams"))

        found.keys shouldBe setOf("douglas Adams")
        found.values.first().id shouldBe EntityId("Q42")
    }

    @Test
    fun `a search returns candidates with what is needed to choose between them`() = runTest {
        val service = service {
            respondJson(
                """{"search":[{"id":"Q42","label":"Douglas Adams","description":"writer"},
                   {"id":"Q5","label":"human"}]}""",
            )
        }

        val matches = service.search("Douglas Adams")

        matches.map { it.id.value } shouldBe listOf("Q42", "Q5")
        matches.first().description shouldBe "writer"
        matches.last().description.shouldBeNull()
    }

    @Test
    fun `an edit posts its data and is flagged as a bot edit`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN+\\"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"entity":{"type":"item","id":"Q42","lastrevid":8},"success":1}""")
            }
        }

        val saved = service.edit(EntityId("Q42")) {
            labels = mapOf("en" to "Douglas Adams")
            summary = "label"
            baseRevision = 7
        }

        saved.lastRevisionId shouldBe 8
        body.contains("action=wbeditentity") shouldBe true
        body.contains("baserevid=7") shouldBe true
        body.contains("bot=1") shouldBe true
        body.contains("token=TOKEN") shouldBe true
    }

    @Test
    fun `an edit sends the data parameter the API expects`() = runTest {
        var data = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                data = request.body.toByteArray().decodeToString()
                    .split("&")
                    .first { it.startsWith("data=") }
                    .removePrefix("data=")
                respondJson("""{"entity":{"type":"item","id":"Q42"},"success":1}""")
            }
        }

        service.edit(EntityId("Q42")) { labels = mapOf("en" to "hello") }

        val decoded = Json.parseToJsonElement(urlDecode(data)).jsonObject
        decoded["labels"]?.jsonObject?.get("en")?.jsonObject
            ?.get("value")?.jsonPrimitive?.content shouldBe "hello"
    }

    @Test
    fun `a statement is saved and read back`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                respondJson(
                    """{"claim":{"id":"Q42${'$'}abc","type":"statement","rank":"preferred",
                       "mainsnak":{"snaktype":"value","property":"P31",
                       "datavalue":{"type":"wikibase-entityid","value":{"id":"Q5"}}}},
                       "success":1}""",
                )
            }
        }

        val saved = service.setStatement(
            EntityId("Q42"),
            Statement(
                Snak.Value(EntityId("P31"), DataValue.EntityRef(EntityId("Q5"))),
                rank = Rank.PREFERRED,
            ),
        )

        saved.rank shouldBe Rank.PREFERRED
        saved.value.shouldBeInstanceOf<DataValue.EntityRef>().id shouldBe EntityId("Q5")
    }

    @Test
    fun `a stale token is refreshed and the write retried`() = runTest {
        var tokensIssued = 0
        var writes = 0
        val service = service { request ->
            when {
                request.url.parameters["meta"] == "tokens" -> {
                    tokensIssued++
                    respondJson("""{"query":{"tokens":{"csrftoken":"T$tokensIssued"}}}""")
                }

                writes++ == 0 -> respondJson(
                    """{"errors":[{"code":"badtoken","text":"invalid token"}]}""",
                )

                else -> respondJson("""{"entity":{"type":"item","id":"Q42"},"success":1}""")
            }
        }

        service.edit(EntityId("Q42")) { labels = mapOf("en" to "x") }

        tokensIssued shouldBe 2
        writes shouldBe 2
    }

    @Test
    fun `an error that is not about the token is raised`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                respondJson(
                    """{"errors":[{"code":"permissiondenied","text":"not allowed"}]}""",
                )
            }
        }

        assertFailsWith<WikiError> {
            service.edit(EntityId("Q42")) { labels = mapOf("en" to "x") }
        }
    }

    @Test
    fun `a merge refuses on conflicts unless told which to overwrite`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"success":1,"redirected":1}""")
            }
        }

        service.mergeItems(EntityId("Q42"), EntityId("Q5"))

        body.contains("action=wbmergeitems") shouldBe true
        body.contains("ignoreconflicts") shouldBe false
    }

    @Test
    fun `conflicts a caller accepts are named in the request`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"success":1}""")
            }
        }

        service.mergeItems(
            EntityId("Q42"),
            EntityId("Q5"),
            ignoreConflicts = setOf("description", "sitelink"),
        )

        body.contains("ignoreconflicts=description%7Csitelink") shouldBe true
    }

    @Test
    fun `values are parsed by the repository rather than guessed at`() = runTest {
        val service = service {
            respondJson(
                """{"results":[{"type":"time","value":{"time":"+2026-08-31T00:00:00Z",
                   "precision":11,"calendarmodel":"http://www.wikidata.org/entity/Q1985727"}}]}""",
            )
        }

        val parsed = service.parseValues("time", listOf("31 August 2026"))

        parsed.single().shouldBeInstanceOf<DataValue.Time>().precision shouldBe DataValue.Time.DAY
    }

    @Test
    fun `an entity that was merged away is not decoded as an empty one`() {
        val response = Json.parseToJsonElement(
            """{"entities":{"Q42":{"id":"Q42","redirects":{"from":"Q42","to":"Q5"}}}}""",
        ).jsonObject

        EntityDecoder.decodeAll(response) shouldBe emptyMap()
        EntityDecoder.redirectTarget(
            response.getValue("entities").jsonObject.getValue("Q42").jsonObject,
        ) shouldBe EntityId("Q5")
    }

    @Test
    fun `a file's structured data decodes as media info`() {
        val response = Json.parseToJsonElement(
            """{"entities":{"M123":{"type":"mediainfo","id":"M123","lastrevid":7,
               "labels":{"en":{"language":"en","value":"A volcano at dawn"}},
               "statements":{}}}}""",
        ).jsonObject

        val media = EntityDecoder.decodeAll(response).getValue(EntityId("M123"))
            .shouldBeInstanceOf<Entity.MediaInfo>()

        media.caption("en") shouldBe "A volcano at dawn"
        media.descriptions shouldBe emptyMap()
    }

    @Test
    fun `removing nothing makes no request`() = runTest {
        var requests = 0
        val service = service {
            requests++
            respondJson("{}")
        }

        service.removeStatements(emptyList())

        requests shouldBe 0
    }

    @Test
    fun `creating an entity names its kind and carries no id`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN+\\"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"entity":{"type":"item","id":"Q99","lastrevid":1},"success":1}""")
            }
        }

        val created = service.create(EntityId.Kind.ITEM) {
            labels = mapOf("en" to "New thing")
        }

        created.id shouldBe EntityId("Q99")
        body.contains("action=wbeditentity") shouldBe true
        body.contains("new=item") shouldBe true
        body.contains("&id=") shouldBe false
    }

    @Test
    fun `removing statements names them by id, not by property`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN+\\"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"success":1}""")
            }
        }

        service.removeStatements(listOf("Q42${'$'}abc", "Q42${'$'}def"), summary = "tidying")

        body.contains("action=wbremoveclaims") shouldBe true
        urlDecode(body).contains("Q42${'$'}abc|Q42${'$'}def") shouldBe true
        urlDecode(body).contains("summary=tidying") shouldBe true
    }

    @Test
    fun `merging lexemes names the source and the target`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN+\\"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"success":1}""")
            }
        }

        service.mergeLexemes(EntityId("L1"), EntityId("L2"), summary = "duplicate")

        body.contains("action=wblmergelexemes") shouldBe true
        urlDecode(body).contains("source=L1") shouldBe true
        urlDecode(body).contains("target=L2") shouldBe true
    }

    @Test
    fun `a redirect points one empty entity at another`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN+\\"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"success":1}""")
            }
        }

        service.redirect(EntityId("Q100"), EntityId("Q42"))

        body.contains("action=wbcreateredirect") shouldBe true
        urlDecode(body).contains("from=Q100") shouldBe true
        urlDecode(body).contains("to=Q42") shouldBe true
    }

    private fun TestScope.service(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): EntityService {
        val transport = KtorTransport(
            client = HttpClient(MockEngine(handler)),
            endpoint = ApiEndpoint("www.wikidata.org"),
            userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
            throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
            retry = RetryPolicy.NONE,
        )
        return ApiEntityService(transport, TokenStore(transport))
    }

    private fun urlDecode(value: String): String =
        java.net.URLDecoder.decode(value, Charsets.UTF_8)

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
