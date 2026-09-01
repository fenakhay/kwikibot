package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration

class WikiClientTest {

    private val userinfo =
        """{"query":{"userinfo":{"id":0,"name":"192.0.2.1","groups":["*"],"rights":["read"]}}}"""

    private val siteinfo = """
        {"query":{
          "general":{"wikiid":"testwiki","sitename":"Test Wiki","lang":"en",
                     "server":"//test.example.org","articlepath":"/wiki/${'$'}1",
                     "mainpage":"Main Page","generator":"MediaWiki 1.47.0"},
          "namespaces":{
            "0":{"id":0,"name":"","case":"first-letter"},
            "4":{"id":4,"name":"Project","canonical":"Project","case":"first-letter"},
            "14":{"id":14,"name":"Category","canonical":"Category","case":"first-letter"}},
          "interwikimap":[{"prefix":"w","url":"https://en.wikipedia.org/wiki/${'$'}1"}]}}
    """.trimIndent()

    private fun TestScope.client(asked: MutableList<String> = mutableListOf()): WikiClient {
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString() + "&" + request.url.encodedQuery
            asked += body
            when {
                "userinfo" in body -> respondJson(userinfo)
                "siteinfo" in body -> respondJson(siteinfo)
                else -> respondJson("""{"error":{"code":"unexpected","info":$body}}""")
            }
        }

        return WikiClient(
            WikiConfig(
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            ),
            engine = engine,
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData = respond(
        ByteReadChannel(body),
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun `opening a wiki reads who we are and what the wiki is`() = runTest {
        val asked = mutableListOf<String>()

        client(asked).use { client ->
            val wiki = client.wiki(ApiEndpoint("test.example.org"))

            wiki.id.dbName shouldBe "testwiki"
            wiki.info.siteName shouldBe "Test Wiki"
            wiki.info.generator shouldBe "MediaWiki 1.47.0"
            wiki.identity.name shouldBe "192.0.2.1"
        }

        asked.count { "userinfo" in it } shouldBe 1
        asked.count { "siteinfo" in it } shouldBe 1
    }

    @Test
    fun `a family and a language code resolve to that family's endpoint`() = runTest {
        client().use { client ->
            val wiki = client.wiki(LangCode("en"), Family.WIKTIONARY)
            wiki.info.siteName shouldBe "Test Wiki"
        }
    }

    @Test
    fun `the handle exposes every service without another request`() = runTest {
        val asked = mutableListOf<String>()

        client(asked).use { client ->
            val wiki = client.wiki(ApiEndpoint("test.example.org"))
            val before = asked.size

            listOf(
                wiki.pages, wiki.lists, wiki.revisions, wiki.users, wiki.logs,
                wiki.files, wiki.extensions, wiki.proofread, wiki.renderer, wiki.meta,
                wiki.paramInfo,
            ).forEach { it shouldBe it }

            asked.size shouldBe before
        }
    }

    @Test
    fun `the wiki resolves a title against the namespaces it read`() = runTest {
        client().use { client ->
            val wiki = client.wiki(ApiEndpoint("test.example.org"))

            val category = wiki.ref("Category:Volcanoes").title.shouldBeInstanceOf<Title.Local>()
            category.namespace shouldBe Namespace.CATEGORY
            category.text shouldBe "Volcanoes"

            wiki.ref("volcano").title.shouldBeInstanceOf<Title.Local>().namespace shouldBe
                Namespace.MAIN
        }
    }

    @Test
    fun `an interwiki target is refused rather than resolved to another project`() = runTest {
        client().use { client ->
            val wiki = client.wiki(ApiEndpoint("test.example.org"))

            wiki.parse("w:Etsy").shouldBeInstanceOf<Title.Interwiki>()
            wiki.parse("volcano").shouldBeInstanceOf<Title.Local>()
        }
    }

    @Test
    fun `two wikis opened from one client share the HTTP stack`() = runTest {
        client().use { client ->
            val first = client.wiki(ApiEndpoint("test.example.org"))
            val second = client.wiki(ApiEndpoint("test.example.org"))

            first.id shouldBe second.id
        }
    }
}
