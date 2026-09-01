package com.fenakhay.kwikibot.net

import com.fenakhay.kwikibot.model.WikiError
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KtorTransportTest {

    private val endpoint = ApiEndpoint("en.wiktionary.org")
    private val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")

    @Test
    fun `a successful call returns the decoded body`() = runTest {
        val transport = transport { respondJson("""{"batchcomplete":true,"query":{"pages":[]}}""") }

        val body = transport.call(ApiRequest.of("query", "titles" to "volcano"))

        body.containsKey("query") shouldBe true
    }

    @Test
    fun `reads go out as GET and writes as POST`() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val transport = transport { request ->
            methods += request.method
            respondJson("""{"ok":true}""")
        }

        transport.call(ApiRequest.of("query", "titles" to "volcano"))
        transport.call(ApiRequest.of("edit", "title" to "Foo", kind = RequestKind.WRITE))

        methods shouldBe listOf(HttpMethod.Get, HttpMethod.Post)
    }

    @Test
    fun `an over-long read is POSTed rather than left for a proxy to truncate`() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val transport = transport { request ->
            methods += request.method
            respondJson("""{"ok":true}""")
        }

        transport.call(ApiRequest.of("query", "titles" to "a".repeat(3000)))

        methods shouldBe listOf(HttpMethod.Post)
    }

    @Test
    fun `token parameters are sent last, in the body rather than the URL`() = runTest {
        var body = ""
        var url = ""
        val transport = transport { request ->
            body = request.body.toByteArray().decodeToString()
            url = request.url.toString()
            respondJson("""{"ok":true}""")
        }

        transport.call(
            ApiRequest(mapOf("action" to "query", "token" to "SECRET+", "titles" to "volcano")),
        )

        body.endsWith("token=SECRET%2B") shouldBe true
        url.contains("SECRET") shouldBe false
    }

    @Test
    fun `the user agent carries contact details on every request`() = runTest {
        var sent: String? = null
        val transport = transport { request ->
            sent = request.headers[HttpHeaders.UserAgent]
            respondJson("""{"ok":true}""")
        }

        transport.call(ApiRequest.of("query"))

        sent shouldBe "TestBot/1.0 (https://example.org/TestBot) kwikibot/1.0.0"
    }

    @Test
    fun `format parameters are added and callers cannot override them`() = runTest {
        var query = ""
        val transport = transport { request ->
            query = request.url.encodedQuery
            respondJson("""{"ok":true}""")
        }

        transport.call(ApiRequest(mapOf("action" to "query", "format" to "xml")))

        query.contains("format=json") shouldBe true
        query.contains("format=xml") shouldBe false
        query.contains("formatversion=2") shouldBe true
        query.contains("maxlag=5") shouldBe true
    }

    @Test
    fun `replication lag is waited out and the call retried`() = runTest {
        var calls = 0
        val transport = transport {
            calls++
            if (calls == 1) {
                respondJson("""{"error":{"code":"maxlag","info":"Waiting for db1234: 7.5 seconds lagged"}}""")
            } else {
                respondJson("""{"ok":true}""")
            }
        }

        val body = transport.call(ApiRequest.of("query"))

        calls shouldBe 2
        body.containsKey("ok") shouldBe true
        currentTime shouldBe 1_000
    }

    @Test
    fun `lag is recognised in the errors array that errorformat=plaintext returns`() = runTest {
        var calls = 0
        val transport = transport {
            calls++
            if (calls == 1) {
                respondJson(
                    """{"errors":[{"code":"maxlag","text":"Waiting for db1234: 7.5 seconds lagged",""" +
                        """"module":"main"}],"docref":"see the docs"}""",
                )
            } else {
                respondJson("""{"ok":true}""")
            }
        }

        transport.call(ApiRequest.of("query"))

        calls shouldBe 2
        currentTime shouldBe 1_000
    }

    @Test
    fun `a Retry-After header wins over the backoff curve`() = runTest {
        var calls = 0
        val transport = transport {
            calls++
            if (calls == 1) {
                respondError(
                    HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, "30"),
                )
            } else {
                respondJson("""{"ok":true}""")
            }
        }

        transport.call(ApiRequest.of("query"))

        calls shouldBe 2
        currentTime shouldBe 30_000
    }

    @Test
    fun `server errors are retried and then reported`() = runTest {
        var calls = 0
        val transport = transport(retry = RetryPolicy(maxRetries = 2, initialDelay = 1.seconds)) {
            calls++
            respondError(HttpStatusCode.ServiceUnavailable)
        }

        val error = assertFailsWith<WikiError.Transport.ServerError> {
            transport.call(ApiRequest.of("query"))
        }

        error.status shouldBe HttpStatusCode.ServiceUnavailable.value
        error.isTransient shouldBe true
        calls shouldBe 3
        currentTime shouldBe 3_000
    }

    @Test
    fun `an html error page from a proxy is treated as a server error`() = runTest {
        var calls = 0
        val transport = transport(retry = RetryPolicy.NONE) {
            calls++
            respond(
                "<html><body>502 Bad Gateway</body></html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }

        assertFailsWith<WikiError.Transport.ServerError> { transport.call(ApiRequest.of("query")) }
        calls shouldBe 1
    }

    @Test
    fun `an API error block is handed back rather than thrown`() = runTest {
        val transport = transport { respondJson("""{"error":{"code":"missingtitle","info":"nope"}}""") }

        val body = transport.call(ApiRequest.of("query"))

        body.containsKey("error") shouldBe true
    }

    @Test
    fun `pacing applies to the retry stream as well as the first attempt`() = runTest {
        var calls = 0
        val throttle = Throttle(read = 2.seconds, timeSource = testScheduler.timeSource)
        val transport = transport(throttle = throttle) {
            calls++
            if (calls == 1) {
                respondError(HttpStatusCode.ServiceUnavailable)
            } else {
                respondJson("""{"ok":true}""")
            }
        }

        transport.call(ApiRequest.of("query"))

        currentTime shouldBe 2_000
        calls shouldBe 2
    }

    private fun TestScope.transport(
        throttle: Throttle = Throttle(
            read = Duration.ZERO,
            write = Duration.ZERO,
            timeSource = testScheduler.timeSource,
        ),
        retry: RetryPolicy = RetryPolicy(maxRetries = 3, initialDelay = 1.seconds),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorTransport = KtorTransport(
        client = HttpClient(MockEngine(handler)),
        endpoint = endpoint,
        userAgent = userAgent,
        throttle = throttle,
        retry = retry,
    )

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
