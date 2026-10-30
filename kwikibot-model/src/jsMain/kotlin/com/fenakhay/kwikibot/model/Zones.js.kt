package com.fenakhay.kwikibot.model

import kotlin.js.Date
import kotlin.js.json
import kotlin.time.Instant
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Read through the runtime's `Intl`, whose zone database Node and every browser ship and keep up to date.
 *
 * kotlinx-datetime knows no named zone on JavaScript until `@js-joda/timezone` is loaded: a second copy of
 * the same database, which every program would load at start-up to read the few signatures some programs
 * read.
 */
internal actual fun wallClock(zone: String): (LocalDateTime) -> Instant {
    val format = runCatching {
        DateTimeFormat(
            "en-US",
            json(
                "timeZone" to zone,
                "hourCycle" to "h23",
                "era" to "short",
                "year" to "numeric",
                "month" to "numeric",
                "day" to "numeric",
                "hour" to "numeric",
                "minute" to "numeric",
                "second" to "numeric",
            ),
        )
    }
        .getOrElse { throw IllegalTimeZoneException("no time zone is called '$zone'", it) }
    return { time -> format.instantOf(time) }
}

/**
 * The instant [time] names, found as Temporal's reference implementation finds it.
 *
 * The offsets a day either side are the only two a time can be read with, short of a zone changing twice in a
 * day. Each is checked against the offset at the instant it gives: none fits in a gap, which is then crossed
 * with the offset from before it, and both fit in an overlap, where the earlier instant is taken.
 */
private fun DateTimeFormat.instantOf(time: LocalDateTime): Instant {
    val wall = time.toInstant(TimeZone.UTC).toEpochMilliseconds()
    val before = offsetAt(wall - DAY)
    val after = offsetAt(wall + DAY)
    val fitting = listOf(before, after).distinct().map { wall - it }.filter { wall - offsetAt(it) == it }
    return Instant.fromEpochMilliseconds(fitting.minOrNull() ?: (wall - before))
}

/** How far ahead of UTC this zone's clocks are at [epochMillis], in milliseconds. */
private fun DateTimeFormat.offsetAt(epochMillis: Long): Long {
    val parts = formatToParts(Date(epochMillis.toDouble())).associate { it.type to it.value }
    val year = parts.getValue("year").toInt()
    val local =
        LocalDateTime(
            year = if (parts["era"] == "BC") 1 - year else year,
            month = parts.getValue("month").toInt(),
            day = parts.getValue("day").toInt(),
            hour = parts.getValue("hour").toInt(),
            minute = parts.getValue("minute").toInt(),
            second = parts.getValue("second").toInt(),
        )
    return local.toInstant(TimeZone.UTC).toEpochMilliseconds() - epochMillis.floorDiv(SECOND) * SECOND
}

private const val SECOND = 1_000L

private const val DAY = 86_400_000L
