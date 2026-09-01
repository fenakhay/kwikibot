package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageContent
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Revision
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.protocol.Continuation
import com.fenakhay.kwikibot.protocol.PageDecoder
import com.fenakhay.kwikibot.protocol.PageResult
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 * Separate from [PageService] because the questions are different: page services are about the
 * page as it is now, these are about how it got there.
 */
public interface RevisionService {

    /**
     * The revisions of a page, newest first unless told otherwise.
     *
     * A cold [Flow], so asking who made the last edit costs one request even on a page with
     * fifty thousand of them.
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
     * HTML rather than a unified diff: this is the wiki rendering its own diff, which is what a
     * report links to. For a text diff of content a bot already holds, use `Diffs.unified`.
     */
    public suspend fun compare(from: RevisionId, to: RevisionId): String

    /**
     * Every revision made across the wiki in a window.
     *
     * Reaches past the thirty days `LogService.recentChanges` keeps, at the cost of being a much
     * heavier query. For a window inside those thirty days, use recent changes.
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

internal class ApiRevisionService(
    private val transport: MediaWikiTransport,
    private val tokens: TokenStore,
    private val decoder: PageDecoder,
    private val namespaces: NamespaceMap,
    private val batchSize: Int = DEFAULT_BATCH,
) : RevisionService {

    private val continuation = Continuation(transport)

    override fun history(
        page: PageRef,
        order: HistoryOrder,
        start: Instant?,
        end: Instant?,
        user: String?,
        excludeUser: String?,
        limit: Int?,
    ): Flow<Revision> {
        val request = ApiRequest.of(
            "query",
            "prop" to "revisions",
            "titles" to namespaces.format(page.title),
            "rvprop" to REVISION_PROPS,
            "rvdir" to order.apiValue,
            "rvstart" to start?.let { MwTimestamp.format(it) },
            "rvend" to end?.let { MwTimestamp.format(it) },
            "rvuser" to user,
            "rvexcludeuser" to excludeUser,
            "rvlimit" to apiLimit(limit),
        )

        val revisions = flow {
            continuation.pages(request).collect { entry ->
                entry["revisions"]?.jsonArray?.forEach {
                    emit(decoder.decodeRevision(it.jsonObject))
                }
            }
        }
        return if (limit == null) revisions else revisions.take(limit)
    }

    override fun allRevisions(
        namespaces: Set<Namespace>,
        user: String?,
        order: HistoryOrder,
        start: Instant?,
        end: Instant?,
        limit: Int?,
    ): Flow<Revision> = revisionsUnder(
        "allrevisions",
        limit,
        "arvnamespace" to namespaces.takeIf { it.isNotEmpty() }
            ?.joinToString("|") { it.id.toString() },
        "arvuser" to user,
        "arvdir" to order.apiValue,
        "arvstart" to start?.let { MwTimestamp.format(it) },
        "arvend" to end?.let { MwTimestamp.format(it) },
        "arvprop" to REVISION_PROPS,
        "arvlimit" to apiLimit(limit),
    )

    override fun allDeletedRevisions(
        namespaces: Set<Namespace>,
        user: String?,
        order: HistoryOrder,
        start: Instant?,
        end: Instant?,
        limit: Int?,
    ): Flow<Revision> = revisionsUnder(
        "alldeletedrevisions",
        limit,
        "adrnamespace" to namespaces.takeIf { it.isNotEmpty() }
            ?.joinToString("|") { it.id.toString() },
        "adruser" to user,
        "adrdir" to order.apiValue,
        "adrstart" to start?.let { MwTimestamp.format(it) },
        "adrend" to end?.let { MwTimestamp.format(it) },
        "adrprop" to REVISION_PROPS,
        "adrlimit" to apiLimit(limit),
    )

    override fun deletedHistory(
        page: PageRef,
        order: HistoryOrder,
        limit: Int?,
    ): Flow<Revision> {
        val request = ApiRequest.of(
            "query",
            "prop" to "deletedrevisions",
            "titles" to namespaces.format(page.title),
            "drvprop" to REVISION_PROPS,
            "drvdir" to order.apiValue,
            "drvlimit" to apiLimit(limit),
        )
        return revisionsIn(continuation.pages(request), limit)
    }

    /**
     * A list module whose entries are pages carrying revisions, not revisions.
     *
     * `allrevisions` and `alldeletedrevisions` both nest what was asked for one level down, so
     * reading their entries as revisions finds nothing.
     */
    private fun revisionsUnder(
        module: String,
        limit: Int?,
        vararg params: Pair<String, String?>,
    ): Flow<Revision> = revisionsIn(
        continuation.list(ApiRequest.of("query", "list" to module, *params), module),
        limit,
    )

    private fun revisionsIn(pages: Flow<JsonObject>, limit: Int?): Flow<Revision> {
        val revisions = flow {
            pages.collect { entry ->
                entry["revisions"]?.jsonArray?.forEach {
                    emit(decoder.decodeRevision(it.jsonObject))
                }
            }
        }
        return if (limit == null) revisions else revisions.take(limit)
    }

    override suspend fun contentAt(revision: RevisionId): PageContent? {
        val response = continuation.pages(
            ApiRequest.of(
                "query",
                "prop" to "revisions",
                "revids" to revision.value.toString(),
                "rvprop" to "$REVISION_PROPS|content",
                "rvslots" to "main",
            ),
        ).toList().firstOrNull() ?: return null

        return (decoder.decode(response) as? PageResult.Existing)?.content
    }

    override suspend fun byId(ids: Collection<RevisionId>): Map<RevisionId, Revision> {
        if (ids.isEmpty()) return emptyMap()

        val found = mutableMapOf<RevisionId, Revision>()
        for (batch in ids.distinct().chunked(batchSize)) {
            continuation.pages(
                ApiRequest.of(
                    "query",
                    "prop" to "revisions",
                    "revids" to batch.joinToString("|") { it.value.toString() },
                    "rvprop" to REVISION_PROPS,
                ),
            ).toList().forEach { entry ->
                entry["revisions"]?.jsonArray?.forEach {
                    val revision = decoder.decodeRevision(it.jsonObject)
                    found[revision.id] = revision
                }
            }
        }
        return found
    }

    override suspend fun compare(from: RevisionId, to: RevisionId): String {
        val response = transport.call(
            ApiRequest.of(
                "compare",
                "fromrev" to from.value.toString(),
                "torev" to to.value.toString(),
            ),
        ).throwOnError()

        return response["compare"]?.jsonObject?.get("body")?.jsonPrimitive?.content.orEmpty()
    }

    override suspend fun revisionDelete(
        page: PageRef,
        revisions: Collection<RevisionId>,
        hide: Set<RevisionPart>,
        show: Set<RevisionPart>,
        reason: String,
        suppress: Boolean,
    ) {
        require(revisions.isNotEmpty()) { "revisionDelete needs at least one revision" }
        require(hide.isNotEmpty() || show.isNotEmpty()) { "revisionDelete must hide or show something" }
        require((hide intersect show).isEmpty()) { "cannot hide and show the same part" }

        tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "revisiondelete")
                        put("type", "revision")
                        put("target", namespaces.format(page.title))
                        put("ids", revisions.joinToString("|") { it.value.toString() })
                        if (hide.isNotEmpty()) put("hide", hide.joinToString("|") { it.apiValue })
                        if (show.isNotEmpty()) put("show", show.joinToString("|") { it.apiValue })
                        put("reason", reason)
                        // The wiki's own default is "nochange", which is not the same as "no".
                        if (suppress) put("suppress", "yes")
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).also { it.raiseBadToken() }.throwOnError()
        }
    }

    /**
     * How many revisions to ask for per request.
     *
     * `max` lets the wiki decide, which is 500 for a bot account and 50 for anyone else.
     */
    private fun apiLimit(limit: Int?): String =
        if (limit != null && limit < MAX_BATCH) limit.toString() else "max"

    private companion object {
        const val DEFAULT_BATCH = 50
        const val MAX_BATCH = 500
        const val REVISION_PROPS = "ids|timestamp|user|comment|size|flags|sha1|tags"
    }
}
