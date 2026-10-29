package com.fenakhay.kwikibot.bot.fix

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PluralFormsTest {

    private fun forms(language: String, vararg counts: Long) = counts.map { PluralForms.index(it, language) }

    @Test
    fun `every rule CLDR ships reads as a condition`() {
        CARDINAL_RULES.values.flatten().forEach { (_, condition) -> PluralCondition.parse(condition) }
    }

    @Test
    fun `english has one form for one and another for everything else`() {
        forms("en", 0, 1, 2, 21) shouldBe listOf(1, 0, 1, 1)
    }

    @Test
    fun `a category only a fraction reaches is still counted before other`() {
        forms("lt", 1, 2, 9, 11, 21) shouldBe listOf(0, 1, 1, 3, 0)
    }

    @Test
    fun `a million takes the many form french and galician keep for millions`() {
        forms("fr", 0, 1, 2, 1_000_000, 2_000_001) shouldBe listOf(0, 0, 2, 1, 2)
        forms("gl", 1, 2, 1_000_000) shouldBe listOf(0, 2, 1)
    }

    @Test
    fun `arabic has six forms in CLDR's order`() {
        forms("ar", 0, 1, 2, 3, 11, 100) shouldBe listOf(0, 1, 2, 3, 4, 5)
    }

    @Test
    fun `vietnamese and tajik have the forms CLDR 49 gives them`() {
        forms("vi", 0, 1, 2) shouldBe listOf(0, 0, 1)
        forms("tg", 1, 2) shouldBe listOf(0, 1)
    }

    @Test
    fun `a negative count takes the form of its size`() {
        forms("en", -1, -2) shouldBe listOf(0, 1)
    }

    @Test
    fun `a variant takes its language's rule and a mediawiki code the rule it is mapped to`() {
        forms("sr-ec", 1, 2, 5) shouldBe forms("sr", 1, 2, 5)
        forms("isv-latn", 1, 2, 5) shouldBe forms("sr", 1, 2, 5)
        forms("bh", 0, 1, 2) shouldBe forms("bho", 0, 1, 2)
    }

    @Test
    fun `a language CLDR has no rule for has no form`() {
        PluralForms.index(1, "tlh").shouldBeNull()
    }

    @Test
    fun `a condition in a syntax CLDR does not use is refused rather than misread`() {
        assertFailsWith<IllegalArgumentException> { PluralCondition.parse("n is 1") }
    }
}
