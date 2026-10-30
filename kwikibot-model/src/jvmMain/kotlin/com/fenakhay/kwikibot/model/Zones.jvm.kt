package com.fenakhay.kwikibot.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** Read with kotlinx-datetime, from the zone database the JVM ships. */
internal actual fun wallClock(zone: String): (LocalDateTime) -> Instant {
    val timeZone = TimeZone.of(zone)
    return { it.toInstant(timeZone) }
}
