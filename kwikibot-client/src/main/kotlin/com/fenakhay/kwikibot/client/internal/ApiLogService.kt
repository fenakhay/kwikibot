package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.internal.wire.Registry
import com.fenakhay.kwikibot.client.service.LogService
import com.fenakhay.kwikibot.client.service.TimeOrder
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.log.LogEvent
import com.fenakhay.kwikibot.model.log.RecentChange
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.decode.ActivityDecoder
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.OptionSet
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take

internal class ApiLogService(
    transport: MediaWikiTransport,
    private val activity: ActivityDecoder,
    private val namespaces: NamespaceMap,
    identity: Identity,
) : LogService {

    private val continuation = Continuation(transport)

    /**
     * Whether this session may ask if a change was patrolled.
     *
     * MediaWiki refuses the flag, and with it the whole query, to an account holding neither right, which is
     * every anonymous client, and a bot unless one of its groups grants one: MediaWiki's own `bot` group does
     * not. The rights were read at login, so deciding costs no request. A wiki with patrolling switched off
     * refuses the flag even to an account that holds one.
     */
    private val seesPatrol = PATROL_RIGHTS.any { it in identity }

    private val recentChangeProps =
        if (seesPatrol) Registry.RECENT_CHANGES else Registry.RECENT_CHANGES_WITHOUT_PATROL

    private val watchlistProps = if (seesPatrol) Registry.WATCHLIST else Registry.WATCHLIST_WITHOUT_PATROL

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
        val flow =
            continuation
                .list(
                    ApiRequest.of(
                        "query",
                        "list" to "logevents",
                        // The API takes one or the other: "action" already names its log.
                        "leaction" to action,
                        "letype" to type?.takeIf { action == null },
                        "leuser" to user,
                        "letitle" to page?.let { this.namespaces.format(it.title) },
                        "lenamespace" to
                            namespaces.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
                        "ledir" to order.apiValue,
                        "lestart" to start?.let { MwTimestamp.format(it) },
                        "leend" to end?.let { MwTimestamp.format(it) },
                        "leprop" to Registry.LOG_EVENTS.joined,
                        "lelimit" to apiLimit(limit),
                    ),
                    "logevents",
                )
                .map { activity.decodeLogEvent(it) }

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
        val flow =
            continuation
                .list(
                    ApiRequest.of(
                        "query",
                        "list" to "recentchanges",
                        "rcnamespace" to
                            namespaces.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
                        "rctype" to types.takeIf { it.isNotEmpty() }?.joinToString("|"),
                        "rcuser" to user,
                        "rcshow" to show.toParam(),
                        "rctag" to tag,
                        "rctoponly" to if (topOnly) "1" else null,
                        "rcdir" to order.apiValue,
                        "rcstart" to start?.let { MwTimestamp.format(it) },
                        "rcend" to end?.let { MwTimestamp.format(it) },
                        "rcprop" to recentChangeProps.joined,
                        "rclimit" to apiLimit(limit),
                    ),
                    "recentchanges",
                )
                .map { activity.decodeRecentChange(it) }

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
        val flow =
            continuation
                .list(
                    ApiRequest.of(
                        "query",
                        "list" to "watchlist",
                        "wlnamespace" to
                            namespaces.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
                        "wltype" to types.takeIf { it.isNotEmpty() }?.joinToString("|"),
                        "wlshow" to show.toParam(),
                        "wlallrev" to if (allRevisions) "1" else null,
                        "wldir" to order.apiValue,
                        "wlstart" to start?.let { MwTimestamp.format(it) },
                        "wlend" to end?.let { MwTimestamp.format(it) },
                        "wlprop" to watchlistProps.joined,
                        "wllimit" to apiLimit(limit),
                    ),
                    "watchlist",
                )
                .map { activity.decodeRecentChange(it) }

        return if (limit == null) flow else flow.take(limit)
    }

    private fun apiLimit(limit: Int?): String =
        if (limit != null && limit < MAX_BATCH) limit.toString() else "max"

    private companion object {
        const val MAX_BATCH = 500

        /** Either one lets a session see the patrol flag. */
        val PATROL_RIGHTS = setOf("patrol", "patrolmarks")
    }
}
