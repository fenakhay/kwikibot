package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.hasExtension
import com.fenakhay.kwikibot.client.internal.wire.Capabilities
import com.fenakhay.kwikibot.client.internal.wire.Registry
import com.fenakhay.kwikibot.client.internal.wire.Wire
import com.fenakhay.kwikibot.client.model.AbuseFilter
import com.fenakhay.kwikibot.client.model.AbuseFilterFlags
import com.fenakhay.kwikibot.client.model.Coordinate
import com.fenakhay.kwikibot.client.model.FlaggedInfo
import com.fenakhay.kwikibot.client.model.LintError
import com.fenakhay.kwikibot.client.model.Notification
import com.fenakhay.kwikibot.client.model.TriageBacklog
import com.fenakhay.kwikibot.client.model.TriagePage
import com.fenakhay.kwikibot.client.model.TriageStats
import com.fenakhay.kwikibot.client.model.TriageStatus
import com.fenakhay.kwikibot.client.requireExtension
import com.fenakhay.kwikibot.client.service.AbuseFilterOrder
import com.fenakhay.kwikibot.client.service.AbuseFilterProperty
import com.fenakhay.kwikibot.client.service.ExtensionService
import com.fenakhay.kwikibot.client.service.NewPagesOrder
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ParamInfo
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.OptionSet
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    private val capabilities: Capabilities = Capabilities(ParamInfo(transport), info.server),
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

    override fun lintErrors(category: String?, limit: Int?): Flow<LintError> = flow {
        // Not a suspend function, so the check happens when the flow is collected rather than
        // when it is built; making it fail early would mean making this suspend for no other
        // reason.
        requireExtension(ExtensionService.LINTER)

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

        emitAll(if (limit == null) errors else errors.take(limit))
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

        // Posted as a write, but with no token: shortenurl takes none, and one sent anyway draws an
        // "unrecognized parameter" warning on every call.
        val response =
            transport
                .call(ApiRequest(mapOf("action" to "shortenurl", "url" to url), RequestKind.WRITE))
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

    override fun abuseFilters(
        show: OptionSet,
        properties: Set<AbuseFilterProperty>,
        order: AbuseFilterOrder,
        limit: Int?,
    ): Flow<AbuseFilter> = flow {
        requireExtension(ExtensionService.ABUSE_FILTER)

        // The id names the filter and is what continuation resumes from, so it is always read.
        val asked = properties + AbuseFilterProperty.ID
        val filters =
            continuation
                .list(
                    ApiRequest.of(
                        "query",
                        "list" to "abusefilters",
                        "abfprop" to asked.flatMap { wire(it) }.distinct().joinToString("|"),
                        "abfshow" to show.toParam(),
                        "abfdir" to order.apiValue,
                        "abflimit" to (limit?.takeIf { it < MAX_BATCH }?.toString() ?: "max"),
                    ),
                    "abusefilters",
                )
                .map { decodeAbuseFilter(it, flags = asked.any { property -> property.choice != null }) }

        emitAll(if (limit == null) filters else filters.take(limit))
    }

    override fun newPages(
        namespace: Namespace,
        reviewed: Boolean?,
        redirects: Boolean,
        creators: Set<String>,
        hideOwnPages: Boolean,
        order: NewPagesOrder,
        limit: Int?,
    ): Flow<TriagePage> = flow {
        requireExtension(ExtensionService.PAGE_TRIAGE)
        requireCreators(creators)

        // PageTriage pages by hand: the next request starts after the last page of this one, by
        // its timestamp and then its id, because several pages can share a timestamp.
        var resume: Array<Pair<String, String?>> = emptyArray()
        var sent = 0
        while (true) {
            val wanted = limit?.let { minOf(it - sent, MAX_TRIAGE_BATCH) } ?: MAX_TRIAGE_BATCH
            val response =
                transport
                    .call(
                        ApiRequest.of(
                            "pagetriagelist",
                            *triageFilters(namespace, creators, hideOwnPages),
                            // Neither of these means no pages at all, rather than every page.
                            "showreviewed" to "1".takeIf { reviewed != false },
                            "showunreviewed" to "1".takeIf { reviewed != true },
                            // Articles are the pages that are not redirects, nominated for deletion or not.
                            "showothers" to "1".takeIf { !redirects },
                            "showdeleted" to "1".takeIf { !redirects },
                            "dir" to order.apiValue,
                            "limit" to wanted.toString(),
                            *resume,
                        )
                    )
                    .throwOnError()["pagetriagelist"]
                    ?.jsonObject

            val pages = response?.get("pages")?.jsonArray?.map { it.jsonObject }.orEmpty()
            pages.forEach { entry -> decodeTriagePage(entry, namespace)?.let { emit(it) } }
            sent += pages.size

            // A page with no metadata is left out of the list but counts towards the batch, so a short
            // batch means the feed ran out only when those are counted too.
            val answered = pages.size + (response?.get("pages_missing_metadata")?.jsonArray?.size ?: 0)
            val done = answered < wanted || (limit != null && sent >= limit)
            val last = pages.lastOrNull()
            val offsetKey = order.offsetKey
            if (last == null || done || offsetKey == null) return@flow

            resume =
                arrayOf(
                    "offset" to last[offsetKey]?.jsonPrimitive?.content,
                    "pageoffset" to last["pageid"]?.jsonPrimitive?.content,
                )
        }
    }

    override suspend fun newPageStats(
        namespace: Namespace,
        creators: Set<String>,
        hideOwnPages: Boolean,
    ): TriageStats {
        requireExtension(ExtensionService.PAGE_TRIAGE)
        requireCreators(creators)

        val stats =
            transport
                .call(
                    ApiRequest.of(
                        "pagetriagestats",
                        *triageFilters(namespace, creators, hideOwnPages),
                        // What the matching count covers: articles, reviewed or not.
                        "showreviewed" to "1",
                        "showunreviewed" to "1",
                        "showothers" to "1",
                        "showdeleted" to "1",
                    )
                )
                .throwOnError()["pagetriagestats"]
                ?.jsonObject
                ?.get("stats")
                ?.jsonObject
                ?: throw WikiError.Api(
                    "nostats",
                    "the wiki returned no new page statistics",
                    "pagetriagestats",
                )

        return TriageStats(
            unreviewedArticles = stats.backlog("unreviewedarticle") ?: TriageBacklog(0, null),
            unreviewedRedirects = stats.backlog("unreviewedredirect") ?: TriageBacklog(0, null),
            unreviewedDrafts = stats.backlog("unrevieweddraft"),
            reviewedArticles = stats.reviewedCount("reviewedarticle"),
            reviewedRedirects = stats.reviewedCount("reviewedredirect"),
            matching = stats["filteredarticle"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    /** What [property] goes out as on this wiki: its own value, or the spelling this wiki takes. */
    private suspend fun wire(property: AbuseFilterProperty): List<String> =
        property.choice?.let { capabilities.resolve(it) } ?: listOf(property.apiValue)

    private fun decodeAbuseFilter(entry: JsonObject, flags: Boolean) =
        AbuseFilter(
            id = entry["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            description = entry.text("description"),
            actions = entry.actions(),
            pattern = entry.text("pattern"),
            comments = entry.text("comments"),
            hits = entry["hits"]?.jsonPrimitive?.longOrNull,
            lastEditor = entry.text("lasteditor"),
            lastEdited = entry.text("lastedittime")?.let { MwTimestamp.parseOrNull(it) },
            flags =
                AbuseFilterFlags(
                        isEnabled = entry.flag("enabled"),
                        isDeleted = entry.flag("deleted"),
                        isPrivate = entry.flag("private"),
                        isProtected = entry.flag("protected"),
                        isSuppressed = entry.flag("suppressed"),
                    )
                    .takeIf { flags },
            redacted = REDACTIONS.filterKeys { entry.flag(it) }.values.toSet(),
        )

    /** An array of names from MediaWiki 1.47 (T435835), and one comma-separated string before it. */
    private fun JsonObject.actions(): List<String> =
        when (val actions = this["actions"]) {
            is JsonArray -> actions.map { it.jsonPrimitive.content }
            is JsonPrimitive -> actions.content.split(',').filter { it.isNotEmpty() }
            else -> emptyList()
        }

    /** One feed entry, or `null` for one whose title cannot be read, which is skipped rather than fatal. */
    private fun decodeTriagePage(entry: JsonObject, namespace: Namespace): TriagePage? {
        val page =
            decoder.refOf(
                entry.text("title").orEmpty(),
                namespace.id,
                entry["pageid"]?.jsonPrimitive?.longOrNull,
            ) ?: return null

        return TriagePage(
            page = page,
            created = entry.text("creation_date_utc")?.let { MwTimestamp.parseOrNull(it) },
            creator = entry.text("user_name").takeUnless { entry.flag("creator_hidden") },
            creatorIsTemporary = entry.flag("creator_is_temp_account"),
            status = TRIAGE_STATUS[entry.text("patrol_status")],
            reviewer = entry.text("reviewer")?.takeIf { it.isNotEmpty() && !entry.flag("reviewer_hidden") },
            reviewUpdated = entry.text("ptrp_reviewed_updated")?.let { MwTimestamp.parseOrNull(it) },
            isRedirect = entry.flag("is_redirect"),
            // The feed sends the inbound link count it compiled; `is_orphan` comes only for a single page.
            isOrphan = entry.text("linkcount")?.toLongOrNull() == 0L,
        )
    }

    private fun triageFilters(
        namespace: Namespace,
        creators: Set<String>,
        hideOwnPages: Boolean,
    ): Array<Pair<String, String?>> =
        arrayOf(
            "namespace" to namespace.id.toString(),
            "username" to creators.takeIf { it.isNotEmpty() }?.joinToString("|"),
            "hideownpages" to "1".takeIf { hideOwnPages },
        )

    private fun requireCreators(creators: Set<String>) {
        require(creators.size <= ExtensionService.MAX_CREATORS) {
            "PageTriage filters by at most ${ExtensionService.MAX_CREATORS} creators, not ${creators.size}"
        }
    }

    private fun JsonObject.backlog(key: String): TriageBacklog? {
        // A queue the wiki does not keep comes back as an empty list rather than as nothing.
        val queue = this[key] as? JsonObject ?: return null
        val count = queue["count"]?.jsonPrimitive?.longOrNull ?: 0
        // For an empty queue PageTriage sends the current time as the oldest entry.
        val oldest = queue.text("oldest")?.takeIf { count > 0 }?.let { MwTimestamp.parseOrNull(it) }
        return TriageBacklog(count, oldest)
    }

    private fun JsonObject.reviewedCount(key: String): Long =
        (this[key] as? JsonObject)?.get("reviewed_count")?.jsonPrimitive?.longOrNull ?: 0

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    /**
     * A flag, in either shape MediaWiki has sent one: a real boolean, or present and empty for true.
     *
     * AbuseFilter sent empty strings until MediaWiki 1.47 (T435835), and absence meant false.
     */
    private fun JsonObject.flag(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: return false
        return value !is JsonNull && value.booleanOrNull != false && value.content != "0"
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

        /** The most pages `pagetriagelist` returns to one request. */
        const val MAX_TRIAGE_BATCH = 200

        /** The marker AbuseFilter sets in place of each property it withholds. */
        val REDACTIONS =
            mapOf(
                "patternredacted" to AbuseFilterProperty.PATTERN,
                "hitsredacted" to AbuseFilterProperty.HITS,
                "commentsredacted" to AbuseFilterProperty.COMMENTS,
            )

        /** PageTriage's `ptrp_reviewed`, which it reports as `patrol_status`. */
        val TRIAGE_STATUS =
            mapOf(
                "0" to TriageStatus.UNREVIEWED,
                "1" to TriageStatus.REVIEWED,
                "2" to TriageStatus.PATROLLED,
                "3" to TriageStatus.AUTOPATROLLED,
            )

        /** The choice an entry goes out through, for the entries MediaWiki has respelled. */
        @Suppress("DEPRECATION")
        val AbuseFilterProperty.choice: Wire.Choice?
            get() =
                when (this) {
                    AbuseFilterProperty.FLAGS,
                    AbuseFilterProperty.STATUS,
                    AbuseFilterProperty.PRIVATE,
                    AbuseFilterProperty.PROTECTED,
                    AbuseFilterProperty.SUPPRESSED -> Registry.ABUSE_FILTER_FLAGS
                    else -> null
                }
    }
}
