package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class ProofreadServiceTest {

    private fun siteinfo(extensions: String) = """
        {"query":{
          "general":{"wikiid":"testwiki","sitename":"Test Wiki","lang":"en",
                     "server":"//test.example.org","articlepath":"/wiki/${'$'}1",
                     "mainpage":"Main Page","generator":"MediaWiki 1.47.0"},
          "namespaces":{
            "0":{"id":0,"name":"","case":"first-letter"},
            "104":{"id":104,"name":"Page","canonical":"Page","case":"first-letter"},
            "106":{"id":106,"name":"Index","canonical":"Index","case":"first-letter"}},
          "extensions":[$extensions]}}
    """.trimIndent()

    private val userinfo =
        """{"query":{"userinfo":{"id":0,"name":"192.0.2.1","groups":["*"],"rights":["read"]}}}"""

    private fun MockRequestHandleScope.json(body: String): HttpResponseData = respond(
        ByteReadChannel(body),
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private suspend fun TestScope.wiki(
        extensions: String = """{"name":"ProofreadPage"}""",
        answer: (String) -> String = { "{}" },
    ): Wiki {
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString() + "&" + request.url.encodedQuery
            when {
                "userinfo" in body -> json(userinfo)
                "siteinfo" in body -> json(siteinfo(extensions))
                else -> json(answer(body))
            }
        }

        return WikiClient(
            WikiConfig(
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            ),
            engine = engine,
        ).wiki(ApiEndpoint("test.example.org"))
    }

    @Test
    fun `a wiki without the extension is told so, not given an empty answer`() = runTest {
        val wiki = wiki(extensions = """{"name":"CiteThisPage"}""")

        assertFailsWith<WikiError.Configuration.MissingExtension> {
            wiki.proofread.indexOf(wiki.ref("Page:Some book.djvu/12"))
        }
        assertFailsWith<WikiError.Configuration.MissingExtension> {
            wiki.proofread.pagesOf(wiki.ref("Index:Some book.djvu"))
        }
        assertFailsWith<WikiError.Configuration.MissingExtension> {
            wiki.proofread.quality(listOf(wiki.ref("Page:Some book.djvu/12")))
        }
    }

    @Test
    fun `quality comes back per page, and pages without a level are left out`() = runTest {
        val wiki = wiki {
            """{"query":{"pages":[
               {"ns":104,"title":"Page:Book.djvu/1","proofread":{"quality":4}},
               {"ns":104,"title":"Page:Book.djvu/2","proofread":{"quality":1}},
               {"ns":104,"title":"Page:Book.djvu/3"}]}}"""
        }

        val refs = listOf(
            wiki.ref("Page:Book.djvu/1"),
            wiki.ref("Page:Book.djvu/2"),
            wiki.ref("Page:Book.djvu/3"),
        )
        val quality = wiki.proofread.quality(refs)

        quality.keys.map { it.title.text } shouldContainExactly listOf("Book.djvu/1", "Book.djvu/2")
    }

    @Test
    fun `asking about nothing asks the wiki nothing`() = runTest {
        val wiki = wiki { error("the wiki should not have been asked") }
        wiki.proofread.quality(emptyList()) shouldBe emptyMap()
    }

    @Test
    fun `a page reports the index it belongs to`() = runTest {
        val wiki = wiki {
            """{"query":{"pages":[
               {"ns":104,"title":"Page:Book.djvu/1","proofread":{"index":"Index:Book.djvu"}}]}}"""
        }

        wiki.proofread.indexOf(wiki.ref("Page:Book.djvu/1"))?.title?.text shouldBe "Book.djvu"
    }

    @Test
    fun `a page belonging to no index reports none rather than failing`() = runTest {
        val wiki = wiki {
            """{"query":{"pages":[{"ns":104,"title":"Page:Loose.djvu/1"}]}}"""
        }

        wiki.proofread.indexOf(wiki.ref("Page:Loose.djvu/1")).shouldBeNull()
    }

    @Test
    fun `an index lists the pages transcluding it`() = runTest {
        val wiki = wiki {
            """{"query":{"embeddedin":[
               {"ns":104,"title":"Page:Book.djvu/1"},
               {"ns":104,"title":"Page:Book.djvu/2"}]}}"""
        }

        wiki.proofread.pagesOf(wiki.ref("Index:Book.djvu")).map { it.title.text } shouldContainExactly
            listOf("Book.djvu/1", "Book.djvu/2")
    }
}
