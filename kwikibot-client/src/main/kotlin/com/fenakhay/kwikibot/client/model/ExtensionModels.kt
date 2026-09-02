package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.model.page.PageRef
import kotlin.time.Instant

/** A point on the globe, as GeoData records it for a page. */
public data class Coordinate(
    /** Degrees north, negative for south. */
    val latitude: Double,
    /** Degrees east, negative for west. */
    val longitude: Double,
    /** Which globe, when it is not Earth. */
    val globe: String = "earth",
    /** Whether this is the page's main coordinate rather than one of several it mentions. */
    val isPrimary: Boolean = true,
    /** What is at the point: `city`, `mountain`, `landmark`. */
    val type: String? = null,
)

/** One notification from Echo. */
public data class Notification(
    /** The notification's own id, which is what marking it read names. */
    val id: Long,
    /** `edit-user-talk`, `mention`, `thank-you-edit`, `reverted`. */
    val type: String,
    /** The page it is about, absent for notifications that are about no page. */
    val title: String?,
    /** Who caused it, absent where the wiki did not say. */
    val agent: String?,
    /** When it happened. */
    val timestamp: Instant?,
    /** Whether it has already been seen. */
    val isRead: Boolean,
)

/** One problem the Linter extension found on a page. */
public data class LintError(
    /** The error's own id, stable while the problem is still on the page. */
    val id: Long,
    /** `obsolete-tag`, `missing-end-tag`, `stripped-tag`, `bogus-image-options`. */
    val category: String,
    /** The page it was found on. */
    val page: PageRef?,
    /** Where in the wikitext it is, as a byte offset range. */
    val range: IntRange? = null,
    /** Whatever else Linter recorded about it, which varies by category. */
    val details: Map<String, String> = emptyMap(),
)

/**
 * What a wiki running FlaggedRevs says about a page's reviewed state.
 *
 * On a wiki with pending changes, readers are shown the last reviewed revision rather than the newest one. A
 * bot that has just edited such a page has not necessarily changed what anybody sees, and needs this to know
 * the difference.
 */
public data class FlaggedInfo(
    /** The revision readers are shown, which lags the newest while changes are pending. */
    val stableRevisionId: Long,
    /** The review level, as the wiki configures them. */
    val level: Int = 0,
    /** The level as the wiki names it: `stable`, `quality`. */
    val levelText: String? = null,
    /** When the oldest unreviewed edit was made, or `null` if nothing is pending. */
    val pendingSince: Instant? = null,
) {
    /** Whether edits are waiting for review, so the newest revision is not the one shown. */
    val hasPendingChanges: Boolean
        get() = pendingSince != null
}
