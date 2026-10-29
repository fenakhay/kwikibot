package com.fenakhay.kwikibot.model.edit

import com.fenakhay.kwikibot.model.MwTimestamp
import kotlin.time.Instant

/**
 * When a protection, block or other restriction runs out.
 *
 * MediaWiki spells "no end" several ways (`infinity`, `infinite`, `indefinite`, `never`), which a plain
 * timestamp type has to represent as a sentinel date. Making it a case instead means "does this ever expire"
 * is a `when`, not a comparison against a magic year.
 */
public sealed interface Expiry {

    /** The restriction has no end. */
    public data object Never : Expiry {
        override fun toString(): String = "infinity"
    }

    /** The restriction ends at a moment in time. */
    public data class At(
        /** When the restriction lapses. */
        val instant: Instant
    ) : Expiry {
        override fun toString(): String = MwTimestamp.format(instant)
    }

    /** Reading an expiry as the API spells it. */
    public companion object {
        /**
         * Reads an expiry as the API reports it.
         *
         * @throws IllegalArgumentException if [raw] is neither a MediaWiki timestamp nor one of its spellings
         *   of "never".
         */
        public fun parse(raw: String): Expiry =
            if (MwTimestamp.isNever(raw)) Never else At(MwTimestamp.parse(raw))
    }
}

/**
 * One protection entry on a page: an action, the group allowed to perform it, and an expiry.
 *
 * @param action the restricted action, such as `edit` or `move`.
 * @param level the group that may still perform it, such as `sysop` or `autoconfirmed`.
 * @param expiry when the restriction lapses, or [Expiry.Never] if it does not.
 * @param cascading whether the protection propagates to transcluded pages.
 */
public data class Protection(
    val action: String,
    val level: String,
    val expiry: Expiry = Expiry.Never,
    val cascading: Boolean = false,
) {
    /** Whether this protection is still in force at [now]. */
    public fun isActiveAt(now: Instant): Boolean =
        when (expiry) {
            Expiry.Never -> true
            is Expiry.At -> expiry.instant > now
        }

    /** The protection levels MediaWiki names, which a wiki may extend. */
    public companion object {
        /** The level MediaWiki reports when only administrators may act. */
        public const val SYSOP: String = "sysop"

        /** The level MediaWiki reports for semi-protection. */
        public const val AUTOCONFIRMED: String = "autoconfirmed"
    }
}
