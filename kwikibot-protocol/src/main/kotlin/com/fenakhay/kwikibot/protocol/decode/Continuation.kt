package com.fenakhay.kwikibot.protocol.decode

import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Walks a query across its continuation batches.
 *
 * MediaWiki answers a list query with as many results as it feels like and a `continue` object describing
 * where to resume. Following that is the single most repeated piece of plumbing in a bot, so it lives here
 * once, as a cold [Flow]: nothing is requested until the flow is collected, and abandoning the collection
 * stops the paging.
 *
 * Errors are raised rather than emitted — a query that fails halfway is not a short result.
 */
public class Continuation(private val transport: MediaWikiTransport) {

    /**
     * Emits each response batch of [request], following `continue` until the query is exhausted.
     *
     * @param request the query to run. Must be a read: a write cannot be continued.
     * @param maxBatches a ceiling on the number of round trips, guarding against a query that continues
     *   forever. `null` means no ceiling.
     */
    public fun batches(request: ApiRequest, maxBatches: Int? = null): Flow<JsonObject> = flow {
        require(request.kind == RequestKind.READ) { "only read queries continue" }

        var params = request.params
        var batches = 0

        while (true) {
            val response = transport.call(ApiRequest(params, RequestKind.READ)).throwOnError()
            emit(response)
            batches++

            if (maxBatches != null && batches >= maxBatches) return@flow

            val next = response.nextBatchParams() ?: return@flow
            // The continue object replaces its own keys and leaves the rest of the query alone.
            params = params + next
        }
    }

    /**
     * Emits the items a list query returns, flattened across batches.
     *
     * @param request the query to run.
     * @param listName the `list=` module name, which is also the key its results appear under.
     * @param maxBatches a ceiling on the number of round trips. `null` means no ceiling.
     */
    public fun list(
        request: ApiRequest,
        listName: String,
        maxBatches: Int? = null,
    ): Flow<JsonObject> = flow {
        batches(request, maxBatches).collect { response ->
            val items = response["query"]?.jsonObject?.get(listName)?.asObjectList().orEmpty()
            items.forEach { emit(it) }
        }
    }

    /**
     * Emits the page entries a `prop=` query returns.
     *
     * A page can arrive in several batches, each carrying different properties, so callers that need whole
     * pages should merge by page id rather than assume one entry per page.
     */
    public fun pages(request: ApiRequest, maxBatches: Int? = null): Flow<JsonObject> = flow {
        batches(request, maxBatches).collect { response ->
            val pages = response["query"]?.jsonObject?.get("pages")?.asObjectList().orEmpty()
            pages.forEach { emit(it) }
        }
    }

    private companion object {
        /**
         * The continue parameters for the next batch, or `null` when the query is complete.
         *
         * Only the modern `continue` object is read. The `query-continue` form belongs to MediaWiki 1.25 and
         * earlier, which no supported wiki still runs.
         */
        fun JsonObject.nextBatchParams(): Map<String, String>? =
            this["continue"]
                ?.jsonObject
                ?.mapValues { (_, value) -> value.jsonPrimitive.content }
                ?.takeIf { it.isNotEmpty() }

        /**
         * Reads a result collection that may be a list or, on older shapes, a map keyed by id.
         *
         * `formatversion=2` returns lists, but a few modules still answer with an object, and one shape
         * crashing on the other is a classic source of bot breakage.
         */
        fun JsonElement.asObjectList(): List<JsonObject> =
            when (this) {
                is JsonArray -> map { it.jsonObject }
                is JsonObject -> values.map { it.jsonObject }
                else -> emptyList()
            }
    }
}
