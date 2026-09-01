package com.fenakhay.kwikibot.benchmarks

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import java.util.concurrent.TimeUnit

/**
 * What parsing a page costs, measured over real wikitext.
 *
 * Recorded before the tokenizer was rewritten so that the rewrite has something to be compared
 * against, and kept afterwards for the same reason: a parser that quietly becomes twice as slow
 * costs a bot that walks a category exactly twice as long, and nothing else in the build notices.
 *
 * One operation is a pass over the whole corpus, not one page. Per-page numbers at this size are
 * dominated by the measurement, and the figure a bot author cares about is the rate — divide
 * [Corpus.characters] by the time to get it.
 *
 * The stages are separated because they fail differently. Parsing is where the algorithm lives
 * and where a pathological page shows up first; serializing is the one that has to be cheap,
 * since every edit ends with it. The tokenizer is no longer measured on its own: it is internal,
 * and a benchmark is a poor reason to publish something.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ParserBenchmark {

    private lateinit var pages: List<String>
    private lateinit var shortPages: List<String>
    private lateinit var longPages: List<String>
    private lateinit var parsed: List<Markup>
    private lateinit var largePages: List<String>

    /** Reads the corpus and pre-parses it, so the timed methods measure only themselves. */
    @Setup
    public fun load() {
        pages = Corpus.pages
        shortPages = Corpus.short
        longPages = Corpus.long
        largePages = Corpus.large
        parsed = pages.map { Wikitext.parse(it) }
    }

    /** Tokenizing and building the node tree, which is what callers actually do. */
    @Benchmark
    public fun parse(): Int = pages.sumOf { Wikitext.parse(it).nodes.size }

    /** Writing a parsed page back out. Every edit ends with this. */
    @Benchmark
    public fun serialize(): Int = parsed.sumOf { it.serialize().length }

    /** Read, then write back unchanged: the shape of every no-op pass a bot makes. */
    @Benchmark
    public fun roundTrip(): Int = pages.sumOf { Wikitext.parse(it).serialize().length }

    /** Entries and template pages, where the per-page cost is what a category walk feels. */
    @Benchmark
    public fun parseShortPages(): Int = shortPages.sumOf { Wikitext.parse(it).nodes.size }

    /** Articles, where the cost of the text itself dominates. */
    @Benchmark
    public fun parseLongPages(): Int = longPages.sumOf { Wikitext.parse(it).nodes.size }

    /**
     * The eight biggest pages, 1.95M characters between them.
     *
     * The only benchmark large enough for splitting the scan across threads to be worth asking
     * about, and the one the parallel path has to beat by a clear margin to be worth keeping.
     */
    @Benchmark
    public fun parseLargePages(): Int = largePages.sumOf { Wikitext.parse(it).nodes.size }
}
