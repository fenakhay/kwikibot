package com.fenakhay.kwikibot.client.internal.wire

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.protocol.ParamInfo
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
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Choosing a spelling by what the wiki says it takes.
 *
 * Four kinds of wiki: one current enough for the new spelling, one too old for it, one that has deprecated
 * everything the library knows except the old spelling, and one that will not say.
 */
class CapabilitiesTest {

    private val toc = Registry.TABLE_OF_CONTENTS

    @Test
    fun `a current wiki gets the new spelling`() = runTest {
        val capabilities = capabilities {
            respondJson(parse("tocdata", "sections", deprecated = listOf("sections")))
        }

        capabilities.resolve(toc) shouldBe listOf("tocdata")
    }

    @Test
    fun `a wiki older than the new spelling gets the old one, which it does not deprecate`() = runTest {
        val capabilities = capabilities { respondJson(parse("sections", "text")) }

        capabilities.resolve(toc) shouldBe listOf("sections")
    }

    @Test
    fun `a wiki that deprecates every spelling it lists still gets one that works`() = runTest {
        val capabilities = capabilities { respondJson(parse("sections", deprecated = listOf("sections"))) }

        capabilities.resolve(toc) shouldBe listOf("sections")
    }

    @Test
    fun `a spelling of several values is chosen only if the wiki takes all of them`() = runTest {
        val capabilities = capabilities {
            respondJson(
                module(
                    "abusefilters",
                    "query+abusefilters",
                    "abf",
                    "prop",
                    listOf("id", "status", "private", "protected"),
                )
            )
        }

        capabilities.resolve(Registry.ABUSE_FILTER_FLAGS) shouldBe listOf("status", "private", "protected")
    }

    @Test
    fun `a wiki from before protected filters gets the flags it has`() = runTest {
        val capabilities = capabilities {
            respondJson(
                module("abusefilters", "query+abusefilters", "abf", "prop", listOf("id", "status", "private"))
            )
        }

        capabilities.resolve(Registry.ABUSE_FILTER_FLAGS) shouldBe listOf("status", "private")
    }

    @Test
    fun `a wiki that lists its values and takes none of the spellings is refused by name`() = runTest {
        val capabilities = capabilities { respondJson(parse("text", "links")) }

        val error = assertFailsWith<WikiError.Configuration.Unsupported> { capabilities.resolve(toc) }

        error.parameter shouldBe "parse prop"
        error.alternatives shouldBe listOf("tocdata", "sections")
    }

    @Test
    fun `a wiki that cannot be asked is assumed to be current`() = runTest {
        val capabilities = capabilities {
            respond("", HttpStatusCode.ServiceUnavailable, headersOf())
        }

        capabilities.resolve(toc) shouldBe listOf("tocdata")
    }

    @Test
    fun `a wiki that does not describe the module is assumed to be current`() = runTest {
        val capabilities = capabilities { respondJson("""{"paraminfo":{"modules":[]}}""") }

        capabilities.resolve(toc) shouldBe listOf("tocdata")
    }

    @Test
    fun `a parameter taking free text gives nothing to choose by, so the preferred spelling goes`() =
        runTest {
            val capabilities = capabilities {
                respondJson(
                    """{"paraminfo":{"modules":[{"name":"parse","path":"parse","prefix":"",
                   "parameters":[{"name":"prop","type":"string"}]}]}}"""
                )
            }

            capabilities.resolve(toc) shouldBe listOf("tocdata")
        }

    @Test
    fun `every registered module is described in one request, and each answer is kept`() = runTest {
        val asked = mutableListOf<String>()
        val capabilities = capabilities { request ->
            asked += request.url.parameters["modules"].orEmpty()
            respondJson(parse("tocdata", "sections"))
        }

        capabilities.resolve(toc)
        capabilities.resolve(toc)
        capabilities.resolve(Registry.ABUSE_FILTER_FLAGS)

        asked shouldBe listOf(Registry.CHOICES.map { it.parameter.module }.distinct().joinToString("|"))
    }

    private fun parse(vararg values: String, deprecated: List<String> = emptyList()): String =
        module("parse", "parse", "", "prop", values.toList(), deprecated)

    private fun module(
        name: String,
        path: String,
        prefix: String,
        parameter: String,
        values: List<String>,
        deprecated: List<String> = emptyList(),
    ): String {
        val quoted = values.joinToString(",") { "\"$it\"" }
        val deprecatedValues = deprecated.joinToString(",") { "\"$it\"" }
        return """{"paraminfo":{"modules":[{"name":"$name","path":"$path","prefix":"$prefix",
            "source":"MediaWiki","parameters":[{"name":"$parameter","type":[$quoted],"multi":true,
            "limit":50,"lowlimit":50,"highlimit":500,"deprecatedvalues":[$deprecatedValues]}]}]}}"""
    }

    private fun TestScope.capabilities(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): Capabilities {
        val transport =
            KtorTransport(
                client = HttpClient(MockEngine(handler)),
                endpoint = ApiEndpoint("en.wiktionary.org"),
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            )
        return Capabilities(ParamInfo(transport), "en.wiktionary.org")
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
