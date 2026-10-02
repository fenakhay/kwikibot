package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import com.fenakhay.kwikibot.protocol.throwOnError
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Records what MediaWiki makes of each case in [WikitextCases].
 *
 * The wikitext parser needs a contract, and MediaWiki has no specification to be checked against — wikitext
 * is defined by the implementation. So the implementation is asked directly, and its answers are committed
 * and replayed offline.
 *
 * Three things are recorded per case, because no one of them covers the ground:
 *
 * - The **structure**: the templates transcluded, the pages linked, the sections and the external URLs. This
 *   is the strongest signal, and it only means anything because every title in the cases is one no wiki has —
 *   a template that exists is expanded before any of this is reported, and the answer then describes the
 *   template rather than the input.
 * - The **rendered HTML**, normalised. For `{{unclosed` and `''bold''` the structure is empty and the
 *   rendering is the only evidence there is.
 * - The **wikitext**, verbatim, so the round-trip assertion has something to compare against.
 */
private const val WIKI = "en.wiktionary.org"

/**
 * The properties that together say what MediaWiki made of a fragment.
 *
 * `tocdata` rather than `sections`: MediaWiki 1.43 added `tocdata` to replace it (T328605), and 1.46
 * deprecated `sections` (T319141). Both list the same headings.
 *
 * `parsetree` is the preprocessor's own reading: which braces are templates, where each `|` and `=` falls,
 * which headings are sections. It is what the parser follows, so it is the closest thing to a specification.
 */
private const val PROPERTIES = "text|templates|links|tocdata|externallinks|parsetree"

/**
 * Records the corpus and writes it as JSON.
 *
 * The first argument is the file to write. A case already in that file with the same input is kept as it was
 * recorded, so adding cases does not re-record the rest; `--all` records every case again.
 */
public fun main(args: Array<String>) {
    val target = Path(args.firstOrNull() ?: "wikitext-cases.json")
    val known = if ("--all" in args || !target.exists()) emptyMap() else recorded(target)
    val document = runBlocking { record(known) }

    target.writeText(document)
    val added = WikitextCases.ALL.count { (it.name to it.input) !in known }
    println("wrote $target: ${WikitextCases.ALL.size} cases, $added recorded now")
}

/** The cases [target] already holds, by name and input. */
private fun recorded(target: Path): Map<Pair<String, String>, JsonObject> =
    (Json.parseToJsonElement(target.readText()).jsonObject["cases"] as? JsonArray)
        .orEmpty()
        .map { it.jsonObject }
        .associateBy { it.text("name") to it.text("input") }

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

private suspend fun record(known: Map<Pair<String, String>, JsonObject>): String {
    val userAgent =
        UserAgent(
            "kwikibot-wikitext-corpus",
            "0.1.0",
            "https://en.wiktionary.org/wiki/User:Fenakhay",
        )

    return WikiHttpClient.create().use { client ->
        val transport =
            KtorTransport(
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
                add(known[case.name to case.input] ?: record(transport, case))
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
    val response =
        transport
            .call(
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
                )
            )
            .throwOnError()

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
            put("parsetree", parsed["parsetree"]?.jsonPrimitive?.content.orEmpty())
        }
    }
}

/**
 * The rendered HTML with the wrapper and whitespace differences taken out.
 *
 * MediaWiki varies the paragraph padding and the trailing newline between releases; keeping those would make
 * the corpus churn on a wiki upgrade rather than on a behaviour change.
 */
private fun JsonObject.html(): String =
    this["text"]?.jsonPrimitive?.content.orEmpty().replace(Regex("\\s+"), " ").trim()

private fun JsonObject.titles(key: String): List<String> =
    (this[key] as? JsonArray)
        .orEmpty()
        .map { it.jsonObject }
        .mapNotNull { it["title"]?.jsonPrimitive?.content }

/**
 * The headings as `level:text`, the form the corpus records.
 *
 * `tocdata` leaves out a key whose value is the default, -1 for the level and empty for the text, and a
 * heading missing either is skipped.
 */
private fun JsonObject.headings(): List<String> =
    ((this["tocdata"] as? JsonObject)?.get("sections") as? JsonArray)
        .orEmpty()
        .map { it.jsonObject }
        .mapNotNull { entry ->
            val line = entry["line"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val level = entry["hLevel"]?.jsonPrimitive?.content ?: return@mapNotNull null
            "$level:$line"
        }

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.content }

private const val PROGRESS_EVERY = 20

private val PRETTY = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}
