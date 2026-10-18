package com.fenakhay.kwikibot.bot.fix

import java.math.BigDecimal
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import net.xyzsd.plurals.PluralCategory
import net.xyzsd.plurals.PluralRule
import net.xyzsd.plurals.PluralRuleType

/**
 * Which `{{PLURAL:}}` form a count takes, by CLDR's plural rules.
 *
 * MediaWiki numbers the forms by the language's own categories in CLDR's order, zero, one, two, few and many,
 * and gives the form after them to everything else, `other`. A language without `two` has no form for it, so
 * its few form is its second. This numbers them the same way, so a bundle from translatewiki lists its forms
 * in the order this expects.
 */
internal object PluralForms {
    private val categories = ConcurrentHashMap<String, List<PluralCategory>>()

    /**
     * MediaWiki codes that take another language's rule, as MediaWiki's own `plurals-mediawiki.xml` gives it:
     * Bhojpuri under its old code, and Interslavic in either script, which takes Serbian's.
     */
    private val RULE_OF = mapOf("bh" to "bho", "isv-cyrl" to "sr", "isv-latn" to "sr")

    /** Whole numbers to 200, which decide most rules. */
    private const val WHOLE = 200L

    /** A million, which takes the many form French, Spanish, Italian and Portuguese keep for millions. */
    private const val MILLION = 1_000_000L

    /** Tenths to 2.9, for the categories only a fraction takes, such as Lithuanian's and Czech's many. */
    private const val TENTHS = 29L

    /** Counts that reach every category of every language CLDR has a rule for. */
    private val SAMPLES: List<BigDecimal> =
        (0L..WHOLE).map(BigDecimal::valueOf) +
            BigDecimal.valueOf(MILLION) +
            (1L..TENTHS).map { BigDecimal.valueOf(it, 1) }

    /**
     * The form [count] takes in [language], counting from 0, or `null` when CLDR has no rule for it.
     *
     * A code naming a variant after a hyphen, `sr-ec`, `be-tarask` or `de-formal`, takes its language's rule,
     * as MediaWiki's do.
     */
    fun index(count: Long, language: String): Int? {
        val code = RULE_OF[language] ?: language.substringBefore('-')
        val rule = PluralRule.create(code, "", PluralRuleType.CARDINAL).orElse(null) ?: return null
        val named = categories.computeIfAbsent(code) { categoriesOf(rule) }
        val category = rule.select(count)
        return if (category == PluralCategory.OTHER) named.size else named.indexOf(category)
    }

    /** The categories [rule] has besides `other`, in CLDR's order, which is the order of the enum. */
    private fun categoriesOf(rule: PluralRule): List<PluralCategory> {
        val seen = SAMPLES.mapTo(EnumSet.noneOf(PluralCategory::class.java)) { rule.select(it) }
        seen.remove(PluralCategory.OTHER)
        return seen.toList()
    }
}
