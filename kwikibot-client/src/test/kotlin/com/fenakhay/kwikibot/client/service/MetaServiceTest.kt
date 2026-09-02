package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiMetaService
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.TextDirection
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import kotlin.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class MetaServiceTest {

    @Test
    fun `a siteinfo block is fetched once and kept`() = runTest {
        var requests = 0
        val meta = meta {
            requests++
            respondJson("""{"query":{"statistics":{"pages":100,"articles":50,"edits":900}}}""")
        }

        meta.statistics()["pages"] shouldBe 100L
        meta.statistics()["articles"] shouldBe 50L

        requests shouldBe 1
    }

    @Test
    fun `concurrent callers produce one request`() = runTest {
        var requests = 0
        val meta = meta {
            requests++
            respondJson("""{"query":{"statistics":{"pages":1}}}""")
        }

        List(5) { async { meta.statistics() } }.awaitAll()

        requests shouldBe 1
    }

    @Test
    fun `magic words come with every alias the wiki accepts`() = runTest {
        val meta = meta {
            respondJson(
                """{"query":{"magicwords":[
                   {"name":"redirect","aliases":["#REDIRECT","#REDIRECCIÓN"],
                    "case-sensitive":false}]}}"""
            )
        }

        meta.magicWords()["redirect"] shouldBe listOf("#REDIRECT", "#REDIRECCIÓN")
    }

    @Test
    fun `interface messages are fetched and then remembered`() = runTest {
        var requests = 0
        val meta = meta {
            requests++
            respondJson(
                """{"query":{"allmessages":[
                   {"name":"disambiguationspage","content":"Template:Disambiguation"},
                   {"name":"nosuchmessage","missing":true}]}}"""
            )
        }

        meta.message("disambiguationspage") shouldBe "Template:Disambiguation"
        meta.message("disambiguationspage") shouldBe "Template:Disambiguation"

        requests shouldBe 1
    }

    @Test
    fun `a message the wiki does not have is absent, not empty`() = runTest {
        val meta = meta {
            respondJson("""{"query":{"allmessages":[{"name":"nope","missing":true}]}}""")
        }

        meta.message("nope").shouldBeNull()
    }

    @Test
    fun `a property this wiki does not report is null`() = runTest {
        val meta = meta { respondJson("""{"query":{}}""") }

        meta.property("restrictions").shouldBeNull()
    }

    @Test
    fun `tagging needs something to tag`() = runTest {
        val meta = meta { respondJson("{}") }

        shouldThrow<IllegalArgumentException> { meta.applyTags(add = setOf("bot trial")) }
    }

    @Test
    fun `defining a tag is not the same call as applying one`() = runTest {
        var body = ""
        val meta = meta { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"managetags":{"status":"success"}}""")
            }
        }

        meta.manageTag("bot trial", TagOperation.CREATE, reason = "new run")

        body shouldContain "action=managetags"
        body shouldContain "operation=create"
    }

    @Test
    fun `languages come back keyed by code, with fallbacks in order`() = runTest {
        var asked = ""
        val meta = meta { request ->
            asked = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            respondJson(
                """{"query":{"languageinfo":{
                   "fr":{"code":"fr","name":"French","autonym":"français","dir":"ltr",
                         "fallbacks":["en"],"bcp47":"fr"},
                   "arz":{"code":"arz","name":"Egyptian Arabic","autonym":"مصرى","dir":"rtl",
                          "fallbacks":["ar","en"],"bcp47":"arz"}}}}"""
            )
        }

        val languages = meta.languages(listOf(LangCode("fr"), LangCode("arz")))

        languages.getValue(LangCode("fr")).autonym shouldBe "français"
        languages.getValue(LangCode("fr")).direction shouldBe TextDirection.LEFT_TO_RIGHT

        val egyptian = languages.getValue(LangCode("arz"))
        egyptian.direction shouldBe TextDirection.RIGHT_TO_LEFT
        egyptian.fallbacks.map { it.code } shouldBe listOf("ar", "en")
        egyptian.bcp47 shouldBe "arz"

        asked.contains("meta=languageinfo") shouldBe true
    }

    @Test
    fun `asking about no language in particular asks about all of them`() = runTest {
        var asked = ""
        val meta = meta { request ->
            asked = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            respondJson("""{"query":{"languageinfo":{}}}""")
        }

        meta.languages() shouldBe emptyMap()

        asked.contains("licode=") shouldBe false
    }

    @Test
    fun `a language the wiki describes sparsely still decodes`() = runTest {
        val meta = meta {
            respondJson("""{"query":{"languageinfo":{"zz":{"code":"zz"}}}}""")
        }

        val only = meta.languages(listOf(LangCode("zz"))).getValue(LangCode("zz"))

        only.name shouldBe ""
        only.fallbacks shouldBe emptyList()
        only.bcp47 shouldBe null
    }

    @Test
    fun `a response carrying no languageinfo block yields nothing rather than failing`() = runTest {
        val meta = meta { respondJson("""{"query":{}}""") }
        meta.languages(listOf(LangCode("fr"))) shouldBe emptyMap()
    }

    private fun TestScope.meta(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): MetaService =
        KtorTransport(
                client = HttpClient(MockEngine(handler)),
                endpoint = ApiEndpoint("en.wiktionary.org"),
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            )
            .let { ApiMetaService(it, TokenStore(it)) }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
