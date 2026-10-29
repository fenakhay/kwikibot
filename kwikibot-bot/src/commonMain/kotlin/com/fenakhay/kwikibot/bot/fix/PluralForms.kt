package com.fenakhay.kwikibot.bot.fix

/**
 * Which `{{PLURAL:}}` form a count takes, by CLDR's plural rules.
 *
 * MediaWiki numbers the forms by the language's own categories in CLDR's order, zero, one, two, few and many,
 * and gives the form after them to everything else, `other`. A language without `two` has no form for it, so
 * its few form is its second. This numbers them the same way, so a bundle from translatewiki lists its forms
 * in the order this expects.
 *
 * The rules are CLDR's own, compiled in from [CARDINAL_RULES]; the categories a language has are the ones its
 * rules name, so one that only a fraction or a compact exponent reaches is still counted.
 */
internal object PluralForms {

    /**
     * MediaWiki codes that take another language's rule, as MediaWiki's own `plurals-mediawiki.xml` gives it:
     * Bhojpuri under its old code, and Interslavic in either script, which takes Serbian's.
     */
    private val RULE_OF = mapOf("bh" to "bho", "isv-cyrl" to "sr", "isv-latn" to "sr")

    /** Each language's conditions, parsed once and in CLDR's order of categories. */
    private val CONDITIONS: Map<String, List<PluralCondition>> by lazy {
        CARDINAL_RULES.mapValues { (_, rules) ->
            rules.map { (_, condition) -> PluralCondition.parse(condition) }
        }
    }

    /**
     * The form [count] takes in [language], counting from 0, or `null` when CLDR has no rule for it.
     *
     * A code naming a variant after a hyphen, `sr-ec`, `be-tarask` or `de-formal`, takes its language's rule,
     * as MediaWiki's do.
     */
    fun index(count: Long, language: String): Int? {
        val code = RULE_OF[language] ?: language.substringBefore('-')
        val conditions = CONDITIONS[code] ?: return null
        val matched = conditions.indexOfFirst { it.matches(count) }
        return if (matched == -1) conditions.size else matched
    }
}

/**
 * One CLDR plural condition, such as `n % 10 = 2..4 and n % 100 != 12..14`, read for whole numbers.
 *
 * CLDR's operands describe a number as written: `n` and `i` its value, `v`, `w`, `f` and `t` its fraction
 * digits, `c` and `e` a compact exponent. A whole count written plainly has no fraction and no exponent, so
 * all but `n` and `i` are 0 for it.
 */
internal class PluralCondition private constructor(private val alternatives: List<List<Relation>>) {

    /** Whether [count] meets this condition. */
    fun matches(count: Long): Boolean {
        val value = if (count < 0) -count else count
        return alternatives.any { relations -> relations.all { it.holdsFor(value) } }
    }

    /** One comparison, `operand [% modulus] (= | !=) ranges`. */
    private class Relation(
        private val operand: Char,
        private val modulus: Long?,
        private val negated: Boolean,
        private val ranges: List<LongRange>,
    ) {
        fun holdsFor(value: Long): Boolean {
            val whole = if (operand == 'n' || operand == 'i') value else 0L
            val operandValue = modulus?.let { whole % it } ?: whole
            return ranges.any { operandValue in it } != negated
        }
    }

    companion object {
        private val RELATION = Regex("""([nivwftce])\s*(?:%\s*(\d+))?\s*(!=|=)\s*([\d.,]+)""")
        private const val OPERAND = 1
        private const val MODULUS = 2
        private const val COMPARISON = 3
        private const val RANGES = 4

        /** Reads [condition] in CLDR's syntax: relations joined by `and`, alternatives by `or`. */
        fun parse(condition: String): PluralCondition =
            PluralCondition(
                condition.split(" or ").map { alternative ->
                    alternative.split(" and ").map { relation ->
                        val match =
                            requireNotNull(RELATION.matchEntire(relation.trim())) {
                                "not a CLDR plural relation: $relation"
                            }
                        val groups = match.groupValues
                        Relation(
                            operand = groups[OPERAND].single(),
                            modulus = groups[MODULUS].takeIf { it.isNotEmpty() }?.toLong(),
                            negated = groups[COMPARISON] == "!=",
                            ranges = groups[RANGES].split(',').map(::rangeOf),
                        )
                    }
                }
            )

        private fun rangeOf(text: String): LongRange {
            val bounds = text.split("..")
            return bounds.first().toLong()..bounds.last().toLong()
        }
    }
}
