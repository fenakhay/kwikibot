package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.cache.ResponseCache
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.protocol.ApiWarning
import com.fenakhay.kwikibot.protocol.ApiWarningListener
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
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class WikiClientTest {

    private val userinfo =
        """{"query":{"userinfo":{"id":0,"name":"192.0.2.1","groups":["*"],"rights":["read"]}}}"""

    private val siteinfo =
        """
        {"query":{
          "general":{"wikiid":"testwiki","sitename":"Test Wiki","lang":"en",
                     "server":"//test.example.org","articlepath":"/wiki/${'$'}1",
                     "mainpage":"Main Page","generator":"MediaWiki 1.47.0"},
          "namespaces":{
            "0":{"id":0,"name":"","case":"first-letter"},
            "4":{"id":4,"name":"Project","canonical":"Project","case":"first-letter"},
            "14":{"id":14,"name":"Category","canonical":"Category","case":"first-letter"}},
          "interwikimap":[{"prefix":"w","url":"https://en.wikipedia.org/wiki/${'$'}1"}]}}
        """
            .trimIndent()

    private fun TestScope.client(
        asked: MutableList<String> = mutableListOf(),
        user: String = userinfo,
        site: String = siteinfo,
        onWarning: ApiWarningListener = ApiWarningListener.LOG,
    ): WikiClient {
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString() + "&" + request.url.encodedQuery
            asked += body
            when {
                "userinfo" in body -> respondJson(user)
                "functionhooks" in body ->
                    respondJson(
                        """{"query":{"extensiontags":["<ref>"],"general":{"langconversion":false}}}"""
                    )
                "revisions" in body -> respondJson("""{"batchcomplete":true,"query":{"pages":[]}}""")
                "siteinfo" in body -> respondJson(site)
                else -> respondJson("""{"error":{"code":"unexpected","info":$body}}""")
            }
        }

        return WikiClient(
            WikiConfig(
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
                onWarning = onWarning,
            ),
            engine = engine,
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(
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
                    wiki.pages,
                    wiki.lists,
                    wiki.revisions,
                    wiki.users,
                    wiki.logs,
                    wiki.files,
                    wiki.extensions,
                    wiki.proofread,
                    wiki.renderer,
                    wiki.meta,
                    wiki.paramInfo,
                )
                .forEach { it shouldBe it }

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

            wiki.ref("volcano").title.shouldBeInstanceOf<Title.Local>().namespace shouldBe Namespace.MAIN
        }
    }

    @Test
    fun `an account with high limits names ten times as many pages in a request`() = runTest {
        val bot =
            """{"query":{"userinfo":{"id":7,"name":"Bot","groups":["bot"],"rights":["read","apihighlimits"]}}}"""

        for ((user, requests) in listOf(bot to 1, userinfo to 3)) {
            val asked = mutableListOf<String>()
            client(asked, user = user).use { client ->
                val wiki = client.wiki(ApiEndpoint("test.example.org"))
                wiki.pages.contents(List(120) { wiki.ref("Page $it") })
            }
            asked.count { "revisions" in it } shouldBe requests
        }
    }

    @Test
    fun `a wiki is asked once how it parses`() = runTest {
        val asked = mutableListOf<String>()

        client(asked).use { client ->
            val wiki = client.wiki(ApiEndpoint("test.example.org"))

            wiki.parseOptions().extensionTags shouldBe setOf("ref")
            wiki.parseOptions().languageConversion shouldBe false
            wiki.titleRules().key("foo") shouldBe "Foo"
        }

        asked.count { "functionhooks" in it } shouldBe 1
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
    fun `the warnings a wiki sends reach the listener the client was configured with`() = runTest {
        val heard = mutableListOf<ApiWarning>()
        val warned =
            siteinfo.replaceFirst(
                "{\"query\":{",
                """{"warnings":[{"code":"deprecation","module":"query","text":"Old."},
                   {"code":"deprecation-help","module":"main","text":"Subscribe."}],"query":{""",
            )

        client(site = warned, onWarning = { _, warning -> heard += warning }).use { client ->
            client.wiki(ApiEndpoint("test.example.org"))
        }

        heard.single().isDeprecation shouldBe true
    }

    @Test
    fun `a wiki older than the supported floor still opens`() = runTest {
        val old = siteinfo.replace("MediaWiki 1.47.0", "MediaWiki 1.35.0")

        client(site = old).use { client ->
            client.wiki(ApiEndpoint("test.example.org")).info.version shouldBe "1.35.0"
        }
    }

    @Test
    fun `code compiled against the 1_1 constructors still links`() {
        val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")

        // The constructors 1.1 callers were compiled against, found by their JVM signatures.
        val config =
            WikiConfig::class
                .java
                .getConstructor(
                    UserAgent::class.java,
                    Throttle::class.java,
                    RetryPolicy::class.java,
                    Int::class.javaObjectType,
                    ResponseCache::class.java,
                )
                .newInstance(userAgent, Throttle(), RetryPolicy.NONE, 5, ResponseCache.NONE)

        config.onWarning shouldBe ApiWarningListener.LOG

        KtorTransport::class
            .java
            .getConstructor(
                io.ktor.client.HttpClient::class.java,
                ApiEndpoint::class.java,
                UserAgent::class.java,
                Throttle::class.java,
                RetryPolicy::class.java,
                Int::class.javaObjectType,
                ResponseCache::class.java,
            )
            .newInstance(
                io.ktor.client.HttpClient(MockEngine { respondJson("{}") }),
                ApiEndpoint("test.example.org"),
                userAgent,
                Throttle(),
                RetryPolicy.NONE,
                5,
                ResponseCache.NONE,
            )
            .endpoint
            .server shouldBe "test.example.org"
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
