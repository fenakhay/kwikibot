import com.fenakhay.kwikibot.bindings.client.eventsWithDefaults
import com.fenakhay.kwikibot.bindings.client.recentChangesWithDefaults
import com.fenakhay.kwikibot.bindings.client.watchlistChangesWithDefaults
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.service.TimeOrder as ClientTimeOrder
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.edit.Protection as ClientProtection
import com.fenakhay.kwikibot.model.log.LogDetails as ClientLogDetails
import com.fenakhay.kwikibot.model.log.LogEvent as ClientLogEvent
import com.fenakhay.kwikibot.model.log.RecentChange as ClientRecentChange
import com.fenakhay.kwikibot.model.title.NamespaceMap
import kotlin.js.Date
import kotlinx.coroutines.CoroutineScope

/** The options of `events`. */
@JsExport
public external interface LogEventsOptions {
    /**
     * The log to read, such as `"block"`, `"move"`, `"delete"`, `"upload"` or `"newusers"`. Every log unless
     * given.
     */
    public val type: String?

    /** A specific action, such as `"move/move_redir"`. It already names its log, so it wins over [type]. */
    public val action: String?

    /** Only this account's entries. */
    public val user: String?

    /** Only entries about this page. */
    public val page: String?

    /** Only entries about pages in these namespaces, by number. Every namespace unless given. */
    public val namespaces: Array<Int>?

    /** `"newest-first"`, the default, or `"oldest-first"`. */
    public val order: TimeOrder?

    /** Where in time to begin. */
    public val start: Date?

    /** Where in time to stop. */
    public val end: Date?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `recentChanges`. */
@JsExport
public external interface RecentChangesOptions {
    /** Which namespaces to watch, by number. Every namespace unless given. */
    public val namespaces: Array<Int>?

    /**
     * Which kinds to include, from `"edit"`, `"new"`, `"log"`, `"categorize"` and `"external"`. Every kind
     * unless given.
     */
    public val types: Array<ChangeType>?

    /** Only this account's changes. */
    public val user: String?

    /**
     * Constrains the flags: each is required, or left out with a leading `!`. `["!bot"]` leaves out edits
     * flagged as a bot's, which is usually what a bot watching for human activity wants.
     */
    public val show: Array<RecentChangeFilter>?

    /** Only changes carrying this change tag. */
    public val tag: String?

    /** Whether to give only changes that are still the newest revision of their page. */
    public val topOnly: Boolean?

    /** `"newest-first"`, the default, or `"oldest-first"`. */
    public val order: TimeOrder?

    /** Where in time to begin. */
    public val start: Date?

    /** Where in time to stop. */
    public val end: Date?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `watchlistChanges`. */
@JsExport
public external interface WatchlistChangesOptions {
    /** Which namespaces to watch, by number. Every namespace unless given. */
    public val namespaces: Array<Int>?

    /**
     * Which kinds to include, from `"edit"`, `"new"`, `"log"`, `"categorize"` and `"external"`. Every kind
     * unless given.
     */
    public val types: Array<ChangeType>?

    /** Constrains the flags: each is required, or left out with a leading `!`, such as `["!bot"]`. */
    public val show: Array<WatchlistFilter>?

    /** Whether to give every revision in the window rather than only the latest per page. */
    public val allRevisions: Boolean?

    /** `"newest-first"`, the default, or `"oldest-first"`. */
    public val order: TimeOrder?

    /** Where in time to begin. */
    public val start: Date?

    /** Where in time to stop. */
    public val end: Date?

    /** How many to give before stopping. */
    public val limit: Int?
}

/**
 * What has happened on a wiki, reached as `wiki.logs`. Each listing pages as it is walked, and an option that
 * names none of its values throws `IllegalArgumentException` when the listing is asked for.
 */
@JsExport
public class LogService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    /** Entries from the wiki's logs, every log unless told. */
    public fun events(options: LogEventsOptions? = null): AsyncIterable<LogEvent> = listing {
        wiki.eventsWithDefaults(
            options?.type,
            options?.action,
            options?.user,
            options?.page,
            options?.namespaces?.asList(),
            timeOrder(options?.order),
            options?.start?.let(::instantOf),
            options?.end?.let(::instantOf),
            options?.limit,
        )
    }
        .toAsyncIterable(scope) { LogEvent(it, wiki.namespaces) }

    /** Recent changes, which mixes edits, page creations and log actions in one stream. */
    public fun recentChanges(options: RecentChangesOptions? = null): AsyncIterable<RecentChange> = listing {
        wiki.recentChangesWithDefaults(
            options?.namespaces?.asList(),
            options?.types?.map { it.unsafeCast<String>() },
            options?.user,
            options?.show?.map { it.unsafeCast<String>() },
            options?.tag,
            options?.topOnly,
            timeOrder(options?.order),
            options?.start?.let(::instantOf),
            options?.end?.let(::instantOf),
            options?.limit,
        )
    }
        .toAsyncIterable(scope) { RecentChange(it, wiki.namespaces) }

    /** Changes to the pages on the watchlist of the account logged in. */
    public fun watchlistChanges(options: WatchlistChangesOptions? = null): AsyncIterable<RecentChange> =
        listing {
            wiki.watchlistChangesWithDefaults(
                options?.namespaces?.asList(),
                options?.types?.map { it.unsafeCast<String>() },
                options?.show?.map { it.unsafeCast<String>() },
                options?.allRevisions,
                timeOrder(options?.order),
                options?.start?.let(::instantOf),
                options?.end?.let(::instantOf),
                options?.limit,
            )
        }
        .toAsyncIterable(scope) { RecentChange(it, wiki.namespaces) }

    private fun timeOrder(order: TimeOrder?): ClientTimeOrder? = enumOf("order", order)
}

/** One entry from a wiki log: a block, a move, a deletion, an upload. */
@JsExport
public class LogEvent
internal constructor(private val event: ClientLogEvent, private val namespaces: NamespaceMap) {

    /** The entry's own id, which orders the log and never repeats. */
    public val id: Double
        get() = event.id.toDouble()

    /** The log it belongs to, such as `"block"`, `"move"`, `"delete"`, `"upload"` or `"newusers"`. */
    public val type: String
        get() = event.type

    /** What was done, such as `"block"`, `"reblock"`, `"move_redir"`, `"revision"` or `"overwrite"`. */
    public val action: String
        get() = event.action

    /** The title of the page it is about, or `null` when that is hidden. */
    public val page: String?
        get() = event.page?.let { namespaces.format(it.title) }

    /** The wiki's own id for that page, or `null` when the wiki did not say. */
    public val pageId: Double?
        get() = event.page?.pageId?.value?.toDouble()?.takeIf { it != 0.0 }

    /** Who did it, or `null` when that is hidden. */
    public val user: String?
        get() = event.user

    /** When it was done. */
    public val timestamp: Date
        get() = dateOf(event.timestamp)

    /** The reason given, or `null` when that is hidden. */
    public val comment: String?
        get() = event.comment

    /** The change tags on it. */
    public val tags: Array<String>
        get() = event.tags.toTypedArray()

    /** The fields particular to this kind of entry. */
    public val details: LogDetails
        get() = LogDetails(event.details, namespaces)

    /** Whether any part of it is hidden. */
    public val isSuppressed: Boolean
        get() = event.isSuppressed
}

/**
 * The part of a log entry that depends on what kind of entry it is. [kind] says which, and only the fields it
 * names are set: the others are `null`.
 */
@JsExport
public class LogDetails
internal constructor(private val details: ClientLogDetails, private val namespaces: NamespaceMap) {

    /**
     * `"none"` for an entry with no fields of its own; `"move"`, with [target] and [suppressedRedirect];
     * `"block"`, with [duration], [expiry], [flags], [isPartial] and [isInfinite]; `"upload"`, with [sha1];
     * `"protect"`, with [protections] and [description]; `"rights"`, with [added] and [removed]; `"patrol"`,
     * with [revision], [previous] and [automatic]; `"unknown"` for a log this library does not model, with
     * [fields].
     */
    public val kind: LogDetailsKind
        get() =
            when (details) {
                ClientLogDetails.None -> "none"
                is ClientLogDetails.Move -> "move"
                is ClientLogDetails.Block -> "block"
                is ClientLogDetails.Upload -> "upload"
                is ClientLogDetails.Protect -> "protect"
                is ClientLogDetails.Rights -> "rights"
                is ClientLogDetails.Patrol -> "patrol"
                is ClientLogDetails.Unknown -> "unknown"
            }.unsafeCast<LogDetailsKind>()

    /** Where a moved page went. */
    public val target: String?
        get() = (details as? ClientLogDetails.Move)?.let { namespaces.format(it.target) }

    /** Whether the mover suppressed the redirect a move normally leaves behind. */
    public val suppressedRedirect: Boolean?
        get() = (details as? ClientLogDetails.Move)?.suppressedRedirect

    /** A block's duration as entered, such as `"1 week"`, or `null` on an unblock. */
    public val duration: String?
        get() = (details as? ClientLogDetails.Block)?.duration

    /** When a block ends, or `null` if it never does, and on an unblock. */
    public val expiry: Date?
        get() = (details as? ClientLogDetails.Block)?.expiry?.let(::dateOf)

    /** A block's flags, such as `"nocreate"`, `"noemail"` or `"autoblock"`. */
    public val flags: Array<String>?
        get() = (details as? ClientLogDetails.Block)?.flags?.toTypedArray()

    /** Whether a block covers only some pages or actions rather than the whole wiki. */
    public val isPartial: Boolean?
        get() = (details as? ClientLogDetails.Block)?.isPartial

    /** Whether a block never expires, which an unblock also says: read the entry's action first. */
    public val isInfinite: Boolean?
        get() = (details as? ClientLogDetails.Block)?.isInfinite

    /** The hash of an uploaded file. */
    public val sha1: String?
        get() = (details as? ClientLogDetails.Upload)?.sha1

    /** The restrictions a change of protection set. */
    public val protections: Array<Protection>?
        get() = (details as? ClientLogDetails.Protect)?.protections?.map(::Protection)?.toTypedArray()

    /** The wiki's own summary of a change of protection, meant for a reader. */
    public val description: String?
        get() = (details as? ClientLogDetails.Protect)?.description

    /** The groups a user gained. */
    public val added: Array<String>?
        get() = (details as? ClientLogDetails.Rights)?.added?.toTypedArray()

    /** The groups a user lost. */
    public val removed: Array<String>?
        get() = (details as? ClientLogDetails.Rights)?.removed?.toTypedArray()

    /** The revision marked patrolled. */
    public val revision: Double?
        get() = (details as? ClientLogDetails.Patrol)?.revision?.value?.toDouble()?.takeIf { it != 0.0 }

    /** The revision patrolled before it, when the log records one. */
    public val previous: Double?
        get() = (details as? ClientLogDetails.Patrol)?.previous?.value?.toDouble()

    /** Whether the wiki patrolled it by itself. */
    public val automatic: Boolean?
        get() = (details as? ClientLogDetails.Patrol)?.automatic

    /** An unmodelled entry's own scalar fields under the API's names, as strings. */
    public val fields: Record<String, String>?
        get() = (details as? ClientLogDetails.Unknown)?.fields?.toRecord { it }
}

/** One restriction on a page: an action, who may still do it, and until when. */
@JsExport
public class Protection internal constructor(private val protection: ClientProtection) {

    /** The restricted action, such as `"edit"` or `"move"`. */
    public val action: String
        get() = protection.action

    /** The group that may still do it, such as `"sysop"` or `"autoconfirmed"`. */
    public val level: String
        get() = protection.level

    /** When the restriction lapses, or `null` if it does not. */
    public val expiry: Date?
        get() = (protection.expiry as? Expiry.At)?.let { dateOf(it.instant) }

    /** Whether it propagates to transcluded pages. */
    public val cascading: Boolean
        get() = protection.cascading

    /** Whether it is still in force at [now]. */
    public fun isActiveAt(now: Date): Boolean = protection.isActiveAt(instantOf(now))
}

/** One entry from recent changes or a watchlist: an edit, a page creation or a log action. */
@JsExport
public class RecentChange
internal constructor(private val change: ClientRecentChange, private val namespaces: NamespaceMap) {

    /** The recent-changes id, which orders the stream. */
    public val id: Double
        get() = change.id.toDouble()

    /** `"edit"`, `"new"`, `"log"`, `"categorize"` or `"external"`. */
    public val type: ChangeType
        get() = change.type.unsafeCast<ChangeType>()

    /** The title of the page changed, or `null` when that is hidden. */
    public val page: String?
        get() = change.page?.let { namespaces.format(it.title) }

    /** The wiki's own id for that page, or `null` when the wiki did not say. */
    public val pageId: Double?
        get() = change.page?.pageId?.value?.toDouble()?.takeIf { it != 0.0 }

    /** Who changed it, or `null` when that is hidden. */
    public val user: String?
        get() = change.user

    /** When it happened. */
    public val timestamp: Date
        get() = dateOf(change.timestamp)

    /** The edit summary or log reason, or `null` when that is hidden. */
    public val comment: String?
        get() = change.comment

    /** The revision it produced, or `null` on a log entry. */
    public val revision: Double?
        get() = change.revisionId?.value?.toDouble()

    /** The revision it was made from, or `null` on a page creation and on a log entry. */
    public val previousRevision: Double?
        get() = change.previousRevisionId?.value?.toDouble()

    /** How many bytes the page gained, negative for a removal. */
    public val sizeChange: Int
        get() = change.sizeChange

    /** Whether the editor marked it minor. */
    public val minor: Boolean
        get() = change.isMinor

    /** Whether it was flagged a bot edit. */
    public val bot: Boolean
        get() = change.isBot

    /** Whether the edit created the page. */
    public val isNew: Boolean
        get() = change.isNew

    /**
     * Whether somebody has reviewed it, also `false` where that is not known, which [patrolled] tells apart.
     */
    public val isPatrolled: Boolean
        get() = change.isPatrolled

    /** The log entry, when this is a log action rather than an edit. */
    public val logEvent: LogEvent?
        get() = change.logEvent?.let { LogEvent(it, namespaces) }

    /** The change tags on it. */
    public val tags: Array<String>
        get() = change.tags.toTypedArray()

    /**
     * Whether somebody has reviewed it, or `null` where the wiki did not say: it tells only a session with
     * the `patrol` or `patrolmarks` right, on a wiki that patrols.
     */
    public val patrolled: Boolean?
        get() = change.patrolled

    /** Whether this is a log action rather than an edit. */
    public val isLogEntry: Boolean
        get() = change.isLogEntry
}
