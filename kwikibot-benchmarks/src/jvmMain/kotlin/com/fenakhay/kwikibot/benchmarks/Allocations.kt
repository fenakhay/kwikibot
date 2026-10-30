package com.fenakhay.kwikibot.benchmarks

import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.ops.mapNodes
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * How many bytes each thing allocates, which the timing benchmarks do not say.
 *
 * kotlinx-benchmark exposes no JMH profiler, so `-prof gc` is out of reach. This asks HotSpot's
 * `ThreadMXBean` instead, which counts a thread's allocation cumulatively and makes bytes per operation a
 * subtraction. A JVM without it fails here, at the first measurement.
 *
 * Allocation, not heap occupancy: occupancy depends on when the collector last ran, allocation does not.
 */
public object Allocations {

    private val threads = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean::class.java)

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

/** Something worth measuring: what it is called, how much input it gets through, and the work itself. */
internal class Workload(val name: String, val units: Long, val work: () -> Any?) {

    /** What [work] allocates, by [Allocations.measure]. */
    fun measure(): Allocations.Measurement = Allocations.measure(name, units, work = work)
}

/** The workloads worth watching, each over the corpus it is named for, counted in characters. */
internal fun workloads(): List<Workload> {
    val parsed by lazy { Corpus.pages.map { Wikitext.parse(it) } }
    return listOf(
        Workload("parse", Corpus.characters.toLong()) { Corpus.pages.map { Wikitext.parse(it) } },
        Workload("parseShort", Corpus.short.sumOf { it.length }.toLong()) {
            Corpus.short.map { Wikitext.parse(it) }
        },
        Workload("parseLong", Corpus.long.sumOf { it.length }.toLong()) {
            Corpus.long.map { Wikitext.parse(it) }
        },
        Workload("parseLarge", Corpus.largeCharacters.toLong()) { Corpus.large.map { Wikitext.parse(it) } },
        Workload("roundTrip", Corpus.characters.toLong()) {
            Corpus.pages.map { Wikitext.parse(it).serialize() }
        },
        Workload("serialize", Corpus.characters.toLong()) { parsed.map { it.serialize() } },
        Workload("ranges", Corpus.characters.toLong()) { parsed.map { it.ranges() } },
        Workload("mapNodes", Corpus.characters.toLong()) { parsed.map { it.mapNodes { node -> node } } },
    )
}

/**
 * Measures the workloads worth watching and writes them down for a later run to compare against.
 *
 * Run through `:kwikibot-benchmarks:measureAllocations`, which prints the figures, or
 * `:kwikibot-benchmarks:recordAllocations`, which also writes them over `allocations.json`.
 */
public fun main(args: Array<String>) {
    recordAllocations(args, System.out) { workloads().map { it.measure() } }
}

/**
 * What [main] does with [args]: prints [measurements] to [out] as a table, and when [args] names a file,
 * writes them there as JSON.
 */
internal fun recordAllocations(
    args: Array<String>,
    out: Appendable,
    measurements: () -> List<Allocations.Measurement>,
) {
    val measured = measurements()

    out.appendLine("%-14s %14s %12s".format(Locale.ROOT, "workload", "bytes", "per char"))
    measured.forEach {
        out.appendLine("%-14s %,14d %12.2f".format(Locale.ROOT, it.name, it.bytes, it.perUnit))
    }

    args.firstOrNull()?.let { path ->
        File(path)
            .writeText(
                measured.joinToString(",\n", "[\n", "\n]\n") { measurement ->
                    """  {"name": "${measurement.name}", "bytes": ${measurement.bytes}, """ +
                        """"units": ${measurement.units}}"""
                }
            )
        out.appendLine()
        out.appendLine("written to $path")
    }
}
