package com.fenakhay.kwikibot.bindings.sparql

import com.fenakhay.kwikibot.bindings.events.eventStreamsWithDefaults
import com.fenakhay.kwikibot.client.model.SparqlAuth
import com.fenakhay.kwikibot.client.model.SparqlClient
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/** The Wikimedia services a binding reaches: whom they ask, and as whom, unless told. */
class SparqlTest {

    private val asked = mutableListOf<HttpRequestData>()

    private val results =
        """{"head":{"vars":["title","page"]},"results":{"bindings":[""" +
            """{"title":{"type":"literal","value":"Volcano"},"page":{"type":"literal","value":"Lava"}}]}}"""

    private val http =
        HttpClient(
            MockEngine { request ->
                asked += request
                respond(
                    ByteReadChannel(results),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/sparql-results+json"),
                )
            }
        )

    @Test
    fun `a query asks Wikidata as this library unless told`() = runTest {
        sparqlClientWithDefaults(http, "bot@example.org").select("SELECT ?title {}").size shouldBe 1
        asked.single().url.toString() shouldStartWith SparqlClient.WIKIDATA
        asked.single().headers[HttpHeaders.UserAgent].orEmpty() shouldContain "bot@example.org"
        asked.clear()
        val agent = UserAgent("MyBot", "1.0", "me@example.org")
        sparqlClientWithDefaults(
                http,
                "bot@example.org",
                agent,
                "https://example.org/sparql",
                SparqlAuth.None,
                RetryPolicy.NONE,
            )
            .select("SELECT ?title {}")
        asked.single().url.toString() shouldStartWith "https://example.org/sparql"
        asked.single().headers[HttpHeaders.UserAgent].orEmpty() shouldContain "MyBot"
    }

    @Test
    fun `a query for pages asks Wikidata as this library unless told`() = runTest {
        val wiki = FakeWiki()
        val _ = sparqlSourceWithDefaults("SELECT ?title {}", http, "bot@example.org").pages(wiki).toList()
        asked.last().url.toString() shouldStartWith SparqlClient.WIKIDATA
        asked.last().headers[HttpHeaders.UserAgent].orEmpty() shouldContain "bot@example.org"
        val source =
            sparqlSourceWithDefaults(
                "SELECT ?page {}",
                http,
                "bot@example.org",
                UserAgent("MyBot", "1.0", "me@example.org"),
                "https://example.org/sparql",
                "page",
                SparqlAuth.None,
            )
        val _ = source.pages(wiki).toList()
        asked.last().url.toString() shouldStartWith "https://example.org/sparql"
        asked.last().headers[HttpHeaders.UserAgent].orEmpty() shouldContain "MyBot"
    }

    @Test
    fun `event streams are reached with nothing given and with everything given`() {
        val agent = UserAgent("MyBot", "1.0", "me@example.org")
        eventStreamsWithDefaults(http, "bot@example.org")
        eventStreamsWithDefaults(http, "bot@example.org", agent, RetryPolicy.NONE, "https://example.org/")
        asked shouldBe emptyList()
    }
}
