package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.WikiHttpClient
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds

/**
 * Records what MediaWiki makes of each case in [WikitextCases].
 *
 * The wikitext parser needs a contract, and MediaWiki has no specification to be checked against —
 * wikitext is defined by the implementation. So the implementation is asked directly, and its
 * answers are committed and replayed offline.
 *
 * Three things are recorded per case, because no one of them covers the ground:
 *
 * - The **structure**: the templates transcluded, the pages linked, the sections and the external
 *   URLs. This is the strongest signal, and it only means anything because every title in the
 *   cases is one no wiki has — a template that exists is expanded before any of this is reported,
 *   and the answer then describes the template rather than the input.
 * - The **rendered HTML**, normalised. For `{{unclosed` and `''bold''` the structure is empty and
 *   the rendering is the only evidence there is.
 * - The **wikitext**, verbatim, so the round-trip assertion has something to compare against.
 */
private const val WIKI = "en.wiktionary.org"

/** The properties that together say what MediaWiki made of a fragment. */
private const val PROPERTIES = "text|templates|links|sections|externallinks"

/**
 * Records the corpus and writes it as JSON.
 *
 * The first argument is the file to write.
 */
public fun main(args: Array<String>) {
    val target = Path(args.firstOrNull() ?: "wikitext-cases.json")
    val recorded = runBlocking { record() }

    target.writeText(recorded)
    println("wrote $target: ${WikitextCases.ALL.size} cases")
}

private suspend fun record(): String {
    val userAgent = UserAgent(
        "kwikibot-wikitext-corpus",
        "0.1.0",
        "https://en.wiktionary.org/wiki/User:Fenakhay",
    )

    return WikiHttpClient.create().use { client ->
        val transport = KtorTransport(
            client = client,
            endpoint = ApiEndpoint(server = WIKI),
            userAgent = userAgent,
            // Politely slow: this is somebody else's production wiki, and the corpus is recorded
            // once rather than on every build.
            throttle = Throttle(read = 500.milliseconds),
            // action=parse is answered from the parser rather than from a database replica, so
            // replica lag is no reason to refuse it.
            maxlag = null,
        )

        val cases = buildJsonArray {
            WikitextCases.ALL.forEachIndexed { index, case ->
                add(record(transport, case))
                if ((index + 1) % PROGRESS_EVERY == 0) {
                    System.err.println("  ${index + 1}/${WikitextCases.ALL.size}")
                }
            }
        }

        val document = buildJsonObject {
            put("wiki", WIKI)
            put(
                "about",
                "What MediaWiki makes of each fragment, recorded from action=parse and replayed " +
                    "offline. Every title in the inputs is one no wiki has, so the structure " +
                    "describes what was written rather than what a template expanded to.",
            )
            put("cases", cases)
        }
        PRETTY.encodeToString(JsonObject.serializer(), document)
    }
}

private suspend fun record(transport: MediaWikiTransport, case: WikitextCases.Case): JsonObject {
    val response = transport.call(
        ApiRequest.of(
            "parse",
            "text" to case.input,
            "title" to "Sandbox",
            "contentmodel" to "wikitext",
            "prop" to PROPERTIES,
            "disablelimitreport" to "1",
            "disableeditsection" to "1",
            // Without this the output is wrapped in a div whose classes vary between skins and
            // releases, which would make the recording churn for no reason.
            "wrapoutputclass" to "",
        ),
    ).throwOnError()

    val parsed = response["parse"]?.jsonObject ?: JsonObject(emptyMap())

    return buildJsonObject {
        put("name", case.name)
        put("input", case.input)
        putJsonObject("mediawiki") {
            put("html", parsed.html())
            putJsonArray("templates") { parsed.titles("templates").forEach { add(it) } }
            putJsonArray("links") { parsed.titles("links").forEach { add(it) } }
            putJsonArray("sections") { parsed.headings().forEach { add(it) } }
            putJsonArray("externallinks") { parsed.strings("externallinks").forEach { add(it) } }
        }
    }
}

/**
 * The rendered HTML with the wrapper and whitespace differences taken out.
 *
 * MediaWiki varies the paragraph padding and the trailing newline between releases; keeping those
 * would make the corpus churn on a wiki upgrade rather than on a behaviour change.
 */
private fun JsonObject.html(): String =
    this["text"]?.jsonPrimitive?.content.orEmpty()
        .replace(Regex("\\s+"), " ")
        .trim()

private fun JsonObject.titles(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty()
        .map { it.jsonObject }
        .mapNotNull { it["title"]?.jsonPrimitive?.content }

private fun JsonObject.headings(): List<String> =
    (this["sections"] as? JsonArray).orEmpty()
        .map { it.jsonObject }
        .mapNotNull { entry ->
            val line = entry["line"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val level = entry["level"]?.jsonPrimitive?.content ?: return@mapNotNull null
            "$level:$line"
        }

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.content }

private const val PROGRESS_EVERY = 20

private val PRETTY = kotlinx.serialization.json.Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}
