package com.fenakhay.kwikibot.model.log

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Title
import kotlin.time.Instant

/**
 * One entry from a wiki log: a block, a move, a deletion, an upload.
 *
 * Shared fields are on the entry; the type-specific ones are a sealed [LogDetails] beside it. Composition
 * rather than a subclass per log type, so reading a log needs no downcast and an unmodelled type still
 * arrives with its fields intact.
 */
public data class LogEvent(
    /** The entry's own id, which orders the log and never repeats. */
    val id: Long,
    /** The log this entry belongs to: `block`, `move`, `delete`, `upload`, `newusers`. */
    val type: String,
    /** What was done: `block`, `reblock`, `move_redir`, `revision`, `overwrite`. */
    val action: String,
    /**
     * The page the entry is about, or `null` if it was hidden.
     *
     * Log entries can be suppressed field by field, so an absent page is a fact about the entry rather than a
     * decoding failure.
     */
    val page: PageRef?,
    /** Who performed the action, or `null` if hidden. */
    val user: String?,
    /** When the action was taken. */
    val timestamp: Instant,
    /** The reason given, or `null` if hidden. */
    val comment: String?,
    /** Change tags on the entry, which is how filtered log queries find it. */
    val tags: List<String> = emptyList(),
    /** The fields particular to this kind of entry, or [LogDetails.None] if it has none. */
    val details: LogDetails = LogDetails.None,
) {
    /** Whether any part of this entry was hidden by log suppression. */
    val isSuppressed: Boolean
        get() = page == null || user == null || comment == null
}

/**
 * The part of a log entry that depends on what kind of entry it is.
 *
 * Sealed, so handling a log means saying what happens for each kind; [Unknown] keeps the raw fields of a log
 * type this library does not model, rather than dropping them.
 */
public sealed interface LogDetails {

    /** An entry whose type carries no extra fields. */
    public data object None : LogDetails

    /** A page rename. */
    public data class Move(
        /** Where the page went. The title it came from is the entry's own page. */
        val target: Title.Local,
        /** Whether the mover suppressed the redirect that a move normally leaves behind. */
        val suppressedRedirect: Boolean = false,
    ) : LogDetails

    /** A block or an unblock. */
    public data class Block(
        /** As entered: `1 week`, `infinite`. Absent on an unblock. */
        val duration: String? = null,
        /**
         * When the block ends, or `null` if it never does.
         *
         * Also `null` on an unblock, which has no expiry to report — read the entry's action before reading
         * this.
         */
        val expiry: Instant? = null,
        /** `nocreate`, `noemail`, `autoblock`, and the rest. */
        val flags: List<String> = emptyList(),
        /** Whether the block covers only some pages or actions rather than the whole wiki. */
        val isPartial: Boolean = false,
    ) : LogDetails {
        /**
         * Whether the block never expires.
         *
         * True for an unblock as well, which carries no expiry either. Check the entry's action first if that
         * distinction matters.
         */
        val isInfinite: Boolean
            get() = expiry == null
    }

    /** A file upload or re-upload. */
    public data class Upload(
        /** The hash of the uploaded file, which identifies it across renames and re-uploads. */
        val sha1: String? = null
    ) : LogDetails

    /**
     * A change to page protection.
     *
     * The log records both a rendered description meant for a human and the restrictions themselves; only the
     * second is worth acting on, which is why both are kept.
     */
    public data class Protect(
        /** The restrictions themselves, which is the half worth acting on. */
        val protections: List<Protection> = emptyList(),
        /** The wiki's rendered summary of the change, meant for a reader rather than a program. */
        val description: String? = null,
    ) : LogDetails

    /** A change to a user's groups. */
    public data class Rights(
        /** Groups the user gained. */
        val added: List<String> = emptyList(),
        /** Groups the user lost. */
        val removed: List<String> = emptyList(),
    ) : LogDetails

    /** A revision being marked patrolled. */
    public data class Patrol(
        /** The revision that was marked patrolled. */
        val revision: RevisionId,
        /** The revision patrolled before it, when the log records one. */
        val previous: RevisionId? = null,
        /** Whether the wiki patrolled it automatically rather than a person doing it. */
        val automatic: Boolean = false,
    ) : LogDetails

    /** A log type this library does not model, with its fields kept as the API sent them. */
    public data class Unknown(
        /** The entry's own fields, as the API named and spelled them. */
        val fields: Map<String, String>
    ) : LogDetails
}

/**
 * One entry from recent changes.
 *
 * Recent changes mixes edits, page creations and log entries in one stream, which is why the revision and the
 * log entry are both nullable: an entry has one or the other, never both.
 */
public data class RecentChange(
    /** The recent-changes id, which orders the stream. */
    val id: Long,
    /** `edit`, `new`, `log`, `categorize`, `external`. */
    val type: String,
    /** The page changed, or `null` where the entry has been suppressed. */
    val page: PageRef?,
    /** Who changed it, or `null` where that has been suppressed. */
    val user: String?,
    /** When the change happened. */
    val timestamp: Instant,
    /** The edit summary or log reason, or `null` where suppressed. */
    val comment: String?,
    /** The revision the change produced, absent on a log entry. */
    val revisionId: RevisionId? = null,
    /** The revision it was made from, absent on a page creation and on a log entry. */
    val previousRevisionId: RevisionId? = null,
    /** How many bytes the page gained, negative for a removal. */
    val sizeChange: Int = 0,
    /** Whether the editor marked it minor. */
    val isMinor: Boolean = false,
    /** Whether it was flagged a bot edit, and so hidden from the default view. */
    val isBot: Boolean = false,
    /** Whether the edit created the page. */
    val isNew: Boolean = false,
    /** Whether somebody has reviewed it. Always false on a wiki without patrolling. */
    val isPatrolled: Boolean = false,
    /** The log entry, when this stream position is a log action rather than an edit. */
    val logEvent: LogEvent? = null,
    /** Change tags on the entry. */
    val tags: List<String> = emptyList(),
) {
    /** Whether this entry is a log action rather than an edit. */
    val isLogEntry: Boolean
        get() = logEvent != null
}
