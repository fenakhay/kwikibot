package com.fenakhay.kwikibot.benchmarks

import com.fenakhay.kwikibot.benchmarks.Allocations.Measurement
import com.fenakhay.kwikibot.wikitext.Markup
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test

class AllocationsTest {

    private val directory = Files.createTempDirectory("kwikibot-allocations")

    @Test
    fun `bytes per unit divide the bytes by what the workload got through, and nothing by none`() {
        Measurement("parse", bytes = 1_000, units = 8).perUnit shouldBe 125.0
        Measurement("parse", bytes = 1_000, units = 0).perUnit shouldBe 0.0
    }

    @Test
    fun `a workload that allocates a megabyte measures as a megabyte`() {
        val measured = Workload("array", units = 1) { ByteArray(MEGABYTE) }.measure()

        measured.name shouldBe "array"
        measured.units shouldBe 1
        measured.bytes shouldBeInRange MEGABYTE.toLong()..MEGABYTE.toLong() + MARGIN
    }

    @Test
    fun `the work runs once per warm-up and once per measured run`() {
        var calls = 0

        Allocations.measure("count", units = 1, warmups = 3, runs = 4) { calls++ }

        calls shouldBe 7
    }

    @Test
    fun `the figure is the median run, which one outlier does not move`() {
        val sizes = intArrayOf(0, 0, 50_000, 1_000, 3_000, 2_000, 4_000)
        var call = 0

        val measured =
            Allocations.measure("median", units = 1, warmups = 2, runs = 5) { ByteArray(sizes[call++]) }

        measured.bytes shouldBeInRange 3_000L..3_000L + MARGIN
    }

    @Test
    fun `the figures are printed as a table and written where the arguments say`() {
        val target = directory.resolve("allocations.json")
        val out = StringBuilder()

        recordAllocations(arrayOf(target.toString()), out) { MEASUREMENTS }

        out.toString() shouldBe TABLE + "\nwritten to $target\n"
        target.readText() shouldBe
            "[\n" +
                "  {\"name\": \"parse\", \"bytes\": 1234567, \"units\": 1000},\n" +
                "  {\"name\": \"empty\", \"bytes\": 5, \"units\": 0}\n" +
                "]\n"
    }

    @Test
    fun `with no file named the figures are only printed`() {
        val out = StringBuilder()

        recordAllocations(emptyArray(), out) { MEASUREMENTS }

        out.toString() shouldBe TABLE
        directory.resolve("allocations.json").exists() shouldBe false
    }

    @Test
    fun `each workload does the work it is named for, over the input it counts`() {
        val workloads = workloads().associateBy { it.name }

        workloads.keys shouldBe setOf("parse", "parseShort", "parseLong", "parseLarge", "roundTrip")
        workloads.getValue("parse").serialized() shouldBe Corpus.pages
        workloads.getValue("parseShort").serialized() shouldBe Corpus.short
        workloads.getValue("parseLong").serialized() shouldBe Corpus.long
        workloads.getValue("parseLarge").serialized() shouldBe Corpus.large
        workloads.getValue("roundTrip").work() shouldBe Corpus.pages
        workloads.mapValues { it.value.units } shouldBe
            mapOf(
                "parse" to 764_152L,
                "parseShort" to 37_859L,
                "parseLong" to 726_293L,
                "parseLarge" to 1_950_246L,
                "roundTrip" to 764_152L,
            )
    }

    /** The pages a parsing workload parsed, written back. */
    private fun Workload.serialized(): List<String> = (work() as List<*>).map { (it as Markup).serialize() }

    private companion object {
        const val MEGABYTE = 1_000_000

        /** What an array's header and the harness may add to the bytes asked for. */
        const val MARGIN = 1_024L

        val MEASUREMENTS = listOf(Measurement("parse", 1_234_567, 1_000), Measurement("empty", 5, 0))

        const val TABLE =
            "workload                bytes     per char\n" +
                "parse               1,234,567      1234.57\n" +
                "empty                       5         0.00\n"
    }
}
