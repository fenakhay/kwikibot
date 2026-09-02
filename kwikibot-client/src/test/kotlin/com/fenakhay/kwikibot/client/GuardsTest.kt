package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import io.kotest.matchers.shouldBe
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
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The checks a run makes before it starts.
 *
 * These exist so a bot missing a right fails immediately rather than after reading ten thousand pages, so the
 * half that throws is the half that matters. Each `require` is paired with the `has` it is built on, because
 * a guard that answered the opposite way would stop a run that should proceed and let through one that should
 * not.
 */
class GuardsTest {

    @Test
    fun `a right the session holds passes, and one it lacks stops the run`() = runTest {
        val wiki = wiki(rights = listOf("read", "edit"))

        wiki.hasRight("edit") shouldBe true
        wiki.requireRight("edit")

        wiki.hasRight("rollback") shouldBe false
        val refused = assertFailsWith<WikiError.Auth.MissingRight> { wiki.requireRight("rollback") }
        refused.message.orEmpty().contains("rollback") shouldBe true
    }

    @Test
    fun `an installed extension passes, and a missing one stops the run`() = runTest {
        val wiki = wiki(extensions = listOf("ProofreadPage"))

        wiki.hasExtension("ProofreadPage") shouldBe true
        wiki.requireExtension("ProofreadPage")

        wiki.hasExtension("WikibaseClient") shouldBe false
        assertFailsWith<WikiError.Configuration.MissingExtension> {
            wiki.requireExtension("WikibaseClient")
        }
    }

    @Test
    fun `an extension is matched without regard to case, as MediaWiki names them loosely`() = runTest {
        wiki(extensions = listOf("ProofreadPage")).hasExtension("proofreadpage") shouldBe true
    }

    @Test
    fun `a new enough wiki passes and an older one stops the run`() = runTest {
        val wiki = wiki(generator = "MediaWiki 1.47.0")

        wiki.hasVersion(MediaWikiVersion.parse("1.43.0")) shouldBe true
        wiki.requireVersion(MediaWikiVersion.parse("1.43.0"))

        wiki.hasVersion(MediaWikiVersion.parse("1.50.0")) shouldBe false
        assertFailsWith<WikiError.Configuration.VersionTooOld> {
            wiki.requireVersion(MediaWikiVersion.parse("1.50.0"))
        }
    }

    @Test
    fun `the running version is enough, so an exact match is not too old`() = runTest {
        val wiki = wiki(generator = "MediaWiki 1.47.0")

        wiki.hasVersion(MediaWikiVersion.parse("1.47.0")) shouldBe true
        wiki.requireVersion(MediaWikiVersion.parse("1.47.0"))
    }

    @Test
    fun `a wmf build loses to the release it leads to`() = runTest {
        // Wikimedia runs 1.47.0-wmf.17 before 1.47.0 exists, so a bot requiring 1.47.0 must not
        // treat the wmf build as good enough.
        val wiki = wiki(generator = "MediaWiki 1.47.0-wmf.17")

        wiki.hasVersion(MediaWikiVersion.parse("1.47.0")) shouldBe false
        wiki.hasVersion(MediaWikiVersion.parse("1.46.0")) shouldBe true
    }

    private suspend fun TestScope.wiki(
        rights: List<String> = listOf("read"),
        extensions: List<String> = emptyList(),
        generator: String = "MediaWiki 1.47.0",
    ): Wiki {
        val userinfo =
            """
            {"query":{"userinfo":{"id":1,"name":"TestBot","groups":["bot"],
             "rights":[${rights.joinToString(",") { "\"$it\"" }}]}}}
        """
                .trimIndent()

        val siteinfo =
            """
            {"query":{
              "general":{"wikiid":"testwiki","sitename":"Test Wiki","lang":"en",
                         "server":"//test.example.org","articlepath":"/wiki/${'$'}1",
                         "mainpage":"Main Page","generator":"$generator"},
              "namespaces":{"0":{"id":0,"name":"","case":"first-letter"}},
              "extensions":[${extensions.joinToString(",") { """{"name":"$it"}""" }}]}}
        """
                .trimIndent()

        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString() + "&" + request.url.encodedQuery
            when {
                "userinfo" in body -> respondJson(userinfo)
                "siteinfo" in body -> respondJson(siteinfo)
                else -> respondJson("{}")
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
            .wiki(ApiEndpoint("test.example.org"))
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(
            ByteReadChannel(body),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
}
