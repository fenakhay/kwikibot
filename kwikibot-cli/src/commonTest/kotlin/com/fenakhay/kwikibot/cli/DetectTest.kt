package com.fenakhay.kwikibot.cli

import com.fenakhay.kwikibot.net.UserAgent
import com.github.ajalt.clikt.command.test
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class DetectTest {

    @OptIn(ExperimentalAtomicApi::class)
    private val sent = AtomicReference(emptyList<Pair<String, String?>>())

    @OptIn(ExperimentalAtomicApi::class)
    private val asked: List<Pair<String, String?>>
        get() = sent.load()

    /** `detect` against a wiki whose every page has [head] in its head. */
    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun detect(head: String, vararg argv: String) =
        kwikibot(
                CliEnvironment(
                    searchPath = { emptyList() },
                    engine =
                        MockEngine { request ->
                            sent.update {
                                it + (request.url.toString() to request.headers[HttpHeaders.UserAgent])
                            }
                            respond(
                                "<!DOCTYPE html><html><head>$head</head><body></body></html>",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "text/html"),
                            )
                        },
                )
            )
            .test(listOf("detect") + argv)

    @Test
    fun `detect reads where the API is from the page head`() = runTest {
        val result =
            detect(
                """<link rel="EditURI" href="//wiki.example.org/w/api.php?action=rsd">""",
                "https://wiki.example.org/wiki/Main_Page",
                "--contact",
                "https://example.org/me",
            )

        result.statusCode shouldBe 0
        result.stdout shouldBe
            """
            api:        https://wiki.example.org/w/api.php
            server:     wiki.example.org
            scriptPath: /w

            """
                .trimIndent()
        asked shouldBe
            listOf(
                "https://wiki.example.org/wiki/Main_Page" to
                    UserAgent("kwikibot", UserAgent.LIBRARY_VERSION, "https://example.org/me").headerValue
            )
    }

    @Test
    fun `detect says when the API is at the root and names the project as the contact by default`() =
        runTest {
            val result =
                detect(
                    """<link href="https://wiki.example.org/api.php?action=rsd" rel="EditURI">""",
                    "https://wiki.example.org/Main_Page",
                )

            result.statusCode shouldBe 0
            result.stdout.lines().drop(1).first() shouldBe "server:     wiki.example.org"
            result.stdout.lines()[2] shouldBe "scriptPath: (none)"
            asked.single().second shouldBe
                UserAgent("kwikibot", UserAgent.LIBRARY_VERSION, "https://github.com/fenakhay/kwikibot")
                    .headerValue
        }

    @Test
    fun `detect fails on a page that advertises no API`() = runTest {
        val result = detect("<title>Not a wiki</title>", "https://example.org/")

        result.statusCode shouldBe 1
        result.stdout shouldBe ""
        result.stderr shouldBe "no MediaWiki API advertised at https://example.org/\n"
    }
}
