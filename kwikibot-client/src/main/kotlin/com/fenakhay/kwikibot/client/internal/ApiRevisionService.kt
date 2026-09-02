package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.raiseBadToken
import com.fenakhay.kwikibot.client.service.HistoryOrder
import com.fenakhay.kwikibot.client.service.RevisionPart
import com.fenakhay.kwikibot.client.service.RevisionService
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.Revision
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.protocol.decode.PageResult
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
        val request =
            ApiRequest.of(
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
    ): Flow<Revision> =
        revisionsUnder(
            "allrevisions",
            limit,
            "arvnamespace" to namespaces.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
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
    ): Flow<Revision> =
        revisionsUnder(
            "alldeletedrevisions",
            limit,
            "adrnamespace" to namespaces.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
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
        val request =
            ApiRequest.of(
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
     * `allrevisions` and `alldeletedrevisions` both nest what was asked for one level down, so reading their
     * entries as revisions finds nothing.
     */
    private fun revisionsUnder(
        module: String,
        limit: Int?,
        vararg params: Pair<String, String?>,
    ): Flow<Revision> =
        revisionsIn(
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
        val response =
            continuation
                .pages(
                    ApiRequest.of(
                        "query",
                        "prop" to "revisions",
                        "revids" to revision.value.toString(),
                        "rvprop" to "$REVISION_PROPS|content",
                        "rvslots" to "main",
                    )
                )
                .toList()
                .firstOrNull() ?: return null

        return (decoder.decode(response) as? PageResult.Existing)?.content
    }

    override suspend fun byId(ids: Collection<RevisionId>): Map<RevisionId, Revision> {
        if (ids.isEmpty()) return emptyMap()

        val found = mutableMapOf<RevisionId, Revision>()
        for (batch in ids.distinct().chunked(batchSize)) {
            continuation
                .pages(
                    ApiRequest.of(
                        "query",
                        "prop" to "revisions",
                        "revids" to batch.joinToString("|") { it.value.toString() },
                        "rvprop" to REVISION_PROPS,
                    )
                )
                .toList()
                .forEach { entry ->
                    entry["revisions"]?.jsonArray?.forEach {
                        val revision = decoder.decodeRevision(it.jsonObject)
                        found[revision.id] = revision
                    }
                }
        }
        return found
    }

    override suspend fun compare(from: RevisionId, to: RevisionId): String {
        val response =
            transport
                .call(
                    ApiRequest.of(
                        "compare",
                        "fromrev" to from.value.toString(),
                        "torev" to to.value.toString(),
                    )
                )
                .throwOnError()

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
            transport
                .call(
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
                    )
                )
                .also { it.raiseBadToken() }
                .throwOnError()
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
