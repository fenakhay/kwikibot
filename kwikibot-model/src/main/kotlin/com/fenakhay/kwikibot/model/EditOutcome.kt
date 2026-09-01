package com.fenakhay.kwikibot.model

import kotlin.time.Duration

/**
 * What became of an edit.
 *
 * A wiki refuses edits routinely and for reasons a bot is expected to handle: another editor
 * changed the page first, an abuse filter disagreed, the page is protected. Those are values
 * rather than exceptions, so handling them is a `when` the compiler checks. Genuine faults (no
 * network, bad credentials, a malformed request) still throw [WikiError].
 */
public sealed interface EditOutcome {

    /** The page the edit was aimed at. */
    public val ref: PageRef

    /** The edit was applied and created a new revision. */
    public data class Saved(
        override val ref: PageRef,
        /** The revision the edit created. */
        val revision: RevisionId,
        /** What the page was at before it, absent when the edit created the page. */
        val previousRevision: RevisionId?,
    ) : EditOutcome

    /**
     * The wiki accepted the edit but stored nothing, because the text was already identical.
     *
     * Not a failure: an idempotent bot re-run reports this for every page it has already fixed.
     */
    public data class NoChange(
        override val ref: PageRef,
        /** The revision the page is still at, which the edit did not change. */
        val revision: RevisionId?,
    ) : EditOutcome

    /** The wiki declined the edit. */
    public sealed interface Refused : EditOutcome {

        /** What the wiki said, verbatim, for logs and skip records. */
        public val detail: String

        /** Whether trying again — after reloading, or after a wait — could succeed. */
        public val isRetryable: Boolean get() = false
    }

    /**
     * Somebody else edited the page after the base revision was read.
     *
     * @param ref the page the edit was refused on.
     * @param detail what the wiki said about the refusal.
     * @param currentRevision the revision the page is on now, when the wiki reported it;
     *   the edit can be recomputed from that and retried.
     */
    public data class Conflict(
        override val ref: PageRef,
        override val detail: String,
        val currentRevision: RevisionId? = null,
    ) : Refused {
        override val isRetryable: Boolean get() = true
    }

    /** The page is protected against this account. */
    public data class Protected(
        override val ref: PageRef,
        override val detail: String,
        /** The protection level that blocked the edit, when the wiki named one. */
        val level: String? = null,
        /** Whether the protection comes from a page transcluding this one. */
        val cascading: Boolean = false,
    ) : Refused

    /**
     * An abuse filter, the spam blacklist or the title blacklist rejected the edit.
     *
     * @param ref the page the edit was refused on.
     * @param detail what the wiki said about the refusal.
     * @param filter the filter name or blacklist entry, when the wiki named one.
     */
    public data class Filtered(
        override val ref: PageRef,
        override val detail: String,
        val filter: String? = null,
    ) : Refused

    /** The account hit an edit rate limit. */
    public data class RateLimited(
        override val ref: PageRef,
        override val detail: String,
        /** How long the wiki asked us to wait before trying again. */
        val retryAfter: Duration? = null,
    ) : Refused {
        override val isRetryable: Boolean get() = true
    }

    /** The wiki demanded a CAPTCHA, which a bot cannot answer. */
    public data class CaptchaRequired(
        override val ref: PageRef,
        override val detail: String,
        /** The captcha the wiki wants solved, which no bot can do unattended. */
        val captchaId: String? = null,
    ) : Refused

    /** The account may not make this edit at all: missing right, blocked, or creation denied. */
    public data class PermissionDenied(
        override val ref: PageRef,
        override val detail: String,
    ) : Refused

    /**
     * The page changed state under the edit: it was deleted, or created, between read and save.
     *
     * MediaWiki reports these separately from a plain conflict because the recovery differs —
     * the page may no longer be there to edit at all.
     */
    public data class PageStateChanged(
        override val ref: PageRef,
        override val detail: String,
        /** Whether the page was deleted rather than never having existed. */
        val wasDeleted: Boolean,
    ) : Refused {
        override val isRetryable: Boolean get() = true
    }

    /**
     * The wiki refused the edit for a reason this library does not model specifically.
     *
     * Carries the API error code, so a caller can still branch on it, and so an unmodelled
     * refusal is visible in logs rather than disguised as one of the cases above.
     */
    public data class Rejected(
        override val ref: PageRef,
        override val detail: String,
        /** The wiki's code for a refusal this library does not model. */
        val code: String,
    ) : Refused
}

/** Whether the edit created a new revision. `false` for a no-op and for every refusal. */
public val EditOutcome.didChange: Boolean
    get() = this is EditOutcome.Saved
