package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.DatePages
import com.fenakhay.kwikibot.model.InterwikiMap
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.Signatures
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.protocol.SiteInfo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class WikiDatesTest {

    @Test
    fun `both the plain and the genitive month name are accepted`() = runTest {
        val wiki = wiki(language = "pl") {
            respondJson(
                """{"query":{"allmessages":[
                   {"name":"january","content":"styczeń"},
                   {"name":"january-gen","content":"stycznia"},
                   {"name":"august","content":"sierpień"},
                   {"name":"august-gen","content":"sierpnia"}]}}""",
            )
        }

        val months = wiki.monthNames()

        months["styczeń"] shouldBe 1
        months["stycznia"] shouldBe 1
        months["sierpnia"] shouldBe 8
    }

    @Test
    fun `signatures read the wiki's own month names`() = runTest {
        val wiki = wiki(language = "de", timezone = "UTC") {
            respondJson(
                """{"query":{"allmessages":[
                   {"name":"august","content":"August"},
                   {"name":"august-gen","content":"August"}]}}""",
            )
        }

        val found = wiki.signatures().latest("-- X 21:43, 31. August 2026 (CEST)")

        found?.instant shouldBe Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `a wiki that signs in local time is not read as UTC`() = runTest {
        val wiki = wiki(language = "de", timezone = "Europe/Berlin") {
            respondJson(
                """{"query":{"allmessages":[{"name":"august","content":"August"}]}}""",
            )
        }

        val found = wiki.signatures().latest("-- X 21:43, 31. August 2026 (CEST)")

        found?.instant shouldBe Instant.parse("2026-08-31T19:43:00Z")
    }

    @Test
    fun `the shipped tables still answer without a session`() {
        Signatures.GERMAN.latest("-- X 21:43, 31. Aug. 2026 (CEST)")?.instant shouldBe
            Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `a day title keeps the wiki's pattern and takes the wiki's words`() = runTest {
        val wiki = wiki(language = "de") {
            respondJson(
                """{"query":{"allmessages":[
                   {"name":"january","content":"Jänner"},
                   {"name":"february","content":"Februar"},
                   {"name":"march","content":"März"},
                   {"name":"april","content":"April"},
                   {"name":"may","content":"Mai"},
                   {"name":"june","content":"Juni"},
                   {"name":"july","content":"Juli"},
                   {"name":"august","content":"August"},
                   {"name":"september","content":"September"},
                   {"name":"october","content":"Oktober"},
                   {"name":"november","content":"November"},
                   {"name":"december","content":"Dezember"}]}}""",
            )
        }

        val format = checkNotNull(wiki.dayTitleFormat())
        DatePages.register("de-test", format)

        DatePages.dayTitle(1, 15, "de-test") shouldBe "15. Jänner"
    }

    @Test
    fun `a wiki missing a month message keeps the shipped names rather than a hole`() = runTest {
        val wiki = wiki(language = "de") {
            respondJson("""{"query":{"allmessages":[{"name":"january","content":"Jänner"}]}}""")
        }

        wiki.dayTitleFormat() shouldBe DatePages.format("de")
    }

    @Test
    fun `a language with no registered day format has none`() = runTest {
        val wiki = wiki(language = "ja") { respondJson("""{"query":{"allmessages":[]}}""") }

        wiki.dayTitleFormat().shouldBeNull()
    }

    private fun TestScope.wiki(
        language: String,
        timezone: String = "UTC",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Wiki {
        val id = WikiId("${language}wiki")
        val transport = KtorTransport(
            client = HttpClient(MockEngine(handler)),
            endpoint = ApiEndpoint("$language.wikipedia.org"),
            userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
            throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
            retry = RetryPolicy.NONE,
        )

        return object : Wiki {
            override val id: WikiId = id
            override val identity get() = unused("identity")
            override val pages get() = unused("pages")
            override val lists get() = unused("lists")
            override val revisions get() = unused("revisions")
            override val users get() = unused("users")
            override val logs get() = unused("logs")
            override val files get() = unused("files")
            override val extensions get() = unused("extensions")
            override val renderer get() = unused("renderer")
            override val proofread get() = unused("proofread")
            override val paramInfo get() = unused("paramInfo")
            override val transport = transport
            override val tokens get() = unused("tokens")

            override val info: SiteInfo = SiteInfo(
                id = id,
                siteName = "Wikipedia",
                language = LangCode(language),
                server = "$language.wikipedia.org",
                articlePath = "/wiki/$1",
                mainPage = "Main Page",
                generator = "MediaWiki 1.47.0",
                namespaces = NamespaceMap.CANONICAL,
                interwiki = InterwikiMap.EMPTY,
                timezone = timezone,
            )

            override val meta: MetaService = ApiMetaService(transport, TokenStore(transport))
        }
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun unused(what: String): Nothing =
        throw NotImplementedError("this test wiki has no $what")
}
