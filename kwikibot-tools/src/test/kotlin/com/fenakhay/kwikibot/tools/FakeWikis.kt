package com.fenakhay.kwikibot.tools

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One request a tool sent: the wiki it went to, and what it asked. */
internal data class Sent(val host: String, val parameters: Map<String, String>) {
    operator fun get(name: String): String? = parameters[name]
}

/**
 * Wikis that answer from [answer] rather than over the network, and everything a tool sent and printed while
 * it asked them.
 */
internal class FakeWikis(
    scope: TestScope,
    private val answer: MockRequestHandleScope.(Sent) -> HttpResponseData,
) {
    /** Every request, in the order it was sent. */
    val sent = mutableListOf<Sent>()
    val output = StringBuilder()
    val log = StringBuilder()
    val variables = mutableMapOf<String, String>()

    val environment =
        ToolEnvironment(
            engine =
                MockEngine { request ->
                    // A read too long for a URL is POSTed, so its parameters are in the body instead.
                    val parameters =
                        if (request.method == HttpMethod.Get) request.url.parameters
                        else parseQueryString(request.body.toByteArray().decodeToString())
                    val sent =
                        Sent(request.url.host, parameters.entries().associate { it.key to it.value.single() })
                    this@FakeWikis.sent += sent
                    answer(sent)
                },
            timeSource = scope.testScheduler.timeSource,
            variables = { variables[it] },
            output = output,
            log = log,
        )
}

internal fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

/** A page as `prop=revisions` with `rvslots=main` gives it: [text] in [model], or no content when null. */
internal fun page(title: String, text: String?, model: String = "wikitext"): JsonObject = buildJsonObject {
    put("title", title)
    putJsonArray("revisions") {
        addJsonObject {
            putJsonObject("slots") {
                putJsonObject("main") {
                    put("contentmodel", model)
                    text?.let { put("content", it) }
                }
            }
        }
    }
}

/** The answer to a query whose pages are [pages]. */
internal fun pages(pages: List<JsonObject>): String = buildJsonObject {
    putJsonObject("query") { put("pages", JsonArray(pages)) }
}
    .toString()
