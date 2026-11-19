package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.HttpSettings
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import com.fenakhay.kwikibot.protocol.ParamInfo
import com.fenakhay.kwikibot.protocol.SiteInfo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject

class PageviewsTest {

    private val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")
    private val january = Instant.parse("2024-01-01T00:00:00Z")
    private val third = Instant.parse("2024-01-03T23:59:59Z")

    private val asked = mutableListOf<HttpRequestData>()

    /**
     * Held while a request is recorded, since the engine answers requests side by side on several threads.
     */
    private val recording = Mutex()

    private val einstein =
        """{"items":[
          {"project":"en.wikipedia","article":"Albert_Einstein","granularity":"daily","timestamp":"2024010100",
           "access":"all-access","agent":"user","views":29632},
          {"project":"en.wikipedia","article":"Albert_Einstein","granularity":"daily","timestamp":"2024010200",
           "access":"all-access","agent":"user","views":24807}]}"""

    private val noData =
        """{"detail":"The date(s) you used are valid, but we either do not have data for those date(s), or the
           project you asked for is not loaded yet. Please check documentation for more information",
           "method":"get","status":404,"title":"Not Found","type":"about:blank"}"""

    @Test
    fun `a page's views are asked for by day for people on every access and read oldest first`() = runTest {
        val views = client {
            respondJson(einstein)
        }
            .perArticle("en.wikipedia", "Albert Einstein", january, third)

        asked.single().url.toString() shouldBe
            "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/all-access/user/" +
                "Albert_Einstein/daily/2024010100/2024010323"
        asked.single().headers[HttpHeaders.UserAgent].orEmpty() shouldContain "TestBot/1.0"
        views shouldBe
            listOf(
                PageviewCount(
                    timestamp = january,
                    views = 29632,
                    project = "en.wikipedia",
                    access = PageviewAccess.ALL_ACCESS,
                    agent = PageviewAgent.USER,
                    granularity = PageviewGranularity.DAILY,
                    title = "Albert Einstein",
                ),
                PageviewCount(
                    timestamp = Instant.parse("2024-01-02T00:00:00Z"),
                    views = 24807,
                    project = "en.wikipedia",
                    access = PageviewAccess.ALL_ACCESS,
                    agent = PageviewAgent.USER,
                    granularity = PageviewGranularity.DAILY,
                    title = "Albert Einstein",
                ),
            )
    }

    @Test
    fun `a title is sent with underscores and every byte outside the unreserved set escaped`() = runTest {
        val service = client { respondJson("""{"items":[]}""") }

        val titles =
            listOf(
                "AC/DC",
                "Who Framed Roger Rabbit?",
                "Zürich",
                "C++",
                "東京都",
                "Talk:Saltburn (film)",
                "100%",
            )
        for (title in titles) service.perArticle("en.wikipedia", title, january, january)

        asked.map { it.url.encodedPath.split('/')[ARTICLE] } shouldBe
            listOf(
                "AC%2FDC",
                "Who_Framed_Roger_Rabbit%3F",
                "Z%C3%BCrich",
                "C%2B%2B",
                "%E6%9D%B1%E4%BA%AC%E9%83%BD",
                "Talk%3ASaltburn_%28film%29",
                "100%25",
            )
    }

    @Test
    fun `the access the agent and the granularity asked for go in the path and come back on each count`() =
        runTest {
            val service = client {
                respondJson(
                    """{"items":[{"project":"en.wikipedia","article":"AC/DC",
                       "timestamp":"2024010100","views":3}]}"""
                )
            }

            val count =
                service
                    .perArticle(
                        "en.wikipedia",
                        "AC/DC",
                        january,
                        Instant.parse("2024-03-31T00:00:00Z"),
                        PageviewGranularity.MONTHLY,
                        PageviewAccess.MOBILE_WEB,
                        PageviewAgent.SPIDER,
                    )
                    .single()

            asked.single().url.encodedPath shouldContain
                "/mobile-web/spider/AC%2FDC/monthly/2024010100/2024033100"
            count.access shouldBe PageviewAccess.MOBILE_WEB
            count.agent shouldBe PageviewAgent.SPIDER
            count.granularity shouldBe PageviewGranularity.MONTHLY
            count.title shouldBe "AC/DC"
        }

    @Test
    fun `a project is counted by the hour and its counts name no page`() = runTest {
        val service = client {
            respondJson(
                """{"items":[{"project":"wikidata","access":"desktop","agent":"automated","granularity":"hourly",
                   "timestamp":"2024010105","views":16807841}]}"""
            )
        }

        val counts =
            service.aggregate(
                "www.wikidata",
                january,
                Instant.parse("2024-01-01T05:30:00Z"),
                PageviewGranularity.HOURLY,
                PageviewAccess.DESKTOP,
                PageviewAgent.AUTOMATED,
            )

        asked.single().url.encodedPath shouldBe
            "/api/rest_v1/metrics/pageviews/aggregate/www.wikidata/desktop/automated/hourly/2024010100/2024010105"
        counts shouldBe
            listOf(
                PageviewCount(
                    timestamp = Instant.parse("2024-01-01T05:00:00Z"),
                    views = 16807841,
                    project = "wikidata",
                    access = PageviewAccess.DESKTOP,
                    agent = PageviewAgent.AUTOMATED,
                    granularity = PageviewGranularity.HOURLY,
                )
            )
    }

    @Test
    fun `a count with no time the library can read is left out and one with no views is none`() = runTest {
        val service = client {
            respondJson(
                """{"items":[{"views":1},{"timestamp":"20240101","views":2},{"timestamp":"2024139900"},
                   {"timestamp":"20240101xx"},{"timestamp":"2024010100"}]}"""
            )
        }

        val counts = service.aggregate("en.wikipedia", january, january)

        counts.map { it.timestamp to it.views } shouldBe listOf(january to 0L)
        counts.single().project shouldBe "en.wikipedia"
    }

    @Test
    fun `the most viewed pages of a day and of a month are ranked with their titles`() = runTest {
        val service = client {
            respondJson(
                """{"items":[{"project":"en.wikipedia","access":"all-access","year":"2024","month":"01",
                   "day":"01","articles":[{"article":"Main_Page","views":4817450,"rank":1},
                   {"article":"New_Year's_Day","views":1271924,"rank":2},{}]}]}"""
            )
        }

        val day = service.top("en.wikipedia", Instant.parse("2024-01-05T12:00:00Z"))
        service.top(
            "en.wikipedia",
            Instant.parse("2024-02-29T23:00:00Z"),
            PageviewAccess.MOBILE_APP,
            PageviewGranularity.MONTHLY,
        )

        asked.map { it.url.encodedPath.substringAfter("pageviews/") } shouldBe
            listOf("top/en.wikipedia/all-access/2024/01/05", "top/en.wikipedia/mobile-app/2024/02/all-days")
        day shouldBe
            listOf(
                TopPage("Main Page", 4817450, 1),
                TopPage("New Year's Day", 1271924, 2),
                TopPage("", 0, 0),
            )
    }

    @Test
    fun `a page or a period the API has no data for is no counts`() = runTest {
        val service = client { respondJson(noData, HttpStatusCode.NotFound) }

        service.perArticle("en.wikipedia", "No such page", january, january).shouldBeEmpty()
        service.aggregate("en.wikipedia", january, january).shouldBeEmpty()
        service.top("en.wikipedia", january).shouldBeEmpty()
        asked.size shouldBe 3
    }

    @Test
    fun `a 404 that is not about missing data is a refusal`() = runTest {
        val service = client {
            respondJson(
                """{"detail":"invalid route","status":404,"title":"Not Found"}""",
                HttpStatusCode.NotFound,
            )
        }

        val refused =
            shouldThrow<WikiError.Transport.Refused> {
                service.perArticle("en.wikipedia", "Albert Einstein", january, january)
            }

        refused.status shouldBe 404
        refused.detail shouldBe "invalid route"
        asked.size shouldBe 1
    }

    @Test
    fun `a bad parameter is refused at once with what the API said`() = runTest {
        val service =
            client(RetryPolicy(maxRetries = 3)) {
                respondJson(
                    """{"detail":"start timestamp is invalid, must be a valid date in YYYYMMDD format",
                   "method":"get","status":400,"title":"Bad Request","type":"about:blank"}""",
                    HttpStatusCode.BadRequest,
                )
            }

        val refused =
            shouldThrow<WikiError.Transport.Refused> { service.aggregate("en.wikipedia", january, third) }

        refused.status shouldBe 400
        refused.detail shouldBe "start timestamp is invalid, must be a valid date in YYYYMMDD format"
        asked.size shouldBe 1
    }

    @Test
    fun `a refusal that is not JSON is reported by its text`() = runTest {
        val service = client { respond("<h1>Forbidden</h1>", HttpStatusCode.Forbidden) }

        val refused =
            shouldThrow<WikiError.Transport.Refused> { service.aggregate("en.wikipedia", january, third) }

        refused.detail shouldBe "Forbidden"
    }

    @Test
    fun `a 429 is waited out for as long as Retry-After says and asked again`() = runTest {
        var calls = 0
        val service =
            client(RetryPolicy(maxRetries = 2, initialDelay = 1.seconds)) {
                calls++
                if (calls == 1) {
                    respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30"))
                } else {
                    respondJson(einstein)
                }
            }

        service.perArticle("en.wikipedia", "Albert Einstein", january, third).size shouldBe 2

        asked.size shouldBe 2
        currentTime shouldBe 30.seconds.inWholeMilliseconds
    }

    @Test
    fun `a server error is asked again and one that lasts is raised once the retries are spent`() = runTest {
        var calls = 0
        val recovering =
            client(RetryPolicy(maxRetries = 2)) {
                calls++
                if (calls == 1) respond("", HttpStatusCode.ServiceUnavailable) else respondJson(einstein)
            }
        recovering.perArticle("en.wikipedia", "Albert Einstein", january, third).size shouldBe 2
        asked.size shouldBe 2

        asked.clear()
        val failing = client(RetryPolicy(maxRetries = 2)) { respond("", HttpStatusCode.BadGateway) }
        val failure =
            shouldThrow<WikiError.Transport.ServerError> { failing.aggregate("en.wikipedia", january, third) }
        failure.status shouldBe 502
        asked.size shouldBe 3
    }

    @Test
    fun `a busy API that never stops being busy is raised as a rate limit`() = runTest {
        val service = client(RetryPolicy(maxRetries = 1)) { respond("", HttpStatusCode.TooManyRequests) }

        shouldThrow<WikiError.Transport.RateLimited> { service.top("en.wikipedia", january) }
        asked.size shouldBe 2
    }

    @Test
    fun `a connection that fails is tried again and then reported as unreachable`() = runTest {
        val service = client(RetryPolicy(maxRetries = 1)) { throw IOException("connection reset") }

        shouldThrow<WikiError.Transport.Unreachable> { service.top("en.wikipedia", january) }
        asked.size shouldBe 2
    }

    @Test
    fun `an answer that is not JSON or not a success is a bad response`() = runTest {
        val garbled = client { respond("<html>maintenance</html>", HttpStatusCode.OK) }
        shouldThrow<WikiError.Api> { garbled.top("en.wikipedia", january) }.code shouldBe "badresponse"

        val moved = client { respond("", HttpStatusCode.NotModified) }
        shouldThrow<WikiError.Api> { moved.top("en.wikipedia", january) }.code shouldBe "badresponse"
    }

    @Test
    fun `what the API cannot answer is refused before anything is sent`() = runTest {
        val service = client { respondJson(einstein) }

        shouldThrow<IllegalArgumentException> {
            service.perArticle("en.wikipedia", "Albert Einstein", january, third, PageviewGranularity.HOURLY)
        }
        shouldThrow<IllegalArgumentException> {
            service.perArticle("en.wikipedia", "Albert Einstein", third, january)
        }
        shouldThrow<IllegalArgumentException> { service.aggregate("en.wikipedia", third, january) }
        shouldThrow<IllegalArgumentException> {
            service.top("en.wikipedia", january, granularity = PageviewGranularity.HOURLY)
        }
        asked.shouldBeEmpty()
    }

    @Test
    fun `a base URL given is where the API is asked`() = runTest {
        val service =
            Pageviews(
                HttpClient(engine { respondJson(einstein) }),
                userAgent,
                RetryPolicy.NONE,
                "http://aqs.test",
            )

        service.aggregate("en.wikipedia", january, january)

        asked.single().url.toString() shouldBe
            "http://aqs.test/aggregate/en.wikipedia/all-access/user/daily/2024010100/2024010100"
    }

    @Test
    fun `a Wikimedia host names its project and any other host has none`() {
        Pageviews.projectOf("en.wikipedia.org") shouldBe "en.wikipedia"
        Pageviews.projectOf("https://en.m.wikipedia.org/wiki/Lava") shouldBe "en.wikipedia"
        Pageviews.projectOf("www.wikidata.org") shouldBe "wikidata"
        Pageviews.projectOf("m.wikidata.org") shouldBe "wikidata"
        Pageviews.projectOf("www.mediawiki.org") shouldBe "mediawiki"
        Pageviews.projectOf("Commons.Wikimedia.org") shouldBe "commons.wikimedia"
        Pageviews.projectOf("de.wikivoyage.org:443") shouldBe "de.wikivoyage"

        for (host in
            listOf("localhost:8080", "wiki.example.org", "wikipedia.org.example.com", "fandom.com")) {
            shouldThrow<IllegalArgumentException> { Pageviews.projectOf(host) }
                .message
                .orEmpty() shouldContain host
        }
    }

    @Test
    fun `a wiki's own pages are counted under its project each once and in the order named`() = runTest {
        val service = client { request ->
            if ("No_such_page" in request.url.encodedPath) {
                respondJson(noData, HttpStatusCode.NotFound)
            } else {
                respondJson(einstein)
            }
        }
        val wiki = wikiAt("en.wikipedia.org")
        val einsteinPage = page(wiki, Namespace.MAIN, "Albert Einstein")
        val talk = page(wiki, Namespace.TALK, "AC/DC")
        val missing = page(wiki, Namespace.MAIN, "No such page")

        val views = wiki.pageviews(service, listOf(talk, einsteinPage, missing, talk), january, third)

        views.keys.toList() shouldBe listOf(talk, einsteinPage, missing)
        views.getValue(einsteinPage).size shouldBe 2
        views.getValue(missing).shouldBeEmpty()
        asked.map { it.url.encodedPath.split('/')[PROJECT] }.toSet() shouldBe setOf("en.wikipedia")
        asked.map { it.url.encodedPath.split('/')[ARTICLE] }.toSet() shouldBe
            setOf("Talk%3AAC%2FDC", "Albert_Einstein", "No_such_page")
        wiki.pageviewsProject shouldBe "en.wikipedia"
    }

    @Test
    fun `a wiki that is not Wikimedia's is refused before anything is sent`() = runTest {
        val service = client { respondJson(einstein) }
        val wiki = wikiAt("localhost:8080")

        shouldThrow<IllegalArgumentException> {
            wiki.pageviews(service, listOf(page(wiki, Namespace.MAIN, "Lava")), january, third)
        }
        asked.shouldBeEmpty()
    }

    @Test
    fun `the README's sample counts a page and ranks a wiki's pages and a day's`() = runTest {
        val printed = mutableListOf<String>()
        HttpClient(
                engine { request ->
                    if ("/top/" in request.url.encodedPath) {
                        respondJson(
                            """{"items":[{"articles":[{"article":"Main_Page","views":9,"rank":1}]}]}"""
                        )
                    } else {
                        respondJson(einstein)
                    }
                }
            )
            .use { http ->
                val pageviews = Pageviews(http, userAgent)
                val january = Instant.parse("2024-01-01T00:00:00Z")
                val end = Instant.parse("2024-01-31T00:00:00Z")

                val einstein = pageviews.perArticle("en.wikipedia", "Albert Einstein", january, end)
                printed += einstein.sumOf { it.views }.toString()

                val wikipedia = wikiAt("en.wikipedia.org")
                val volcanoes = listOf("Mount Etna", "Mount Fuji").map { page(wikipedia, Namespace.MAIN, it) }
                wikipedia
                    .pageviews(pageviews, volcanoes, january, end, PageviewGranularity.MONTHLY)
                    .entries
                    .sortedByDescending { (_, counts) -> counts.sumOf { it.views } }
                    .take(10)
                    .forEach { (page, counts) -> printed += "${page.title}: ${counts.sumOf { it.views }}" }

                pageviews.top("en.wikipedia", january).take(5).forEach {
                    printed += "${it.rank}. ${it.title}"
                }
            }

        printed shouldBe listOf("54439", "Mount Etna: 54439", "Mount Fuji: 54439", "1. Main Page")
        asked.count { "/monthly/" in it.url.encodedPath } shouldBe 2
    }

    @Test
    fun `no more than four pages are asked about at once`() = runTest {
        mostInFlight(HttpClient(slowEngine())) shouldBe 4
    }

    @Test
    fun `a client's own limit on requests in flight holds for a wiki's pages`() = runTest {
        WikiHttpClient.create(HttpSettings(maxConcurrentRequests = 2), engine = slowEngine()).use { http ->
            mostInFlight(http) shouldBeLessThanOrEqual 2
        }
    }

    private val inFlight = AtomicIntHolder()

    @OptIn(ExperimentalAtomicApi::class)
    private class AtomicIntHolder {
        val now = AtomicInt(0)
        val most = AtomicInt(0)
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun slowEngine() = engine {
        val now = inFlight.now.incrementAndFetch()
        inFlight.most.update { maxOf(it, now) }
        delay(100.milliseconds)
        inFlight.now.update { it - 1 }
        respondJson(einstein)
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun mostInFlight(http: HttpClient): Int {
        val wiki = wikiAt("en.wikipedia.org")
        val pages = List(PAGES) { page(wiki, Namespace.MAIN, "Page $it") }

        wiki.pageviews(Pageviews(http, userAgent, RetryPolicy.NONE), pages, january, third).size shouldBe
            PAGES
        return inFlight.most.load()
    }

    private fun page(wiki: Wiki, namespace: Namespace, text: String) =
        PageRef(wiki.id, Title.Local(namespace, text))

    private fun engine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        MockEngine { request ->
            recording.withLock { asked += request }
            handler(request)
        }

    private fun client(
        retry: RetryPolicy = RetryPolicy.NONE,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pageviews = Pageviews(HttpClient(engine(handler)), userAgent, retry)

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    /** A wiki served from [server] that answers nothing: only its site information is read. */
    private fun wikiAt(server: String): Wiki =
        object : Wiki {
            override val id = WikiId("enwiki")
            override val info =
                SiteInfo(
                    id = id,
                    siteName = "Wikipedia",
                    language = LangCode("en"),
                    server = server,
                    articlePath = "/wiki/$1",
                    mainPage = "Main Page",
                    generator = "MediaWiki 1.47.0",
                    namespaces = NamespaceMap.CANONICAL,
                    interwiki = InterwikiMap.EMPTY,
                )
            override val identity: Identity
                get() = unused("identity")

            override val pages
                get() = unused("pages")

            override val lists
                get() = unused("lists")

            override val revisions
                get() = unused("revisions")

            override val users
                get() = unused("users")

            override val logs
                get() = unused("logs")

            override val files
                get() = unused("files")

            override val extensions
                get() = unused("extensions")

            override val proofread
                get() = unused("proofread")

            override val renderer
                get() = unused("renderer")

            override val meta
                get() = unused("meta")

            override val paramInfo: ParamInfo
                get() = unused("paramInfo")

            override val transport: MediaWikiTransport =
                object : MediaWikiTransport {
                    override val endpoint = ApiEndpoint(server)

                    override suspend fun call(request: ApiRequest): JsonObject = unused("transport")
                }

            override val tokens: TokenStore
                get() = unused("tokens")
        }

    private fun unused(what: String): Nothing = throw AssertionError("$what was not expected to be used")

    private companion object {
        const val PROJECT = 6
        const val ARTICLE = 9
        const val PAGES = 12
    }
}
