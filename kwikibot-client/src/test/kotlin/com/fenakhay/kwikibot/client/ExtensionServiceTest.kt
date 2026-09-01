package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.InterwikiMap
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.RevisionId
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
import com.fenakhay.kwikibot.protocol.SiteInfo
import io.kotest.matchers.shouldBe
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
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ExtensionServiceTest {

    private val wiki = WikiId("enwiki")
    private val page = PageRef(wiki, Title.Local(Namespace.MAIN, "Volcano"))

    @Test
    fun `an extension that is not installed is refused, not answered with nothing`() = runTest {
        var requests = 0
        val service = service(installed = emptyList()) {
            requests++
            respondJson("{}")
        }

        assertFailsWith<WikiError.Configuration.MissingExtension> {
            service.coordinates(listOf(page))
        }
        requests shouldBe 0
    }

    @Test
    fun `coordinates are read from GeoData`() = runTest {
        val service = service(installed = listOf("GeoData")) {
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano","coordinates":[
                   {"lat":45.9,"lon":6.8,"primary":"","globe":"earth","type":"mountain"}]}]}}""",
            )
        }

        val found = service.coordinates(listOf(page)).getValue(page).single()

        found.latitude shouldBe 45.9
        found.isPrimary shouldBe true
        found.type shouldBe "mountain"
    }

    @Test
    fun `a coordinate on another globe says so`() = runTest {
        val service = service(installed = listOf("GeoData")) {
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano","coordinates":[
                   {"lat":18.6,"lon":226.2,"globe":"mars"}]}]}}""",
            )
        }

        service.coordinates(listOf(page)).getValue(page).single().globe shouldBe "mars"
    }

    @Test
    fun `a nearby search returns pages`() = runTest {
        val service = service(installed = listOf("GeoData")) {
            respondJson(
                """{"query":{"geosearch":[{"pageid":1,"ns":0,"title":"Volcano","dist":120.4}]}}""",
            )
        }

        service.nearby(45.9, 6.8).map { it.title.text } shouldBe listOf("Volcano")
    }

    @Test
    fun `the wikidata item of a page is read from its page properties`() = runTest {
        val service = service(installed = listOf("WikibaseClient")) {
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano",
                   "pageprops":{"wikibase_item":"Q8072"}}]}}""",
            )
        }

        service.wikibaseItems(listOf(page)).getValue(page) shouldBe "Q8072"
    }

    @Test
    fun `a page with no item is absent rather than empty`() = runTest {
        val service = service(installed = listOf("WikibaseClient")) {
            respondJson("""{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano"}]}}""")
        }

        service.wikibaseItems(listOf(page)) shouldBe emptyMap()
    }

    @Test
    fun `lint errors carry where in the wikitext they are`() = runTest {
        val service = service(installed = listOf("Linter")) {
            respondJson(
                """{"query":{"linterrors":[{"lintId":9,"category":"obsolete-tag","pageid":1,
                   "ns":0,"title":"Volcano","location":[120,140],
                   "templateInfo":{},"params":{"name":"font"}}]}}""",
            )
        }

        val error = service.lintErrors().toList().single()

        error.category shouldBe "obsolete-tag"
        error.range shouldBe 120..140
        error.details["name"] shouldBe "font"
        error.page?.title?.text shouldBe "Volcano"
    }

    @Test
    fun `notifications report whether they have been read`() = runTest {
        val service = service(installed = listOf("Echo")) {
            respondJson(
                """{"query":{"notifications":{"list":[
                   {"id":1,"type":"mention","title":{"full":"Talk:Volcano"},
                    "agent":{"name":"Someone"},
                    "timestamp":{"utciso8601":"2026-08-01T12:00:00Z"}},
                   {"id":2,"type":"thank-you-edit","read":"2026-08-02T00:00:00Z"}]}}}""",
            )
        }

        val notifications = service.notifications()

        notifications.first().isRead shouldBe false
        notifications.first().agent shouldBe "Someone"
        notifications.last().isRead shouldBe true
    }

    @Test
    fun `thanking sends a token, because there is no way to take it back`() = runTest {
        var body = ""
        val service = service(installed = listOf("Thanks")) { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"result":{"success":1}}""")
            }
        }

        service.thank(RevisionId(9001))

        body.contains("rev=9001") shouldBe true
        body.contains("token=T") shouldBe true
    }

    @Test
    fun `a short url is returned`() = runTest {
        val service = service(installed = listOf("UrlShortener")) { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                respondJson("""{"shortenurl":{"shorturl":"https://w.wiki/abc"}}""")
            }
        }

        service.shortenUrl("https://en.wikipedia.org/wiki/Volcano") shouldBe "https://w.wiki/abc"
    }

    @Test
    fun `the stable revision is what readers see, not the newest one`() = runTest {
        val service = service(installed = listOf("FlaggedRevs")) {
            respondJson(
                """{"query":{"pages":[{"pageid":8504,"ns":0,"title":"Volcano",
                   "flagged":{"stable_revid":266300685,"level":0,"level_text":"stable"}}]}}""",
            )
        }

        val flagged = service.flagged(listOf(page)).getValue(page)

        flagged.stableRevisionId shouldBe 266300685L
        flagged.levelText shouldBe "stable"
        flagged.hasPendingChanges shouldBe false
    }

    @Test
    fun `a page with edits awaiting review says so`() = runTest {
        val service = service(installed = listOf("FlaggedRevs")) {
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano",
                   "flagged":{"stable_revid":100,"level":0,
                   "pending_since":"2026-08-31T21:43:00Z"}}]}}""",
            )
        }

        val flagged = service.flagged(listOf(page)).getValue(page)

        flagged.hasPendingChanges shouldBe true
        flagged.pendingSince shouldBe Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `a page never reviewed is absent rather than reported as revision zero`() = runTest {
        val service = service(installed = listOf("FlaggedRevs")) {
            respondJson("""{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano"}]}}""")
        }

        service.flagged(listOf(page)) shouldBe emptyMap()
    }

    @Test
    fun `a wiki without the extension refuses rather than reporting nothing pending`() = runTest {
        val service = service(installed = emptyList()) { respondJson("{}") }

        assertFailsWith<WikiError.Configuration.MissingExtension> {
            service.flagged(listOf(page))
        }
    }

    @Test
    fun `reviewing sends the revision, the flags and a token`() = runTest {
        var body = ""
        val service = service(installed = listOf("FlaggedRevs")) { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"review":{"result":"Success"}}""")
            }
        }

        service.review(RevisionId(9001), flags = mapOf("accuracy" to 1), comment = "checked")

        body.contains("action=review") shouldBe true
        body.contains("revid=9001") shouldBe true
        body.contains("flag_accuracy=1") shouldBe true
        body.contains("token=T") shouldBe true
    }

    @Test
    fun `extension names are matched however the wiki cases them`() = runTest {
        val service = service(installed = listOf("geodata")) { respondJson("{}") }

        service.has("GeoData") shouldBe true
    }

    private fun TestScope.service(
        installed: List<String>,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ExtensionService {
        val transport = KtorTransport(
            client = HttpClient(MockEngine(handler)),
            endpoint = ApiEndpoint("en.wikipedia.org"),
            userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
            throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
            retry = RetryPolicy.NONE,
        )

        return ApiExtensionService(
            transport = transport,
            tokens = TokenStore(transport),
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
            info = SiteInfo(
                id = wiki,
                siteName = "Wikipedia",
                language = LangCode("en"),
                server = "en.wikipedia.org",
                articlePath = "/wiki/$1",
                mainPage = "Main Page",
                generator = "MediaWiki 1.47.0",
                namespaces = NamespaceMap.CANONICAL,
                interwiki = InterwikiMap.EMPTY,
                extensions = installed,
            ),
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
