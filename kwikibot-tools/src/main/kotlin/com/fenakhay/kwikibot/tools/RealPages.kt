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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.zip.GZIPOutputStream
import kotlin.io.path.Path
import kotlin.io.path.outputStream
import kotlin.time.Duration.Companion.milliseconds

/**
 * Records real page text for the round-trip assertion to run against offline.
 *
 * The recorded structural corpus in [WikitextCases] says what the parser should make of eighty
 * fragments somebody sat down and thought of. This says it survives the wikitext nobody thought
 * about: whatever the wikis happened to hand over.
 *
 * Round-trip is the one property that needs no external authority to check. `parse(text)` then
 * `serialize()` must give back the same bytes, because a bot rewrites a page by changing one
 * thing in it and writing the whole thing back — anything the parser quietly normalises becomes
 * collateral damage on every page the bot touches.
 *
 * The pages are drawn at random rather than chosen. A case somebody picks is a case somebody was
 * already thinking about, and those are already in [WikitextCases].
 */
private val SOURCES = listOf(
    // Entries and the templates behind them: short, dense, and heavy on the constructs
    // a Wiktionary bot actually meets.
    Source("en.wiktionary.org", namespace = 0, pages = 60),
    Source("en.wiktionary.org", namespace = 10, pages = 25),
    // Articles: tables, references, infoboxes, and the longest text in the sample.
    Source("en.wikipedia.org", namespace = 0, pages = 40),
    // Template space is where the parser functions and the deliberate unbalanced braces live.
    Source("en.wikipedia.org", namespace = 10, pages = 25),
    // Other languages, for non-Latin text and localised template syntax.
    Source("fr.wikipedia.org", namespace = 0, pages = 20),
    Source("de.wikipedia.org", namespace = 0, pages = 20),
    Source("ja.wikipedia.org", namespace = 0, pages = 20),
    // File description pages: licence template soup, and almost nothing else.
    Source("commons.wikimedia.org", namespace = 6, pages = 20),
)

/** One wiki and namespace to draw pages from. */
private data class Source(val wiki: String, val namespace: Int, val pages: Int)

/**
 * The largest page worth recording.
 *
 * Above this a page is a list or an archive, which is the same few constructs repeated thousands
 * of times. It stresses the repository more than it stresses the parser.
 */
private const val MAX_PAGE_BYTES = 80_000

/** What `list=random` returns at most in one request for an anonymous client. */
private const val RANDOM_BATCH = 10

/**
 * Records the pages and writes them gzipped.
 *
 * The first argument is the file to write.
 */
public fun main(args: Array<String>) {
    val target = Path(args.firstOrNull() ?: "roundtrip-pages.json.gz")
    val document = runBlocking { record() }

    GZIPOutputStream(target.outputStream()).bufferedWriter().use { it.write(document) }
    println("wrote $target")
}

private suspend fun record(): String {
    val userAgent = UserAgent(
        "kwikibot-roundtrip-corpus",
        "0.1.0",
        "https://en.wiktionary.org/wiki/User:Fenakhay",
    )

    val pages = buildJsonArray {
        var kept = 0
        var skipped = 0

        for (source in SOURCES) {
            WikiHttpClient.create().use { client ->
                val transport = KtorTransport(
                    client = client,
                    endpoint = ApiEndpoint(server = source.wiki),
                    userAgent = userAgent,
                    // Somebody else's production wiki, and this is recorded once rather than on
                    // every build. There is no hurry.
                    throttle = Throttle(read = 500.milliseconds),
                    maxlag = null,
                )

                var taken = 0
                while (taken < source.pages) {
                    val batch = randomPages(transport, source, RANDOM_BATCH)
                    if (batch.isEmpty()) break

                    val usable = batch.filter { (_, text) -> text.length <= MAX_PAGE_BYTES }
                    skipped += batch.size - usable.size

                    for ((title, text) in usable.take(source.pages - taken)) {
                        add(
                            buildJsonObject {
                                put("wiki", source.wiki)
                                put("title", title)
                                put("text", text)
                            },
                        )
                        taken++
                        kept++
                    }
                }
                System.err.println("  ${source.wiki} ns${source.namespace}: $taken")
            }
        }
        System.err.println("kept $kept, skipped $skipped over $MAX_PAGE_BYTES bytes")
    }

    val document = buildJsonObject {
        put(
            "about",
            "Real page text, drawn at random from several wikis, for the round-trip assertion " +
                "to run against offline. Not chosen for anything: the point is wikitext nobody " +
                "wrote with this parser in mind.",
        )
        put("pages", pages)
    }
    return PLAIN.encodeToString(JsonObject.serializer(), document)
}

/** Asks for a batch of random pages in one namespace, with their wikitext. */
private suspend fun randomPages(
    transport: MediaWikiTransport,
    source: Source,
    limit: Int,
): List<Pair<String, String>> {
    val response = transport.call(
        ApiRequest.of(
            "query",
            "generator" to "random",
            "grnnamespace" to source.namespace.toString(),
            "grnlimit" to limit.toString(),
            "prop" to "revisions",
            "rvprop" to "content",
            "rvslots" to "main",
        ),
    ).throwOnError()

    val pages = response["query"]?.jsonObject?.get("pages") as? JsonArray ?: return emptyList()

    return pages.mapNotNull { entry ->
        val page = entry.jsonObject
        val title = page["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val slot = (page["revisions"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonObject
            ?.get("slots")
            ?.jsonObject
            ?.get("main")
            ?.jsonObject
            ?: return@mapNotNull null

        // Anything that is not wikitext is somebody else's parser's problem.
        if (slot["contentmodel"]?.jsonPrimitive?.content != "wikitext") return@mapNotNull null
        val text = slot["content"]?.jsonPrimitive?.content ?: return@mapNotNull null

        title to text
    }
}

private val PLAIN = kotlinx.serialization.json.Json { prettyPrint = false }
