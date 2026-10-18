package com.fenakhay.kwikibot.benchmarks

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ParserBenchmarkTest {

    private val benchmark = ParserBenchmark().apply { load() }

    @Test
    fun `a round trip and a serialization each write back every character of the corpus`() {
        benchmark.roundTrip() shouldBe Corpus.characters
        benchmark.serialize() shouldBe Corpus.characters
    }

    @Test
    fun `parsing the corpus is parsing its short half and its long half`() {
        benchmark.parse() shouldBe benchmark.parseShortPages() + benchmark.parseLongPages()
    }

    @Test
    fun `every large page parses into nodes`() {
        benchmark.parseLargePages() shouldBeGreaterThanOrEqual Corpus.large.size
    }
}
