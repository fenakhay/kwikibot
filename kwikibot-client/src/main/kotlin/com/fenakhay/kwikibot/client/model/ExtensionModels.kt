package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.service.AbuseFilterProperty
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

/**
 * One abuse filter, as AbuseFilter describes it.
 *
 * Only what was asked for is filled in. A property the account may not see is left out, and from MediaWiki
 * 1.47 named in [redacted], so a missing pattern is not mistaken for an empty one.
 */
public data class AbuseFilter(
    /** The filter's number. */
    val id: Long,
    /** The public description. */
    val description: String? = null,
    /** What the filter does when it matches: `warn`, `disallow`, `tag`. */
    val actions: List<String> = emptyList(),
    /** The rules it matches against. */
    val pattern: String? = null,
    /** Notes left by its editors. */
    val comments: String? = null,
    /** How often it has matched. */
    val hits: Long? = null,
    /** Who last changed it. */
    val lastEditor: String? = null,
    /** When it was last changed. */
    val lastEdited: Instant? = null,
    /** Its state, when [AbuseFilterProperty.FLAGS] was asked for. */
    val flags: AbuseFilterFlags? = null,
    /** The properties asked for that the wiki withheld from this account. */
    val redacted: Set<AbuseFilterProperty> = emptySet(),
)

/** The state of an abuse filter. */
public data class AbuseFilterFlags(
    /** Whether it checks edits at all. */
    val isEnabled: Boolean,
    /** Whether it has been deleted, which leaves it listed but inert. */
    val isDeleted: Boolean,
    /** Whether its rules are hidden from the public. */
    val isPrivate: Boolean,
    /** Whether it reads protected variables, which hides its rules from more people still. */
    val isProtected: Boolean,
    /** Whether it is hidden even from administrators. Always false before MediaWiki 1.47. */
    val isSuppressed: Boolean,
)

/** How far a new page has got through review in PageTriage. */
public enum class TriageStatus {
    /** Waiting for a reviewer. */
    UNREVIEWED,

    /** Marked reviewed by a reviewer. */
    REVIEWED,

    /** Marked patrolled, which PageTriage counts as reviewed. */
    PATROLLED,

    /** Created by an account whose pages need no review. */
    AUTOPATROLLED,
}

/** One page in the new pages feed. */
public data class TriagePage(
    /** The page. */
    val page: PageRef,
    /** When it was created. */
    val created: Instant?,
    /** Who created it, absent when the account has been hidden. */
    val creator: String?,
    /** Whether the creator is a temporary account. */
    val creatorIsTemporary: Boolean,
    /** How far it has got through review, or `null` for a state this library does not know. */
    val status: TriageStatus?,
    /** Who last reviewed it, absent when it has not been, or the reviewer has been hidden. */
    val reviewer: String?,
    /** When its review state last changed. */
    val reviewUpdated: Instant?,
    /** Whether it is a redirect. */
    val isRedirect: Boolean,
    /** Whether nothing links to it, as PageTriage last counted. */
    val isOrphan: Boolean,
)

/** A queue in the new pages feed: how long it is, and how long its oldest entry has waited. */
public data class TriageBacklog(
    /** How many pages are in it. */
    val count: Long,
    /**
     * When the longest-waiting of them entered the queue or last changed review state, or `null` when the
     * queue is empty.
     */
    val oldest: Instant?,
)

/** How long the new pages backlog is. */
public data class TriageStats(
    /** Articles waiting for review. */
    val unreviewedArticles: TriageBacklog,
    /** Redirects waiting for review. */
    val unreviewedRedirects: TriageBacklog,
    /** Drafts waiting for review, or `null` where the wiki does not review drafts in the feed. */
    val unreviewedDrafts: TriageBacklog?,
    /** Articles reviewed in the past week. */
    val reviewedArticles: Long,
    /** Redirects reviewed in the past week. */
    val reviewedRedirects: Long,
    /** Articles, reviewed or not, created by the accounts the stats were asked about. */
    val matching: Long,
)
