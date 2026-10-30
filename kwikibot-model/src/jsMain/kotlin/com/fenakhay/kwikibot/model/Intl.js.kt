@file:JsQualifier("Intl")

package com.fenakhay.kwikibot.model

import kotlin.js.Date
import kotlin.js.Json

/** JavaScript's `Intl.DateTimeFormat`, which writes instants in a locale and a zone. */
internal external class DateTimeFormat(locales: String, options: Json) {

    /** The fields [date] is written with, in order. */
    fun formatToParts(date: Date): Array<DateTimeFormatPart>
}

/** One field of a written date, such as `{type: "hour", value: "21"}`. */
internal external interface DateTimeFormatPart {
    val type: String
    val value: String
}
