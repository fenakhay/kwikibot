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
import com.fenakhay.kwikibot.protocol.decode.array
import com.fenakhay.kwikibot.protocol.decode.boolean
import com.fenakhay.kwikibot.protocol.decode.double
import com.fenakhay.kwikibot.protocol.decode.int
import com.fenakhay.kwikibot.protocol.decode.long
import com.fenakhay.kwikibot.protocol.decode.obj
import com.fenakhay.kwikibot.protocol.decode.objects
import com.fenakhay.kwikibot.protocol.decode.string
import com.fenakhay.kwikibot.protocol.decode.strings
import com.fenakhay.kwikibot.protocol.decode.time
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject

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
            page.array("coordinates")?.objects()?.map { fields ->
                Coordinate(
                    latitude = fields.double("lat") ?: 0.0,
                    longitude = fields.double("lon") ?: 0.0,
                    globe = fields.string("globe") ?: "earth",
                    isPrimary = fields.containsKey("primary"),
                    type = fields.string("type"),
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
            page.string("pageimage")
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
            page.string("extract")?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun wikibaseItems(refs: Collection<PageRef>): Map<PageRef, String> {
        requireExtension(ExtensionService.WIKIBASE_CLIENT)
        return byPage(refs, "pageprops", "ppprop" to "wikibase_item") { page ->
            page.obj("pageprops")?.string("wikibase_item")
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
                        "lntlimit" to limitParam(limit),
                    ),
                    "linterrors",
                )
                .map { entry ->
                    LintError(
                        id = entry.long("lintId") ?: 0L,
                        category = entry.string("category").orEmpty(),
                        page = decoder.refOf(entry),
                        range =
                            entry
                                .array("location")
                                .strings()
                                .mapNotNull { it.toIntOrNull() }
                                .takeIf { it.size >= 2 }
                                ?.let { (start, end) -> start..end },
                        details =
                            entry
                                .obj("params")
                                ?.mapValues { (_, value) -> value.toString().trim('"') }
                                .orEmpty(),
                    )
                }

        emitAll(errors.limitedTo(limit))
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

        return response.obj("query")?.obj("notifications")?.array("list").objects().map { fields ->
            Notification(
                id = fields.long("id") ?: 0L,
                type = fields.string("type").orEmpty(),
                title = fields.obj("title")?.string("full"),
                agent = fields.obj("agent")?.string("name"),
                timestamp = fields.obj("timestamp")?.time("utciso8601"),
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

        return response.obj("shortenurl")?.string("shorturl")
            ?: throw WikiError.Api("noshorturl", "the wiki returned no short URL", "shortenurl")
    }

    override suspend fun flagged(refs: Collection<PageRef>): Map<PageRef, FlaggedInfo> {
        requireExtension(ExtensionService.FLAGGED_REVS)
        return byPage(refs, "flagged") { page ->
            val flagged = page.obj("flagged") ?: return@byPage null
            val stable = flagged.long("stable_revid") ?: return@byPage null

            FlaggedInfo(
                stableRevisionId = stable,
                level = flagged.int("level") ?: 0,
                levelText = flagged.string("level_text"),
                // Sent only while edits are waiting, which is what makes it the signal.
                pendingSince = flagged.time("pending_since"),
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
                        "abflimit" to limitParam(limit),
                    ),
                    "abusefilters",
                )
                .map { decodeAbuseFilter(it, flags = asked.any { property -> property.choice != null }) }

        emitAll(filters.limitedTo(limit))
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
                    .throwOnError()
                    .obj("pagetriagelist")

            val pages = response?.array("pages").objects()
            pages.forEach { entry -> decodeTriagePage(entry, namespace)?.let { emit(it) } }
            sent += pages.size

            // A page with no metadata is left out of the list but counts towards the batch, so a short
            // batch means the feed ran out only when those are counted too.
            val answered = pages.size + response?.array("pages_missing_metadata").orEmpty().size
            val done = answered < wanted || (limit != null && sent >= limit)
            val last = pages.lastOrNull()
            val offsetKey = order.offsetKey
            if (last == null || done || offsetKey == null) return@flow

            resume =
                arrayOf(
                    "offset" to last.string(offsetKey),
                    "pageoffset" to last.string("pageid"),
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
                .throwOnError()
                .obj("pagetriagestats")
                ?.obj("stats")
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
            matching = stats.long("filteredarticle") ?: 0,
        )
    }

    /** What [property] goes out as on this wiki: its own value, or the spelling this wiki takes. */
    private suspend fun wire(property: AbuseFilterProperty): List<String> =
        property.choice?.let { capabilities.resolve(it) } ?: listOf(property.apiValue)

    private fun decodeAbuseFilter(entry: JsonObject, flags: Boolean) =
        AbuseFilter(
            id = entry.long("id") ?: 0L,
            description = entry.string("description"),
            actions = entry.actions(),
            pattern = entry.string("pattern"),
            comments = entry.string("comments"),
            hits = entry.long("hits"),
            lastEditor = entry.string("lasteditor"),
            lastEdited = entry.time("lastedittime"),
            flags =
                AbuseFilterFlags(
                        isEnabled = entry.lenientFlag("enabled"),
                        isDeleted = entry.lenientFlag("deleted"),
                        isPrivate = entry.lenientFlag("private"),
                        isProtected = entry.lenientFlag("protected"),
                        isSuppressed = entry.lenientFlag("suppressed"),
                    )
                    .takeIf { flags },
            redacted = REDACTIONS.filterKeys { entry.lenientFlag(it) }.values.toSet(),
        )

    /** An array of names from MediaWiki 1.47 (T435835), and one comma-separated string before it. */
    private fun JsonObject.actions(): List<String> =
        array("actions")?.strings() ?: string("actions").orEmpty().split(',').filter { it.isNotEmpty() }

    /** One feed entry, or `null` for one whose title cannot be read, which is skipped rather than fatal. */
    private fun decodeTriagePage(entry: JsonObject, namespace: Namespace): TriagePage? {
        val page =
            decoder.refOf(
                entry.string("title").orEmpty(),
                namespace.id,
                entry.long("pageid"),
            ) ?: return null

        return TriagePage(
            page = page,
            created = entry.time("creation_date_utc"),
            creator = entry.string("user_name").takeUnless { entry.lenientFlag("creator_hidden") },
            creatorIsTemporary = entry.lenientFlag("creator_is_temp_account"),
            status = TRIAGE_STATUS[entry.string("patrol_status")],
            reviewer =
                entry.string("reviewer")?.takeIf { it.isNotEmpty() && !entry.lenientFlag("reviewer_hidden") },
            reviewUpdated = entry.time("ptrp_reviewed_updated"),
            isRedirect = entry.lenientFlag("is_redirect"),
            // The feed sends the inbound link count it compiled; `is_orphan` comes only for a single page.
            isOrphan = entry.long("linkcount") == 0L,
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
        val queue = obj(key) ?: return null
        val count = queue.long("count") ?: 0
        // For an empty queue PageTriage sends the current time as the oldest entry.
        val oldest = queue.time("oldest")?.takeIf { count > 0 }
        return TriageBacklog(count, oldest)
    }

    private fun JsonObject.reviewedCount(key: String): Long = obj(key)?.long("reviewed_count") ?: 0

    /**
     * A flag in any shape these extensions send one: a boolean, or a string that is set unless it is `"0"` or
     * `"false"`.
     *
     * AbuseFilter sent an empty string for true until MediaWiki 1.47 (T435835), and PageTriage sends its
     * flags as the `"0"` or `"1"` of the database column. Absence is false in both.
     */
    private fun JsonObject.lenientFlag(key: String): Boolean {
        val value = string(key) ?: return false
        return boolean(key) != false && value != "0"
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
