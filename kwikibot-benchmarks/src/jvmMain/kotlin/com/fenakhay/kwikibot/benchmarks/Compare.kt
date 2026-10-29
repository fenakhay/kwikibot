package com.fenakhay.kwikibot.benchmarks

import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What to print when the arguments are wrong. */
private const val USAGE = "usage: compare <baseline.json> <current.json>"

/** Below this, a difference is not worth printing even when the error bars agree it is real. */
private const val NOISE_FLOOR_PERCENT = 3.0

/** One benchmark's result: the score, and how far JMH thinks it could be out. */
private data class Result(val score: Double, val error: Double, val unit: String)

/**
 * Prints what changed between two benchmark runs, named on the command line.
 *
 * A JMH report on its own says how fast something was on one laptop on one afternoon, which is not a fact
 * anybody needs. What is worth knowing is the difference between two runs, and whether it is bigger than the
 * noise — so a change is only called out when the two error bars do not overlap and the gap clears
 * [NOISE_FLOOR_PERCENT]. Everything else is reported as unchanged, however different the numbers look.
 *
 * Both runs have to come from the same machine, and preferably the same afternoon. A baseline recorded on
 * other hardware measures the hardware.
 *
 * Exits non-zero when something got slower, so this can gate a change rather than only describe one.
 */
public fun main(args: Array<String>) {
    exitProcess(compare(args, System.out, System.err))
}

/**
 * What [main] does with [args], printing the table to [out] and a usage line to [err]. Returns the status to
 * exit with: 2 when the arguments are wrong, 1 when something got slower, 0 otherwise.
 */
internal fun compare(args: Array<String>, out: Appendable, err: Appendable): Int {
    if (args.size != 2) {
        err.appendLine(USAGE)
        return 2
    }

    val baseline = read(args[0])
    val current = read(args[1])

    val names = (baseline.keys + current.keys).sorted()
    var regressions = 0

    // Every benchmark here is an average in the same unit, so naming it once in the header is
    // enough; a mixed-mode report would need it per row.
    val results = baseline.values + current.values
    val unit = if (results.isEmpty()) "?" else results.first().unit

    out.appendLine(row("%-52s %12s %12s %10s", "benchmark", "baseline ($unit)", "current ($unit)", "change"))
    out.appendLine("-".repeat(RULE_WIDTH))

    for (name in names) {
        val before = baseline[name]
        val after = current[name]

        when {
            before == null ->
                out.appendLine(row("%-52s %12s %12.3f %10s", name, "-", current.getValue(name).score, "new"))
            after == null -> out.appendLine(row("%-52s %12.3f %12s %10s", name, before.score, "-", "gone"))

            else -> {
                val change = (after.score - before.score) / before.score * PERCENT
                val separated = abs(after.score - before.score) > before.error + after.error
                val verdict =
                    when {
                        !separated || abs(change) < NOISE_FLOOR_PERCENT -> "same"
                        change > 0 -> "SLOWER".also { regressions++ }
                        else -> "faster"
                    }
                out.appendLine(
                    row("%-52s %12.3f %12.3f %+9.1f%% %s", name, before.score, after.score, change, verdict)
                )
            }
        }
    }

    out.appendLine()
    out.appendLine(
        if (regressions == 0) {
            "nothing got measurably slower"
        } else {
            "$regressions benchmark(s) got slower by more than the error bars"
        }
    )

    // These are averages: lower is better, so a rise is the regression.
    return if (regressions > 0) 1 else 0
}

/** One line of the table, with the decimal point the same on every machine. */
private fun row(format: String, vararg values: Any): String = format.format(Locale.ROOT, *values)

/** Reads a JMH JSON report into scores by benchmark name, with the package prefix dropped. */
private fun read(path: String): Map<String, Result> =
    Json.parseToJsonElement(Path(path).readText()).jsonArray.associate { element ->
        val entry = element.jsonObject
        val metric = entry.getValue("primaryMetric").jsonObject
        val name = entry.getValue("benchmark").jsonPrimitive.content.removePrefix(PACKAGE_PREFIX)

        name to
            Result(
                score = metric.getValue("score").jsonPrimitive.double,
                // JMH reports NaN when a run had too few iterations to say anything about spread.
                error = metric.getValue("scoreError").jsonPrimitive.double.takeIf { it.isFinite() } ?: 0.0,
                unit = metric.getValue("scoreUnit").jsonPrimitive.content,
            )
    }

private const val PACKAGE_PREFIX = "com.fenakhay.kwikibot.benchmarks."

private const val PERCENT = 100.0

/** Wide enough for the longest benchmark name plus the three columns after it. */
private const val RULE_WIDTH = 90
