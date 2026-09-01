package com.fenakhay.kwikibot.model

/**
 * The titles of date pages, in the languages this library ships formats for.
 *
 * Wikipedias have a page per calendar day, named in the wiki's own language: `January 1` on en,
 * `1er janvier` on fr, `1. Januar` on de, `1 de enero` on es. A bot that links or categorises
 * them has to build and read those titles.
 *
 * Covers the languages this library holds month names for; [register] adds another, which takes
 * a month list and a format string. Exhaustive per-language date handling — Roman numerals, local
 * digit systems, era suffixes, non-Gregorian calendars — is out of scope: it served cross-wiki
 * date linking, which Wikidata sitelinks now do.
 */
public object DatePages {

    /** How one language writes a day page title. */
    public data class DayFormat(
        /** The month names in this language, January first. */
        val months: List<String>,
        /** `$day` and `$month` are replaced; `$ordinal` is the day with its suffix, if any. */
        val pattern: String,
        /** The suffix on the first of the month, where a language uses one: `er` in French. */
        val firstOrdinal: String? = null,
    )

    private val formats = mutableMapOf(
        "en" to DayFormat(EN_MONTHS, "\$month \$day"),
        "fr" to DayFormat(FR_MONTHS, "\$ordinal \$month", firstOrdinal = "er"),
        "de" to DayFormat(DE_MONTHS, "\$day. \$month"),
        "es" to DayFormat(ES_MONTHS, "\$day de \$month"),
        "it" to DayFormat(IT_MONTHS, "\$day \$month"),
        "nl" to DayFormat(NL_MONTHS, "\$day \$month"),
        "pt" to DayFormat(PT_MONTHS, "\$day de \$month"),
    )

    /** The languages a day title can be built for. */
    public val languages: Set<String> get() = formats.keys

    /** Adds or replaces the format for a language. */
    public fun register(language: String, format: DayFormat) {
        formats[language] = format
    }

    /**
     * The format registered for a language, or `null`.
     *
     * Exposed so a caller can keep the pattern and replace the month names, which is what
     * `Wiki.dayTitleFormat()` in `kwikibot-client` does.
     */
    public fun format(language: String): DayFormat? = formats[language]

    /**
     * The title of the page for a calendar day, or `null` for a language with no format here.
     *
     * @throws IllegalArgumentException if the month or day is not a real one — a bot that builds
     *   `February 31` links to a page that cannot exist.
     */
    public fun dayTitle(month: Int, day: Int, language: String = "en"): String? {
        require(month in 1..MONTHS) { "month must be 1..12, was $month" }
        require(day in 1..daysIn(month)) { "day $day is not in month $month" }

        val format = formats[language] ?: return null
        val ordinal = if (day == 1 && format.firstOrdinal != null) "$day${format.firstOrdinal}" else "$day"

        return format.pattern
            .replace("\$ordinal", ordinal)
            .replace("\$day", day.toString())
            .replace("\$month", format.months[month - 1])
    }

    /**
     * The month and day a title names, or `null` if it is not a day page in this language.
     *
     * Matching is case-insensitive on the month, since a title arrives capitalised on wikis where
     * MediaWiki capitalises the first letter and not on the ones where it does not.
     */
    public fun parseDayTitle(title: String, language: String = "en"): Pair<Int, Int>? {
        val format = formats[language] ?: return null

        val monthIndex = format.months.indexOfFirst { month ->
            title.contains(month, ignoreCase = true)
        }
        if (monthIndex < 0) return null

        // The month name is removed first: a language whose month contains a digit, or a wiki
        // whose format puts the month before the day, would otherwise yield the wrong number.
        val withoutMonth = title.replace(format.months[monthIndex], "", ignoreCase = true)
        val day = DAY.find(withoutMonth)?.value?.toIntOrNull()
        val month = monthIndex + 1

        return if (day != null && day in 1..daysIn(month)) month to day else null
    }

    /**
     * The days a month has, treating February as having 29.
     *
     * A day page is about a date rather than a date in a year, and 29 February has a page.
     */
    public fun daysIn(month: Int): Int = when (month) {
        FEBRUARY -> LEAP_FEBRUARY
        APRIL, JUNE, SEPTEMBER, NOVEMBER -> SHORT_MONTH
        else -> LONG_MONTH
    }

    private val DAY = Regex("""\d{1,2}""")

    private const val MONTHS = 12
    private const val FEBRUARY = 2
    private const val APRIL = 4
    private const val JUNE = 6
    private const val SEPTEMBER = 9
    private const val NOVEMBER = 11
    private const val LEAP_FEBRUARY = 29
    private const val SHORT_MONTH = 30
    private const val LONG_MONTH = 31
}

private val EN_MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private val FR_MONTHS = listOf(
    "janvier", "février", "mars", "avril", "mai", "juin",
    "juillet", "août", "septembre", "octobre", "novembre", "décembre",
)

private val DE_MONTHS = listOf(
    "Januar", "Februar", "März", "April", "Mai", "Juni",
    "Juli", "August", "September", "Oktober", "November", "Dezember",
)

private val ES_MONTHS = listOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)

private val IT_MONTHS = listOf(
    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
    "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
)

private val NL_MONTHS = listOf(
    "januari", "februari", "maart", "april", "mei", "juni",
    "juli", "augustus", "september", "oktober", "november", "december",
)

private val PT_MONTHS = listOf(
    "janeiro", "fevereiro", "março", "abril", "maio", "junho",
    "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
)
