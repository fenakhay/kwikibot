package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class RequestPermitTest {

    private val endpoint = ApiEndpoint("en.wiktionary.org")
    private val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")

    @Test
    fun `a client has no more than three requests in flight unless told otherwise`() = runTest {
        mostInFlight(HttpSettings()) shouldBe 3
    }

    @Test
    fun `a client told to allow more requests in flight lets them all go`() = runTest {
        mostInFlight(HttpSettings(maxConcurrentRequests = 5)) shouldBe 5
    }

    @Test
    fun `the limit is shared by every wiki the client talks to`() = runTest {
        val wikis = listOf(endpoint, ApiEndpoint("www.wikidata.org"), ApiEndpoint("commons.wikimedia.org"))

        mostInFlight(HttpSettings(maxConcurrentRequests = 2), wikis) shouldBe 2
    }

    @Test
    fun `a request that fails gives its place back`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) throw IOException("connection reset")
            respond("""{"ok":true}""")
        }

        WikiHttpClient.create(HttpSettings(maxConcurrentRequests = 1), engine = engine).use { client ->
            val transport = KtorTransport(client, endpoint, userAgent, Throttle.unpaced(), RetryPolicy.NONE)

            assertFailsWith<WikiError.Transport> { transport.call(ApiRequest.of("query")) }
            transport.call(ApiRequest.of("query")).containsKey("ok") shouldBe true
        }
    }

    @Test
    fun `a client made some other way sends at once`() = runTest {
        HttpClient(MockEngine { respond("") }).use { client ->
            client.withRequestPermit { "sent" } shouldBe "sent"
        }
    }

    /**
     * The most requests seen in flight at once when twice as many as [settings] allow are sent together,
     * spread over [wikis].
     */
    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun mostInFlight(
        settings: HttpSettings,
        wikis: List<ApiEndpoint> = listOf(endpoint),
    ): Int {
        val inFlight = AtomicInt(0)
        val most = AtomicInt(0)
        val engine = MockEngine {
            val now = inFlight.incrementAndFetch()
            most.update { maxOf(it, now) }
            delay(100.milliseconds)
            inFlight.update { it - 1 }
            respond("""{"ok":true}""")
        }

        WikiHttpClient.create(settings, engine = engine).use { client ->
            val transports = wikis.map { KtorTransport(client, it, userAgent, Throttle.unpaced()) }
            coroutineScope {
                List(settings.maxConcurrentRequests * 2) { index ->
                        async { transports[index % transports.size].call(ApiRequest.of("query")) }
                    }
                    .awaitAll()
            }
        }
        return most.load()
    }
}
