package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.wire.MediaWikiParameter
import com.fenakhay.kwikibot.client.model.AbuseFilter
import com.fenakhay.kwikibot.client.model.Coordinate
import com.fenakhay.kwikibot.client.model.FlaggedInfo
import com.fenakhay.kwikibot.client.model.LintError
import com.fenakhay.kwikibot.client.model.Notification
import com.fenakhay.kwikibot.client.model.TriagePage
import com.fenakhay.kwikibot.client.model.TriageStats
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.protocol.decode.OptionSet
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

    /**
     * Problems Linter found, optionally of one category.
     *
     * @throws WikiError.Configuration.MissingExtension if Linter is not installed, when the flow is
     *   collected.
     */
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
     * The abuse filters a wiki defines, from AbuseFilter. Needs the `abusefilter-view` right.
     *
     * What this account may not see comes back missing rather than refused: a private filter has no pattern.
     * From MediaWiki 1.47, [AbuseFilter.redacted] says which properties were withheld, so an absent pattern
     * is not mistaken for an empty one.
     *
     * @param show which filters, by state: `OptionSet().on("enabled").off("deleted")`. The states are
     *   `enabled`, `deleted`, `private`, `protected` and `suppressed`; `protected` only from MediaWiki 1.43,
     *   and `suppressed` only from 1.47.
     * @param properties what to read about each filter. The id is read whether or not it is asked for.
     * @param order which end of the id range to start from.
     * @param limit how many to return, or `null` for all of them.
     * @throws WikiError.Configuration.MissingExtension if AbuseFilter is not installed, when the flow is
     *   collected.
     */
    public fun abuseFilters(
        show: OptionSet = OptionSet(),
        properties: Set<AbuseFilterProperty> = AbuseFilterProperty.DEFAULT,
        order: AbuseFilterOrder = AbuseFilterOrder.LOWEST_ID_FIRST,
        limit: Int? = null,
    ): Flow<AbuseFilter>

    /**
     * Pages in the new pages feed, from PageTriage.
     *
     * The feed is where new articles wait for review, so this is the list a patrolling bot works through.
     *
     * @param namespace which namespace's new pages. PageTriage covers the main namespace and any others the
     *   wiki configures, such as drafts, and reads a namespace it does not cover as the main one.
     * @param reviewed `false` for pages awaiting review, `true` for pages already reviewed, `null` for both.
     * @param redirects whether to include redirects, which the feed lists beside articles.
     * @param creators only pages created by these accounts. At most [MAX_CREATORS].
     * @param hideOwnPages leave out pages this account created.
     * @param order the order to read them in.
     * @param limit how many to return, or `null` for all of them.
     * @throws WikiError.Configuration.MissingExtension if PageTriage is not installed, when the flow is
     *   collected.
     */
    public fun newPages(
        namespace: Namespace = Namespace.MAIN,
        reviewed: Boolean? = false,
        redirects: Boolean = false,
        creators: Set<String> = emptySet(),
        hideOwnPages: Boolean = false,
        order: NewPagesOrder = NewPagesOrder.NEWEST_FIRST,
        limit: Int? = null,
    ): Flow<TriagePage>

    /**
     * How long the new pages backlog is, from PageTriage.
     *
     * @param namespace which namespace's backlog.
     * @param creators count only pages created by these accounts in [TriageStats.matching]. At most
     *   [MAX_CREATORS].
     * @param hideOwnPages leave this account's own pages out of [TriageStats.matching].
     * @throws WikiError.Configuration.MissingExtension if PageTriage is not installed.
     */
    public suspend fun newPageStats(
        namespace: Namespace = Namespace.MAIN,
        creators: Set<String> = emptySet(),
        hideOwnPages: Boolean = false,
    ): TriageStats

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

        /** Rules that check every edit as it is made. The name has a space, as the wiki reports it. */
        public const val ABUSE_FILTER: String = "Abuse Filter"

        /** The new pages feed, where new articles wait for review. */
        public const val PAGE_TRIAGE: String = "PageTriage"

        /** The most accounts [newPages] and [newPageStats] can filter by at once. */
        public const val MAX_CREATORS: Int = 10

        internal const val DEFAULT_RADIUS = 1000
        internal const val DEFAULT_NEARBY = 10
        internal const val DEFAULT_SENTENCES = 2
        internal const val DEFAULT_NOTIFICATIONS = 25
    }
}

/** What to read about an abuse filter, named as `list=abusefilters` names it. */
@MediaWikiParameter("query+abusefilters", "abfprop")
public enum class AbuseFilterProperty(internal val apiValue: String) {
    /** The filter's number, which its log entries and its page are named by. Always read. */
    ID("id"),

    /** The public description. */
    DESCRIPTION("description"),

    /** The rules the filter matches against. Withheld from a private filter's unprivileged viewers. */
    PATTERN("pattern"),

    /** What the filter does when it matches: `warn`, `disallow`, `tag`. */
    ACTIONS("actions"),

    /** How often it has matched. Withheld from an account that may not see the filter's log details. */
    HITS("hits"),

    /** Notes left by the filter's editors. Withheld where the pattern is. */
    COMMENTS("comments"),

    /** Who last changed the filter. */
    LAST_EDITOR("lasteditor"),

    /** When the filter was last changed. */
    LAST_EDIT_TIME("lastedittime"),

    /**
     * Whether the filter is enabled, deleted, private, protected or suppressed, as [AbuseFilter.flags].
     *
     * Asked for as `flags` from MediaWiki 1.47, and as the separate properties it replaced before that.
     */
    FLAGS("flags"),

    /** Whether the filter is enabled or deleted. Read as [FLAGS] is. */
    @Deprecated(
        "MediaWiki 1.47 (T435834) deprecated query+abusefilters abfprop=status; use FLAGS. " +
            "Deprecated since kwikibot 1.2.0.",
        ReplaceWith("AbuseFilterProperty.FLAGS", "com.fenakhay.kwikibot.client.service.AbuseFilterProperty"),
    )
    STATUS("status"),

    /** Whether the filter is hidden from the public. Read as [FLAGS] is. */
    @Deprecated(
        "MediaWiki 1.47 (T435834) deprecated query+abusefilters abfprop=private; use FLAGS. " +
            "Deprecated since kwikibot 1.2.0.",
        ReplaceWith("AbuseFilterProperty.FLAGS", "com.fenakhay.kwikibot.client.service.AbuseFilterProperty"),
    )
    PRIVATE("private"),

    /** Whether the filter uses protected variables. Read as [FLAGS] is. */
    @Deprecated(
        "MediaWiki 1.47 (T435834) deprecated query+abusefilters abfprop=protected; use FLAGS. " +
            "Deprecated since kwikibot 1.2.0.",
        ReplaceWith("AbuseFilterProperty.FLAGS", "com.fenakhay.kwikibot.client.service.AbuseFilterProperty"),
    )
    PROTECTED("protected"),

    /** Whether the filter is hidden even from administrators. Read as [FLAGS] is. */
    @Deprecated(
        "MediaWiki 1.47 (T435834) deprecated query+abusefilters abfprop=suppressed; use FLAGS. " +
            "Deprecated since kwikibot 1.2.0.",
        ReplaceWith("AbuseFilterProperty.FLAGS", "com.fenakhay.kwikibot.client.service.AbuseFilterProperty"),
    )
    SUPPRESSED("suppressed");

    /** The groupings worth asking for as a set. */
    public companion object {
        /** What the wiki reads when not told, which is enough to list the filters and what they do. */
        public val DEFAULT: Set<AbuseFilterProperty> = setOf(ID, DESCRIPTION, ACTIONS, FLAGS)
    }
}

/**
 * Which end of the id range [ExtensionService.abuseFilters] starts from.
 *
 * Sent as `newer` and `older`, which MediaWiki 1.47 kept as aliases of its new `ascending` and `descending`
 * and which every older release takes.
 */
@MediaWikiParameter("query+abusefilters", "abfdir")
public enum class AbuseFilterOrder(internal val apiValue: String) {
    /** Filter 1 first: the oldest filters. */
    LOWEST_ID_FIRST("newer"),

    /** The newest filter first. */
    HIGHEST_ID_FIRST("older"),
}

/** The order [ExtensionService.newPages] reads the feed in. */
@MediaWikiParameter("pagetriagelist", "dir")
public enum class NewPagesOrder(internal val apiValue: String, internal val offsetKey: String?) {
    /** The most recently created first, which is the feed's own default. */
    NEWEST_FIRST("newestfirst", CREATED),

    /** The longest-waiting first, which is where a backlog drive starts. */
    OLDEST_FIRST("oldestfirst", CREATED),

    /**
     * The most recently reviewed first.
     *
     * PageTriage continues a list by creation time whatever its order, so a list in review order cannot be
     * continued: it stops after one batch of at most 200 pages.
     */
    NEWEST_REVIEW_FIRST("newestreview", null),

    /** The least recently reviewed first, stopping after one batch as [NEWEST_REVIEW_FIRST] does. */
    OLDEST_REVIEW_FIRST("oldestreview", null),
}

/** Where in a feed entry the next request resumes from. */
private const val CREATED = "creation_date_utc"
