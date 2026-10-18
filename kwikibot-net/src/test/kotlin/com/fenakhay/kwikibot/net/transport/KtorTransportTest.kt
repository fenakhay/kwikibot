package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.cache.ResponseCache
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject

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

        transport.call(ApiRequest(mapOf("action" to "query", "token" to "SECRET+", "titles" to "volcano")))

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

        sent shouldBe "TestBot/1.0 (https://example.org/TestBot) kwikibot/${UserAgent.LIBRARY_VERSION}"
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
                        """"module":"main"}],"docref":"see the docs"}"""
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
    fun `a listener hears each retry, why, and each pause the wiki asked for`() = runTest {
        val retries = mutableListOf<String>()
        val pauses = mutableListOf<Duration>()
        val listener =
            object : TransportListener {
                override fun onRetry(request: ApiRequest, attempt: Int, wait: Duration, reason: String) {
                    retries += "$attempt $wait $reason"
                }

                override fun onPenalty(wait: Duration) {
                    pauses += wait
                }
            }
        var calls = 0
        val transport =
            transport(listener = listener) {
                calls++
                when (calls) {
                    1 ->
                        respondError(
                            HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.RetryAfter, "30"),
                        )
                    2 ->
                        respondJson(
                            """{"error":{"code":"readonly","info":"The wiki is in read-only mode."}}"""
                        )
                    else -> respondJson("""{"ok":true}""")
                }
            }

        transport.call(ApiRequest.of("query"))

        retries shouldBe listOf("1 30s rate limited", "2 2s the wiki reported readonly")
        pauses shouldBe listOf(30.seconds)
    }

    @Test
    fun `server errors are retried and then reported`() = runTest {
        var calls = 0
        val transport =
            transport(retry = RetryPolicy(maxRetries = 2, initialDelay = 1.seconds)) {
                calls++
                respondError(HttpStatusCode.ServiceUnavailable)
            }

        val error =
            assertFailsWith<WikiError.Transport.ServerError> {
                transport.call(ApiRequest.of("query"))
            }

        error.status shouldBe HttpStatusCode.ServiceUnavailable.value
        error.isTransient shouldBe true
        calls shouldBe 3
        currentTime shouldBe 3_000
    }

    @Test
    fun `a request that times out is retried and then reported as a timeout`() = runTest {
        val timeouts =
            listOf(
                HttpRequestTimeoutException(endpoint.apiUrl, 60_000, null),
                ConnectTimeoutException("connect timed out", null),
                SocketTimeoutException("read timed out"),
            )

        for (timeout in timeouts) {
            var calls = 0
            val transport =
                transport(retry = RetryPolicy(maxRetries = 1, initialDelay = 1.seconds)) {
                    calls++
                    throw timeout
                }

            val error =
                assertFailsWith<WikiError.Transport.Timeout> { transport.call(ApiRequest.of("query")) }

            error.url shouldBe endpoint.apiUrl
            error.cause?.message shouldBe timeout.message
            error.isTransient shouldBe true
            calls shouldBe 2
        }
    }

    @Test
    fun `a wiki that does not answer within the client's timeout is reported as a timeout`() {
        val slow = MockEngine {
            delay(30.seconds)
            respondJson("""{"ok":true}""")
        }

        WikiHttpClient.create(engine = slow, timeout = 100.milliseconds).use { client ->
            val transport =
                KtorTransport(client, endpoint, userAgent, Throttle.unpaced(), retry = RetryPolicy.NONE)

            assertFailsWith<WikiError.Transport.Timeout> {
                runBlocking { transport.call(ApiRequest.of("query")) }
            }
        }
    }

    @Test
    fun `a connection that fails is retried, then reported as unreachable with its cause`() = runTest {
        val reasons = mutableListOf<String>()
        val listener =
            object : TransportListener {
                override fun onRetry(request: ApiRequest, attempt: Int, wait: Duration, reason: String) {
                    reasons += reason
                }
            }
        var calls = 0
        val reset = IOException("connection reset")
        val transport =
            transport(retry = RetryPolicy(maxRetries = 2, initialDelay = 1.seconds), listener = listener) {
                calls++
                throw if (calls == 1) IOException() else reset
            }

        val error =
            assertFailsWith<WikiError.Transport.Unreachable> { transport.call(ApiRequest.of("query")) }

        error.url shouldBe endpoint.apiUrl
        error.cause?.message shouldBe "connection reset"
        calls shouldBe 3
        reasons shouldBe
            listOf(
                "could not reach ${endpoint.apiUrl} (IOException)",
                "could not reach ${endpoint.apiUrl} (connection reset)",
            )
    }

    @Test
    fun `an html error page from a proxy is treated as a server error`() = runTest {
        var calls = 0
        val transport =
            transport(retry = RetryPolicy.NONE) {
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
    fun `a refusal is raised at once, with what the server said`() = runTest {
        var calls = 0
        val transport = transport { _ ->
            calls++
            respond(
                "<html><body><p>Please set a user-agent and respect our robot policy\n" +
                    "https://w.wiki/4wJS.</p></body></html>",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }

        val error = assertFailsWith<WikiError.Transport.Refused> { transport.call(ApiRequest.of("query")) }

        calls shouldBe 1
        currentTime shouldBe 0
        error.status shouldBe 403
        error.isTransient shouldBe false
        error.detail shouldBe "Please set a user-agent and respect our robot policy https://w.wiki/4wJS."
        error.message shouldContain "HTTP 403 from https://en.wiktionary.org/w/api.php"
    }

    @Test
    fun `a 4xx that means not now is retried like a server error`() = runTest {
        var calls = 0
        val transport =
            transport(retry = RetryPolicy(maxRetries = 1, initialDelay = 1.seconds)) {
                calls++
                respondError(HttpStatusCode.RequestTimeout)
            }

        assertFailsWith<WikiError.Transport.ServerError> { transport.call(ApiRequest.of("query")) }

        calls shouldBe 2
    }

    @Test
    fun `a 4xx that carries an API answer is handed back like any other`() = runTest {
        val transport = transport {
            respond(
                """{"errors":[{"code":"badvalue","text":"Unrecognized value"}]}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val body = transport.call(ApiRequest.of("query"))

        body.containsKey("errors") shouldBe true
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
        val transport =
            transport(throttle = throttle) {
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

    @Test
    fun `the listener sees each answer the wiki sent, once, and not a cached copy of it`() = runTest {
        val heard = mutableListOf<String>()
        val remembered = mutableMapOf<ApiRequest, JsonObject>()
        val cache =
            object : ResponseCache {
                override suspend fun get(request: ApiRequest): JsonObject? = remembered[request]

                override suspend fun put(request: ApiRequest, response: JsonObject) {
                    remembered[request] = response
                }
            }
        var calls = 0
        val transport =
            transport(
                cache = cache,
                listener = responses { request, response -> heard += "${request.action}:${response["n"]}" },
            ) {
                calls++
                respondJson("""{"n":$calls}""")
            }

        transport.call(ApiRequest.of("query", "titles" to "volcano"))
        transport.call(ApiRequest.of("query", "titles" to "volcano"))
        transport.call(ApiRequest.of("query", "titles" to "lava"))

        heard shouldBe listOf("query:1", "query:2")
    }

    @Test
    fun `a listener that throws fails the call, which is how a test makes a warning fatal`() = runTest {
        val transport =
            transport(listener = responses { _, _ -> throw AssertionError("deprecated") }) {
                respondJson("""{"ok":true}""")
            }

        assertFailsWith<AssertionError> { transport.call(ApiRequest.of("query")) }
    }

    @Test
    fun `a read that must be current goes to the wiki whatever the cache holds, and is not stored`() =
        runTest {
            val remembered = mutableMapOf<ApiRequest, JsonObject>()
            val cache =
                object : ResponseCache {
                    override suspend fun get(request: ApiRequest): JsonObject? = remembered[request]

                    override suspend fun put(request: ApiRequest, response: JsonObject) {
                        remembered[request] = response
                    }
                }
            var calls = 0
            val transport =
                transport(cache = cache) {
                    calls++
                    respondJson("""{"n":$calls}""")
                }
            val stopPage = ApiRequest.of("query", "titles" to "User:Bot/Stop")

            transport.call(stopPage)
            transport.call(stopPage.copy(cacheable = false))["n"].toString() shouldBe "2"
            transport.call(stopPage.copy(cacheable = false))["n"].toString() shouldBe "3"

            calls shouldBe 3
            remembered.keys.single().cacheable shouldBe true
            ResponseCache.isCacheable(stopPage.copy(cacheable = false)) shouldBe false
        }

    @Test
    fun `a database connection error is waited out and the call retried`() = runTest {
        var calls = 0
        val transport = transport {
            calls++
            if (calls == 1) {
                respondJson(
                    """{"errors":[{"code":"internal_api_error_DBConnectionError",
                       "text":"[abc] Database error","module":"main"}]}"""
                )
            } else {
                respondJson("""{"ok":true}""")
            }
        }

        val body = transport.call(ApiRequest.of("query"))

        body.containsKey("ok") shouldBe true
        calls shouldBe 2
    }

    @Test
    fun `a wiki still read-only once the retries are spent is reported as the wiki said it`() = runTest {
        var calls = 0
        val transport =
            transport(retry = RetryPolicy(maxRetries = 2, initialDelay = 1.seconds)) {
                calls++
                respondJson(
                    """{"errors":[{"code":"readonly","text":"The wiki is read-only.","module":"main"}]}"""
                )
            }

        val body = transport.call(ApiRequest.of("edit", "title" to "Foo", kind = RequestKind.WRITE))

        body.toString() shouldContain "readonly"
        calls shouldBe 3
    }

    @Test
    fun `every database error that belongs to the moment is waited out, doubling the wait each time`() =
        runTest {
            val codes =
                listOf(
                    "readonly",
                    "internal_api_error_DBConnectionError",
                    "internal_api_error_DBQueryTimeoutError",
                    "internal_api_error_DBReadOnlyError",
                    "internal_api_error_DBTransactionStateError",
                )

            for (code in codes) {
                val waits = mutableListOf<Duration>()
                val listener =
                    object : TransportListener {
                        override fun onRetry(
                            request: ApiRequest,
                            attempt: Int,
                            wait: Duration,
                            reason: String,
                        ) {
                            waits += wait
                        }
                    }
                var calls = 0
                val transport =
                    transport(listener = listener) {
                        calls++
                        if (calls <= 3) {
                            respondJson("""{"errors":[{"code":"$code","text":"x","module":"main"}]}""")
                        } else {
                            respondJson("""{"ok":true}""")
                        }
                    }
                val started = currentTime

                val body = transport.call(ApiRequest.of("query"))

                body.containsKey("ok") shouldBe true
                calls shouldBe 4
                waits shouldBe listOf(1.seconds, 2.seconds, 4.seconds)
                currentTime - started shouldBe 7_000
            }
        }

    @Test
    fun `a rate limit that outlasts the retries is raised with the wait the server asked for`() = runTest {
        var calls = 0
        val transport =
            transport(retry = RetryPolicy(maxRetries = 1, initialDelay = 1.seconds)) {
                calls++
                respondError(
                    HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, " 30 "),
                )
            }

        val error =
            assertFailsWith<WikiError.Transport.RateLimited> { transport.call(ApiRequest.of("query")) }

        error.retryAfter shouldBe 30.seconds
        calls shouldBe 2
        currentTime shouldBe 30_000
    }

    @Test
    fun `a rate limit the wiki reports in its body is waited out like a 429`() = runTest {
        var calls = 0
        val transport = transport {
            calls++
            if (calls == 1) {
                respondJson("""{"errors":[{"code":"ratelimited","text":"slow down","module":"main"}]}""")
            } else {
                respondJson("""{"ok":true}""")
            }
        }

        transport.call(ApiRequest.of("query")).containsKey("ok") shouldBe true

        calls shouldBe 2
        currentTime shouldBe 1_000
    }

    @Test
    fun `lag that outlasts the retries is raised with how far behind the replica was`() = runTest {
        val reasons = mutableListOf<String>()
        val listener =
            object : TransportListener {
                override fun onRetry(request: ApiRequest, attempt: Int, wait: Duration, reason: String) {
                    reasons += reason
                }
            }
        var calls = 0
        val transport =
            transport(retry = RetryPolicy(maxRetries = 1, initialDelay = 1.seconds), listener = listener) {
                calls++
                if (calls == 1) {
                    respondJson("""{"error":{"code":"maxlag","info":"lagged"}}""")
                } else {
                    respondJson(
                        """{"error":{"code":"maxlag","info":"Waiting for db1234: 7.5 seconds lagged"}}"""
                    )
                }
            }

        val error = assertFailsWith<WikiError.Transport.Maxlag> { transport.call(ApiRequest.of("query")) }

        error.lag shouldBe 7.5.seconds
        error.host shouldBe "db1234"
        reasons shouldBe listOf("replication lag of 0s")
    }

    @Test
    fun `a transport with no maxlag leaves the parameter out`() = runTest {
        var query = ""
        val transport =
            KtorTransport(
                client =
                    HttpClient(
                        MockEngine { request ->
                            query = request.url.encodedQuery
                            respondJson("""{"ok":true}""")
                        }
                    ),
                endpoint = endpoint,
                userAgent = userAgent,
                throttle = Throttle.unpaced(),
                maxlag = null,
            )

        transport.call(ApiRequest.of("query"))

        query.contains("maxlag") shouldBe false
        query.contains("format=json") shouldBe true
    }

    @Test
    fun `a query error is not retried, since it is as likely to be the query as the moment`() = runTest {
        var calls = 0
        val transport = transport {
            calls++
            respondJson(
                """{"errors":[{"code":"internal_api_error_DBQueryError","text":"x","module":"main"}]}"""
            )
        }

        transport.call(ApiRequest.of("query"))

        calls shouldBe 1
    }

    @Test
    fun `a lagged answer is retried rather than shown to the listener`() = runTest {
        var heard = 0
        var calls = 0
        val transport =
            transport(listener = responses { _, _ -> heard++ }) {
                calls++
                if (calls == 1) {
                    respondJson("""{"error":{"code":"maxlag","info":"Waiting for db1: 6 seconds lagged."}}""")
                } else {
                    respondJson("""{"ok":true}""")
                }
            }

        transport.call(ApiRequest.of("query"))

        heard shouldBe 1
    }

    private fun TestScope.transport(
        throttle: Throttle =
            Throttle(
                read = Duration.ZERO,
                write = Duration.ZERO,
                timeSource = testScheduler.timeSource,
            ),
        retry: RetryPolicy = RetryPolicy(maxRetries = 3, initialDelay = 1.seconds),
        cache: ResponseCache = ResponseCache.NONE,
        listener: TransportListener = TransportListener.NONE,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorTransport =
        KtorTransport(
            client = HttpClient(MockEngine(handler)),
            endpoint = endpoint,
            userAgent = userAgent,
            throttle = throttle,
            retry = retry,
            cache = cache,
            listener = listener,
        )

    /** A listener that only hears responses, which is what most of these tests want. */
    private fun responses(heard: (ApiRequest, JsonObject) -> Unit): TransportListener =
        object : TransportListener {
            override fun onResponse(request: ApiRequest, response: JsonObject) = heard(request, response)
        }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
