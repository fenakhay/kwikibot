package com.fenakhay.kwikibot.benchmarks

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import kotlin.test.Test

class QueryBenchmarkTest {

    private val benchmark = QueryBenchmark().apply { load() }

    @Test
    fun `the templates of one name are among all the templates`() {
        val byName = benchmark.templatesByName()

        byName shouldBeGreaterThanOrEqual 1
        benchmark.templates() shouldBeGreaterThanOrEqual byName
    }

    @Test
    fun `walking every node meets every template and every link`() {
        benchmark.allNodes() shouldBeGreaterThanOrEqual benchmark.templates() + benchmark.wikilinks()
    }

    @Test
    fun `the top of each outline is made of headings`() {
        val outline = benchmark.outline()

        outline shouldBeGreaterThan 0
        outline shouldBeLessThanOrEqual benchmark.headings()
    }

    @Test
    fun `the visible text is some of the corpus and not all of it`() {
        val visible = benchmark.visibleText()

        visible shouldBeGreaterThan 0
        visible shouldBeLessThan Corpus.characters
    }
}
