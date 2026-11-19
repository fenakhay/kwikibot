package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class WikiHttpClientTest {

    private val url = "https://en.wiktionary.org/w/api.php"

    private val wiki = ApiEndpoint("en.wiktionary.org")

    @Test
    fun `an OAuth token goes only to the wikis a transport on the client reaches`() = runTest {
        val sent = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { request ->
            sent += request.url.host to request.headers[HttpHeaders.Authorization]
            respond("{}")
        }

        WikiHttpClient.create(Credentials.OAuth2("ya29.token"), engine).use { client ->
            client.get("https://petscan.wmcloud.org/?psid=1")
            KtorTransport(client, wiki, UserAgent("test", "1", "https://example.org"))
            client.get(url)
            client.get("https://en.wiktionary.org:8443/w/api.php")
            client.get("http://en.wiktionary.org/w/api.php")
            client.get("https://petscan.wmcloud.org/?psid=2")
        }

        sent shouldBe
            listOf(
                "petscan.wmcloud.org" to null,
                "en.wiktionary.org" to "Bearer ya29.token",
                "en.wiktionary.org" to null,
                "en.wiktionary.org" to null,
                "petscan.wmcloud.org" to null,
            )
    }

    @Test
    fun `a redirect off the wiki loses the token and a header set for another service is kept`() = runTest {
        val sent = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { request ->
            sent += request.url.host to request.headers[HttpHeaders.Authorization]
            if (request.url.host == "en.wiktionary.org") {
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://elsewhere.example/x"),
                )
            } else {
                respond("{}")
            }
        }

        WikiHttpClient.create(Credentials.OAuth2("ya29.token"), engine).use { client ->
            client.allowCredentials(wiki)
            client.get(url)
            client.get("https://api.example.org/score") { header(HttpHeaders.Authorization, "Bearer other") }
        }

        sent shouldBe
            listOf(
                "en.wiktionary.org" to "Bearer ya29.token",
                "elsewhere.example" to null,
                "api.example.org" to "Bearer other",
            )
    }

    @Test
    fun `an OAuth 1 signature goes only to the wikis a transport on the client reaches`() = runTest {
        val sent = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { request ->
            sent += request.url.host to request.headers[HttpHeaders.Authorization]
            respond("{}")
        }
        val login = Credentials.OAuth1("consumer", "consumer-secret", "token", "token-secret")

        WikiHttpClient.create(login, engine).use { client ->
            client.get("https://petscan.wmcloud.org/?psid=1")
            client.allowCredentials(wiki)
            client.get(url)
            client.get("https://stream.wikimedia.org/v2/stream/recentchange")
        }

        sent.map { (host, header) -> host to (header?.startsWith("OAuth ") == true) } shouldBe
            listOf(
                "petscan.wmcloud.org" to false,
                "en.wiktionary.org" to true,
                "stream.wikimedia.org" to false,
            )
    }

    @Test
    fun `an OAuth token goes on every request as a bearer header`() = runTest {
        val sent = mutableListOf<String?>()
        val engine = MockEngine { request ->
            sent += request.headers[HttpHeaders.Authorization]
            respond("{}")
        }

        WikiHttpClient.create(Credentials.OAuth2("ya29.token"), engine).use { client ->
            client.allowCredentials(wiki)
            client.get(url)
            client.get(url)
        }

        sent shouldBe listOf("Bearer ya29.token", "Bearer ya29.token")
    }

    @Test
    fun `an OAuth token is sent with the session cookies the wiki set`() = runTest {
        val sent = mutableListOf<Pair<String?, String?>>()
        val engine = MockEngine { request ->
            sent += request.headers[HttpHeaders.Authorization] to request.headers[HttpHeaders.Cookie]
            respond(
                "{}",
                headers =
                    headersOf(HttpHeaders.SetCookie, "enwiktionarySession=abc123; path=/; secure; HttpOnly"),
            )
        }

        WikiHttpClient.create(Credentials.OAuth2("ya29.token"), engine).use { client ->
            client.allowCredentials(wiki)
            client.get(url)
            client.get(url)
        }

        sent shouldBe
            listOf(
                "Bearer ya29.token" to null,
                "Bearer ya29.token" to "enwiktionarySession=abc123",
            )
    }

    @Test
    fun `a bot password sends no authorization header since it logs in for a cookie instead`() = runTest {
        val sent = mutableListOf<String?>()
        val engine = MockEngine { request ->
            sent += request.headers[HttpHeaders.Authorization]
            respond("{}")
        }

        WikiHttpClient.create(Credentials.BotPassword("FenaBot", "task", "secret"), engine).use { client ->
            client.get(url)
        }

        sent shouldBe listOf(null)
    }

    @Test
    fun `a request slower than the timeout given is abandoned`() = runTest {
        val engine = MockEngine {
            delay(30.seconds)
            respond("{}")
        }

        WikiHttpClient.create(engine = engine, timeout = 100.milliseconds).use { client ->
            assertFailsWith<HttpRequestTimeoutException> { client.get(url) }
        }
    }
}
