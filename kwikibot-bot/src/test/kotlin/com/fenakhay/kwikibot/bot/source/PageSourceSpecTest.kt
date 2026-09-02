package com.fenakhay.kwikibot.bot.source

import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PageSourceSpecTest {

    @Test
    fun `every advertised kind parses`() {
        PageSourceSpec.kinds.forEach { kind ->
            PageSourceSpec.parse("$kind:argument")
        }
    }

    @Test
    fun `a spec with no kind says what one looks like`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PageSourceSpec.parse("English lemmas")
            }

        failure.message.orEmpty() shouldContain "kind:argument"
    }

    @Test
    fun `an unknown kind lists the ones that exist`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PageSourceSpec.parse("categories:English lemmas")
            }

        failure.message.orEmpty() shouldContain "cat"
        failure.message.orEmpty() shouldContain "transcludes"
    }

    @Test
    fun `a kind that needs an argument refuses to run without one`() {
        assertFailsWith<IllegalArgumentException> { PageSourceSpec.parse("cat:") }
        assertFailsWith<IllegalArgumentException> { PageSourceSpec.parse("page:") }
    }

    @Test
    fun `the two kinds that mean something on their own do not need an argument`() {
        PageSourceSpec.parse("allpages:")
        PageSourceSpec.parse("recentchanges:")
    }

    @Test
    fun `only the first colon separates the kind from its argument`() {
        PageSourceSpec.parse("transcludes:Template:col")

        assertFailsWith<IllegalArgumentException> { PageSourceSpec.parse("Template:col:x") }
    }

    @Test
    fun `the kind is read case-insensitively`() {
        PageSourceSpec.parse("CAT:English lemmas")
        PageSourceSpec.parse("Page:volcano")
    }

    @Test
    fun `no sources at all is an empty source rather than an error`() {
        PageSourceSpec.parseAll(emptyList())
    }
}
