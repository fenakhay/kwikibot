package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.HistoryOrder
import com.fenakhay.kwikibot.client.service.RevisionPart
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.Revision
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/** The revisions of [page], newest first unless told. */
@Suppress("LongParameterList")
public fun Wiki.historyWithDefaults(
    page: String,
    order: HistoryOrder? = null,
    start: Instant? = null,
    end: Instant? = null,
    user: String? = null,
    excludeUser: String? = null,
    limit: Int? = null,
): Flow<Revision> =
    revisions.history(ref(page), order ?: HistoryOrder.NEWEST_FIRST, start, end, user, excludeUser, limit)

/** Every revision made across the wiki, in every namespace and newest first unless told. */
@Suppress("LongParameterList")
public fun Wiki.allRevisionsWithDefaults(
    namespaces: Collection<Int>? = null,
    user: String? = null,
    order: HistoryOrder? = null,
    start: Instant? = null,
    end: Instant? = null,
    limit: Int? = null,
): Flow<Revision> =
    revisions.allRevisions(
        namespacesOf(namespaces),
        user,
        order ?: HistoryOrder.NEWEST_FIRST,
        start,
        end,
        limit,
    )

/** The deleted revisions of [page], newest first unless told. */
public fun Wiki.deletedHistoryWithDefaults(
    page: String,
    order: HistoryOrder? = null,
    limit: Int? = null,
): Flow<Revision> = revisions.deletedHistory(ref(page), order ?: HistoryOrder.NEWEST_FIRST, limit)

/** Deleted revisions across the wiki, in the shape of [allRevisionsWithDefaults]. */
@Suppress("LongParameterList")
public fun Wiki.allDeletedRevisionsWithDefaults(
    namespaces: Collection<Int>? = null,
    user: String? = null,
    order: HistoryOrder? = null,
    start: Instant? = null,
    end: Instant? = null,
    limit: Int? = null,
): Flow<Revision> =
    revisions.allDeletedRevisions(
        namespacesOf(namespaces),
        user,
        order ?: HistoryOrder.NEWEST_FIRST,
        start,
        end,
        limit,
    )

/**
 * Hides [hide] and restores [show] of [revisions] of [page], neither unless given, and from administrators
 * too only when told to [suppress].
 */
@Suppress("LongParameterList")
public suspend fun Wiki.revisionDeleteWithDefaults(
    page: String,
    revisions: List<RevisionId>,
    hide: Collection<RevisionPart>? = null,
    show: Collection<RevisionPart>? = null,
    reason: String? = null,
    suppress: Boolean? = null,
) {
    this.revisions.revisionDelete(
        ref(page),
        revisions,
        hide?.toSet().orEmpty(),
        show?.toSet().orEmpty(),
        reason.orEmpty(),
        suppress ?: false,
    )
}
