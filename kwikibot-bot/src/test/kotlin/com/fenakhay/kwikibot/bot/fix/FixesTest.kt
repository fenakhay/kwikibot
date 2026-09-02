package com.fenakhay.kwikibot.bot.fix

import com.fenakhay.kwikibot.bot.run.apply
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FixesTest {

    @Test
    fun `runs of spaces inside a line are collapsed`() {
        Fixes.EXTRA_SPACES.apply("a  b   c") shouldBe "a b c"
    }

    @Test
    fun `indentation is not a run of spaces between words`() {
        val before = "  indented\na  b"

        Fixes.EXTRA_SPACES.apply(before) shouldBe "  indented\na b"
    }

    @Test
    fun `a year range gets an en dash and an ordinary hyphen does not`() {
        Fixes.DATE_RANGES.apply("1990-1995 and well-known") shouldBe "1990–1995 and well-known"
    }

    @Test
    fun `three dots become an ellipsis and four do not`() {
        Fixes.ELLIPSIS.apply("wait... and then.... hmm") shouldBe "wait… and then.... hmm"
    }

    @Test
    fun `a fix does not reach inside a template or a link target`() {
        val before = "{{col|en|a  b}} and [[a  b]] but a  b"

        Fixes.EXTRA_SPACES.apply(before) shouldBe "{{col|en|a  b}} and [[a  b]] but a b"
    }

    @Test
    fun `fixes are looked up by name and an unknown one lists the others`() {
        Fixes["ellipsis"] shouldBe Fixes.ELLIPSIS

        val failure =
            assertFailsWith<IllegalArgumentException> {
                Fixes.named(listOf("elipsis"))
            }
        failure.message.orEmpty().contains("ellipsis") shouldBe true
    }

    @Test
    fun `a bot can register its own fix`() {
        Fixes.register(
            Fix(
                name = "test-only",
                description = "for the test",
                replacements = listOf(Replacement(Regex("teh"), "the")),
            )
        )

        Fixes["test-only"]?.apply("teh word") shouldBe "the word"
    }

    @Test
    fun `a fix reports whether it would change anything`() {
        Fixes.ELLIPSIS.wouldChange("nothing here") shouldBe false
        Fixes.ELLIPSIS.wouldChange("wait...") shouldBe true
    }
}
