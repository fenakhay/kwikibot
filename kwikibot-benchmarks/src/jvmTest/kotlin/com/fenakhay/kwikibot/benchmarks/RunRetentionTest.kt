package com.fenakhay.kwikibot.benchmarks

import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class RunRetentionTest {

    private fun retention(vararg readings: Long): List<String> {
        val heap = ArrayDeque(readings.toList())
        val out = StringBuilder()

        measureRetention(pages = 3, sentences = 2, out) { heap.removeFirst() }

        heap shouldBe ArrayDeque()
        return out.lines()
    }

    @Test
    fun `what the report holds is the heap it gives back when dropped`() {
        retention(10 * MEGABYTE, 4 * MEGABYTE) shouldBe
            listOf(
                "pages: 3, 108 chars each, 0 MB of text",
                "run: processed=3 saved=0 pending=3 skipped=0 refused=0 failed=0",
                "",
                "heap with the report held:    10,240 KB",
                "heap with the report dropped: 4,096 KB",
                "the report was holding:       6,144 KB",
                "",
            )
    }

    @Test
    fun `a heap that grew after the report was dropped says it held nothing`() {
        retention(4 * MEGABYTE, 10 * MEGABYTE).last { it.isNotEmpty() } shouldBe
            "the report was holding:       0 KB"
    }

    @Test
    fun `the heap in use is read after the collector has run`() {
        heapInUse() shouldBeGreaterThan 0
    }

    private companion object {
        const val MEGABYTE = 1_048_576L
    }
}
