package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.decode.array
import com.fenakhay.kwikibot.protocol.decode.obj
import com.fenakhay.kwikibot.protocol.decode.objects
import com.fenakhay.kwikibot.protocol.decode.string
import com.fenakhay.kwikibot.protocol.throwOnError
import java.util.zip.GZIPOutputStream
import kotlin.io.path.outputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Records real page text for the round-trip assertion to run against offline.
 *
 * The recorded structural corpus in [WikitextCases] says what the parser should make of eighty fragments
 * somebody sat down and thought of. This says it survives the wikitext nobody thought about: whatever the
 * wikis happened to hand over.
 *
 * Round-trip is the one property that needs no external authority to check. `parse(text)` then `serialize()`
 * must give back the same bytes, because a bot rewrites a page by changing one thing in it and writing the
 * whole thing back — anything the parser quietly normalises becomes collateral damage on every page the bot
 * touches.
 *
 * The pages are drawn at random rather than chosen. A case somebody picks is a case somebody was already
 * thinking about, and those are already in [WikitextCases].
 */
private val SOURCES =
    listOf(
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
 * Above this a page is a list or an archive, which is the same few constructs repeated thousands of times. It
 * stresses the repository more than it stresses the parser.
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
    runBlocking { recordRealPages(args, ToolEnvironment.SYSTEM) }
}

/** What [main] does with [args], asking the wikis and printing through [environment]. */
internal suspend fun recordRealPages(args: Array<String>, environment: ToolEnvironment) {
    val target = environment.workingDirectory.resolve(args.firstOrNull() ?: "roundtrip-pages.json.gz")
    val document = record(environment)

    withContext(Dispatchers.IO) {
        GZIPOutputStream(target.outputStream()).bufferedWriter().use { it.write(document) }
    }
    environment.output.appendLine("wrote $target")
}

private suspend fun record(environment: ToolEnvironment): String {
    val userAgent =
        UserAgent(
            "kwikibot-roundtrip-corpus",
            "0.1.0",
            "https://en.wiktionary.org/wiki/User:Fenakhay",
        )

    val pages = buildJsonArray {
        var kept = 0
        var skipped = 0

        for (source in SOURCES) {
            environment.client().use { client ->
                val transport =
                    KtorTransport(
                        client = client,
                        endpoint = ApiEndpoint(server = source.wiki),
                        userAgent = userAgent,
                        // Somebody else's production wiki, and this is recorded once rather than on
                        // every build. There is no hurry.
                        throttle = Throttle(read = 500.milliseconds, timeSource = environment.timeSource),
                        maxlag = null,
                    )

                var taken = 0
                while (taken < source.pages) {
                    val batch = randomPages(transport, source, RANDOM_BATCH)
                    if (batch.isEmpty()) break

                    // In bytes, as the wiki measures a page.
                    val usable = batch.filter { (_, text) -> text.encodeToByteArray().size <= MAX_PAGE_BYTES }
                    skipped += batch.size - usable.size

                    for ((title, text) in usable.take(source.pages - taken)) {
                        add(
                            buildJsonObject {
                                put("wiki", source.wiki)
                                put("title", title)
                                put("text", text)
                            }
                        )
                        taken++
                        kept++
                    }
                }
                environment.log.appendLine("  ${source.wiki} ns${source.namespace}: $taken")
            }
        }
        environment.log.appendLine("kept $kept, skipped $skipped over $MAX_PAGE_BYTES bytes")
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
    val response =
        transport
            .call(
                ApiRequest.of(
                    "query",
                    "generator" to "random",
                    "grnnamespace" to source.namespace.toString(),
                    "grnlimit" to limit.toString(),
                    "prop" to "revisions",
                    "rvprop" to "content",
                    "rvslots" to "main",
                )
            )
            .throwOnError()

    return wikitexts(response)
}

/**
 * The title and text of each page in [response], a query for `prop=revisions` with `rvprop=content` and
 * `rvslots=main`.
 *
 * A page the answer gives no title, no main slot or no content for is left out, and so is one whose content
 * is not wikitext.
 */
internal fun wikitexts(response: JsonObject): List<Pair<String, String>> =
    response.obj("query")?.array("pages").objects().mapNotNull { page ->
        val title = page.string("title") ?: return@mapNotNull null
        val slot =
            page.array("revisions").objects().firstOrNull()?.obj("slots")?.obj("main")
                ?: return@mapNotNull null
        if (slot.string("contentmodel") != "wikitext") return@mapNotNull null
        val text = slot.string("content") ?: return@mapNotNull null

        title to text
    }

private val PLAIN = Json { prettyPrint = false }
