package com.fenakhay.kwikibot.fleet

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

internal actual fun zoneClock(zone: String): ZoneClock {
    val timeZone = TimeZone.of(zone)
    return object : ZoneClock {
        override fun localOf(instant: Instant): LocalDateTime = instant.toLocalDateTime(timeZone)

        override fun instantOf(time: LocalDateTime): Instant = time.toInstant(timeZone)
    }
}
