package com.fenakhay.kwikibot.benchmarks

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
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
    fun `each page equals its copy and every pass over it keeps its nodes`() {
        benchmark.equalCopies() shouldBe Corpus.pages.size
        benchmark.mapNodesUnchanged() shouldBe benchmark.mapNodesRebuilding()
        benchmark.ranges() shouldBe Corpus.characters - Corpus.pages.size
    }

    @Test
    fun `the questions about templates and text each find something`() {
        listOf(
                benchmark.rawValues(),
                benchmark.titles(),
                benchmark.replaceTextProse(),
                benchmark.textInEverywhere(),
                benchmark.replaceOneTemplate(),
                benchmark.mapTemplatesWithParameter(),
                benchmark.cosmeticSafe(),
            )
            .forEach { it shouldBeGreaterThan 0 }
    }

    @Test
    fun `the visible text is some of the corpus and not all of it`() {
        val visible = benchmark.visibleText()

        visible shouldBeGreaterThan 0
        visible shouldBeLessThan Corpus.characters
    }
}
