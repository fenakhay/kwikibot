package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.LogEvent
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.RecentChange
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ActivityDecoder
import com.fenakhay.kwikibot.protocol.Continuation
import com.fenakhay.kwikibot.protocol.OptionSet
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take

/** Which way through time a stream of activity runs. */
public enum class TimeOrder(internal val apiValue: String) {
    /** Newest first, which is what a log page shows. */
    NEWEST_FIRST("older"),

    /** Oldest first, which is what catching up from a saved position needs. */
    OLDEST_FIRST("newer"),
}

/**
 * What has happened on a wiki: its logs and its recent changes.
 *
 * Both are cold [Flow]s that page as they are collected, so a bot watching for one kind of event
 * pays for what it reads rather than for the whole log.
 */
public interface LogService {

    /**
     * Entries from the wiki logs.
     *
     * @param type the log to read: `block`, `move`, `delete`, `upload`, `newusers`. `null`
     *   reads every log, which is what the combined log page shows.
     * @param action a specific action within that log, such as `move_redir`.
     * @param user only this account's entries.
     * @param page only entries about this page.
     * @param namespaces only entries about pages in these namespaces.
     * @param order newest first by default.
     * @param start where in time to begin.
     * @param end where in time to stop.
     * @param limit how many to emit before stopping.
     */
    public fun events(
        type: String? = null,
        action: String? = null,
        user: String? = null,
        page: PageRef? = null,
        namespaces: Set<Namespace> = emptySet(),
        order: TimeOrder = TimeOrder.NEWEST_FIRST,
        start: Instant? = null,
        end: Instant? = null,
        limit: Int? = null,
    ): Flow<LogEvent>

    /**
     * Recent changes, which mixes edits, page creations and log actions in one stream.
     *
     * @param namespaces which namespaces to watch. Empty means all of them.
     * @param types which kinds to include: `edit`, `new`, `log`, `categorize`. Empty means
     *   all.
     * @param user only this account's changes.
     * @param show constrains the flags: `show.off("bot")` leaves out edits already flagged
     *   as a bot's, which is usually what a bot watching for human activity wants.
     * @param tag only changes carrying this change tag.
     * @param topOnly only changes that are still the newest revision of their page.
     * @param order newest first by default.
     * @param start where in time to begin.
     * @param end where in time to stop.
     * @param limit how many to emit before stopping. `null` reads until exhausted.
     */
    public fun recentChanges(
        namespaces: Set<Namespace> = emptySet(),
        types: Set<String> = emptySet(),
        user: String? = null,
        show: OptionSet = OptionSet(),
        tag: String? = null,
        topOnly: Boolean = false,
        order: TimeOrder = TimeOrder.NEWEST_FIRST,
        start: Instant? = null,
        end: Instant? = null,
        limit: Int? = null,
    ): Flow<RecentChange>

    /**
     * Changes to the pages on this session's watchlist.
     *
     * The changes, where `ListService.watchlist` gives the titles. A bot that watches what it
     * edits reads this to see whether anyone has touched its work.
     *
     * @param namespaces which namespaces to watch. Empty means all of them.
     * @param types which kinds to include. Empty means all.
     * @param show constrains the flags, as on [recentChanges].
     * @param allRevisions every revision in the window rather than only the latest per
     *   page.
     * @param order newest first by default.
     * @param start where in time to begin.
     * @param end where in time to stop.
     * @param limit how many to emit before stopping.
     */
    public fun watchlistChanges(
        namespaces: Set<Namespace> = emptySet(),
        types: Set<String> = emptySet(),
        show: OptionSet = OptionSet(),
        allRevisions: Boolean = false,
        order: TimeOrder = TimeOrder.NEWEST_FIRST,
        start: Instant? = null,
        end: Instant? = null,
        limit: Int? = null,
    ): Flow<RecentChange>
}

internal class ApiLogService(
    transport: MediaWikiTransport,
    private val activity: ActivityDecoder,
    private val namespaces: NamespaceMap,
) : LogService {

    private val continuation = Continuation(transport)

    override fun events(
        type: String?,
        action: String?,
        user: String?,
        page: PageRef?,
        namespaces: Set<Namespace>,
        order: TimeOrder,
        start: Instant?,
        end: Instant?,
        limit: Int?,
    ): Flow<LogEvent> {
        val flow = continuation.list(
            ApiRequest.of(
                "query",
                "list" to "logevents",
                // The API takes one or the other: "action" already names its log.
                "leaction" to action,
                "letype" to type?.takeIf { action == null },
                "leuser" to user,
                "letitle" to page?.let { this.namespaces.format(it.title) },
                "lenamespace" to namespaces.takeIf { it.isNotEmpty() }
                    ?.joinToString("|") { it.id.toString() },
                "ledir" to order.apiValue,
                "lestart" to start?.let { MwTimestamp.format(it) },
                "leend" to end?.let { MwTimestamp.format(it) },
                "leprop" to LOG_PROPS,
                "lelimit" to apiLimit(limit),
            ),
            "logevents",
        ).map { activity.decodeLogEvent(it) }

        return if (limit == null) flow else flow.take(limit)
    }

    override fun recentChanges(
        namespaces: Set<Namespace>,
        types: Set<String>,
        user: String?,
        show: OptionSet,
        tag: String?,
        topOnly: Boolean,
        order: TimeOrder,
        start: Instant?,
        end: Instant?,
        limit: Int?,
    ): Flow<RecentChange> {
        val flow = continuation.list(
            ApiRequest.of(
                "query",
                "list" to "recentchanges",
                "rcnamespace" to namespaces.takeIf { it.isNotEmpty() }
                    ?.joinToString("|") { it.id.toString() },
                "rctype" to types.takeIf { it.isNotEmpty() }?.joinToString("|"),
                "rcuser" to user,
                "rcshow" to show.toParam(),
                "rctag" to tag,
                "rctoponly" to if (topOnly) "1" else null,
                "rcdir" to order.apiValue,
                "rcstart" to start?.let { MwTimestamp.format(it) },
                "rcend" to end?.let { MwTimestamp.format(it) },
                "rcprop" to RECENT_CHANGE_PROPS,
                "rclimit" to apiLimit(limit),
            ),
            "recentchanges",
        ).map { activity.decodeRecentChange(it) }

        return if (limit == null) flow else flow.take(limit)
    }

    override fun watchlistChanges(
        namespaces: Set<Namespace>,
        types: Set<String>,
        show: OptionSet,
        allRevisions: Boolean,
        order: TimeOrder,
        start: Instant?,
        end: Instant?,
        limit: Int?,
    ): Flow<RecentChange> {
        val flow = continuation.list(
            ApiRequest.of(
                "query",
                "list" to "watchlist",
                "wlnamespace" to namespaces.takeIf { it.isNotEmpty() }
                    ?.joinToString("|") { it.id.toString() },
                "wltype" to types.takeIf { it.isNotEmpty() }?.joinToString("|"),
                "wlshow" to show.toParam(),
                "wlallrev" to if (allRevisions) "1" else null,
                "wldir" to order.apiValue,
                "wlstart" to start?.let { MwTimestamp.format(it) },
                "wlend" to end?.let { MwTimestamp.format(it) },
                "wlprop" to RECENT_CHANGE_PROPS,
                "wllimit" to apiLimit(limit),
            ),
            "watchlist",
        ).map { activity.decodeRecentChange(it) }

        return if (limit == null) flow else flow.take(limit)
    }

    private fun apiLimit(limit: Int?): String =
        if (limit != null && limit < MAX_BATCH) limit.toString() else "max"

    private companion object {
        const val MAX_BATCH = 500
        const val LOG_PROPS = "ids|title|type|user|timestamp|comment|details|tags"
        const val RECENT_CHANGE_PROPS =
            "ids|title|timestamp|user|comment|flags|sizes|loginfo|patrolled|tags"
    }
}
