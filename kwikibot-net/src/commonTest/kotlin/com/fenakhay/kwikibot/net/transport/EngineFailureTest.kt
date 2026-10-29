package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * What each platform's own engine throws when it cannot reach a wiki, as the transport reads it.
 *
 * The engines share no type for it, so this goes through the real one: a connection to a port on this machine
 * that nothing listens on, which is refused at once and never leaves the machine.
 */
class EngineFailureTest {

    private val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")

    private val closed = ApiEndpoint("127.0.0.1:1", secure = false)

    private fun TestScope.transport(client: HttpClient, attempts: MutableList<Int>): KtorTransport =
        KtorTransport(
            client = client,
            endpoint = closed,
            userAgent = userAgent,
            throttle =
                Throttle(read = Duration.ZERO, write = Duration.ZERO, timeSource = testScheduler.timeSource),
            retry = RetryPolicy(maxRetries = 1, initialDelay = 1.seconds),
            listener =
                object : TransportListener {
                    override fun onRetry(request: ApiRequest, attempt: Int, wait: Duration, reason: String) {
                        attempts += attempt
                    }
                },
        )

    @Test
    fun `a wiki the engine of this platform cannot connect to is retried then reported as unreachable`() =
        runTest {
            val retries = mutableListOf<Int>()

            WikiHttpClient.create(timeout = 10.seconds).use { client ->
                val error =
                    assertFailsWith<WikiError.Transport.Unreachable> {
                        transport(client, retries).call(ApiRequest.of("query"))
                    }

                error.url shouldBe closed.apiUrl
                retries shouldBe listOf(1)
            }
        }

    @Test
    fun `a failure that is not the connection is raised as it is and not retried`() = runTest {
        val retries = mutableListOf<Int>()
        var calls = 0
        val client =
            HttpClient(
                MockEngine {
                    calls++
                    throw IllegalArgumentException("a request it cannot build")
                }
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                transport(client, retries).call(ApiRequest.of("query"))
            }

        error.message shouldBe "a request it cannot build"
        calls shouldBe 1
        retries shouldBe emptyList()
    }
}
