package com.fenakhay.kwikibot.model

import kotlin.time.Instant

/**
 * The timestamp formats MediaWiki emits and accepts.
 *
 * The API returns ISO 8601 (`2026-08-31T21:43:26Z`), while database fields, `sort=timestamp` parameters and
 * older dumps use the compact form (`20260831214326`). Both are read; the ISO form is written, since every
 * modern endpoint accepts it.
 */
public object MwTimestamp {

    private val COMPACT =
        Regex("""(?<year>\d{4})(?<month>\d{2})(?<day>\d{2})(?<hour>\d{2})(?<minute>\d{2})(?<second>\d{2})""")

    /** Values MediaWiki uses to mean "no end", in protection expiries and block durations. */
    private val NEVER = setOf("infinity", "infinite", "indefinite", "never")

    /** Whether [raw] is one of MediaWiki's spellings of an expiry that never arrives. */
    public fun isNever(raw: String): Boolean = raw.trim().lowercase() in NEVER

    /**
     * Parses a MediaWiki timestamp in either the ISO 8601 or the compact form.
     *
     * @throws IllegalArgumentException if [raw] is neither.
     */
    public fun parse(raw: String): Instant =
        parseOrNull(raw) ?: throw IllegalArgumentException("not a MediaWiki timestamp: $raw")

    /** Parses a MediaWiki timestamp, returning `null` rather than throwing. */
    public fun parseOrNull(raw: String): Instant? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val iso = text.compactToIso() ?: text
        return runCatching { Instant.parse(iso) }.getOrNull()
    }

    /** Renders [instant] in the ISO 8601 form the API expects, to whole seconds. */
    public fun format(instant: Instant): String {
        val text = instant.toString()
        // kotlinx-datetime prints sub-second precision when it is non-zero; MediaWiki does not
        // accept fractional seconds in timestamp parameters.
        val withoutFraction = text.substringBefore('.').removeSuffix("Z")
        return "${withoutFraction}Z"
    }

    /** Renders [instant] in the compact `YYYYMMDDHHMMSS` form. */
    public fun formatCompact(instant: Instant): String = format(instant).filter { it.isDigit() }

    /** `20260831214326` to `2026-08-31T21:43:26Z`, or `null` if this is not the compact form. */
    private fun String.compactToIso(): String? {
        val groups = COMPACT.matchEntire(this)?.groups ?: return null
        fun part(name: String) = checkNotNull(groups[name]).value
        return "${part("year")}-${part("month")}-${part("day")}T" +
            "${part("hour")}:${part("minute")}:${part("second")}Z"
    }
}
