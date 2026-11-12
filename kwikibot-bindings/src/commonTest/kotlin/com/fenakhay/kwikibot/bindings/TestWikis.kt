package com.fenakhay.kwikibot.bindings

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.utils.io.ByteReadChannel
import kotlin.time.Duration

/** A configuration that never waits between requests nor retries a refusal. */
val QUICK: WikiConfig =
    WikiConfig(
        userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
        throttle = Throttle(Duration.ZERO, Duration.ZERO),
        retry = RetryPolicy.NONE,
    )

private val SITE_INFO =
    """
    {"query":{
      "general":{"wikiid":"testwiki","sitename":"Test Wiki","lang":"en","timezone":"UTC",
                 "server":"//test.example.org","articlepath":"/wiki/${'$'}1",
                 "mainpage":"Main Page","generator":"MediaWiki 1.47.0"},
      "namespaces":{
        "0":{"id":0,"name":"","case":"first-letter"},
        "2":{"id":2,"name":"User","canonical":"User","case":"first-letter"},
        "6":{"id":6,"name":"File","canonical":"File","case":"first-letter"},
        "10":{"id":10,"name":"Template","canonical":"Template","case":"first-letter"},
        "14":{"id":14,"name":"Category","canonical":"Category","case":"first-letter"}},
      "interwikimap":[]}}
    """
        .trimIndent()

private const val USER_INFO = """{"query":{"userinfo":{"id":0,"name":"192.0.2.1","groups":["*"]}}}"""

/**
 * A real wiki behind a mock engine that answers only what opening it asks, so that its services exist to be
 * stood in for. [answer] answers every other request by its parameters.
 */
suspend fun openWiki(answer: (Map<String, String>) -> String = { "{}" }): Wiki {
    val engine = MockEngine { request ->
        val body = request.body.toByteArray().decodeToString()
        val parameters =
            (parseQueryString(request.url.encodedQuery).entries() + parseQueryString(body).entries())
                .associate { it.key to it.value.first() }
        val text =
            when (parameters["meta"]) {
                "siteinfo" -> SITE_INFO
                "userinfo" -> USER_INFO
                else -> answer(parameters)
            }
        respond(
            ByteReadChannel(text),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
    return WikiClient(QUICK, Credentials.Anonymous, engine = engine).wiki(ApiEndpoint("test.example.org"))
}

/** Every call a recording service heard, in order: its name and its arguments. */
class Calls {
    private val heard = mutableListOf<List<Any?>>()

    /** What was heard. */
    val all: List<List<Any?>>
        get() = heard

    /** The one call heard, which a test expects to be the only one. */
    val only: List<Any?>
        get() = heard.single()

    /** Hears a call to [name] with [arguments]. */
    fun record(name: String, vararg arguments: Any?) {
        heard += listOf(name, *arguments)
    }

    /** Forgets what was heard. */
    fun clear() {
        heard.clear()
    }
}
