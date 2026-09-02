package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.model.log.LogEvent
import com.fenakhay.kwikibot.model.log.RecentChange
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.protocol.decode.OptionSet
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

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
 * Both are cold [Flow]s that page as they are collected, so a bot watching for one kind of event pays for
 * what it reads rather than for the whole log.
 */
public interface LogService {

    /**
     * Entries from the wiki logs.
     *
     * @param type the log to read: `block`, `move`, `delete`, `upload`, `newusers`. `null` reads every log,
     *   which is what the combined log page shows.
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
     * @param types which kinds to include: `edit`, `new`, `log`, `categorize`. Empty means all.
     * @param user only this account's changes.
     * @param show constrains the flags: `show.off("bot")` leaves out edits already flagged as a bot's, which
     *   is usually what a bot watching for human activity wants.
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
     * The changes, where `ListService.watchlist` gives the titles. A bot that watches what it edits reads
     * this to see whether anyone has touched its work.
     *
     * @param namespaces which namespaces to watch. Empty means all of them.
     * @param types which kinds to include. Empty means all.
     * @param show constrains the flags, as on [recentChanges].
     * @param allRevisions every revision in the window rather than only the latest per page.
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
