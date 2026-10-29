package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.auth.Credentials
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class WikiHttpClientTest {

    private val url = "https://en.wiktionary.org/w/api.php"

    @Test
    fun `an OAuth token goes on every request as a bearer header`() = runTest {
        val sent = mutableListOf<String?>()
        val engine = MockEngine { request ->
            sent += request.headers[HttpHeaders.Authorization]
            respond("{}")
        }

        WikiHttpClient.create(Credentials.OAuth2("ya29.token"), engine).use { client ->
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
