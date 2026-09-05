package com.fenakhay.kwikibot.benchmarks

import com.fenakhay.kwikibot.wikitext.Wikitext
import java.io.File
import java.lang.management.ManagementFactory

/**
 * How many bytes each thing allocates, which the timing benchmarks do not say.
 *
 * kotlinx-benchmark exposes no JMH profiler, so `-prof gc` is out of reach. This asks HotSpot's
 * `ThreadMXBean` instead, which counts a thread's allocation cumulatively and makes bytes per operation a
 * subtraction.
 *
 * Allocation, not heap occupancy: occupancy depends on when the collector last ran, allocation does not.
 */
public object Allocations {

    private val threads =
        ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            ?: error("this JVM does not report per-thread allocation; run it on HotSpot")

    /** What one workload costs, in bytes the JVM was asked for. */
    public data class Measurement(
        /** What was measured. */
        public val name: String,
        /** Bytes allocated per run, less the harness's own cost. */
        public val bytes: Long,
        /** What the workload got through. */
        public val units: Long,
    ) {
        /** Bytes allocated per unit of input. */
        public val perUnit: Double
            get() = if (units == 0L) 0.0 else bytes.toDouble() / units
    }

    /**
     * Runs [work] [runs] times after [warmups] warm-ups, and reports the median allocation with the harness's
     * own cost taken off.
     */
    public fun measure(
        name: String,
        units: Long,
        warmups: Int = 2,
        runs: Int = 5,
        work: () -> Any?,
    ): Measurement {
        repeat(warmups) { keep(work()) }

        val floor = overhead()
        val samples =
            (0 until runs).map {
                val before = threads.currentThreadAllocatedBytes
                keep(work())
                threads.currentThreadAllocatedBytes - before
            }

        val median = samples.sorted()[runs / 2]

        return Measurement(name, (median - floor).coerceAtLeast(0), units)
    }

    /** Bytes the harness allocates on its own, with no workload. */
    private fun overhead(): Long {
        val samples =
            (0 until OVERHEAD_RUNS).map {
                val before = threads.currentThreadAllocatedBytes
                keep(Unit)
                threads.currentThreadAllocatedBytes - before
            }

        return samples.sorted()[OVERHEAD_RUNS / 2]
    }

    /** Keeps a result reachable so the optimiser cannot delete the work that made it. */
    @Volatile private var sink: Any? = null

    private fun keep(value: Any?) {
        sink = value
    }

    private const val OVERHEAD_RUNS = 11
}

/**
 * Measures the workloads worth watching and writes them down for a later run to compare against.
 *
 * Run through `:kwikibot-benchmarks:recordAllocations` or `:kwikibot-benchmarks:compareAllocations`.
 */
public fun main(args: Array<String>) {
    val measurements =
        listOf(
            Allocations.measure("parse", Corpus.characters.toLong()) {
                Corpus.pages.map { Wikitext.parse(it) }
            },
            Allocations.measure("parseShort", Corpus.short.sumOf { it.length }.toLong()) {
                Corpus.short.map { Wikitext.parse(it) }
            },
            Allocations.measure("parseLong", Corpus.long.sumOf { it.length }.toLong()) {
                Corpus.long.map { Wikitext.parse(it) }
            },
            Allocations.measure("parseLarge", Corpus.largeCharacters.toLong()) {
                Corpus.large.map { Wikitext.parse(it) }
            },
            Allocations.measure("roundTrip", Corpus.characters.toLong()) {
                Corpus.pages.map { Wikitext.parse(it).serialize() }
            },
        )

    println("%-14s %14s %12s".format("workload", "bytes", "per char"))
    measurements.forEach { println("%-14s %,14d %12.2f".format(it.name, it.bytes, it.perUnit)) }

    args.firstOrNull()?.let { path ->
        File(path)
            .writeText(
                measurements.joinToString(",\n", "[\n", "\n]\n") { measurement ->
                    """  {"name": "${measurement.name}", "bytes": ${measurement.bytes}, """ +
                        """"units": ${measurement.units}}"""
                }
            )
        println()
        println("written to $path")
    }
}
