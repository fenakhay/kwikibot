package com.fenakhay.kwikibot.fleet

import kotlin.js.Date
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Through the runtime's `Intl`, since kotlinx-datetime knows no named zone on JavaScript without loading a
 * second copy of the zone database. A time is found as Temporal's reference implementation finds it: each of
 * the offsets a day either side is checked against the offset at the instant it gives.
 */
internal actual fun zoneClock(zone: String): ZoneClock {
    val format: dynamic = runCatching {
        dateTimeFormat(zone)
    }
        .getOrElse { throw IllegalTimeZoneException("no time zone is called '$zone'", it) }

    fun wall(epochMillis: Double): LocalDateTime {
        val parts: dynamic = format.formatToParts(Date(epochMillis))
        val fields = mutableMapOf<String, Int>()
        for (index in 0 until (parts.length as Int)) {
            val part = parts[index]
            (part.value as String).toIntOrNull()?.let { fields[part.type as String] = it }
        }
        return LocalDateTime(
            fields.getValue("year"),
            fields.getValue("month"),
            fields.getValue("day"),
            fields.getValue("hour"),
            fields.getValue("minute"),
            fields.getValue("second"),
        )
    }

    return object : ZoneClock {
        override fun localOf(instant: Instant): LocalDateTime = wall(instant.toEpochMilliseconds().toDouble())

        override fun instantOf(time: LocalDateTime): Instant {
            val asUtc = time.toInstant(TimeZone.UTC)
            val offsetAt = { at: Instant ->
                wall(at.toEpochMilliseconds().toDouble()).toInstant(TimeZone.UTC) - at
            }
            val before = offsetAt(asUtc - 1.days)
            val after = offsetAt(asUtc + 1.days)
            val fitting =
                listOf(before, after).distinct().map { asUtc - it }.filter { offsetAt(it) == asUtc - it }
            return fitting.minOrNull() ?: (asUtc - before)
        }
    }
}

@Suppress("UNUSED_PARAMETER", "UnusedParameter")
private fun dateTimeFormat(zone: String): dynamic =
    js(
        "new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', " +
            "month: 'numeric', " +
            "day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' })"
    )
