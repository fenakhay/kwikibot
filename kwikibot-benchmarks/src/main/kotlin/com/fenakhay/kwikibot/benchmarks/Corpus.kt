package com.fenakhay.kwikibot.benchmarks

import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The wikitext the benchmarks run over.
 *
 * The same 230 pages the round-trip test replays, drawn at random from eight wikis and namespaces.
 * Benchmarking a parser on input somebody chose for it measures the choosing; this is the mix a bot actually
 * meets, in the proportions it meets it.
 */
internal object Corpus {

    /** Every recorded page, largest last. */
    val pages: List<String> by lazy { load(RESOURCE) }

    /**
     * The short half: dictionary entries and template pages, a few hundred characters each.
     *
     * Split from the long half because the two say different things. A bot walking a category parses
     * thousands of these, so their fixed cost per page is what it feels; an article is one page where the
     * cost of the text itself dominates.
     */
    val short: List<String> by lazy { pages.take(pages.size / 2) }

    /** The long half: articles, references and infoboxes. */
    val long: List<String> by lazy { pages.drop(pages.size / 2) }

    /** Total characters, for turning a time per pass into a rate. */
    val characters: Int by lazy { pages.sumOf { it.length } }

    /**
     * The eight biggest pages, 1.95M characters, recorded separately.
     *
     * These exist for one question: whether splitting the scan across threads pays. Nothing in [pages] is
     * large enough to ask it — the round-trip corpus skips anything over 80KB and its largest is 38KB, so a
     * threshold benchmarked against it would time the sequential path twice and report a tie.
     *
     * Not part of the correctness contract. The pages the parser has to be *right* about are the ones a bot
     * meets, and those are the other list.
     */
    val large: List<String> by lazy { load(LARGE_RESOURCE) }

    /** Total characters across [large]. */
    val largeCharacters: Int by lazy { large.sumOf { it.length } }

    private fun load(resource: String): List<String> {
        val stream =
            checkNotNull(Corpus::class.java.getResourceAsStream(resource)) {
                "$resource missing: run the recorder in :kwikibot-tools"
            }
        return Json.parseToJsonElement(GZIPInputStream(stream).reader().readText())
            .jsonObject["pages"]!!
            .jsonArray
            .map { it.jsonObject["text"]!!.jsonPrimitive.content }
            .sortedBy { it.length }
    }

    private const val RESOURCE = "/roundtrip-pages.json.gz"

    private const val LARGE_RESOURCE = "/large-pages.json.gz"
}
