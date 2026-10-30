package com.fenakhay.kwikibot.benchmarks

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.fail

class PathologicalBenchmarkTest {

    private val benchmark = PathologicalBenchmark()

    @Test
    fun `unclosed bold down a template is still the one template`() {
        within(benchmark::unclosedStyleInTemplates) shouldBe 1
    }

    @Test
    fun `nesting that closes is one outer template`() {
        within(benchmark::deeplyNestedTemplates) shouldBe 1
    }

    @Test
    fun `nesting ten thousand deep is parsed and written back whole`() {
        within(benchmark::veryDeeplyNestedTemplates) shouldBe 10_000 * "{{a|}}".length + 1
        within(benchmark::veryDeeplyNestedTags) shouldBe 10_000 * "<div></div>".length + 1
    }

    @Test
    fun `openings that never close, runs and nested links all finish`() {
        listOf(
                benchmark::unclosedTemplates,
                benchmark::unclosedWikilinks,
                benchmark::unclosedTags,
                benchmark::deeplyNestedWikilinks,
                benchmark::unclosedOnOneLine,
                benchmark::bracketRun,
                benchmark::pairingAcrossBlocks,
            )
            .forEach { within(it) shouldBeGreaterThanOrEqual 1 }
    }

    /** What [run] returns, which it must do within [DEADLINE_MILLIS]: one that backtracks never does. */
    private fun within(run: () -> Int): Int {
        var result: Result<Int>? = null

        val worker = Thread { result = runCatching(run) }
        worker.isDaemon = true
        worker.start()
        worker.join(DEADLINE_MILLIS)

        if (worker.isAlive) fail("the benchmark did not return within ${DEADLINE_MILLIS}ms")
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val DEADLINE_MILLIS = 20_000L
    }
}
