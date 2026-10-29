package com.fenakhay.kwikibot.benchmarks

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test

class CompareTest {

    private val directory = Files.createTempDirectory("kwikibot-compare")

    /** What one comparison printed, and the status it would exit with. */
    private class Outcome(val status: Int, val out: String, val err: String) {
        /** The rows of the table, after its header and rule and before the summary. */
        val rows: List<String>
            get() = out.lines().drop(2).takeWhile { it.isNotEmpty() }
    }

    private fun run(vararg args: String): Outcome {
        val out = StringBuilder()
        val err = StringBuilder()
        val status = compare(arrayOf(*args), out, err)
        return Outcome(status, out.toString(), err.toString())
    }

    private fun compareReports(baseline: List<String>, current: List<String>): Outcome =
        run(report("baseline.json", baseline), report("current.json", current))

    /** A JMH report holding [results], written to [file]. */
    private fun report(file: String, results: List<String>): String {
        val path = directory.resolve(file)
        path.writeText(results.joinToString(",\n", "[\n", "\n]\n"))
        return path.toString()
    }

    /** One benchmark's entry in a JMH report, [error] as the JSON JMH writes it. */
    private fun result(name: String, score: Double, error: String = "0.1"): String =
        """{"benchmark": "com.fenakhay.kwikibot.benchmarks.$name", "mode": "avgt", """ +
            """"primaryMetric": {"score": $score, "scoreError": $error, "scoreUnit": "ms/op"}}"""

    @Test
    fun `the wrong number of arguments prints the usage and nothing else`() {
        val outcome = run("only-one.json")

        outcome.status shouldBe 2
        outcome.err shouldBe "usage: compare <baseline.json> <current.json>\n"
        outcome.out shouldBe ""
    }

    @Test
    fun `a slowdown clear of the error bars and the noise is a regression`() {
        val outcome =
            compareReports(
                listOf(result("ParserBenchmark.parse", 10.0)),
                listOf(result("ParserBenchmark.parse", 12.0)),
            )

        outcome.status shouldBe 1
        outcome.out shouldBe
            "benchmark".padEnd(NAME_WIDTH) +
                " baseline (ms/op) current (ms/op)     change\n" +
                "-".repeat(90) +
                "\n" +
                "ParserBenchmark.parse".padEnd(NAME_WIDTH) +
                "       10.000       12.000     +20.0% SLOWER\n" +
                "\n" +
                "1 benchmark(s) got slower by more than the error bars\n"
    }

    @Test
    fun `a speed-up is reported and passes`() {
        val outcome = compareReports(listOf(result("a", 12.0)), listOf(result("a", 10.0)))

        outcome.status shouldBe 0
        outcome.rows.single() shouldBe "a".padEnd(NAME_WIDTH) + "       12.000       10.000     -16.7% faster"
        outcome.out.lines().last { it.isNotEmpty() } shouldBe "nothing got measurably slower"
    }

    @Test
    fun `a difference inside the error bars is no change`() {
        val outcome = compareReports(listOf(result("a", 10.0, "1.0")), listOf(result("a", 10.5, "1.0")))

        outcome.status shouldBe 0
        outcome.rows.single() shouldBe "a".padEnd(NAME_WIDTH) + "       10.000       10.500      +5.0% same"
    }

    @Test
    fun `a real difference under the noise floor is no change`() {
        val outcome = compareReports(listOf(result("a", 10.0, "0.01")), listOf(result("a", 10.2, "0.01")))

        outcome.status shouldBe 0
        outcome.rows.single() shouldBe "a".padEnd(NAME_WIDTH) + "       10.000       10.200      +2.0% same"
    }

    @Test
    fun `a benchmark in only one of the runs is new or gone`() {
        val outcome = compareReports(listOf(result("gone", 1.5)), listOf(result("new", 2.25)))

        outcome.status shouldBe 0
        outcome.rows shouldBe
            listOf(
                "gone".padEnd(NAME_WIDTH) + "        1.500            -       gone",
                "new".padEnd(NAME_WIDTH) + "            -        2.250        new",
            )
    }

    @Test
    fun `an error JMH could not estimate counts as none`() {
        val outcome =
            compareReports(listOf(result("a", 10.0, "\"NaN\"")), listOf(result("a", 10.5, "\"NaN\"")))

        outcome.status shouldBe 1
        outcome.rows.single() shouldBe "a".padEnd(NAME_WIDTH) + "       10.000       10.500      +5.0% SLOWER"
    }

    @Test
    fun `rows are named without the package and sorted`() {
        val outcome =
            compareReports(
                listOf(result("QueryBenchmark.templates", 1.0), result("ParserBenchmark.parse", 1.0)),
                listOf(result("ParserBenchmark.parse", 1.0), result("QueryBenchmark.templates", 1.0)),
            )

        outcome.rows.map { it.substringBefore(' ') } shouldBe
            listOf("ParserBenchmark.parse", "QueryBenchmark.templates")
        outcome.out shouldNotContain "com.fenakhay"
    }

    @Test
    fun `two empty reports have nothing to compare and no unit to name`() {
        val outcome = compareReports(emptyList(), emptyList())

        outcome.status shouldBe 0
        outcome.out.lines().first() shouldBe
            "benchmark".padEnd(NAME_WIDTH) + " baseline (?)  current (?)     change"
        outcome.rows shouldBe emptyList()
    }

    @Test
    fun `the committed baseline is the same as itself`() {
        val outcome = run("baseline.json", "baseline.json")

        outcome.status shouldBe 0
        outcome.rows shouldHaveSize 22
        outcome.rows.forEach { it.substringAfterLast(' ') shouldBe "same" }
    }

    private companion object {
        /** The width of the benchmark column. */
        const val NAME_WIDTH = 52
    }
}
