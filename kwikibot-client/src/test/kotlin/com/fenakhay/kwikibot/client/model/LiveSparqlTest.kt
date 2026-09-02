package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.kotest.matchers.collections.shouldNotBeEmpty
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

@Tag("live")
class LiveSparqlTest {

    private val userAgent =
        UserAgent("kwikibot-livetest", "0.1.0", "https://en.wiktionary.org/wiki/User:FenaBot")

    private val token: String? = System.getenv("WCQS_AUTH_TOKEN")?.takeIf { it.isNotBlank() }

    @Test
    fun `wikidata answers without any credentials`() =
        runBlocking<Unit> {
            WikiHttpClient.create().use { http ->
                val rows =
                    SparqlClient(http, userAgent, SparqlClient.WIKIDATA)
                        .select("SELECT ?item WHERE { ?item wdt:P31 wd:Q5 } LIMIT 1")

                rows.shouldNotBeEmpty()
            }
        }

    /**
     * Commons does not answer the first request.
     *
     * It replies `307` and sets a short-lived session cookie on the redirect, so a client has to follow it
     * and keep what it was given. Ktor does neither for a POST by default, and the empty `307` body reads
     * exactly like a rejected query — which is how this looked like an expired token for a while.
     */
    @Test
    fun `commons answers a token that is carried through its redirect`() =
        runBlocking<Unit> {
            assumeTrue(token != null, "WCQS_AUTH_TOKEN is not set")

            WikiHttpClient.create().use { http ->
                val rows =
                    SparqlClient(
                            client = http,
                            userAgent = userAgent,
                            endpoint = SparqlClient.COMMONS,
                            auth = SparqlAuth.wcqs(checkNotNull(token)),
                        )
                        .select("SELECT ?f WHERE { ?f schema:contentUrl ?u } LIMIT 1")

                rows.shouldNotBeEmpty()
            }
        }
}
