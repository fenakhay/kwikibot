import com.fenakhay.kwikibot.model.page.DatePages

/** How one language titles its pages about a calendar day. */
@JsExport
public class DayFormat(
    /** The month names in this language, January first. */
    public val months: Array<String>,
    /** `$day` and `$month` are replaced; `$ordinal` is the day with its suffix, if any. */
    public val pattern: String,
    /** The suffix on the first of the month, where a language uses one, such as `"er"` in French. */
    public val firstOrdinal: String? = null,
) {
    internal constructor(
        format: DatePages.DayFormat
    ) : this(format.months.toTypedArray(), format.pattern, format.firstOrdinal)
}

/** A calendar day, without a year. */
@JsExport
public class MonthDay
internal constructor(
    /** The month, from 1 to 12. */
    public val month: Int,
    /** The day of the month. */
    public val day: Int,
)

/** The languages a day title can be built for. */
@JsExport public fun dayTitleLanguages(): Array<String> = DatePages.languages.toTypedArray()

/** Adds or replaces [language]'s format for day titles. It holds for the whole process. */
@JsExport
public fun registerDayFormat(language: String, format: DayFormat) {
    DatePages.register(
        language,
        DatePages.DayFormat(format.months.toList(), format.pattern, format.firstOrdinal),
    )
}

/** The format registered for [language], or `null`. */
@JsExport public fun dayFormat(language: String): DayFormat? = DatePages.format(language)?.let(::DayFormat)

/**
 * The title of the page for a calendar day, or `null` for a language with no format. Throws when it is not a
 * real day.
 */
@JsExport
public fun dayTitle(month: Int, day: Int, language: String = "en"): String? =
    DatePages.dayTitle(month, day, language)

/** The day [title] names, or `null` when it is not a day page in [language]. */
@JsExport
public fun parseDayTitle(title: String, language: String = "en"): MonthDay? =
    DatePages.parseDayTitle(title, language)?.let { (month, day) -> MonthDay(month, day) }

/** The days [month] has, counting 29 for February, since 29 February has a page. */
@JsExport public fun daysInMonth(month: Int): Int = DatePages.daysIn(month)
