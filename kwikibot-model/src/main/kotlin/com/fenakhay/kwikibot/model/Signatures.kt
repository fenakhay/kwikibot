package com.fenakhay.kwikibot.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** A timestamp found in a signature, with the text it was written as. */
public data class SignatureTime(
    /** The moment the signature names, resolved out of its local zone. */
    val instant: Instant,
    /** The text that produced it, exactly as it appears on the page. */
    val text: String,
    /** Where it sits in the wikitext, so a caller can cut the thread at the right place. */
    val range: IntRange,
    /** The language whose format matched. */
    val language: String,
)

/**
 * Finds the timestamps that `~~~~` leaves behind.
 *
 * Exists for one job: an archiving bot needs to know when a discussion last had a reply, and the only record
 * of that is the signature timestamps in the page text. No API reports them.
 *
 * The formats differ by wiki, because MediaWiki renders a signature in the wiki's own language: `21:43, 31
 * August 2026 (UTC)` on en, `31 août 2026 à 21:43 (CEST)` on fr. What is here is the formats of several large
 * projects, plus [SignatureFormat] to add others.
 *
 * ```
 * val last = Signatures.ENGLISH.latest(page.text)
 * ```
 *
 * Housed here rather than in `kwikibot-wikitext`, where the plan put it: a signature timestamp is plain text
 * rather than markup, this is the same job [MwTimestamp] does for API timestamps, and the wikitext module
 * stays free of dependencies.
 */
public class Signatures(
    /**
     * The formats tried, in order.
     *
     * Public so a caller can rebuild them with a wiki's own month names and zone, which is what
     * `Wiki.signatures()` in `kwikibot-client` does.
     */
    public val formats: List<SignatureFormat>
) {

    /** Every signature timestamp in [wikitext], in the order they appear. */
    public fun findAll(wikitext: String): List<SignatureTime> =
        formats.flatMap { it.findAll(wikitext) }.sortedBy { it.range.first }

    /** The most recent signature timestamp, or `null` if the text has none. */
    public fun latest(wikitext: String): SignatureTime? = findAll(wikitext).maxByOrNull { it.instant }

    /** The earliest signature timestamp, which is when a discussion started. */
    public fun earliest(wikitext: String): SignatureTime? = findAll(wikitext).minByOrNull { it.instant }

    /** These formats and some more. */
    public operator fun plus(other: Signatures): Signatures = Signatures(formats + other.formats)

    /** The patterns these formats match, for rebuilding them against different month names. */
    public fun patterns(): List<Regex> = formats.map { it.pattern }

    /** The formats shipped for the wikis this library has been used against. */
    public companion object {

        /** `21:43, 31 August 2026 (UTC)`, and the American order older signatures use. */
        public val ENGLISH: Signatures =
            Signatures(
                listOf(
                    // Named groups rather than positions: the field order differs between wikis, and
                    // a pattern that says which group is the month cannot be mismatched to it.
                    SignatureFormat(
                        "en",
                        Regex(
                            "(?<hour>\\p{Nd}{2}):(?<minute>\\p{Nd}{2}), (?<day>\\p{Nd}{1,2}) " +
                                "(?<month>\\p{L}+) (?<year>\\p{Nd}{4}) \\(UTC\\)"
                        ),
                        ENGLISH_MONTHS,
                    ),
                    SignatureFormat(
                        "en",
                        Regex(
                            "(?<hour>\\p{Nd}{2}):(?<minute>\\p{Nd}{2}), (?<month>\\p{L}+) " +
                                "(?<day>\\p{Nd}{1,2}), (?<year>\\p{Nd}{4}) \\(UTC\\)"
                        ),
                        ENGLISH_MONTHS,
                    ),
                )
            )

        /** `31 août 2026 à 21:43 (CEST)` */
        public val FRENCH: Signatures =
            Signatures(
                listOf(
                    SignatureFormat(
                        "fr",
                        Regex(
                            "(?<day>\\p{Nd}{1,2}) (?<month>\\p{L}+) (?<year>\\p{Nd}{4}) à " +
                                "(?<hour>\\p{Nd}{2}):(?<minute>\\p{Nd}{2}) \\(\\p{L}+\\)"
                        ),
                        FRENCH_MONTHS,
                    )
                )
            )

        /** `21:43, 31. Aug. 2026 (CEST)` */
        public val GERMAN: Signatures =
            Signatures(
                listOf(
                    SignatureFormat(
                        "de",
                        Regex(
                            "(?<hour>\\p{Nd}{2}):(?<minute>\\p{Nd}{2}), (?<day>\\p{Nd}{1,2})\\. " +
                                "(?<month>\\p{L}+)\\.? (?<year>\\p{Nd}{4}) \\(\\p{L}+\\)"
                        ),
                        GERMAN_MONTHS,
                    )
                )
            )

        /** `21:43 31 ago 2026 (UTC)` */
        public val SPANISH: Signatures =
            Signatures(
                listOf(
                    SignatureFormat(
                        "es",
                        Regex(
                            "(?<hour>\\p{Nd}{2}):(?<minute>\\p{Nd}{2}) (?<day>\\p{Nd}{1,2}) " +
                                "(?<month>\\p{L}+) (?<year>\\p{Nd}{4}) \\(UTC\\)"
                        ),
                        SPANISH_MONTHS,
                    )
                )
            )

        /** Every format this library ships. */
        public val ALL: Signatures = ENGLISH + FRENCH + GERMAN + SPANISH

        /**
         * The formats for a language code, or [ENGLISH] for a language not shipped.
         *
         * The shipped formats read their timestamps as UTC, which is right for en.wikipedia and wrong for any
         * wiki that signs in local time. Use `Wiki.signatures()` in `kwikibot-client` to get formats that
         * carry the wiki's own zone and its own month names.
         */
        public fun forLanguage(language: String): Signatures =
            when (language) {
                "fr" -> FRENCH
                "de" -> GERMAN
                "es" -> SPANISH
                else -> ENGLISH
            }
    }
}

/**
 * One wiki's signature format.
 *
 * @param language the code this format belongs to, carried onto every match it makes.
 * @param pattern must use the named groups `hour`, `minute`, `day`, `month` and `year`. Naming them rather
 *   than numbering them is what lets one class read formats whose fields come in different orders.
 * @param months the month names as that wiki writes them, lowercased, mapped to their number. Abbreviations
 *   are included where a wiki uses them, since `Aug.` and `August` both appear on de.wikipedia depending on
 *   when the signature was left.
 * @param zone the zone the timestamps are in. MediaWiki writes a local zone name into the signature —
 *   `(CEST)` — and writes the time in that zone; reading it as UTC puts a discussion two hours in the past,
 *   enough to archive a thread that is still active.
 */
public class SignatureFormat(
    private val language: String,
    /** The pattern this format matches, exposed so a caller can reuse it with other months. */
    public val pattern: Regex,
    private val months: Map<String, Int>,
    private val zone: TimeZone = TimeZone.UTC,
) {

    /**
     * Every timestamp in [wikitext] this format matches.
     *
     * Text inside HTML comments is excluded. A commented-out discussion is not active, and a timestamp inside
     * one that counted as [Signatures.latest] would defer archiving forever.
     */
    public fun findAll(wikitext: String): List<SignatureTime> =
        pattern.findAll(withoutComments(wikitext)).mapNotNull { toTime(it, wikitext) }.toList()

    private fun toTime(match: MatchResult, original: String): SignatureTime? {
        val name = match.text("month")?.lowercase()?.trimEnd('.')
        val month = months[name] ?: return null
        val moment = momentOf(match, month) ?: return null

        return SignatureTime(
            instant = moment.toInstant(zone),
            // Read back from the original text: masking preserves offsets but not characters.
            text = original.substring(match.range),
            range = match.range,
            language = language,
        )
    }

    /**
     * The moment a match names, or `null` if it is not a real one.
     *
     * A lenient parser turns 31 February into 3 March, which is a date a bot would then act on.
     */
    private fun momentOf(match: MatchResult, month: Int): LocalDateTime? = runCatching {
        LocalDateTime(
            year = match.number("year"),
            month = month,
            day = match.number("day"),
            hour = match.number("hour"),
            minute = match.number("minute"),
        )
    }
        .getOrNull()

    private fun MatchResult.text(group: String): String? = runCatching { groups[group]?.value }.getOrNull()

    /**
     * A captured group as a number, in whatever numeral system the wiki writes.
     *
     * `toIntOrNull` reads ASCII only, so digits are folded to their numeric value first — a wiki writing
     * Devanagari or Arabic-Indic numerals is otherwise unparseable.
     */
    private fun MatchResult.number(group: String): Int {
        val digits = checkNotNull(text(group)) { "group '$group' did not match" }
        return digits.fold(0) { value, character ->
            val digit = Character.digit(character, DECIMAL)
            require(digit >= 0) { "group '$group' holds a non-digit: $digits" }
            value * DECIMAL + digit
        }
    }
}

/**
 * Month names to month numbers, in calendar order.
 *
 * Built from the order rather than written as pairs, so a month cannot be given the wrong number by a typo.
 * Alternative spellings for one month are separated by `|`: `Aug.` and `August` both appear on de.wikipedia
 * depending on when the signature was left.
 */
private fun months(vararg spellings: String): Map<String, Int> = buildMap {
    spellings.forEachIndexed { index, month ->
        month.split('|').forEach { put(it, index + 1) }
    }
}

/**
 * The text with HTML comment bodies replaced by spaces.
 *
 * Same length as the input, so a match range still indexes the original. Comments may span lines, and an
 * unterminated one runs to the end of the page, which is how MediaWiki renders it.
 */
private fun withoutComments(wikitext: String): String {
    if (COMMENT_OPEN !in wikitext) return wikitext

    val masked = StringBuilder(wikitext)
    var index = wikitext.indexOf(COMMENT_OPEN)

    while (index >= 0) {
        val closed = wikitext.indexOf(COMMENT_CLOSE, index + COMMENT_OPEN.length)
        val end = if (closed < 0) wikitext.length else closed + COMMENT_CLOSE.length

        for (position in index until end) {
            if (!masked[position].isWhitespace()) masked[position] = ' '
        }
        if (closed < 0) break

        index = wikitext.indexOf(COMMENT_OPEN, end)
    }

    return masked.toString()
}

private const val COMMENT_OPEN = "<!--"
private const val COMMENT_CLOSE = "-->"

/** The radix every wiki writes timestamps in, whatever glyphs it uses for the digits. */
private const val DECIMAL = 10

private val ENGLISH_MONTHS =
    months(
        "january",
        "february",
        "march",
        "april",
        "may",
        "june",
        "july",
        "august",
        "september",
        "october",
        "november",
        "december",
    )

private val FRENCH_MONTHS =
    months(
        "janvier",
        "février",
        "mars",
        "avril",
        "mai",
        "juin",
        "juillet",
        "août",
        "septembre",
        "octobre",
        "novembre",
        "décembre",
    )

private val GERMAN_MONTHS =
    months(
        "januar|jan",
        "februar|feb",
        "märz|mär",
        "april|apr",
        "mai",
        "juni|jun",
        "juli|jul",
        "august|aug",
        "september|sep",
        "oktober|okt",
        "november|nov",
        "dezember|dez",
    )

private val SPANISH_MONTHS =
    months(
        "enero|ene",
        "febrero|feb",
        "marzo|mar",
        "abril|abr",
        "mayo|may",
        "junio|jun",
        "julio|jul",
        "agosto|ago",
        "septiembre|sep",
        "octubre|oct",
        "noviembre|nov",
        "diciembre|dic",
    )
