package com.fenakhay.kwikibot.benchmarks

import com.fenakhay.kwikibot.wikitext.Wikitext
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

/**
 * Input that invites the tokenizer to backtrack.
 *
 * These are not representative of anything and are not meant to be. They are the shapes that took a parser
 * which had no memory of its failed attempts from linear to exponential, and one of them is real:
 * `{{#ifeq:{{pagename}}|x|'''}}` down a quotation template, fifty of them on `Template:RQ:la:Garigliano`,
 * which did not finish parsing at all.
 *
 * The numbers here matter less than the fact that there are numbers. If a change reintroduces exponential
 * backtracking these do not get slower, they stop returning, and the benchmark run hangs rather than
 * reporting a regression.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
public class PathologicalBenchmark {

    /** The real one: unclosed bold markup inside a template, repeated. */
    @Benchmark
    public fun unclosedStyleInTemplates(): Int =
        Wikitext.parse("{{outer|" + "{{#ifeq:{{pagename}}|a|'''}}".repeat(REPEATS) + "}}").nodes.size

    /** Openings that never close, which is where a parser guesses instead of checking. */
    @Benchmark public fun unclosedTemplates(): Int = Wikitext.parse("{{a|".repeat(REPEATS)).nodes.size

    /** A link opening that never closes. */
    @Benchmark public fun unclosedWikilinks(): Int = Wikitext.parse("[[a|".repeat(REPEATS)).nodes.size

    /** A tag that never closes, which swallows the rest of a page if the parser guesses. */
    @Benchmark public fun unclosedTags(): Int = Wikitext.parse("<ref>".repeat(REPEATS)).nodes.size

    /** Nesting that does close, for the cost of depth on its own. */
    @Benchmark
    public fun deeplyNestedTemplates(): Int =
        Wikitext.parse("{{a|".repeat(DEPTH) + "x" + "}}".repeat(DEPTH)).nodes.size

    /** Links nested as deeply as templates above, for the same reason. */
    @Benchmark
    public fun deeplyNestedWikilinks(): Int =
        Wikitext.parse("[[a|".repeat(DEPTH) + "x" + "]]".repeat(DEPTH)).nodes.size

    /**
     * Templates nested far deeper than any page, parsed and written back: past the depth where every pass
     * over a tree stops recursing, so this is the cost of nesting carried on the heap.
     */
    @Benchmark
    public fun veryDeeplyNestedTemplates(): Int =
        Wikitext.parse("{{a|".repeat(DEEP) + "x" + "}}".repeat(DEEP)).serialize().length

    /** Tags nested as deeply as the templates above, which pair and close at every level. */
    @Benchmark
    public fun veryDeeplyNestedTags(): Int =
        Wikitext.parse("<div>".repeat(DEEP) + "x" + "</div>".repeat(DEEP)).serialize().length

    /**
     * Unclosed openings whose partner the parser searches for along the line: external links, bold, HTML tags
     * and extension tags. Each failed search is remembered; this is where forgetting one would show.
     */
    @Benchmark
    public fun unclosedOnOneLine(): Int =
        Wikitext.parse(
                "[http://example.org a ".repeat(REPEATS) +
                    "''a ".repeat(REPEATS) +
                    "<b>a".repeat(REPEATS) +
                    "<ref a".repeat(REPEATS)
            )
            .nodes
            .size

    /**
     * A run of opening brackets, which MediaWiki's link pass (`Parser::handleInternalLinks2`) pairs from the
     * left and its preprocessor from the right.
     */
    @Benchmark
    public fun bracketRun(): Int = Wikitext.parse("[".repeat(REPEATS) + "a" + "]".repeat(REPEATS)).nodes.size

    /**
     * Tags paired across paragraphs, lists and table cells, where each line's paragraph, item or cell ends
     * what is open in it: spans ended at every blank line, bold run on through a list, cells split by `||`.
     */
    @Benchmark
    public fun pairingAcrossBlocks(): Int =
        Wikitext.parse(
                "<span>a\n\n".repeat(REPEATS) +
                    "<b>x" +
                    "\n* a".repeat(REPEATS) +
                    "\n</b>\n{|\n" +
                    "|<span>a||b</span>\n".repeat(REPEATS) +
                    "|}\n<div>" +
                    "\n\n<i>a".repeat(REPEATS) +
                    "</div>"
            )
            .nodes
            .size

    private companion object {
        /** Well past the point where an exponential parser stops finishing, and instant for a linear one. */
        const val REPEATS = 200

        /** Nesting as deep as a page with templates in templates ever goes, and then some. */
        const val DEPTH = 100

        /** A hundred times deeper, which no recursive parser survives. */
        const val DEEP = 10_000
    }
}
