package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.Revision
import com.fenakhay.kwikibot.model.title.Namespace
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/** Which end of a page history to start from. */
public enum class HistoryOrder(internal val apiValue: String) {
    /** Newest first, which is what a page history shows. */
    NEWEST_FIRST("older"),

    /** Oldest first, which is what "who created this page" needs. */
    OLDEST_FIRST("newer"),
}

/**
 * The history of a page.
 *
 * Separate from [PageService] because the questions are different: page services are about the page as it is
 * now, these are about how it got there.
 */
public interface RevisionService {

    /**
     * The revisions of a page, newest first unless told otherwise.
     *
     * A cold [Flow], so asking who made the last edit costs one request even on a page with fifty thousand of
     * them.
     */
    public fun history(
        page: PageRef,
        order: HistoryOrder = HistoryOrder.NEWEST_FIRST,
        start: Instant? = null,
        end: Instant? = null,
        user: String? = null,
        excludeUser: String? = null,
        limit: Int? = null,
    ): Flow<Revision>

    /** The page as it was at one revision, or `null` if there is no such revision. */
    public suspend fun contentAt(revision: RevisionId): PageContent?

    /** Revisions by id, for following a log entry or a diff back to what it changed. */
    public suspend fun byId(ids: Collection<RevisionId>): Map<RevisionId, Revision>

    /**
     * The rendered difference between two revisions, as the wiki draws it.
     *
     * HTML rather than a unified diff: this is the wiki rendering its own diff, which is what a report links
     * to. For a text diff of content a bot already holds, use `Diffs.unified`.
     */
    public suspend fun compare(from: RevisionId, to: RevisionId): String

    /**
     * Every revision made across the wiki in a window.
     *
     * Reaches past the thirty days `LogService.recentChanges` keeps, at the cost of being a much heavier
     * query. For a window inside those thirty days, use recent changes.
     */
    public fun allRevisions(
        namespaces: Set<Namespace> = emptySet(),
        user: String? = null,
        order: HistoryOrder = HistoryOrder.NEWEST_FIRST,
        start: Instant? = null,
        end: Instant? = null,
        limit: Int? = null,
    ): Flow<Revision>

    /**
     * The deleted revisions of a page. Needs the `deletedhistory` right.
     *
     * Untested against a live wiki: this account does not hold the right.
     */
    public fun deletedHistory(
        page: PageRef,
        order: HistoryOrder = HistoryOrder.NEWEST_FIRST,
        limit: Int? = null,
    ): Flow<Revision>

    /**
     * Deleted revisions across the wiki, in the shape of [allRevisions].
     *
     * Untested against a live wiki: needs the `deletedhistory` right.
     */
    public fun allDeletedRevisions(
        namespaces: Set<Namespace> = emptySet(),
        user: String? = null,
        order: HistoryOrder = HistoryOrder.NEWEST_FIRST,
        start: Instant? = null,
        end: Instant? = null,
        limit: Int? = null,
    ): Flow<Revision>

    /**
     * Hides or restores parts of revisions. Needs the `deleterevision` right.
     *
     * Untested against a live wiki: this account does not hold the right.
     *
     * @param page the page the revisions belong to.
     * @param revisions the revisions to act on.
     * @param hide what to hide, from `content`, `comment` and `user`.
     * @param show what to restore, which is the same set read the other way.
     * @param reason the log summary.
     * @param suppress hide from administrators too, which needs `suppressrevision` on top.
     */
    public suspend fun revisionDelete(
        page: PageRef,
        revisions: Collection<RevisionId>,
        hide: Set<RevisionPart> = emptySet(),
        show: Set<RevisionPart> = emptySet(),
        reason: String = "",
        suppress: Boolean = false,
    )
}
