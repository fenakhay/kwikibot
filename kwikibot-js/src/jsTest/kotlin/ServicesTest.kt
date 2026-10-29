import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class ServicesTest {

    private val contact = "https://example.org/TestBot"

    @Test
    fun `an entity's labels and statements are read`() = runTest {
        val wiki = MockWiki().open()

        val entity = wiki.entity("Q42").await() ?: error("no entity")

        listOf(entity.id, entity.type, entity.lastRevision) shouldBe listOf("Q42", "item", 7.0)
        entity.label("en") shouldBe "Douglas Adams"
        entity.label("fr") shouldBe null
        entity.description("en") shouldBe "English writer"
        entity.aliases("en").toList() shouldBe listOf("DNA")
        entity.properties.toList() shouldBe listOf("P31", "P20")
        val instance = entity.claims("P31").single()
        listOf(instance.id, instance.property, instance.rank, instance.snakType, instance.dataType) shouldBe
            listOf("Q42$1", "P31", "normal", "value", "wikibase-item")
        listOf(instance.value.type, instance.value.value.id) shouldBe listOf("wikibase-entityid", "Q5")
        JSON.parse<dynamic>(JSON.stringify(instance)).mainsnak.property.unsafeCast<String>() shouldBe "P31"
        val death = entity.bestClaim("P20") ?: error("no best claim")
        listOf(death.snakType, death.rank, death.value) shouldBe listOf("somevalue", "preferred", null)
        entity.claims("P569").toList() shouldBe emptyList()
        wiki.close()
    }

    @Test
    fun `sparql rows are read by variable`() = runTest {
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            asked += request.url.host
            respond(
                """{"head":{"vars":["item","label"]},"results":{"bindings":[
                   {"item":{"type":"uri","value":"http://www.wikidata.org/entity/Q42"},
                    "label":{"type":"literal","value":"Douglas Adams","xml:lang":"en"}}]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/sparql-results+json"),
            )
        }

        val rows = sparql("SELECT ?item ?label WHERE {}", contact, WIKIDATA_QUERY_SERVICE, engine).await()

        val row = rows.single()
        row.names.toList() shouldBe listOf("item", "label")
        val item = row.get("item") ?: error("no item")
        listOf(item.type, item.value, item.entityId) shouldBe
            listOf("uri", "http://www.wikidata.org/entity/Q42", "Q42")
        val label = row.get("label") ?: error("no label")
        listOf(label.value, label.language, label.dataType) shouldBe listOf("Douglas Adams", "en", null)
        row.get("none") shouldBe null
        asked shouldBe listOf("query.wikidata.org")
    }

    @Test
    fun `detect reads where the API is from a page's head`() = runTest {
        val engine = MockEngine {
            respond(
                """<!DOCTYPE html><html><head><link rel="EditURI" href="//wiki.example.org/w/api.php?action=rsd">
                   </head><body></body></html>""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }

        val endpoint =
            detect("https://wiki.example.org/wiki/Main_Page", contact, engine).await() ?: error("none")

        listOf(endpoint.apiUrl, endpoint.server, endpoint.scriptPath) shouldBe
            listOf("https://wiki.example.org/w/api.php", "wiki.example.org", "/w")
    }

    @Test
    fun `detect gives null for a page that advertises no API`() = runTest {
        val engine = MockEngine {
            respond(
                "<html><head></head></html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }

        detect("https://example.org/", contact, engine).await() shouldBe null
    }

    @Test
    fun `anonymous requests from a page on another origin ask for any origin, and none from Node`() =
        runTest {
            val mock = MockWiki()
            val wiki = mock.open()
            val inBrowser =
                js("typeof window !== 'undefined' && typeof window.location !== 'undefined'") == true

            mock.requests.map { it["origin"] }.toSet() shouldBe setOf(if (inBrowser) "*" else null)
            wiki.close()
        }
}
