package com.fenakhay.kwikibot.net.transport

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class HttpSettingsTest {

    @Test
    fun `more requests than the HTTP library's default of five reach one host at once`() {
        val wanted = 8
        val arrived = CountDownLatch(wanted)
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newFixedThreadPool(wanted * 2)
        server.executor = pool
        server.createContext("/") { exchange ->
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            arrived.countDown()
            // Held until every request is in flight, which only happens if the client lets them all go.
            arrived.await(5, TimeUnit.SECONDS)
            inFlight.decrementAndGet()
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()

        try {
            WikiHttpClient.create(HttpSettings(maxRequestsPerHost = wanted)).use { client ->
                val url = "http://127.0.0.1:${server.address.port}/"
                runBlocking { List(wanted) { async { client.get(url).bodyAsText() } }.awaitAll() }
            }
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }

        most.get() shouldBe wanted
    }

    @Test
    fun `settings that could not work are refused`() {
        assertFailsWith<IllegalArgumentException> { HttpSettings(maxRequestsPerHost = 0) }
        assertFailsWith<IllegalArgumentException> { HttpSettings(maxIdleConnections = -1) }
        assertFailsWith<IllegalArgumentException> { HttpSettings(timeout = Duration.ZERO) }
    }
}
