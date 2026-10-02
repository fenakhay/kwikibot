package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiExtensionService
import com.fenakhay.kwikibot.client.model.AbuseFilterFlags
import com.fenakhay.kwikibot.client.model.TriageBacklog
import com.fenakhay.kwikibot.client.model.TriageStatus
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.decode.OptionSet
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
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
        val service =
            service(installed = emptyList()) {
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
        val service =
            service(installed = listOf("GeoData")) {
                respondJson(
                    """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano","coordinates":[
                   {"lat":45.9,"lon":6.8,"primary":"","globe":"earth","type":"mountain"}]}]}}"""
                )
            }

        val found = service.coordinates(listOf(page)).getValue(page).single()

        found.latitude shouldBe 45.9
        found.isPrimary shouldBe true
        found.type shouldBe "mountain"
    }

    @Test
    fun `a coordinate on another globe says so`() = runTest {
        val service =
            service(installed = listOf("GeoData")) {
                respondJson(
                    """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano","coordinates":[
                   {"lat":18.6,"lon":226.2,"globe":"mars"}]}]}}"""
                )
            }

        service.coordinates(listOf(page)).getValue(page).single().globe shouldBe "mars"
    }

    @Test
    fun `a nearby search returns pages`() = runTest {
        val service =
            service(installed = listOf("GeoData")) {
                respondJson(
                    """{"query":{"geosearch":[{"pageid":1,"ns":0,"title":"Volcano","dist":120.4}]}}"""
                )
            }

        service.nearby(45.9, 6.8).map { it.title.text } shouldBe listOf("Volcano")
    }

    @Test
    fun `the wikidata item of a page is read from its page properties`() = runTest {
        val service =
            service(installed = listOf("WikibaseClient")) {
                respondJson(
                    """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano",
                   "pageprops":{"wikibase_item":"Q8072"}}]}}"""
                )
            }

        service.wikibaseItems(listOf(page)).getValue(page) shouldBe "Q8072"
    }

    @Test
    fun `a page with no item is absent rather than empty`() = runTest {
        val service =
            service(installed = listOf("WikibaseClient")) {
                respondJson("""{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano"}]}}""")
            }

        service.wikibaseItems(listOf(page)) shouldBe emptyMap()
    }

    @Test
    fun `lint errors carry where in the wikitext they are`() = runTest {
        val service =
            service(installed = listOf("Linter")) {
                respondJson(
                    """{"query":{"linterrors":[{"lintId":9,"category":"obsolete-tag","pageid":1,
                   "ns":0,"title":"Volcano","location":[120,140],
                   "templateInfo":{},"params":{"name":"font"}}]}}"""
                )
            }

        val error = service.lintErrors().toList().single()

        error.category shouldBe "obsolete-tag"
        error.range shouldBe 120..140
        error.details["name"] shouldBe "font"
        error.page?.title?.text shouldBe "Volcano"
    }

    @Test
    fun `a wiki without Linter is refused when the errors are read, not answered with none`() = runTest {
        var requests = 0
        val service =
            service(installed = emptyList()) {
                requests++
                respondJson("""{"query":{"linterrors":[]}}""")
            }

        val errors = service.lintErrors()
        requests shouldBe 0

        assertFailsWith<WikiError.Configuration.MissingExtension> { errors.toList() }
        requests shouldBe 0
    }

    @Test
    fun `abuse filters on a current wiki are asked for their flags and read with them`() = runTest {
        val sent = mutableListOf<Map<String, String>>()
        val service =
            service(installed = listOf("Abuse Filter")) { request ->
                if (request.url.parameters["action"] == "paraminfo") {
                    respondJson(abuseFilterParamInfo(current = true))
                } else {
                    sent +=
                        request.url.parameters.entries().associate { (key, values) -> key to values.single() }
                    respondJson(
                        """{"query":{"abusefilters":[
                           {"id":1,"description":"Page blanking","actions":["warn","tag"],"enabled":true,
                            "deleted":false,"private":false,"protected":false,"suppressed":false,
                            "lastedittime":"2026-09-30T12:00:00Z","patternredacted":true}]}}"""
                    )
                }
            }

        val filter =
            service
                .abuseFilters(
                    show = OptionSet().on("enabled"),
                    properties = setOf(AbuseFilterProperty.FLAGS, AbuseFilterProperty.LAST_EDIT_TIME),
                )
                .toList()
                .single()

        sent.single()["abfprop"] shouldBe "flags|lastedittime|id"
        sent.single()["abfshow"] shouldBe "enabled"
        sent.single()["abfdir"] shouldBe "newer"
        filter.id shouldBe 1
        filter.actions shouldBe listOf("warn", "tag")
        filter.flags shouldBe AbuseFilterFlags(true, false, false, false, false)
        filter.lastEdited shouldBe Instant.parse("2026-09-30T12:00:00Z")
        filter.pattern shouldBe null
        filter.redacted shouldBe setOf(AbuseFilterProperty.PATTERN)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `a wiki older than the flags property is asked the old way, and its old answer read`() = runTest {
        val sent = mutableListOf<String>()
        val service =
            service(installed = listOf("Abuse Filter")) { request ->
                if (request.url.parameters["action"] == "paraminfo") {
                    respondJson(abuseFilterParamInfo(current = false))
                } else {
                    sent += request.url.parameters["abfprop"].orEmpty()
                    // Before MediaWiki 1.47 a flag was present and empty when true, and absent when false,
                    // and the actions were one comma-separated string.
                    respondJson(
                        """{"query":{"abusefilters":[
                           {"id":3,"actions":"disallow,tag","enabled":"","private":""}]}}"""
                    )
                }
            }

        val filter = service.abuseFilters(properties = setOf(AbuseFilterProperty.STATUS)).toList().single()

        sent.single() shouldBe "status|private|protected|id"
        filter.flags shouldBe AbuseFilterFlags(true, false, true, false, false)
        filter.actions shouldBe listOf("disallow", "tag")
    }

    @Test
    fun `abuse filter actions sent as one string are split`() = runTest {
        val service =
            service(installed = listOf("Abuse Filter")) { request ->
                if (request.url.parameters["action"] == "paraminfo") {
                    respondJson(abuseFilterParamInfo(current = true))
                } else {
                    respondJson("""{"query":{"abusefilters":[{"id":3,"actions":"disallow,tag"}]}}""")
                }
            }

        val filter = service.abuseFilters(properties = setOf(AbuseFilterProperty.ACTIONS)).toList().single()

        filter.actions shouldBe listOf("disallow", "tag")
        filter.flags shouldBe null
    }

    @Test
    fun `the new pages feed is paged by the last page's timestamp and id`() = runTest {
        val sent = mutableListOf<Map<String, String>>()
        // A full batch: two pages worth reading, 197 sharing one timestamp, and one with no metadata,
        // which counts towards the batch without being listed.
        val fillers =
            (0 until 197).joinToString(",") {
                """{"pageid":${100 + it},"title":"Filler $it","creation_date_utc":"20260929000000"}"""
            }
        val service =
            service(installed = listOf("PageTriage")) { request ->
                val params =
                    request.url.parameters.entries().associate { (key, values) -> key to values.single() }
                sent += params
                if ("offset" !in params) {
                    respondJson(
                        """{"pagetriagelist":{"result":"success","pages_missing_metadata":[999],"pages":[
                           {"pageid":11,"title":"Volcano","creation_date_utc":"20260930120000",
                            "user_name":"Alice","creator_is_temp_account":false,"patrol_status":"0",
                            "is_redirect":"0","linkcount":"0","reviewer":null},
                           {"pageid":12,"title":"Lava","creation_date_utc":"20260930110000",
                            "user_name":"Bob","creator_hidden":true,"patrol_status":"1","reviewer":"Carol",
                            "ptrp_reviewed_updated":"20260930130000","is_redirect":"0","linkcount":"3"},$fillers]}}"""
                    )
                } else {
                    respondJson(
                        """{"pagetriagelist":{"result":"success","pages_missing_metadata":[],"pages":[
                           {"pageid":13,"title":"Ash","creation_date_utc":"20260928100000","patrol_status":"3"}]}}"""
                    )
                }
            }

        val pages = service.newPages(reviewed = null, creators = setOf("Alice", "Bob")).toList()

        pages.size shouldBe 200
        pages.first().page.title.text shouldBe "Volcano"
        pages.last().page.title.text shouldBe "Ash"
        pages[0].creator shouldBe "Alice"
        pages[0].status shouldBe TriageStatus.UNREVIEWED
        pages[0].isOrphan shouldBe true
        pages[1].isOrphan shouldBe false
        pages[0].isRedirect shouldBe false
        pages[0].created shouldBe Instant.parse("2026-09-30T12:00:00Z")
        pages[1].creator shouldBe null
        pages[1].reviewer shouldBe "Carol"
        pages[1].status shouldBe TriageStatus.REVIEWED
        pages.last().status shouldBe TriageStatus.AUTOPATROLLED

        sent.size shouldBe 2
        sent[0]["username"] shouldBe "Alice|Bob"
        sent[0]["showreviewed"] shouldBe "1"
        sent[0]["showunreviewed"] shouldBe "1"
        sent[0]["showothers"] shouldBe "1"
        sent[0]["dir"] shouldBe "newestfirst"
        sent[0]["limit"] shouldBe "200"
        sent[1]["offset"] shouldBe "20260929000000"
        sent[1]["pageoffset"] shouldBe "296"
    }

    @Test
    fun `a limit is asked for rather than read past`() = runTest {
        val sent = mutableListOf<String>()
        val service =
            service(installed = listOf("PageTriage")) { request ->
                sent += request.url.parameters["limit"].orEmpty()
                respondJson(
                    """{"pagetriagelist":{"result":"success","pages":[
                       {"pageid":11,"title":"Volcano","creation_date_utc":"20260930120000"},
                       {"pageid":12,"title":"Lava","creation_date_utc":"20260930110000"}]}}"""
                )
            }

        service.newPages(limit = 2).toList().size shouldBe 2
        sent shouldBe listOf("2")
    }

    @Test
    fun `the feed stops when the wiki returns fewer pages than were asked for`() = runTest {
        var requests = 0
        val service =
            service(installed = listOf("PageTriage")) {
                requests++
                respondJson(
                    """{"pagetriagelist":{"result":"success","pages":[
                       {"pageid":11,"title":"Volcano","creation_date_utc":"20260930120000"}]}}"""
                )
            }

        service.newPages(order = NewPagesOrder.OLDEST_FIRST, redirects = true).toList().size shouldBe 1
        requests shouldBe 1
    }

    @Test
    fun `a list in review order stops after one batch, since PageTriage cannot continue it`() = runTest {
        var requests = 0
        val full =
            (1..200).joinToString(",") {
                """{"pageid":$it,"title":"P$it","creation_date_utc":"20260930120000"}"""
            }
        val service =
            service(installed = listOf("PageTriage")) {
                requests++
                respondJson("""{"pagetriagelist":{"result":"success","pages":[$full]}}""")
            }

        service.newPages(order = NewPagesOrder.OLDEST_REVIEW_FIRST).toList().size shouldBe 200
        requests shouldBe 1
    }

    @Test
    fun `asking about more creators than the feed takes is refused rather than sent`() = runTest {
        var requests = 0
        val service =
            service(installed = listOf("PageTriage")) {
                requests++
                respondJson("{}")
            }
        val creators = (1..ExtensionService.MAX_CREATORS + 1).map { "User $it" }.toSet()

        assertFailsWith<IllegalArgumentException> { service.newPages(creators = creators).toList() }
        assertFailsWith<IllegalArgumentException> { service.newPageStats(creators = creators) }
        requests shouldBe 0
    }

    @Test
    fun `the backlog counts what is waiting, and a queue the wiki does not keep is absent`() = runTest {
        val service =
            service(installed = listOf("PageTriage")) {
                respondJson(
                    """{"pagetriagestats":{"result":"success","stats":{
                       "unreviewedarticle":{"count":12,"oldest":"2026-09-01T00:00:00Z"},
                       "unreviewedredirect":{"count":0,"oldest":"2026-10-02T09:00:00Z"},
                       "reviewedarticle":{"reviewed_count":40},"reviewedredirect":{"reviewed_count":5},
                       "filteredarticle":7,"unrevieweddraft":[],"namespace":0}}}"""
                )
            }

        val stats = service.newPageStats()

        stats.unreviewedArticles shouldBe TriageBacklog(12, Instant.parse("2026-09-01T00:00:00Z"))
        stats.unreviewedRedirects shouldBe TriageBacklog(0, null)
        stats.unreviewedDrafts shouldBe null
        stats.reviewedArticles shouldBe 40
        stats.reviewedRedirects shouldBe 5
        stats.matching shouldBe 7
    }

    @Test
    fun `a wiki without PageTriage is refused`() = runTest {
        val service = service(installed = emptyList()) { respondJson("{}") }

        assertFailsWith<WikiError.Configuration.MissingExtension> { service.newPages().toList() }
        assertFailsWith<WikiError.Configuration.MissingExtension> { service.newPageStats() }
        assertFailsWith<WikiError.Configuration.MissingExtension> { service.abuseFilters().toList() }
    }

    private fun abuseFilterParamInfo(current: Boolean): String {
        val values =
            if (current) {
                """["actions","flags","id","lastedittime","private","protected","status","suppressed"]"""
            } else {
                """["actions","id","lastedittime","private","protected","status"]"""
            }
        val deprecated = if (current) """["private","protected","status","suppressed"]""" else "[]"
        return """{"paraminfo":{"modules":[{"name":"abusefilters","path":"query+abusefilters","prefix":"abf",
            "source":"Abuse Filter","parameters":[{"name":"prop","type":$values,"multi":true,
            "deprecatedvalues":$deprecated}]}]}}"""
    }

    @Test
    fun `notifications report whether they have been read`() = runTest {
        val service =
            service(installed = listOf("Echo")) {
                respondJson(
                    """{"query":{"notifications":{"list":[
                   {"id":1,"type":"mention","title":{"full":"Talk:Volcano"},
                    "agent":{"name":"Someone"},
                    "timestamp":{"utciso8601":"2026-08-01T12:00:00Z"}},
                   {"id":2,"type":"thank-you-edit","read":"2026-08-02T00:00:00Z"}]}}}"""
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
        val service =
            service(installed = listOf("Thanks")) { request ->
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
    fun `a short url is asked for in one request, carrying no token the module does not take`() = runTest {
        val sent = mutableListOf<String>()
        val service =
            service(installed = listOf("UrlShortener")) { request ->
                sent += request.body.toByteArray().decodeToString()
                respondJson("""{"shortenurl":{"shorturl":"https://w.wiki/abc"}}""")
            }

        service.shortenUrl("https://en.wikipedia.org/wiki/Volcano") shouldBe "https://w.wiki/abc"
        sent.single().contains("token=") shouldBe false
        sent.single().contains("action=shortenurl") shouldBe true
    }

    @Test
    fun `the stable revision is what readers see, not the newest one`() = runTest {
        val service =
            service(installed = listOf("FlaggedRevs")) {
                respondJson(
                    """{"query":{"pages":[{"pageid":8504,"ns":0,"title":"Volcano",
                   "flagged":{"stable_revid":266300685,"level":0,"level_text":"stable"}}]}}"""
                )
            }

        val flagged = service.flagged(listOf(page)).getValue(page)

        flagged.stableRevisionId shouldBe 266300685L
        flagged.levelText shouldBe "stable"
        flagged.hasPendingChanges shouldBe false
    }

    @Test
    fun `a page with edits awaiting review says so`() = runTest {
        val service =
            service(installed = listOf("FlaggedRevs")) {
                respondJson(
                    """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano",
                   "flagged":{"stable_revid":100,"level":0,
                   "pending_since":"2026-08-31T21:43:00Z"}}]}}"""
                )
            }

        val flagged = service.flagged(listOf(page)).getValue(page)

        flagged.hasPendingChanges shouldBe true
        flagged.pendingSince shouldBe Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `a page never reviewed is absent rather than reported as revision zero`() = runTest {
        val service =
            service(installed = listOf("FlaggedRevs")) {
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
        val service =
            service(installed = listOf("FlaggedRevs")) { request ->
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
        val transport =
            KtorTransport(
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
            info =
                SiteInfo(
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
