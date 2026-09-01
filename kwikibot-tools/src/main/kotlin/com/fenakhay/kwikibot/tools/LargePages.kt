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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.zip.GZIPOutputStream
import kotlin.io.path.Path
import kotlin.io.path.outputStream
import kotlin.time.Duration.Companion.milliseconds

/**
 * Records the biggest pages the wikis have, for the parallel pre-scan to be judged on.
 *
 * The round-trip corpus in [RealPages] deliberately skips anything over 80KB: past that a page is
 * a list or an archive, which is the same few constructs repeated thousands of times, and it
 * stresses the repository more than the parser. That makes it the wrong corpus for one question.
 *
 * Splitting the scan across threads only pays when there is enough text to amortise handing work
 * to another thread, so it runs above a size threshold — and the round-trip corpus has nothing
 * that would reach one. Its largest page is 38KB. Benchmarking the threshold against it would
 * measure the sequential path twice and report a tie.
 *
 * So these are recorded separately and used only by the benchmark. They are not round-tripped and
 * not part of the correctness contract: the pages the parser has to be *right* about are the ones
 * a bot meets, and those are in the other file.
 */
private val SOURCES = listOf(
    // Long lists, discographies and filmographies: templates and table rows by the thousand.
    LargeSource("en.wikipedia.org", namespace = 0),
    // Talk archives, which are prose, signatures and deep indentation instead.
    LargeSource("en.wikipedia.org", namespace = 1),
)

/**
 * Where in the alphabet to start looking.
 *
 * `list=allpages` walks in title order rather than sampling, so asking it once returns whatever
 * sorts first — which on en.wikipedia is a dozen consecutive years of the same election table.
 * Restarting at each of these takes one page from each part of the alphabet instead, which is
 * not a random sample either but is at least a sample of different things.
 *
 * Four rather than more, because eight pages of a quarter of a megabyte already answer the one
 * question these are for, and the file is committed.
 */
private val SEEDS = listOf("A", "F", "L", "S")

/** One wiki and namespace to draw large pages from, one page per seed. */
private data class LargeSource(val wiki: String, val namespace: Int)

/**
 * The smallest page worth recording here.
 *
 * Below this the question the corpus exists to answer — does splitting the scan pay — has an
 * obvious answer, and it is no.
 */
private const val MIN_PAGE_BYTES = 200_000

/**
 * The largest, so the recording stays a test fixture rather than a database.
 *
 * The biggest pages on en.wikipedia run past 2MB, and one of them would outweigh everything else
 * committed here. Anything over 200KB already answers the question these are here for.
 */
private const val MAX_PAGE_BYTES = 400_000

/** What `list=allpages` returns at most in one request for an anonymous client. */
private const val PAGE_BATCH = 20

/**
 * Records the pages and writes them gzipped.
 *
 * The first argument is the file to write.
 */
public fun main(args: Array<String>) {
    val target = Path(args.firstOrNull() ?: "large-pages.json.gz")
    val document = runBlocking { record() }

    GZIPOutputStream(target.outputStream()).bufferedWriter().use { it.write(document) }
    println("wrote $target")
}

private suspend fun record(): String {
    val userAgent = UserAgent(
        "kwikibot-largepage-corpus",
        "0.1.0",
        "https://en.wiktionary.org/wiki/User:Fenakhay",
    )

    var characters = 0L

    val pages = buildJsonArray {
        for (source in SOURCES) {
            WikiHttpClient.create().use { client ->
                val transport = KtorTransport(
                    client = client,
                    endpoint = ApiEndpoint(server = source.wiki),
                    userAgent = userAgent,
                    throttle = Throttle(read = 500.milliseconds),
                    maxlag = null,
                )

                var taken = 0

                for (seed in SEEDS) {
                    val (batch, _) = largePages(transport, source, seed)
                    val (title, text) = batch.firstOrNull() ?: continue

                    add(
                        buildJsonObject {
                            put("wiki", source.wiki)
                            put("title", title)
                            put("text", text)
                        },
                    )
                    taken++
                    characters += text.length
                }
                System.err.println("  ${source.wiki} ns${source.namespace}: $taken")
            }
        }
    }

    System.err.println("$characters characters over ${pages.size} pages")

    val document = buildJsonObject {
        put(
            "about",
            "The biggest pages the wikis have, for benchmarking the parallel pre-scan against " +
                "the sequential one. Not round-tripped and not part of the correctness " +
                "contract: the pages the parser has to be right about are in roundtrip-pages.",
        )
        put("pages", pages)
    }
    return PLAIN.encodeToString(JsonObject.serializer(), document)
}

/**
 * Asks for a batch of pages over [MIN_PAGE_BYTES], with their wikitext.
 *
 * `list=allpages` walks in title order rather than sampling, so [from] carries the continuation
 * and the result is the alphabetically first large pages rather than a random draw. That is a
 * biased sample and it does not matter here: nothing is asserted about these pages, only timed.
 */
private suspend fun largePages(
    transport: MediaWikiTransport,
    source: LargeSource,
    from: String?,
): Pair<List<Pair<String, String>>, String?> {
    val response = transport.call(
        ApiRequest.of(
            "query",
            "generator" to "allpages",
            "gapnamespace" to source.namespace.toString(),
            "gapminsize" to MIN_PAGE_BYTES.toString(),
            "gaplimit" to PAGE_BATCH.toString(),
            "gapfilterredir" to "nonredirects",
            *(from?.let { arrayOf("gapfrom" to it) } ?: emptyArray()),
            "prop" to "revisions",
            "rvprop" to "content",
            "rvslots" to "main",
        ),
    ).throwOnError()

    val next = response["continue"]?.jsonObject?.get("gapcontinue")?.jsonPrimitive?.content
    val pages = response["query"]?.jsonObject?.get("pages") as? JsonArray
        ?: return emptyList<Pair<String, String>>() to next

    val usable = pages.mapNotNull { entry ->
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

        if (slot["contentmodel"]?.jsonPrimitive?.content != "wikitext") return@mapNotNull null
        val text = slot["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
        if (text.length !in MIN_PAGE_BYTES..MAX_PAGE_BYTES) return@mapNotNull null

        title to text
    }

    return usable to next
}

private val PLAIN = kotlinx.serialization.json.Json { prettyPrint = false }
