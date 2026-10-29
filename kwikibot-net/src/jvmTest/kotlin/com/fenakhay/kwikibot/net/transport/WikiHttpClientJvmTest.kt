package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.auth.Credentials
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class WikiHttpClientJvmTest {

    @Test
    fun `the built-in engine is used when none is given and carries the bearer header too`() {
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
}
