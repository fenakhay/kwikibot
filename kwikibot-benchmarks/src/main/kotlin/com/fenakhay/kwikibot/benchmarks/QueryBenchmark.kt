package com.fenakhay.kwikibot.benchmarks

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.ops.outline
import java.util.concurrent.TimeUnit
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * What asking a parsed page a question costs.
 *
 * Separate from [ParserBenchmark] because the pages are parsed once in setup: these measure the queries, not
 * the parse. A bot parses a page once and then asks it several things, so this is the part that multiplies.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class QueryBenchmark {

    private lateinit var parsed: List<Markup>

    /** Parses the corpus once, so these measure the questions rather than the parse. */
    @Setup
    public fun load() {
        parsed = Corpus.pages.map { Wikitext.parse(it) }
    }

    /** Every template on the page, the commonest question a bot asks. */
    @Benchmark public fun templates(): Int = parsed.sumOf { it.templates().size }

    /** Templates of one name, which is how a bot finds the one it maintains. */
    @Benchmark public fun templatesByName(): Int = parsed.sumOf { it.templates("col").size }

    /** Every wikilink, which is what a link-maintenance bot walks. */
    @Benchmark public fun wikilinks(): Int = parsed.sumOf { it.wikilinks().size }

    /** Every heading, the flat form of the section tree. */
    @Benchmark public fun headings(): Int = parsed.sumOf { it.headings().size }

    /** Walking every node, which every unindexed question falls back to. */
    @Benchmark public fun allNodes(): Int = parsed.sumOf { markup -> markup.allNodes().count() }

    /** The section tree: what an entry-layout bot navigates before it can place anything. */
    @Benchmark public fun outline(): Int = parsed.sumOf { it.outline().subsections.size }

    /** Markup stripped to the text a reader sees. */
    @Benchmark public fun visibleText(): Int = parsed.sumOf { it.text.length }
}
