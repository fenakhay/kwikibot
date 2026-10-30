package com.fenakhay.kwikibot.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime

/**
 * What the wall-clock times of the zone named [zone], such as `Europe/Berlin`, are as instants.
 *
 * Read as kotlinx-datetime's `toInstant` reads them: a time that a clock change skips is moved forward by the
 * gap, and a time that occurs twice is the earlier of the two instants.
 *
 * @throws kotlinx.datetime.IllegalTimeZoneException when no zone is called [zone].
 */
internal expect fun wallClock(zone: String): (LocalDateTime) -> Instant
