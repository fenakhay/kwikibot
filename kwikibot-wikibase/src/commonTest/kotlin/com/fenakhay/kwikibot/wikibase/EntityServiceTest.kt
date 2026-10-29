package com.fenakhay.kwikibot.wikibase

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import com.fenakhay.kwikibot.testkit.MockTransport
import com.fenakhay.kwikibot.wikibase.entity.Entity
import com.fenakhay.kwikibot.wikibase.entity.SiteLink
import com.fenakhay.kwikibot.wikibase.value.DataValue
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Rank
import com.fenakhay.kwikibot.wikibase.value.Snak
import com.fenakhay.kwikibot.wikibase.value.Statement
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
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EntityServiceTest {

    @Test
    fun `entities are fetched and decoded`() = runTest {
        val service = service {
            respondJson(
                """{"entities":{"Q42":{"type":"item","id":"Q42","lastrevid":7,
                   "labels":{"en":{"language":"en","value":"Douglas Adams"}}}}}"""
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
                   "sitelinks":{"enwiki":{"site":"enwiki","title":"Douglas Adams"}}}}}"""
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
                   {"id":"Q5","label":"human"}]}"""
            )
        }

        val matches = service.search("Douglas Adams")

        matches.map { it.id.value } shouldBe listOf("Q42", "Q5")
        matches.first().description shouldBe "writer"
        matches.last().description.shouldBeNull()
    }

    @Test
    fun `wbsearchentities takes no mediainfo type so asking is refused rather than sent`() = runTest {
        var requests = 0
        val service = service {
            requests++
            respondJson("""{"search":[]}""")
        }

        assertFailsWith<IllegalArgumentException> {
            service.search("sunset", kind = EntityId.Kind.MEDIA_INFO)
        }
        requests shouldBe 0
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

        val saved =
            service.edit(EntityId("Q42")) {
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
                data =
                    request.body
                        .toByteArray()
                        .decodeToString()
                        .split("&")
                        .first { it.startsWith("data=") }
                        .removePrefix("data=")
                respondJson("""{"entity":{"type":"item","id":"Q42"},"success":1}""")
            }
        }

        service.edit(EntityId("Q42")) { labels = mapOf("en" to "hello") }

        val decoded = Json.parseToJsonElement(urlDecode(data)).jsonObject
        decoded["labels"]?.jsonObject?.get("en")?.jsonObject?.get("value")?.jsonPrimitive?.content shouldBe
            "hello"
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
                       "success":1}"""
                )
            }
        }

        val saved =
            service.setStatement(
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

                writes++ == 0 -> respondJson("""{"errors":[{"code":"badtoken","text":"invalid token"}]}""")

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
                respondJson("""{"errors":[{"code":"permissiondenied","text":"not allowed"}]}""")
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
                   "precision":11,"calendarmodel":"http://www.wikidata.org/entity/Q1985727"}}]}"""
            )
        }

        val parsed = service.parseValues("time", listOf("31 August 2026"))

        parsed.single().shouldBeInstanceOf<DataValue.Time>().precision shouldBe DataValue.Time.DAY
    }

    @Test
    fun `an entity that was merged away is not decoded as an empty one`() {
        val response =
            Json.parseToJsonElement(
                    """{"entities":{"Q42":{"id":"Q42","redirects":{"from":"Q42","to":"Q5"}}}}"""
                )
                .jsonObject

        EntityDecoder.decodeAll(response) shouldBe emptyMap()
        EntityDecoder.redirectTarget(
            response.getValue("entities").jsonObject.getValue("Q42").jsonObject
        ) shouldBe EntityId("Q5")
    }

    @Test
    fun `a file's structured data decodes as media info`() {
        val response =
            Json.parseToJsonElement(
                    """{"entities":{"M123":{"type":"mediainfo","id":"M123","lastrevid":7,
               "labels":{"en":{"language":"en","value":"A volcano at dawn"}},
               "statements":{}}}}"""
                )
                .jsonObject

        val media =
            EntityDecoder.decodeAll(response)
                .getValue(EntityId("M123"))
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

        val created =
            service.create(EntityId.Kind.ITEM) {
                labels = mapOf("en" to "New thing")
            }

        created.id shouldBe EntityId("Q99")
        body.contains("action=wbeditentity") shouldBe true
        body.contains("new=item") shouldBe true
        body.contains("&id=") shouldBe false
    }

    @Test
    fun `every kind of entity is created under the type Wikibase gives it and an unknown one is refused`() =
        runTest {
            val created = mutableListOf<String>()
            val service = service { request ->
                if (request.url.parameters["meta"] == "tokens") {
                    respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
                } else {
                    val body = urlDecode(request.body.toByteArray().decodeToString())
                    created += body.split("&").first { it.startsWith("new=") }.removePrefix("new=")
                    respondJson("""{"entity":{"type":"property","id":"P1","lastrevid":1},"success":1}""")
                }
            }

            listOf(
                    EntityId.Kind.PROPERTY,
                    EntityId.Kind.LEXEME,
                    EntityId.Kind.FORM,
                    EntityId.Kind.SENSE,
                    EntityId.Kind.MEDIA_INFO,
                )
                .forEach { kind -> service.create(kind) { labels = mapOf("en" to "x") } }
            assertFailsWith<IllegalArgumentException> { service.create(EntityId.Kind.UNKNOWN) {} }

            created shouldBe listOf("property", "lexeme", "form", "sense", "mediainfo")
        }

    @Test
    fun `an edit sends every part it sets and can replace the entity outright`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = urlDecode(request.body.toByteArray().decodeToString())
                respondJson("""{"entity":{"type":"item","id":"Q42","lastrevid":9},"success":1}""")
            }
        }
        val location =
            Statement(
                Snak.Value(EntityId("P625"), DataValue.GlobeCoordinate(48.8, 2.3, 0.0001, "Q2")),
                rank = Rank.DEPRECATED,
            )

        service.edit(EntityId("Q42")) {
            descriptions = mapOf("en" to "English writer")
            aliases = mapOf("en" to listOf("Douglas Noël Adams", "DNA"))
            siteLinks = mapOf("enwiki" to SiteLink("enwiki", "Douglas Adams"))
            statements = mapOf(EntityId("P625") to listOf(location))
            bot = false
            clear = true
        }

        val params = body.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
        val data = Json.parseToJsonElement(params.getValue("data")).jsonObject
        data.keys shouldBe setOf("descriptions", "aliases", "sitelinks", "claims")
        data["descriptions"]!!.jsonObject["en"]!!.jsonObject["value"]!!.jsonPrimitive.content shouldBe
            "English writer"
        data["aliases"]!!.jsonObject["en"]!!.jsonArray.map {
            it.jsonObject["value"]!!.jsonPrimitive.content
        } shouldBe listOf("Douglas Noël Adams", "DNA")
        data["sitelinks"]!!.jsonObject["enwiki"]!!.jsonObject["title"]!!.jsonPrimitive.content shouldBe
            "Douglas Adams"
        val claim = data["claims"]!!.jsonObject["P625"]!!.jsonArray.single().jsonObject
        EntityDecoder.decodeStatement(claim) shouldBe location
        params["clear"] shouldBe "1"
        params.containsKey("bot") shouldBe false
    }

    @Test
    fun `a write the repository answers without what it saved is an error not an empty result`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                respondJson("""{"success":1}""")
            }
        }
        val statement = Statement(Snak.Value(EntityId("P31"), DataValue.EntityRef(EntityId("Q5"))))

        assertFailsWith<WikiError.Api> { service.setStatement(EntityId("Q42"), statement) }.code shouldBe
            "noclaim"
        assertFailsWith<WikiError.Api> { service.edit(EntityId("Q42")) { labels = mapOf("en" to "x") } }
            .code shouldBe "noentity"
    }

    @Test
    fun `the repository is reached through the session of the wiki it was opened from`() = runTest {
        val transport = MockTransport { request ->
            when {
                request.params["meta"] == "tokens" ->
                    MockTransport.json("""{"query":{"tokens":{"csrftoken":"T"}}}""")
                request.action == "wbgetentities" ->
                    MockTransport.json("""{"entities":{"Q42":{"type":"item","id":"Q42"}}}""")
                else -> MockTransport.json("""{"success":1}""")
            }
        }
        val repository = FakeWiki(FakePageService(), transport = transport).wikibase()

        repository.entity(EntityId("Q42"))?.id shouldBe EntityId("Q42")
        repository.mergeLexemes(EntityId("L1"), EntityId("L2"))

        transport.requests.map { it.action } shouldBe listOf("wbgetentities", "query", "wblmergelexemes")
        transport.requests.last().params["summary"] shouldBe ""
    }

    @Test
    fun `removing statements names them by id not by property`() = runTest {
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

    @Test
    fun `asking for nothing sends nothing`() = runTest {
        var requests = 0
        val service = service {
            requests++
            respondJson("{}")
        }

        service.entities(emptyList()) shouldBe emptyMap()
        service.forPages("enwiki", emptyList()) shouldBe emptyMap()
        service.parseValues("time", emptyList()) shouldBe emptyList()
        requests shouldBe 0
    }

    @Test
    fun `an id that was merged away comes back absent rather than as the item it was merged into`() =
        runTest {
            val service = service {
                respondJson("""{"entities":{"Q42":{"id":"Q42","redirects":{"from":"Q42","to":"Q5"}}}}""")
            }

            service.entities(listOf(EntityId("Q42"))) shouldBe emptyMap()
        }

    @Test
    fun `a page matches its item exactly first then in any case and never without a sitelink`() = runTest {
        val service = service {
            respondJson(
                """{"entities":{
                       "Q42":{"type":"item","id":"Q42",
                         "sitelinks":{"enwiki":{"site":"enwiki","title":"Douglas Adams"}}},
                       "Q5":{"type":"item","id":"Q5",
                         "sitelinks":{"frwiki":{"site":"frwiki","title":"Humain"}}},
                       "P31":{"type":"property","id":"P31","datatype":"wikibase-item"}}}"""
            )
        }

        val found = service.forPages("enwiki", listOf("Douglas Adams", "DOUGLAS ADAMS", "Human"))

        found.mapValues { it.value.id } shouldBe
            mapOf("Douglas Adams" to EntityId("Q42"), "DOUGLAS ADAMS" to EntityId("Q42"))
    }

    @Test
    fun `a search names the type language and page size it asks for`() = runTest {
        var asked = mapOf<String, String>()
        val service = service { request ->
            asked = request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }
            respondJson("""{"search":[{"id":"L7"}]}""")
        }

        val matches = service.search("volcano", language = "de", kind = EntityId.Kind.LEXEME, limit = 3)

        matches shouldBe listOf(EntityMatch(EntityId("L7"), label = null, description = null))
        asked.filterKeys { it in setOf("search", "language", "uselang", "type", "limit") } shouldBe
            mapOf(
                "search" to "volcano",
                "language" to "de",
                "uselang" to "de",
                "type" to "lexeme",
                "limit" to "3",
            )
    }

    @Test
    fun `a search for a kind of entity Wikibase cannot name is refused rather than sent`() = runTest {
        var requests = 0
        val service = service {
            requests++
            respondJson("""{"search":[]}""")
        }

        assertFailsWith<IllegalArgumentException> { service.search("x", kind = EntityId.Kind.UNKNOWN) }
            .message shouldBe
            "wbsearchentities cannot search for UNKNOWN entities; search the File namespace with list=search"
        requests shouldBe 0
    }

    @Test
    fun `answers that carry no results read as none`() = runTest {
        val service = service { respondJson("""{"success":1}""") }

        service.search("nothing") shouldBe emptyList()
        service.parseValues("time", listOf("not a date")) shouldBe emptyList()
    }

    @Test
    fun `a statement write names the revision it was computed from`() = runTest {
        val bodies = mutableListOf<String>()
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                bodies += urlDecode(request.body.toByteArray().decodeToString())
                respondJson("""{"claim":{"mainsnak":{"snaktype":"novalue","property":"P40"}},"success":1}""")
            }
        }

        service.setStatement(EntityId("Q42"), Statement(Snak.NoValue(EntityId("P40"))), baseRevision = 7)
        service.removeStatements(listOf("Q42${'$'}abc"), baseRevision = 8)

        bodies.map { body -> body.split("&").first { it.startsWith("baserevid=") } } shouldBe
            listOf("baserevid=7", "baserevid=8")
    }

    private fun TestScope.service(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): EntityService {
        val transport =
            KtorTransport(
                client = HttpClient(MockEngine(handler)),
                endpoint = ApiEndpoint("www.wikidata.org"),
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            )
        return ApiEntityService(transport, TokenStore(transport))
    }

    private fun urlDecode(value: String): String = value.decodeURLQueryComponent(plusIsSpace = true)

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
