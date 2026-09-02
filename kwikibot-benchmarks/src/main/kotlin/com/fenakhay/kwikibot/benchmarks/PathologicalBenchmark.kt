package com.fenakhay.kwikibot.benchmarks

import com.fenakhay.kwikibot.wikitext.Wikitext
import java.util.concurrent.TimeUnit
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
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
@OutputTimeUnit(TimeUnit.MILLISECONDS)
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

    private companion object {
        /** Well past the point where an exponential parser stops finishing, and instant for a linear one. */
        const val REPEATS = 200

        /** Kept lower, since these recurse and the stack is the limit rather than the clock. */
        const val DEPTH = 100
    }
}
