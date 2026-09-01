package com.fenakhay.kwikibot.protocol

import app.cash.turbine.test
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class ContinuationTest {

    @Test
    fun `a query with no continue makes one request`() = runTest {
        var requests = 0
        val continuation = Continuation(
            transport {
                requests++
                respondJson("""{"batchcomplete":true,"query":{"allpages":[{"title":"A"}]}}""")
            },
        )

        val titles = continuation.list(query(), "allpages").toList()

        titles.map { it["title"]!!.jsonPrimitive.content } shouldBe listOf("A")
        requests shouldBe 1
    }

    @Test
    fun `batches are followed until the query is exhausted`() = runTest {
        val continuation = Continuation(transport(pagedResponder()))

        val titles = continuation.list(query(), "allpages")
            .toList()
            .map { it["title"]!!.jsonPrimitive.content }

        titles shouldBe listOf("A", "B", "C", "D", "E")
    }

    @Test
    fun `continue parameters are carried into the next request`() = runTest {
        val queries = mutableListOf<String>()
        val continuation = Continuation(
            transport { request ->
                queries += request.url.encodedQuery
                pagedBatch(queries.size)
            },
        )

        continuation.list(query(), "allpages").toList()

        queries[0].contains("apcontinue") shouldBe false
        queries[1].contains("apcontinue=B") shouldBe true
        queries[1].contains("list=allpages") shouldBe true
    }

    @Test
    fun `nothing is requested until the flow is collected`() = runTest {
        var requests = 0
        val continuation = Continuation(
            transport {
                requests++
                respondJson("""{"query":{"allpages":[]}}""")
            },
        )

        val flow = continuation.list(query(), "allpages")
        requests shouldBe 0

        flow.toList()
        requests shouldBe 1
    }

    @Test
    fun `abandoning the collection stops the paging`() = runTest {
        var requests = 0
        val continuation = Continuation(
            transport { request ->
                requests++
                pagedBatch(requests)
            },
        )

        continuation.list(query(), "allpages").first()

        requests shouldBe 1
    }

    @Test
    fun `a batch ceiling bounds a query that would otherwise run forever`() = runTest {
        var requests = 0
        val continuation = Continuation(
            transport {
                requests++
                respondJson(
                    """{"continue":{"apcontinue":"X","continue":"-||"},""" +
                        """"query":{"allpages":[{"title":"X"}]}}""",
                )
            },
        )

        val count = continuation.list(query(), "allpages", maxBatches = 3).count()

        count shouldBe 3
        requests shouldBe 3
    }

    @Test
    fun `an error partway through is raised, not swallowed as a short result`() = runTest {
        var requests = 0
        val continuation = Continuation(
            transport { request ->
                requests++
                if (requests == 1) {
                    pagedBatch(1)
                } else {
                    respondJson("""{"errors":[{"code":"badvalue","text":"Bad apcontinue"}]}""")
                }
            },
        )

        continuation.list(query(), "allpages").test {
            awaitItem()
            awaitItem()
            assertFailsWith<WikiError.Api> { awaitError().let { throw it } }
        }
    }

    @Test
    fun `page results are emitted whether the wiki returns a list or a map`() = runTest {
        val asList = Continuation(
            transport { respondJson("""{"query":{"pages":[{"pageid":1,"title":"A"}]}}""") },
        ).pages(query()).toList()

        val asMap = Continuation(
            transport { respondJson("""{"query":{"pages":{"1":{"pageid":1,"title":"A"}}}}""") },
        ).pages(query()).toList()

        asList.map { it["title"]!!.jsonPrimitive.content } shouldBe listOf("A")
        asMap.map { it["title"]!!.jsonPrimitive.content } shouldBe listOf("A")
    }

    private fun query() = ApiRequest.of("query", "list" to "allpages", "aplimit" to "2")

    private fun pagedResponder(): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData {
        var batch = 0
        return {
            batch++
            pagedBatch(batch)
        }
    }

    private fun MockRequestHandleScope.pagedBatch(batch: Int): HttpResponseData = when (batch) {
        1 -> respondJson(
            """{"continue":{"apcontinue":"B","continue":"-||"},""" +
                """"query":{"allpages":[{"title":"A"},{"title":"B"}]}}""",
        )

        2 -> respondJson(
            """{"continue":{"apcontinue":"D","continue":"-||"},""" +
                """"query":{"allpages":[{"title":"C"},{"title":"D"}]}}""",
        )

        else -> respondJson("""{"batchcomplete":true,"query":{"allpages":[{"title":"E"}]}}""")
    }

    private fun TestScope.transport(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorTransport = KtorTransport(
        client = HttpClient(MockEngine(handler)),
        endpoint = ApiEndpoint("en.wiktionary.org"),
        userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
        throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
        retry = RetryPolicy.NONE,
    )

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
