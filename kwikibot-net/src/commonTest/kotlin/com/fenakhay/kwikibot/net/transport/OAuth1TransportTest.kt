package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.auth.OAuth1Signer
import com.fenakhay.kwikibot.net.cache.ResponseCache
import com.fenakhay.kwikibot.net.cache.scopedTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

/** An OAuth 1.0a login as the wiki sees it: a signature on every request, made afresh for each one sent. */
class OAuth1TransportTest {

    private val credentials =
        Credentials.OAuth1("ck-visible", "cs-hush-hush", "at-visible", "as-hush-hush", "FenaBot")

    private val secrets = listOf("cs-hush-hush", "as-hush-hush")

    private val endpoint = ApiEndpoint("en.wiktionary.org")
    private val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")

    /** One request the wiki was sent, with the parameters of its `Authorization: OAuth` header. */
    private class Sent(val request: HttpRequestData) {
        val header: String? = request.headers[HttpHeaders.Authorization]

        val oauth: Map<String, String> =
            header
                ?.removePrefix("OAuth ")
                ?.split(", ")
                ?.associate { pair ->
                    val (name, quoted) = pair.split("=", limit = 2)
                    name to quoted.removeSurrounding("\"").decodeURLQueryComponent()
                }
                .orEmpty()

        val form: Parameters? = (request.body as? FormDataContent)?.formData
    }

    /** Whether [sent] carries the signature [credentials] make for it, its body read as [form]. */
    private fun signs(sent: Sent, form: Parameters?): Boolean {
        val signer =
            OAuth1Signer(
                credentials,
                { sent.oauth.getValue("oauth_nonce") },
                { sent.oauth.getValue("oauth_timestamp").toLong() },
            )
        val expected = signer.authorization(sent.request.method.value, sent.request.url, form)
        return expected == sent.header
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun TestScope.transport(
        engine: MockEngine,
        cache: ResponseCache = ResponseCache.NONE,
        listener: TransportListener = TransportListener.NONE,
    ): KtorTransport =
        KtorTransport(
            client = WikiHttpClient.create(credentials, engine),
            endpoint = endpoint,
            userAgent = userAgent,
            throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
            retry = RetryPolicy(maxRetries = 3, initialDelay = 1.seconds),
            cache = cache.scopedTo(credentials),
            listener = listener,
        )

    @Test
    fun `every request carries an OAuth header naming the consumer and the token with a nonce of its own`() =
        runTest {
            val sent = mutableListOf<Sent>()
            val engine = MockEngine { request ->
                sent += Sent(request)
                respondJson("{}")
            }

            WikiHttpClient.create(credentials, engine).use { client ->
                client.get("https://en.wiktionary.org/w/api.php?action=query&titles=Caf%C3%A9%20au%20lait")
                client.get("https://en.wiktionary.org/w/api.php?action=query&titles=Caf%C3%A9%20au%20lait")
            }

            sent.size shouldBe 2
            sent.forEach {
                it.header!!.startsWith("OAuth ") shouldBe true
                it.oauth.keys shouldBe
                    setOf(
                        "oauth_consumer_key",
                        "oauth_nonce",
                        "oauth_signature_method",
                        "oauth_timestamp",
                        "oauth_token",
                        "oauth_version",
                        "oauth_signature",
                    )
                it.oauth.getValue("oauth_consumer_key") shouldBe "ck-visible"
                it.oauth.getValue("oauth_token") shouldBe "at-visible"
                it.oauth.getValue("oauth_signature_method") shouldBe "HMAC-SHA1"
                it.oauth.getValue("oauth_version") shouldBe "1.0"
                signs(it, null) shouldBe true
                secrets.forEach { secret -> it.header shouldNotContain secret }
            }
            sent[0].oauth.getValue("oauth_nonce") shouldNotBe sent[1].oauth.getValue("oauth_nonce")
        }

    @Test
    fun `a request tried again is signed again with a new nonce and a new signature`() = runTest {
        val sent = mutableListOf<Sent>()
        val engine = MockEngine { request ->
            sent += Sent(request)
            if (sent.size == 1) respond("busy", HttpStatusCode.ServiceUnavailable)
            else respondJson("""{"ok":1}""")
        }

        transport(engine).call(ApiRequest.of("query", "titles" to "Lava"))

        sent.size shouldBe 2
        sent.forEach { signs(it, null) shouldBe true }
        sent[1].oauth.getValue("oauth_nonce") shouldNotBe sent[0].oauth.getValue("oauth_nonce")
        sent[1].oauth.getValue("oauth_signature") shouldNotBe sent[0].oauth.getValue("oauth_signature")
    }

    @Test
    fun `the parameters of a form body are signed with the query`() = runTest {
        val sent = mutableListOf<Sent>()
        val engine = MockEngine { request ->
            sent += Sent(request)
            respondJson("""{"edit":{"result":"Success"}}""")
        }

        transport(engine)
            .call(
                ApiRequest.of(
                    "edit",
                    "title" to "Lava",
                    "text" to "héllo wörld ~ + & =",
                    "token" to "csrf+\\",
                    kind = RequestKind.WRITE,
                )
            )

        val post = sent.single()
        post.request.method shouldBe HttpMethod.Post
        val form = post.form!!
        form["text"] shouldBe "héllo wörld ~ + & ="
        signs(post, form) shouldBe true
        signs(post, null) shouldBe false
    }

    @Test
    fun `a multipart upload is signed without its body`() = runTest {
        val sent = mutableListOf<Sent>()
        val engine = MockEngine { request ->
            sent += Sent(request)
            respondJson("{}")
        }

        WikiHttpClient.create(credentials, engine).use { client ->
            client.post("https://en.wiktionary.org/w/api.php") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("action", "upload")
                            append("filename", "Lava.png")
                            append("file", byteArrayOf(1, 2, 3))
                        }
                    )
                )
            }
        }

        val upload = sent.single()
        (upload.request.body is MultiPartFormDataContent) shouldBe true
        signs(upload, null) shouldBe true
        signs(upload, Parameters.build { append("action", "upload") }) shouldBe false
    }

    @Test
    fun `a redirect to the same host is signed for where it goes and one to another host is not signed`() =
        runTest {
            val sent = mutableListOf<Sent>()
            val engine = MockEngine { request ->
                sent += Sent(request)
                when (request.url.encodedPath) {
                    "/w/api.php" ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "https://en.wiktionary.org/w/index.php?moved=1"),
                        )
                    "/w/index.php" ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "https://elsewhere.example/steal"),
                        )
                    else -> respondJson("{}")
                }
            }

            WikiHttpClient.create(credentials, engine).use { client ->
                client.get("https://en.wiktionary.org/w/api.php?action=query")
            }

            sent.map { it.request.url.host } shouldBe
                listOf("en.wiktionary.org", "en.wiktionary.org", "elsewhere.example")
            signs(sent[0], null) shouldBe true
            signs(sent[1], null) shouldBe true
            sent[1].oauth.getValue("oauth_nonce") shouldNotBe sent[0].oauth.getValue("oauth_nonce")
            sent[2].header shouldBe null
        }

    @Test
    fun `nothing secret reaches the listener or the cache nor the OAuth parameters`() = runTest {
        val heard = mutableListOf<String>()
        val remembered = mutableListOf<String>()
        val cache =
            object : ResponseCache {
                override suspend fun get(request: ApiRequest): JsonObject? {
                    remembered += request.toString()
                    return null
                }

                override suspend fun put(request: ApiRequest, response: JsonObject) {
                    remembered += request.toString() + response
                }
            }
        val listener =
            object : TransportListener {
                override fun onResponse(request: ApiRequest, response: JsonObject) {
                    heard += request.toString() + response
                }

                override fun onRetry(request: ApiRequest, attempt: Int, wait: Duration, reason: String) {
                    heard += request.toString() + reason
                }
            }
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respond("busy", HttpStatusCode.ServiceUnavailable)
            else respondJson("""{"ok":1}""")
        }

        transport(engine, cache, listener).call(ApiRequest.of("query", "titles" to "Lava"))

        heard.size shouldBe 2
        remembered.size shouldBe 2
        (heard + remembered).forEach { seen ->
            (secrets + listOf("ck-visible", "at-visible", "oauth_", "FenaBot")).forEach {
                seen shouldNotContain it
            }
        }
    }
}
