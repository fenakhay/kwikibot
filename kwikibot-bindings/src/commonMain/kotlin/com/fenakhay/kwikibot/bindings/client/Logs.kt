package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.TimeOrder
import com.fenakhay.kwikibot.model.log.LogEvent
import com.fenakhay.kwikibot.model.log.RecentChange
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/** Entries from the wiki's logs: every log, every namespace, newest first unless told. */
@Suppress("LongParameterList")
public fun Wiki.eventsWithDefaults(
    type: String? = null,
    action: String? = null,
    user: String? = null,
    page: String? = null,
    namespaces: Collection<Int>? = null,
    order: TimeOrder? = null,
    start: Instant? = null,
    end: Instant? = null,
    limit: Int? = null,
): Flow<LogEvent> =
    logs.events(
        type,
        action,
        user,
        page?.let { ref(it) },
        namespacesOf(namespaces),
        order ?: TimeOrder.NEWEST_FIRST,
        start,
        end,
        limit,
    )

/**
 * Recent changes: of every type, in every namespace, with no filter, every revision rather than the latest
 * per page, newest first unless told. [show] names filters, each negated with a leading `!`.
 */
@Suppress("LongParameterList")
public fun Wiki.recentChangesWithDefaults(
    namespaces: Collection<Int>? = null,
    types: Collection<String>? = null,
    user: String? = null,
    show: Collection<String>? = null,
    tag: String? = null,
    topOnly: Boolean? = null,
    order: TimeOrder? = null,
    start: Instant? = null,
    end: Instant? = null,
    limit: Int? = null,
): Flow<RecentChange> =
    logs.recentChanges(
        namespacesOf(namespaces),
        types?.toSet().orEmpty(),
        user,
        optionSetOf(show),
        tag,
        topOnly ?: false,
        order ?: TimeOrder.NEWEST_FIRST,
        start,
        end,
        limit,
    )

/**
 * Changes to the watched pages: of every type, in every namespace, with no filter, the latest per page,
 * newest first unless told.
 */
@Suppress("LongParameterList")
public fun Wiki.watchlistChangesWithDefaults(
    namespaces: Collection<Int>? = null,
    types: Collection<String>? = null,
    show: Collection<String>? = null,
    allRevisions: Boolean? = null,
    order: TimeOrder? = null,
    start: Instant? = null,
    end: Instant? = null,
    limit: Int? = null,
): Flow<RecentChange> =
    logs.watchlistChanges(
        namespacesOf(namespaces),
        types?.toSet().orEmpty(),
        optionSetOf(show),
        allRevisions ?: false,
        order ?: TimeOrder.NEWEST_FIRST,
        start,
        end,
        limit,
    )
