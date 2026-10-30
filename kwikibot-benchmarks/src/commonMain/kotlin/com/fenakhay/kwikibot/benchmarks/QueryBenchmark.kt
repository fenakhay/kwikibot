package com.fenakhay.kwikibot.benchmarks

import com.fenakhay.kwikibot.wikitext.CosmeticChanges
import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.TextScope
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.ops.mapNodes
import com.fenakhay.kwikibot.wikitext.ops.outline
import com.fenakhay.kwikibot.wikitext.ops.replaceText
import com.fenakhay.kwikibot.wikitext.ops.textIn
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
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
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
public class QueryBenchmark {

    private lateinit var parsed: List<Markup>
    private lateinit var copies: List<Markup>
    private lateinit var templates: List<List<Template>>

    /** Parses the corpus once, so these measure the questions rather than the parse. */
    @Setup
    public fun load() {
        parsed = Corpus.pages.map { Wikitext.parse(it) }
        copies = Corpus.pages.map { Wikitext.parse(it) }
        templates = parsed.map { it.templates() }
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

    /** Where every node, parameter and attribute sits in the text, which an edit is spliced by. */
    @Benchmark public fun ranges(): Int = parsed.sumOf { it.ranges()[it]?.last ?: 0 }

    /** Every parameter's value as written, which serializes a little markup many times over. */
    @Benchmark
    public fun rawValues(): Int = templates.sumOf { page ->
        page.sumOf { t -> t.parameters.sumOf { it.rawValue.length } }
    }

    /** Every template's name as MediaWiki compares it. */
    @Benchmark public fun titles(): Int = templates.sumOf { page -> page.sumOf { it.title.length } }

    /** A pass over every node that changes none of them. */
    @Benchmark public fun mapNodesUnchanged(): Int = parsed.sumOf { it.mapNodes { node -> node }.nodes.size }

    /** A pass over every node that rebuilds each piece of text. */
    @Benchmark
    public fun mapNodesRebuilding(): Int = parsed.sumOf { markup ->
        markup.mapNodes { node -> if (node is TextNode) TextNode(node.text) else node }.nodes.size
    }

    /** Replacing a common word in prose, the commonest text edit. */
    @Benchmark public fun replaceTextProse(): Int = parsed.sumOf { it.replaceText(WORD, "a").nodes.size }

    /** Every piece of text, markup's included, that a text search reads. */
    @Benchmark public fun textInEverywhere(): Int = parsed.sumOf { it.textIn(TextScope.EVERYWHERE).size }

    /** Comparing each page with a separate parse of the same text. */
    @Benchmark public fun equalCopies(): Int = parsed.indices.count { parsed[it] == copies[it] }

    /** Replacing the first template on each page. */
    @Benchmark
    public fun replaceOneTemplate(): Int =
        parsed.indices.sumOf { page ->
            templates[page].firstOrNull()?.let { parsed[page].replace(it, REPLACEMENT).nodes.size } ?: 0
        }

    /** Adding a parameter to every template of one name, the shape of most bot edits. */
    @Benchmark
    public fun mapTemplatesWithParameter(): Int = parsed.sumOf { markup ->
        markup.mapTemplates("col") { it.withParameter("sort", "x") }.nodes.size
    }

    /** The tidying passes that change nothing a reader sees. */
    @Benchmark public fun cosmeticSafe(): Int = parsed.sumOf { CosmeticChanges.SAFE.apply(it).nodes.size }

    private companion object {
        val WORD = Regex("\\bthe\\b")
        val REPLACEMENT = TextNode("x")
    }
}
