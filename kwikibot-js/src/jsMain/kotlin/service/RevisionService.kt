import com.fenakhay.kwikibot.bindings.client.allDeletedRevisionsWithDefaults
import com.fenakhay.kwikibot.bindings.client.allRevisionsWithDefaults
import com.fenakhay.kwikibot.bindings.client.deletedHistoryWithDefaults
import com.fenakhay.kwikibot.bindings.client.historyWithDefaults
import com.fenakhay.kwikibot.bindings.client.revisionDeleteWithDefaults
import com.fenakhay.kwikibot.bindings.common.enumArgument
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.service.HistoryOrder
import com.fenakhay.kwikibot.client.service.RevisionPart as ClientRevisionPart
import com.fenakhay.kwikibot.model.RevisionId
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise

/** The options of `history`. */
@JsExport
public external interface HistoryOptions {
    /** `"newest-first"`, the default, or `"oldest-first"`. */
    public val order: TimeOrder?

    /** Where in time to begin. */
    public val start: Date?

    /** Where in time to stop. */
    public val end: Date?

    /** Only this account's revisions. */
    public val user: String?

    /** Leave out this account's revisions. */
    public val excludeUser: String?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `allRevisions` and `allDeletedRevisions`. */
@JsExport
public external interface AllRevisionsOptions {
    /** Only revisions in these namespaces, by number. Every namespace unless given. */
    public val namespaces: Array<Int>?

    /** Only this account's revisions. */
    public val user: String?

    /** `"newest-first"`, the default, or `"oldest-first"`. */
    public val order: TimeOrder?

    /** Where in time to begin. */
    public val start: Date?

    /** Where in time to stop. */
    public val end: Date?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `revisionDelete`, which needs [hide] or [show] to have something in it. */
@JsExport
public external interface RevisionDeleteOptions {
    /** What to hide, from `"content"`, `"comment"` and `"user"`. */
    public val hide: Array<RevisionPart>?

    /** What to restore, from `"content"`, `"comment"` and `"user"`. */
    public val show: Array<RevisionPart>?

    /** The log summary. */
    public val reason: String?

    /** Whether to hide it from administrators too, which needs the `suppressrevision` right. */
    public val suppress: Boolean?
}

/**
 * The history of pages, reached as `wiki.revisions`. An option that names none of its values throws
 * `IllegalArgumentException` when a listing is asked for, and rejects a promise otherwise.
 */
@JsExport
public class RevisionService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    /** The revisions of [page], newest first unless told. */
    public fun history(page: String, options: HistoryOptions? = null): AsyncIterable<Revision> = listing {
        wiki.historyWithDefaults(
            page,
            historyOrder(options?.order),
            options?.start?.let(::instantOf),
            options?.end?.let(::instantOf),
            options?.user,
            options?.excludeUser,
            options?.limit,
        )
    }
        .toAsyncIterable(scope, ::Revision)

    /** The page as it was at [revision], or `null` when there is no such revision. */
    public fun contentAt(revision: Double): Promise<Page?> = scope.promise {
        wiki.revisions.contentAt(revisionId(revision))?.let { Page(it, wiki.namespaces) }
    }

    /**
     * The pages as they were at each of [revisions], read many to a request, in the order asked for. A
     * revision that does not exist, or whose text is hidden, is left out.
     */
    public fun contentsAt(revisions: Array<Double>): Promise<Array<Page>> = scope.promise {
        val ids = revisions.map(::revisionId).distinct()
        val found = wiki.revisions.contentsAt(ids)
        ids.mapNotNull { found[it] }.map { Page(it, wiki.namespaces) }.toTypedArray()
    }

    /** The revisions [ids] that exist, in the order asked for: how to follow a log entry back to its edit. */
    public fun byId(ids: Array<Double>): Promise<Array<Revision>> = scope.promise {
        val wanted = ids.map(::revisionId).distinct()
        val found = wiki.revisions.byId(wanted)
        wanted.mapNotNull { found[it] }.map(::Revision).toTypedArray()
    }

    /** The difference between two revisions as the wiki draws it, in HTML. */
    public fun compare(from: Double, to: Double): Promise<String> = scope.promise {
        wiki.revisions.compare(revisionId(from), revisionId(to))
    }

    /**
     * Every revision made across the wiki in a window. Reaches past the thirty days `wiki.logs.recentChanges`
     * keeps, at the cost of a much heavier query.
     */
    public fun allRevisions(options: AllRevisionsOptions? = null): AsyncIterable<Revision> = listing {
        wiki.allRevisionsWithDefaults(
            options?.namespaces?.asList(),
            options?.user,
            historyOrder(options?.order),
            options?.start?.let(::instantOf),
            options?.end?.let(::instantOf),
            options?.limit,
        )
    }
        .toAsyncIterable(scope, ::Revision)

    /**
     * The deleted revisions of [page]. Needs the `deletedhistory` right.
     *
     * @param page the page.
     * @param order `"newest-first"`, the default, or `"oldest-first"`.
     * @param limit how many to give before stopping.
     */
    public fun deletedHistory(
        page: String,
        order: TimeOrder? = null,
        limit: Int? = null,
    ): AsyncIterable<Revision> = listing {
        wiki.deletedHistoryWithDefaults(page, historyOrder(order), limit)
    }
        .toAsyncIterable(scope, ::Revision)

    /**
     * Deleted revisions across the wiki, in the shape of [allRevisions]. Needs the `deletedhistory` right.
     */
    public fun allDeletedRevisions(options: AllRevisionsOptions? = null): AsyncIterable<Revision> = listing {
        wiki.allDeletedRevisionsWithDefaults(
            options?.namespaces?.asList(),
            options?.user,
            historyOrder(options?.order),
            options?.start?.let(::instantOf),
            options?.end?.let(::instantOf),
            options?.limit,
        )
    }
        .toAsyncIterable(scope, ::Revision)

    /**
     * Hides or restores parts of [revisions] of [page]. Needs the `deleterevision` right.
     *
     * Rejects when there are no revisions, when nothing is hidden or shown, or when one part is both.
     */
    public fun revisionDelete(
        page: String,
        revisions: Array<Double>,
        options: RevisionDeleteOptions? = null,
    ): Promise<Unit> = scope.promiseVoid {
        wiki.revisionDeleteWithDefaults(
            page,
            revisions.map(::revisionId),
            revisionParts("hide", options?.hide),
            revisionParts("show", options?.show),
            options?.reason,
            options?.suppress,
        )
    }

    private fun revisionId(id: Double): RevisionId = RevisionId(id.toLong())

    private fun historyOrder(order: TimeOrder?): HistoryOrder? = enumOf("order", order)

    private fun revisionParts(parameter: String, parts: Array<RevisionPart>?): List<ClientRevisionPart>? =
        parts?.map {
            enumArgument<ClientRevisionPart>(parameter, it.unsafeCast<String>())
        }
}
