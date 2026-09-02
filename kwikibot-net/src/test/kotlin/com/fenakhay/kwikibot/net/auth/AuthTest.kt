package com.fenakhay.kwikibot.net.auth

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class AuthTest {

    private val endpoint = ApiEndpoint("en.wiktionary.org")
    private val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")

    private val botPassword = Credentials.BotPassword("FenaBot", "compounds", "hunter2")

    @Test
    fun `a bot password is sent as account at botname`() {
        botPassword.loginName shouldBe "FenaBot@compounds"
        botPassword.toString().contains("hunter2") shouldBe false
    }

    @Test
    fun `a token is fetched once and reused`() = runTest {
        var tokenRequests = 0
        val store =
            TokenStore(
                transport {
                    tokenRequests++
                    respondJson("""{"query":{"tokens":{"csrftoken":"abc123+\\"}}}""")
                }
            )

        store.token() shouldBe """abc123+\"""
        store.token() shouldBe """abc123+\"""

        tokenRequests shouldBe 1
    }

    @Test
    fun `concurrent callers share one token request`() = runTest {
        var tokenRequests = 0
        val store =
            TokenStore(
                transport {
                    tokenRequests++
                    respondJson("""{"query":{"tokens":{"csrftoken":"abc123"}}}""")
                }
            )

        List(8) { async { store.token() } }.awaitAll()

        tokenRequests shouldBe 1
    }

    @Test
    fun `invalidating forces the next request to fetch again`() = runTest {
        var tokenRequests = 0
        val store =
            TokenStore(
                transport {
                    tokenRequests++
                    respondJson("""{"query":{"tokens":{"csrftoken":"token$tokenRequests"}}}""")
                }
            )

        store.token() shouldBe "token1"
        store.invalidate()
        store.token() shouldBe "token2"
    }

    @Test
    fun `a rejected token is refetched once and the work retried`() = runTest {
        var tokenRequests = 0
        val store =
            TokenStore(
                transport {
                    tokenRequests++
                    respondJson("""{"query":{"tokens":{"csrftoken":"token$tokenRequests"}}}""")
                }
            )
        val used = mutableListOf<String>()

        val result = store.withFreshToken { token ->
            used += token
            if (used.size == 1) throw WikiError.Auth.BadToken(TokenStore.CSRF)
            "saved with $token"
        }

        result shouldBe "saved with token2"
        used shouldBe listOf("token1", "token2")
    }

    @Test
    fun `the anonymous placeholder token is reported as not being logged in`() = runTest {
        val store = TokenStore(transport { respondJson("""{"query":{"tokens":{"csrftoken":"+\\"}}}""") })

        assertFailsWith<WikiError.Auth.NotLoggedIn> { store.token() }
    }

    @Test
    fun `a bot password login fetches a token then posts the credentials`() = runTest {
        val actions = mutableListOf<String>()
        val transport = transport { request ->
            val sent = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            when {
                sent.contains("meta=tokens") -> {
                    actions += "token"
                    respondJson("""{"query":{"tokens":{"logintoken":"LOGIN+\\"}}}""")
                }

                sent.contains("action=login") -> {
                    actions += "login"
                    respondJson("""{"login":{"result":"Success","lgusername":"FenaBot"}}""")
                }

                else -> {
                    actions += "userinfo"
                    respondJson(
                        """{"query":{"userinfo":{"id":42,"name":"FenaBot","groups":["bot","user"],""" +
                            """"rights":["edit","writeapi"]}}}"""
                    )
                }
            }
        }

        val identity = LoginManager(transport, botPassword).login()

        identity.name shouldBe "FenaBot"
        identity.isBot shouldBe true
        identity.isAnonymous shouldBe false
        ("edit" in identity) shouldBe true
        actions shouldBe listOf("token", "login", "userinfo")
    }

    @Test
    fun `credentials and tokens are POSTed, never put in a URL`() = runTest {
        val urls = mutableListOf<String>()
        val methods = mutableListOf<String>()
        val transport = transport { request ->
            urls += request.url.toString()
            methods += request.method.value
            if (request.url.encodedQuery.contains("meta=tokens")) {
                respondJson("""{"query":{"tokens":{"logintoken":"LOGIN"}}}""")
            } else if (methods.size == 2) {
                respondJson("""{"login":{"result":"Success"}}""")
            } else {
                respondJson("""{"query":{"userinfo":{"id":42,"name":"FenaBot","groups":["bot"]}}}""")
            }
        }

        LoginManager(transport, botPassword).login()

        urls.none { it.contains("hunter2") } shouldBe true
        urls.none { it.contains("lgtoken") } shouldBe true
        methods[1] shouldBe "POST"
    }

    @Test
    fun `a refused login reports the reason the wiki gave`() = runTest {
        val transport = transport { request ->
            if (request.url.encodedQuery.contains("meta=tokens")) {
                respondJson("""{"query":{"tokens":{"logintoken":"LOGIN"}}}""")
            } else {
                respondJson("""{"login":{"result":"Failed","reason":"Incorrect username or password."}}""")
            }
        }

        val error =
            assertFailsWith<WikiError.Auth.LoginFailed> {
                LoginManager(transport, botPassword).login()
            }

        error.reason shouldBe "Incorrect username or password."
    }

    @Test
    fun `a refused login reports the reason when it arrives as an object`() = runTest {
        val transport = transport { request ->
            if (request.url.encodedQuery.contains("meta=tokens")) {
                respondJson("""{"query":{"tokens":{"logintoken":"LOGIN"}}}""")
            } else {
                respondJson(
                    """{"login":{"result":"Failed","reason":{"code":"wrongpassword",
                       "text":"Incorrect username or password entered.","module":"login"}}}"""
                )
            }
        }

        val error =
            assertFailsWith<WikiError.Auth.LoginFailed> {
                LoginManager(transport, botPassword).login()
            }

        error.reason shouldBe "Incorrect username or password entered."
    }

    @Test
    fun `a login the wiki accepts but does not apply is still a failure`() = runTest {
        val transport = transport { request ->
            val query = request.url.encodedQuery
            when {
                query.contains("meta=tokens") -> respondJson("""{"query":{"tokens":{"logintoken":"L"}}}""")
                query.contains("meta=userinfo") ->
                    respondJson("""{"query":{"userinfo":{"id":0,"name":"1.2.3.4"}}}""")

                else -> respondJson("""{"login":{"result":"Success"}}""")
            }
        }

        assertFailsWith<WikiError.Auth.LoginFailed> { LoginManager(transport, botPassword).login() }
    }

    @Test
    fun `logging in twice at once produces one login`() = runTest {
        var logins = 0
        val transport = transport { request ->
            val query = request.url.encodedQuery
            when {
                query.contains("meta=tokens") -> respondJson("""{"query":{"tokens":{"logintoken":"L"}}}""")
                query.contains("meta=userinfo") ->
                    respondJson("""{"query":{"userinfo":{"id":42,"name":"FenaBot","groups":["bot"]}}}""")

                else -> {
                    logins++
                    respondJson("""{"login":{"result":"Success"}}""")
                }
            }
        }
        val manager = LoginManager(transport, botPassword)

        List(5) { async { manager.login() } }.awaitAll()

        logins shouldBe 1
    }

    @Test
    fun `an anonymous session needs no login and reports the IP`() = runTest {
        val transport = transport {
            respondJson("""{"query":{"userinfo":{"id":0,"name":"1.2.3.4"}}}""")
        }

        val identity = LoginManager(transport, Credentials.Anonymous).login()

        identity.isAnonymous shouldBe true
        identity.name shouldBe "1.2.3.4"
    }

    @Test
    fun `logging out tells the wiki, then forgets the session either way`() = runTest {
        val calls = mutableListOf<String>()
        val transport = transport { request ->
            val body = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            calls += body
            when {
                "meta=tokens" in body ->
                    respondJson("""{"query":{"tokens":{"csrftoken":"abc+\\","logintoken":"L+\\"}}}""")

                "action=login" in body ->
                    respondJson("""{"login":{"result":"Success","lgusername":"FenaBot"}}""")

                "userinfo" in body ->
                    respondJson("""{"query":{"userinfo":{"id":7,"name":"FenaBot","groups":["bot"]}}}""")

                else -> respondJson("""{"logout":{}}""")
            }
        }

        val manager =
            LoginManager(
                transport,
                Credentials.BotPassword("FenaBot", "task", "secret"),
                TokenStore(transport),
            )
        manager.login().name shouldBe "FenaBot"

        manager.logout()

        calls.count { "action=logout" in it } shouldBe 1

        manager.login().name shouldBe "FenaBot"
        calls.count { "action=login" in it } shouldBe 2
    }

    @Test
    fun `an anonymous session has nothing to tell the wiki about logging out`() = runTest {
        val calls = mutableListOf<String>()
        val transport = transport { request ->
            calls += request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            respondJson("""{"query":{"userinfo":{"id":0,"name":"1.2.3.4"}}}""")
        }

        val manager = LoginManager(transport, Credentials.Anonymous, TokenStore(transport))
        manager.login()
        manager.logout()

        calls.none { "action=logout" in it } shouldBe true
    }

    @Test
    fun `a wiki that refuses the logout still ends the session locally`() = runTest {
        val transport = transport { request ->
            val body = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            when {
                "meta=tokens" in body ->
                    respondJson("""{"query":{"tokens":{"csrftoken":"abc+\\","logintoken":"L+\\"}}}""")

                "action=login" in body ->
                    respondJson("""{"login":{"result":"Success","lgusername":"FenaBot"}}""")

                "userinfo" in body ->
                    respondJson("""{"query":{"userinfo":{"id":7,"name":"FenaBot","groups":["bot"]}}}""")

                else -> respondJson("""{"error":{"code":"internal_api_error","info":"boom"}}""")
            }
        }

        val manager =
            LoginManager(
                transport,
                Credentials.BotPassword("FenaBot", "task", "secret"),
                TokenStore(transport),
            )
        manager.login()

        manager.logout()
        manager.login().name shouldBe "FenaBot"
    }

    @Test
    fun `credentials keep their secret out of their own toString`() {
        val botPassword = Credentials.BotPassword("FenaBot", "task", "hunter2")
        val oauth = Credentials.OAuth2("ya29.super-secret-token", username = "FenaBot")

        "$botPassword" shouldNotContain "hunter2"
        "$oauth" shouldNotContain "ya29.super-secret-token"
        "$oauth" shouldContain "FenaBot"
    }

    private fun TestScope.transport(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): KtorTransport =
        KtorTransport(
            client = HttpClient(MockEngine(handler)),
            endpoint = endpoint,
            userAgent = userAgent,
            throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
        )

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
