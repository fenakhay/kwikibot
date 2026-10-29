package com.fenakhay.kwikibot.benchmarks

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CorpusTest {

    @Test
    fun `the corpus is the 230 round-trip pages shortest first`() {
        Corpus.pages.size shouldBe 230
        Corpus.pages.map { it.length } shouldBe Corpus.pages.map { it.length }.sorted()
        Corpus.characters shouldBe 764_152
    }

    @Test
    fun `the short and the long half are the corpus split down the middle`() {
        Corpus.short + Corpus.long shouldBe Corpus.pages
        Corpus.short.size shouldBe 115
        Corpus.long.size shouldBe 115
        Corpus.short.sumOf { it.length } shouldBe 37_859
        Corpus.long.sumOf { it.length } shouldBe 726_293
    }

    @Test
    fun `the large pages are eight and each is longer than any round-trip page`() {
        Corpus.large.size shouldBe 8
        Corpus.largeCharacters shouldBe 1_950_246
        Corpus.large.minOf { it.length } shouldBeGreaterThan Corpus.pages.last().length
    }

    @Test
    fun `a corpus the build did not unpack says how to record it`() {
        val error = assertFailsWith<IllegalStateException> { Corpus.load("missing-pages.json") }

        error.message shouldBe "missing-pages.json missing: run the recorder in :kwikibot-tools"
    }
}
