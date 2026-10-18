package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.auth.Credentials
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class WikiHttpClientTest {

    private val url = "https://en.wiktionary.org/w/api.php"

    @Test
    fun `an OAuth token goes on every request as a bearer header`() {
        val sent = mutableListOf<String?>()
        val engine = MockEngine { request ->
            sent += request.headers[HttpHeaders.Authorization]
            respond("{}")
        }

        WikiHttpClient.create(Credentials.OAuth2("ya29.token"), engine).use { client ->
            runBlocking {
                client.get(url)
                client.get(url)
            }
        }

        sent shouldBe listOf("Bearer ya29.token", "Bearer ya29.token")
    }

    @Test
    fun `a bot password sends no authorization header, since it logs in for a cookie instead`() {
        val sent = mutableListOf<String?>()
        val engine = MockEngine { request ->
            sent += request.headers[HttpHeaders.Authorization]
            respond("{}")
        }

        WikiHttpClient.create(Credentials.BotPassword("FenaBot", "task", "secret"), engine).use { client ->
            runBlocking { client.get(url) }
        }

        sent shouldBe listOf(null)
    }

    @Test
    fun `the built-in engine is used when none is given, and carries the bearer header too`() {
        val sent = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            sent.set(exchange.requestHeaders.getFirst(HttpHeaders.Authorization))
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.start()

        try {
            WikiHttpClient.create(Credentials.OAuth2("ya29.token")).use { client ->
                runBlocking { client.get("http://127.0.0.1:${server.address.port}/").bodyAsText() } shouldBe
                    "{}"
            }
        } finally {
            server.stop(0)
        }

        sent.get() shouldBe "Bearer ya29.token"
    }

    @Test
    fun `a request slower than the timeout given is abandoned`() {
        val engine = MockEngine {
            delay(30.seconds)
            respond("{}")
        }

        WikiHttpClient.create(engine = engine, timeout = 100.milliseconds).use { client ->
            assertFailsWith<HttpRequestTimeoutException> { runBlocking { client.get(url) } }
        }
    }
}
