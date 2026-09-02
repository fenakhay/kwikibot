package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.model.Coordinate
import com.fenakhay.kwikibot.client.model.FlaggedInfo
import com.fenakhay.kwikibot.client.model.LintError
import com.fenakhay.kwikibot.client.model.Notification
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.PageRef
import kotlinx.coroutines.flow.Flow

/**
 * The services a wiki has only because an extension is installed.
 *
 * Grouped into one service rather than added to the wiki handle, so a wiki without an extension does not
 * appear to offer its operations. Every method checks that its extension is installed before sending
 * anything: an empty result would read as "nothing to fix".
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
     * Read from page properties rather than from the repository, so one request covers fifty pages and needs
     * no session on Wikidata.
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
     * There is no way to take it back, which is why it is not something a bot should do in a loop without a
     * person having decided.
     */
    public suspend fun thank(revision: RevisionId, source: String = "kwikibot")

    /** A short URL for a page, from UrlShortener. */
    public suspend fun shortenUrl(url: String): String

    /**
     * The reviewed state of pages, from FlaggedRevs.
     *
     * A page that has never been reviewed is absent from the result rather than present with a zero revision:
     * there is no stable revision to report.
     */
    public suspend fun flagged(refs: Collection<PageRef>): Map<PageRef, FlaggedInfo>

    /**
     * Marks a revision reviewed. Needs the `review` right.
     *
     * @param revision the revision to review.
     * @param flags the review dimensions the wiki defines, by name — `accuracy` on the wikis that configure
     *   one. An empty map accepts the wiki's defaults.
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
     * Spelling matters: a wiki reports its own name for an extension, and a mismatch reads as the extension
     * being absent rather than as a typo.
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
