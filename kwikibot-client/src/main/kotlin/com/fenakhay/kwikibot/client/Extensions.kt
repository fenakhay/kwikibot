package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.protocol.Continuation
import com.fenakhay.kwikibot.protocol.PageDecoder
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A point on the globe, as GeoData records it for a page. */
public data class Coordinate(
    /** Degrees north, negative for south. */
    val latitude: Double,
    /** Degrees east, negative for west. */
    val longitude: Double,
    /** Which globe, when it is not Earth. */
    val globe: String = "earth",
    /** Whether this is the page's main coordinate rather than one of several it mentions. */
    val isPrimary: Boolean = true,
    /** What is at the point: `city`, `mountain`, `landmark`. */
    val type: String? = null,
)

/** One notification from Echo. */
public data class Notification(
    /** The notification's own id, which is what marking it read names. */
    val id: Long,
    /** `edit-user-talk`, `mention`, `thank-you-edit`, `reverted`. */
    val type: String,
    /** The page it is about, absent for notifications that are about no page. */
    val title: String?,
    /** Who caused it, absent where the wiki did not say. */
    val agent: String?,
    /** When it happened. */
    val timestamp: Instant?,
    /** Whether it has already been seen. */
    val isRead: Boolean,
)

/** One problem the Linter extension found on a page. */
public data class LintError(
    /** The error's own id, stable while the problem is still on the page. */
    val id: Long,
    /** `obsolete-tag`, `missing-end-tag`, `stripped-tag`, `bogus-image-options`. */
    val category: String,
    /** The page it was found on. */
    val page: PageRef?,
    /** Where in the wikitext it is, as a byte offset range. */
    val range: IntRange? = null,
    /** Whatever else Linter recorded about it, which varies by category. */
    val details: Map<String, String> = emptyMap(),
)

/**
 * What a wiki running FlaggedRevs says about a page's reviewed state.
 *
 * On a wiki with pending changes, readers are shown the last reviewed revision rather than the
 * newest one. A bot that has just edited such a page has not necessarily changed what anybody
 * sees, and needs this to know the difference.
 */
public data class FlaggedInfo(
    /** The revision readers are shown, which lags the newest while changes are pending. */
    val stableRevisionId: Long,
    /** The review level, as the wiki configures them. */
    val level: Int = 0,
    /** The level as the wiki names it: `stable`, `quality`. */
    val levelText: String? = null,
    /** When the oldest unreviewed edit was made, or `null` if nothing is pending. */
    val pendingSince: Instant? = null,
) {
    /** Whether edits are waiting for review, so the newest revision is not the one shown. */
    val hasPendingChanges: Boolean get() = pendingSince != null
}

/**
 * The services a wiki has only because an extension is installed.
 *
 * Grouped into one service rather than added to the wiki handle, so a wiki without an extension
 * does not appear to offer its operations. Every method checks that its extension is installed
 * before sending anything: an empty result would read as "nothing to fix".
 */
public interface ExtensionService {

    /** Whether an extension is installed, by the name MediaWiki reports. */
    public fun has(extension: String): Boolean

    /**
     * The coordinates of pages, from GeoData.
     *
     * @throws WikiError.Configuration.MissingExtension if GeoData is not installed.
     */
    public suspend fun coordinates(refs: Collection<PageRef>): Map<PageRef, List<Coordinate>>

    /**
     * Pages near a point, nearest first, from GeoData.
     *
     * @param latitude degrees north of the equator.
     * @param longitude degrees east of the meridian.
     * @param radius in metres. The API caps it, and the cap differs between wikis.
     * @param limit how many to return.
     */
    public suspend fun nearby(
        latitude: Double,
        longitude: Double,
        radius: Int = DEFAULT_RADIUS,
        limit: Int = DEFAULT_NEARBY,
    ): List<PageRef>

    /**
     * The lead image of pages, from PageImages.
     *
     * The value is a file name without its namespace, as the extension reports it.
     */
    public suspend fun pageImages(refs: Collection<PageRef>): Map<PageRef, String>

    /**
     * The opening plain-text extract of pages, from TextExtracts.
     *
     * @param refs the pages to take openings from.
     * @param sentences how many sentences to take. Zero takes the whole lead section.
     */
    public suspend fun extracts(
        refs: Collection<PageRef>,
        sentences: Int = DEFAULT_SENTENCES,
    ): Map<PageRef, String>

    /**
     * The Wikidata item a page is about, from WikibaseClient.
     *
     * Read from page properties rather than from the repository, so one request covers fifty
     * pages and needs no session on Wikidata.
     */
    public suspend fun wikibaseItems(refs: Collection<PageRef>): Map<PageRef, String>

    /** Problems Linter found, optionally of one category. */
    public fun lintErrors(category: String? = null, limit: Int? = null): Flow<LintError>

    /** This session's notifications, from Echo. */
    public suspend fun notifications(
        unreadOnly: Boolean = false,
        limit: Int = DEFAULT_NOTIFICATIONS,
    ): List<Notification>

    /**
     * Thanks the author of a revision, through the Thanks extension.
     *
     * There is no way to take it back, which is why it is not something a bot should do in a
     * loop without a person having decided.
     */
    public suspend fun thank(revision: RevisionId, source: String = "kwikibot")

    /** A short URL for a page, from UrlShortener. */
    public suspend fun shortenUrl(url: String): String

    /**
     * The reviewed state of pages, from FlaggedRevs.
     *
     * A page that has never been reviewed is absent from the result rather than present with a
     * zero revision: there is no stable revision to report.
     */
    public suspend fun flagged(refs: Collection<PageRef>): Map<PageRef, FlaggedInfo>

    /**
     * Marks a revision reviewed. Needs the `review` right.
     *
     * @param revision the revision to review.
     * @param flags the review dimensions the wiki defines, by name — `accuracy` on the wikis that
     *   configure one. An empty map accepts the wiki's defaults.
     * @param comment the note to record with the review.
     */
    public suspend fun review(
        revision: RevisionId,
        flags: Map<String, Int> = emptyMap(),
        comment: String = "",
    )

    /**
     * The extensions this service reads, named as `siprop=extensions` names them.
     *
     * Spelling matters: a wiki reports its own name for an extension, and a mismatch
     * reads as the extension being absent rather than as a typo.
     */
    public companion object {
        /** Coordinates on pages. */
        public const val GEO_DATA: String = "GeoData"

        /** A representative image per page. */
        public const val PAGE_IMAGES: String = "PageImages"

        /** Plain-text openings of articles. */
        public const val TEXT_EXTRACTS: String = "TextExtracts"

        /** The link from a wiki to its Wikidata item. */
        public const val WIKIBASE_CLIENT: String = "WikibaseClient"

        /** The wiki's own record of broken markup. */
        public const val LINTER: String = "Linter"

        /** Notifications. */
        public const val ECHO: String = "Echo"

        /** Thanking somebody for a revision. */
        public const val THANKS: String = "Thanks"

        /** Short URLs for long permalinks. */
        public const val URL_SHORTENER: String = "UrlShortener"

        /** Pending changes, which decides which revision readers see. */
        public const val FLAGGED_REVS: String = "FlaggedRevs"

        internal const val DEFAULT_RADIUS = 1000
        internal const val DEFAULT_NEARBY = 10
        internal const val DEFAULT_SENTENCES = 2
        internal const val DEFAULT_NOTIFICATIONS = 25
    }
}

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

        return continuation.list(
            ApiRequest.of(
                "query",
                "list" to "geosearch",
                "gscoord" to "$latitude|$longitude",
                "gsradius" to radius.toString(),
                "gslimit" to limit.toString(),
            ),
            "geosearch",
        ).mapNotNull { decoder.refOf(it) }.take(limit).toList()
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
        val errors = continuation.list(
            ApiRequest.of(
                "query",
                "list" to "linterrors",
                "lntcategories" to category,
                "lntlimit" to (limit?.takeIf { it < MAX_BATCH }?.toString() ?: "max"),
            ),
            "linterrors",
        ).map { entry ->
            LintError(
                id = entry["lintId"]?.jsonPrimitive?.longOrNull ?: 0L,
                category = entry["category"]?.jsonPrimitive?.content.orEmpty(),
                page = decoder.refOf(entry),
                range = entry["location"]?.jsonArray?.takeIf { it.size >= 2 }?.let { location ->
                    val start = location[0].jsonPrimitive.content.toIntOrNull() ?: 0
                    val end = location[1].jsonPrimitive.content.toIntOrNull() ?: 0
                    start..end
                },
                details = entry["params"]?.jsonObject
                    ?.mapValues { (_, value) -> value.toString().trim('"') }
                    .orEmpty(),
            )
        }

        return if (limit == null) errors else errors.take(limit)
    }

    override suspend fun notifications(unreadOnly: Boolean, limit: Int): List<Notification> {
        requireExtension(ExtensionService.ECHO)

        val response = transport.call(
            ApiRequest.of(
                "query",
                "meta" to "notifications",
                "notfilter" to if (unreadOnly) "!read" else null,
                "notlimit" to limit.toString(),
                "notprop" to "list",
            ),
        ).throwOnError()

        val list = response["query"]?.jsonObject
            ?.get("notifications")?.jsonObject
            ?.get("list")?.jsonArray
            ?: return emptyList()

        return list.map { entry ->
            val fields = entry.jsonObject
            Notification(
                id = fields["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                type = fields["type"]?.jsonPrimitive?.content.orEmpty(),
                title = fields["title"]?.jsonObject?.get("full")?.jsonPrimitive?.content,
                agent = fields["agent"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
                timestamp = fields["timestamp"]?.jsonObject?.get("utciso8601")
                    ?.jsonPrimitive?.content
                    ?.let { MwTimestamp.parseOrNull(it) },
                // Echo marks a notification read by giving it a read timestamp.
                isRead = fields.containsKey("read"),
            )
        }
    }

    override suspend fun thank(revision: RevisionId, source: String) {
        requireExtension(ExtensionService.THANKS)

        tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    mapOf(
                        "action" to "thank",
                        "rev" to revision.value.toString(),
                        "source" to source,
                        "token" to token,
                    ),
                    RequestKind.WRITE,
                ),
            ).throwOnError()
        }
    }

    override suspend fun shortenUrl(url: String): String {
        requireExtension(ExtensionService.URL_SHORTENER)

        val response = tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    mapOf("action" to "shortenurl", "url" to url, "token" to token),
                    RequestKind.WRITE,
                ),
            )
        }.throwOnError()

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
                pendingSince = flagged["pending_since"]?.jsonPrimitive?.content
                    ?.let { MwTimestamp.parseOrNull(it) },
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
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "review")
                        put("revid", revision.value.toString())
                        flags.forEach { (name, value) -> put("flag_$name", value.toString()) }
                        if (comment.isNotEmpty()) put("comment", comment)
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).throwOnError()
        }
    }

    /**
     * Fails unless the extension is installed.
     *
     * An empty result is indistinguishable from a clean wiki: a bot querying Linter would
     * read "no such extension" as "nothing to fix".
     */
    private fun requireExtension(extension: String) {
        if (!has(extension)) throw WikiError.Configuration.MissingExtension(extension)
    }

    /** A `prop=` query over a batch of pages, keeping whatever [read] finds on each. */
    private suspend fun <T : Any> byPage(
        refs: Collection<PageRef>,
        prop: String,
        vararg params: Pair<String, String?>,
        read: (kotlinx.serialization.json.JsonObject) -> T?,
    ): Map<PageRef, T> {
        if (refs.isEmpty()) return emptyMap()

        val found = mutableMapOf<PageRef, T>()
        for (batch in refs.distinct().chunked(batchSize)) {
            continuation.pages(
                ApiRequest.of(
                    "query",
                    "prop" to prop,
                    *params,
                    "titles" to batch.joinToString("|") { namespaces.format(it.title) },
                ),
            ).toList().forEach { page ->
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
