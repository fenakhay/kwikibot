package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.hasExtension
import com.fenakhay.kwikibot.client.model.Coordinate
import com.fenakhay.kwikibot.client.model.FlaggedInfo
import com.fenakhay.kwikibot.client.model.LintError
import com.fenakhay.kwikibot.client.model.Notification
import com.fenakhay.kwikibot.client.requireExtension
import com.fenakhay.kwikibot.client.service.ExtensionService
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class ApiExtensionService(
    private val transport: MediaWikiTransport,
    private val tokens: TokenStore,
    private val decoder: PageDecoder,
    private val namespaces: NamespaceMap,
    private val info: SiteInfo,
    private val batchSize: Int = DEFAULT_BATCH,
) : ExtensionService {

    private val continuation = Continuation(transport)

    override fun has(extension: String): Boolean = info.hasExtension(extension)

    override suspend fun coordinates(refs: Collection<PageRef>): Map<PageRef, List<Coordinate>> {
        requireExtension(ExtensionService.GEO_DATA)
        return byPage(refs, "coordinates", "coprop" to "type|globe", "coprimary" to "all") { page ->
            page["coordinates"]?.jsonArray?.map { entry ->
                val fields = entry.jsonObject
                Coordinate(
                    latitude = fields["lat"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    longitude = fields["lon"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    globe = fields["globe"]?.jsonPrimitive?.content ?: "earth",
                    isPrimary = fields.containsKey("primary"),
                    type = fields["type"]?.jsonPrimitive?.content,
                )
            }
        }
    }

    override suspend fun nearby(
        latitude: Double,
        longitude: Double,
        radius: Int,
        limit: Int,
    ): List<PageRef> {
        requireExtension(ExtensionService.GEO_DATA)

        return continuation
            .list(
                ApiRequest.of(
                    "query",
                    "list" to "geosearch",
                    "gscoord" to "$latitude|$longitude",
                    "gsradius" to radius.toString(),
                    "gslimit" to limit.toString(),
                ),
                "geosearch",
            )
            .mapNotNull { decoder.refOf(it) }
            .take(limit)
            .toList()
    }

    override suspend fun pageImages(refs: Collection<PageRef>): Map<PageRef, String> {
        requireExtension(ExtensionService.PAGE_IMAGES)
        return byPage(refs, "pageimages", "piprop" to "name") { page ->
            page["pageimage"]?.jsonPrimitive?.content
        }
    }

    override suspend fun extracts(
        refs: Collection<PageRef>,
        sentences: Int,
    ): Map<PageRef, String> {
        requireExtension(ExtensionService.TEXT_EXTRACTS)
        return byPage(
            refs,
            "extracts",
            "explaintext" to "1",
            "exintro" to "1",
            "exsentences" to sentences.takeIf { it > 0 }?.toString(),
            // Without this the API silently drops all but the first page of a batch.
            "exlimit" to "max",
        ) { page ->
            page["extract"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun wikibaseItems(refs: Collection<PageRef>): Map<PageRef, String> {
        requireExtension(ExtensionService.WIKIBASE_CLIENT)
        return byPage(refs, "pageprops", "ppprop" to "wikibase_item") { page ->
            page["pageprops"]?.jsonObject?.get("wikibase_item")?.jsonPrimitive?.content
        }
    }

    override fun lintErrors(category: String?, limit: Int?): Flow<LintError> {
        // Not a suspend function, so the check happens when the flow is collected rather than
        // when it is built; making it fail early would mean making this suspend for no other
        // reason.
        val errors =
            continuation
                .list(
                    ApiRequest.of(
                        "query",
                        "list" to "linterrors",
                        "lntcategories" to category,
                        "lntlimit" to (limit?.takeIf { it < MAX_BATCH }?.toString() ?: "max"),
                    ),
                    "linterrors",
                )
                .map { entry ->
                    LintError(
                        id = entry["lintId"]?.jsonPrimitive?.longOrNull ?: 0L,
                        category = entry["category"]?.jsonPrimitive?.content.orEmpty(),
                        page = decoder.refOf(entry),
                        range =
                            entry["location"]
                                ?.jsonArray
                                ?.takeIf { it.size >= 2 }
                                ?.let { location ->
                                    val start = location[0].jsonPrimitive.content.toIntOrNull() ?: 0
                                    val end = location[1].jsonPrimitive.content.toIntOrNull() ?: 0
                                    start..end
                                },
                        details =
                            entry["params"]
                                ?.jsonObject
                                ?.mapValues { (_, value) -> value.toString().trim('"') }
                                .orEmpty(),
                    )
                }

        return if (limit == null) errors else errors.take(limit)
    }

    override suspend fun notifications(unreadOnly: Boolean, limit: Int): List<Notification> {
        requireExtension(ExtensionService.ECHO)

        val response =
            transport
                .call(
                    ApiRequest.of(
                        "query",
                        "meta" to "notifications",
                        "notfilter" to if (unreadOnly) "!read" else null,
                        "notlimit" to limit.toString(),
                        "notprop" to "list",
                    )
                )
                .throwOnError()

        val list =
            response["query"]?.jsonObject?.get("notifications")?.jsonObject?.get("list")?.jsonArray
                ?: return emptyList()

        return list.map { entry ->
            val fields = entry.jsonObject
            Notification(
                id = fields["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                type = fields["type"]?.jsonPrimitive?.content.orEmpty(),
                title = fields["title"]?.jsonObject?.get("full")?.jsonPrimitive?.content,
                agent = fields["agent"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
                timestamp =
                    fields["timestamp"]?.jsonObject?.get("utciso8601")?.jsonPrimitive?.content?.let {
                        MwTimestamp.parseOrNull(it)
                    },
                // Echo marks a notification read by giving it a read timestamp.
                isRead = fields.containsKey("read"),
            )
        }
    }

    override suspend fun thank(revision: RevisionId, source: String) {
        requireExtension(ExtensionService.THANKS)

        tokens.withFreshToken { token ->
            transport
                .call(
                    ApiRequest(
                        mapOf(
                            "action" to "thank",
                            "rev" to revision.value.toString(),
                            "source" to source,
                            "token" to token,
                        ),
                        RequestKind.WRITE,
                    )
                )
                .throwOnError()
        }
    }

    override suspend fun shortenUrl(url: String): String {
        requireExtension(ExtensionService.URL_SHORTENER)

        val response =
            tokens
                .withFreshToken { token ->
                    transport.call(
                        ApiRequest(
                            mapOf("action" to "shortenurl", "url" to url, "token" to token),
                            RequestKind.WRITE,
                        )
                    )
                }
                .throwOnError()

        return response["shortenurl"]?.jsonObject?.get("shorturl")?.jsonPrimitive?.content
            ?: throw WikiError.Api("noshorturl", "the wiki returned no short URL", "shortenurl")
    }

    override suspend fun flagged(refs: Collection<PageRef>): Map<PageRef, FlaggedInfo> {
        requireExtension(ExtensionService.FLAGGED_REVS)
        return byPage(refs, "flagged") { page ->
            val flagged = page["flagged"]?.jsonObject ?: return@byPage null
            val stable = flagged["stable_revid"]?.jsonPrimitive?.longOrNull ?: return@byPage null

            FlaggedInfo(
                stableRevisionId = stable,
                level = flagged["level"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                levelText = flagged["level_text"]?.jsonPrimitive?.content,
                // Sent only while edits are waiting, which is what makes it the signal.
                pendingSince =
                    flagged["pending_since"]?.jsonPrimitive?.content?.let { MwTimestamp.parseOrNull(it) },
            )
        }
    }

    override suspend fun review(
        revision: RevisionId,
        flags: Map<String, Int>,
        comment: String,
    ) {
        requireExtension(ExtensionService.FLAGGED_REVS)

        tokens.withFreshToken { token ->
            transport
                .call(
                    ApiRequest(
                        buildMap {
                            put("action", "review")
                            put("revid", revision.value.toString())
                            flags.forEach { (name, value) -> put("flag_$name", value.toString()) }
                            if (comment.isNotEmpty()) put("comment", comment)
                            put("token", token)
                        },
                        RequestKind.WRITE,
                    )
                )
                .throwOnError()
        }
    }

    /**
     * Fails unless the extension is installed.
     *
     * An empty result is indistinguishable from a clean wiki: a bot querying Linter would read "no such
     * extension" as "nothing to fix".
     */
    private fun requireExtension(extension: String) {
        if (!has(extension)) throw WikiError.Configuration.MissingExtension(extension)
    }

    /** A `prop=` query over a batch of pages, keeping whatever [read] finds on each. */
    private suspend fun <T : Any> byPage(
        refs: Collection<PageRef>,
        prop: String,
        vararg params: Pair<String, String?>,
        read: (JsonObject) -> T?,
    ): Map<PageRef, T> {
        if (refs.isEmpty()) return emptyMap()

        val found = mutableMapOf<PageRef, T>()
        for (batch in refs.distinct().chunked(batchSize)) {
            continuation
                .pages(
                    ApiRequest.of(
                        "query",
                        "prop" to prop,
                        *params,
                        "titles" to batch.joinToString("|") { namespaces.format(it.title) },
                    )
                )
                .toList()
                .forEach { page ->
                    val ref = decoder.refOf(page) ?: return@forEach
                    val value = read(page) ?: return@forEach
                    // Keyed by the caller's own ref, so what goes in is what comes back out.
                    batch.firstOrNull { it.title == ref.title }?.let { found[it] = value }
                }
        }
        return found
    }

    private companion object {
        const val DEFAULT_BATCH = 50
        const val MAX_BATCH = 500
    }
}
