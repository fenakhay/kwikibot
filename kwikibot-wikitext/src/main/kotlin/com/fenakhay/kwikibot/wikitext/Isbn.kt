package com.fenakhay.kwikibot.wikitext

/**
 * An ISBN, checked.
 *
 * The point of the type is the check digit. An ISBN with a wrong one is a typo, and a bot that
 * "reformats" it without noticing has tidied a number that identifies nothing.
 *
 * **Hyphenation is not done.** Where the hyphens go in an ISBN depends on the registration group
 * and the registrant, which is a table the ISBN agency publishes and revises; guessing produces
 * hyphens in the wrong places rather than none at all. [normalised] gives the digits, and an
 * ISBN that arrived hyphenated keeps its own hyphens through [ReformatIsbns].
 */
public class Isbn private constructor(
    /** The digits, without hyphens or spaces, with any check character in upper case. */
    public val normalised: String,
) {

    /** Whether this is the thirteen-digit form. */
    public val isIsbn13: Boolean get() = normalised.length == LENGTH_13

    /**
     * This ISBN as thirteen digits.
     *
     * A ten-digit ISBN converts by prefixing `978` and recomputing the check digit; a
     * thirteen-digit one is returned unchanged.
     */
    public fun toIsbn13(): Isbn {
        if (isIsbn13) return this

        val body = BOOKLAND + normalised.dropLast(1)
        return Isbn(body + checkDigit13(body))
    }

    override fun toString(): String = normalised

    override fun equals(other: Any?): Boolean = other is Isbn && other.normalised == normalised

    override fun hashCode(): Int = normalised.hashCode()

    /** Reading and checking an ISBN, in both the ten- and thirteen-digit forms. */
    public companion object {
        private const val LENGTH_10 = 10
        private const val LENGTH_13 = 13
        private const val BOOKLAND = "978"
        private const val MODULUS_10 = 11
        private const val MODULUS_13 = 10
        private const val WEIGHT_ODD = 3
        private const val CHECK_X = 'X'

        /**
         * Reads an ISBN, or `null` if it is not one.
         *
         * `null` covers both "this is not the right number of digits" and "the check digit does
         * not add up". A caller that wants to tell them apart is looking at a typo either way.
         */
        public fun parse(raw: String): Isbn? {
            val digits = raw.filterNot { it == '-' || it == ' ' }.uppercase()

            return when {
                digits.length == LENGTH_10 && isValid10(digits) -> Isbn(digits)
                digits.length == LENGTH_13 && isValid13(digits) -> Isbn(digits)
                else -> null
            }
        }

        /** Whether [raw] is a well-formed ISBN, check digit included. */
        public fun isValid(raw: String): Boolean = parse(raw) != null

        private fun isValid10(digits: String): Boolean {
            // Weighted 10..1, with X standing for ten in the last place only.
            val sum = digits.withIndex().sumOf { (index, character) ->
                val value = when {
                    character.isDigit() -> character - '0'
                    character == CHECK_X && index == LENGTH_10 - 1 -> LENGTH_10
                    else -> return false
                }
                value * (LENGTH_10 - index)
            }
            return sum % MODULUS_10 == 0
        }

        private fun isValid13(digits: String): Boolean {
            if (!digits.all { it.isDigit() }) return false
            return checkDigit13(digits.dropLast(1)) == digits.last()
        }

        /** The check digit for the first twelve digits of a thirteen-digit ISBN. */
        private fun checkDigit13(body: String): Char {
            val sum = body.withIndex().sumOf { (index, character) ->
                (character - '0') * if (index % 2 == 0) 1 else WEIGHT_ODD
            }
            return '0' + (MODULUS_13 - sum % MODULUS_13) % MODULUS_13
        }
    }
}

/**
 * Rewrites the ISBNs on a page.
 *
 * Two things, both optional, and neither of them hyphenation:
 *
 * - an ISBN whose check digit does not add up is left exactly as written, because it is a typo
 *   and a bot cannot know which digit was mistyped;
 * - a valid ten-digit ISBN can be converted to thirteen digits, which is what most projects
 *   asked for when the format changed.
 *
 * Only prose is touched: an ISBN inside a template parameter belongs to that template, and
 * rewriting it can change what the citation renders.
 */
public class ReformatIsbns(
    /** Whether to convert valid ten-digit ISBNs to the thirteen-digit form. */
    private val toIsbn13: Boolean = false,
) : CosmeticPass {

    override fun apply(code: Markup): Markup {
        if (!toIsbn13) return code

        return code.replaceText(ISBN, TextScope.PROSE) { match ->
            val parsed = Isbn.parse(match.groupValues[1])
            // Left alone when it does not parse: a wrong check digit is a typo, and tidying the
            // formatting of a number that identifies nothing serves no purpose.
            if (parsed == null) match.value else "ISBN ${parsed.toIsbn13()}"
        }
    }

    private companion object {
        /** `ISBN 0-19-853737-9`, as the magic link and the citation templates write it. */
        val ISBN = Regex("""ISBN\s+([\dXx][\dXx\- ]{8,20}[\dXx])""")
    }
}
