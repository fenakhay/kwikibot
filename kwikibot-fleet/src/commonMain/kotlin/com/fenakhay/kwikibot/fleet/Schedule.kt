@file:Suppress("MagicNumber")

package com.fenakhay.kwikibot.fleet

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus

/**
 * When `serve` runs a bot.
 *
 * A firing queues a run, so one that comes round during a run waits its turn, and at most one waits. Firings
 * missed while nothing was running are not made up: starting a nightly job the moment a machine comes back is
 * a surprise, and these runs are long. Nothing about a schedule is kept between runs of the process.
 */
public sealed interface Schedule {

    /** The first firing after [after]. */
    public fun next(after: Instant): Instant

    /** Ways to make one. */
    public companion object {
        /** Every [interval], counted from when the last firing was. */
        public fun every(interval: Duration): Schedule = Every(interval)

        /**
         * Per a five-field cron expression, minute hour day-of-month month day-of-week, as [zone] tells the
         * time.
         *
         * Each field takes `*`, numbers, ranges and lists, each with an optional `/step`. As in cron, when
         * both day fields are restricted, a day matching either fires. A time the clock skips going forward
         * fires at the moment after the gap, and one it repeats going back fires the first time.
         *
         * @param expression the expression.
         * @param zone the name of the time zone it is read in, such as `"Europe/Berlin"`.
         * @throws IllegalArgumentException if it is not a valid expression.
         */
        public fun cron(expression: String, zone: String = "UTC"): Schedule =
            Cron(expression, zoneClock(zone))
    }
}

/** A time zone by name, which each platform reads its own way. */
internal interface ZoneClock {
    /** What the clocks there say at [instant]. */
    fun localOf(instant: Instant): LocalDateTime

    /** When the clocks there say [time], the moment after a gap, and the first time if it comes twice. */
    fun instantOf(time: LocalDateTime): Instant
}

internal expect fun zoneClock(zone: String): ZoneClock

private class Every(private val interval: Duration) : Schedule {
    init {
        require(interval.isPositive()) { "a schedule needs a positive interval, not $interval" }
    }

    override fun next(after: Instant): Instant = after + interval
}

private class Cron(expression: String, private val zone: ZoneClock) : Schedule {
    private val minutes: Set<Int>
    private val hours: Set<Int>
    private val days: Set<Int>
    private val months: Set<Int>
    private val weekdays: Set<Int>
    private val daysRestricted: Boolean
    private val weekdaysRestricted: Boolean

    init {
        val fields = expression.trim().split(Regex("\\s+"))
        require(fields.size == FIELDS) {
            "a cron expression has five fields, and '$expression' has ${fields.size}"
        }
        minutes = field(fields[0], 0..59)
        hours = field(fields[1], 0..23)
        days = field(fields[2], 1..31)
        months = field(fields[3], 1..12)
        weekdays = field(fields[4], 0..7).map { it % 7 }.toSet()
        daysRestricted = !fields[2].startsWith("*")
        weekdaysRestricted = !fields[4].startsWith("*")
    }

    private fun field(text: String, range: IntRange): Set<Int> =
        text.split(',').flatMapTo(mutableSetOf()) { part ->
            val pieces = part.split('/')
            require(pieces.size <= 2) { "'$part' is not a cron field" }
            val step = pieces.getOrNull(1)?.toIntOrNull() ?: if (pieces.size == 1) 1 else 0
            require(step > 0) { "'$part' has no usable step" }
            val span =
                when {
                    pieces[0] == "*" -> range
                    '-' in pieces[0] -> {
                        val (from, to) = pieces[0].split('-').map { number(it, range, part) }
                        require(from <= to) { "'$part' runs backwards" }
                        from..to
                    }
                    pieces.size == 2 -> number(pieces[0], range, part)..range.last
                    else -> number(pieces[0], range, part).let { it..it }
                }
            span step step
        }

    private fun number(text: String, range: IntRange, part: String): Int {
        val value = text.toIntOrNull()
        require(value != null && value in range) { "'$part' is outside $range" }
        return value
    }

    private fun matches(date: LocalDate): Boolean {
        if (date.month.number !in months) return false
        val day = date.day in days
        val weekday = date.dayOfWeek.isoDayNumber % 7 in weekdays
        return when {
            daysRestricted && weekdaysRestricted -> day || weekday
            daysRestricted -> day
            weekdaysRestricted -> weekday
            else -> true
        }
    }

    override fun next(after: Instant): Instant {
        var date = zone.localOf(after).date
        val sortedHours = hours.sorted()
        val sortedMinutes = minutes.sorted()

        repeat(SEARCH_DAYS) {
            if (matches(date)) {
                for (hour in sortedHours) {
                    for (minute in sortedMinutes) {
                        val firing = zone.instantOf(LocalDateTime(date, LocalTime(hour, minute)))
                        if (firing > after) return firing
                    }
                }
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        throw IllegalStateException("no firing within eight years; February 30th never comes")
    }

    private companion object {
        const val FIELDS = 5
        const val SEARCH_DAYS = 366 * 8
    }
}
