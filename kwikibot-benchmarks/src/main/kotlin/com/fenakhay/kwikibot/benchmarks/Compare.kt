package com.fenakhay.kwikibot.benchmarks

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
    if (args.size != 2) {
        System.err.println(USAGE)
        exitProcess(2)
    }

    val baseline = read(args[0])
    val current = read(args[1])

    val names = (baseline.keys + current.keys).sorted()
    var regressions = 0

    // Every benchmark here is an average in the same unit, so naming it once in the header is
    // enough; a mixed-mode report would need it per row.
    val unit = (baseline.values + current.values).firstOrNull()?.unit ?: "?"

    println("%-52s %12s %12s %10s".format("benchmark", "baseline ($unit)", "current ($unit)", "change"))
    println("-".repeat(RULE_WIDTH))

    for (name in names) {
        val before = baseline[name]
        val after = current[name]

        when {
            before == null -> println("%-52s %12s %12.3f %10s".format(name, "-", after!!.score, "new"))
            after == null -> println("%-52s %12.3f %12s %10s".format(name, before.score, "-", "gone"))

            else -> {
                val change = (after.score - before.score) / before.score * PERCENT
                val separated = abs(after.score - before.score) > before.error + after.error
                val verdict =
                    when {
                        !separated || abs(change) < NOISE_FLOOR_PERCENT -> "same"
                        change > 0 -> "SLOWER".also { regressions++ }
                        else -> "faster"
                    }
                println(
                    "%-52s %12.3f %12.3f %+9.1f%% %s".format(name, before.score, after.score, change, verdict)
                )
            }
        }
    }

    println()
    println(
        if (regressions == 0) {
            "nothing got measurably slower"
        } else {
            "$regressions benchmark(s) got slower by more than the error bars"
        }
    )

    // These are averages: lower is better, so a rise is the regression.
    if (regressions > 0) exitProcess(1)
}

/** Reads a JMH JSON report into scores by benchmark name, with the package prefix dropped. */
private fun read(path: String): Map<String, Result> =
    Json.parseToJsonElement(Path(path).readText()).jsonArray.associate { element ->
        val entry = element.jsonObject
        val metric = entry["primaryMetric"]!!.jsonObject
        val name = entry["benchmark"]!!.jsonPrimitive.content.removePrefix(PACKAGE_PREFIX)

        name to
            Result(
                score = metric["score"]!!.jsonPrimitive.double,
                // JMH reports NaN when a run had too few iterations to say anything about spread.
                error = metric["scoreError"]!!.jsonPrimitive.double.takeIf { it.isFinite() } ?: 0.0,
                unit = metric["scoreUnit"]!!.jsonPrimitive.content,
            )
    }

private const val PACKAGE_PREFIX = "com.fenakhay.kwikibot.benchmarks."

private const val PERCENT = 100.0

/** Wide enough for the longest benchmark name plus the three columns after it. */
private const val RULE_WIDTH = 90
