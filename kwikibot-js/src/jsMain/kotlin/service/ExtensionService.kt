import com.fenakhay.kwikibot.bindings.client.abuseFiltersWithDefaults
import com.fenakhay.kwikibot.bindings.client.extractsWithDefaults
import com.fenakhay.kwikibot.bindings.client.nearbyWithDefaults
import com.fenakhay.kwikibot.bindings.client.newPageStatsWithDefaults
import com.fenakhay.kwikibot.bindings.client.newPagesWithDefaults
import com.fenakhay.kwikibot.bindings.client.notificationsWithDefaults
import com.fenakhay.kwikibot.bindings.client.reviewWithDefaults
import com.fenakhay.kwikibot.bindings.client.reviewedNamed
import com.fenakhay.kwikibot.bindings.client.thankWithDefaults
import com.fenakhay.kwikibot.bindings.client.titleOf
import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.common.enumArgument
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.model.AbuseFilter as ClientAbuseFilter
import com.fenakhay.kwikibot.client.model.AbuseFilterFlags as ClientAbuseFilterFlags
import com.fenakhay.kwikibot.client.model.Coordinate as ClientCoordinate
import com.fenakhay.kwikibot.client.model.FlaggedInfo as ClientFlaggedInfo
import com.fenakhay.kwikibot.client.model.LintError as ClientLintError
import com.fenakhay.kwikibot.client.model.Notification as ClientNotification
import com.fenakhay.kwikibot.client.model.TriageBacklog as ClientTriageBacklog
import com.fenakhay.kwikibot.client.model.TriagePage as ClientTriagePage
import com.fenakhay.kwikibot.client.model.TriageStats as ClientTriageStats
import com.fenakhay.kwikibot.client.service.AbuseFilterOrder as ClientAbuseFilterOrder
import com.fenakhay.kwikibot.client.service.AbuseFilterProperty as ClientAbuseFilterProperty
import com.fenakhay.kwikibot.client.service.ExtensionService as ClientExtensionService
import com.fenakhay.kwikibot.client.service.NewPagesOrder as ClientNewPagesOrder
import com.fenakhay.kwikibot.model.page.PageRef
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise

/** The options of `abuseFilters`. */
@JsExport
public external interface AbuseFiltersOptions {
    /**
     * Which filters, by state: each of `"enabled"`, `"deleted"`, `"private"`, `"protected"` and
     * `"suppressed"` that has to hold, or with a leading `!` that must not.
     */
    public val show: Array<Negatable<AbuseFilterState>>?

    /**
     * What to read about each, from `"id"`, always read, `"description"`, `"pattern"`, `"actions"`, `"hits"`,
     * `"comments"`, `"last-editor"`, `"last-edit-time"`, `"flags"` and the deprecated `"status"`,
     * `"private"`, `"protected"` and `"suppressed"`. The id, description, actions and flags unless given.
     */
    public val properties: Array<AbuseFilterProperty>?

    /** `"lowest-id-first"`, the default, or `"highest-id-first"`. */
    public val order: AbuseFilterOrder?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `newPages`. */
@JsExport
public external interface NewPagesOptions {
    /** Which namespace's new pages, by number, the main one unless given. */
    public val namespace: Int?

    /** `"unreviewed"`, the default, `"reviewed"` or `"all"`. */
    public val reviewed: NewPagesReviewed?

    /** Whether to include redirects, `false` unless given. */
    public val redirects: Boolean?

    /** Only pages created by these accounts, at most `maxCreators`. */
    public val creators: Array<String>?

    /** Whether to leave out pages this account created, `false` unless given. */
    public val hideOwnPages: Boolean?

    /**
     * `"newest-first"`, the default, `"oldest-first"`, or, each stopping after one batch of at most 200
     * pages, `"newest-review-first"` or `"oldest-review-first"`.
     */
    public val order: NewPagesOrder?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `newPageStats`. */
@JsExport
public external interface NewPageStatsOptions {
    /** Which namespace's backlog, by number, the main one unless given. */
    public val namespace: Int?

    /** Count only pages created by these accounts in `matching`, at most `maxCreators`. */
    public val creators: Array<String>?

    /** Whether to leave this account's own pages out of `matching`, `false` unless given. */
    public val hideOwnPages: Boolean?
}

/**
 * What a wiki can do only because an extension is installed, reached as `wiki.extensions`. Every method
 * rejects when its extension is not installed, or for a listing throws on walking it, since an empty result
 * would read as "nothing there".
 */
@JsExport
public class ExtensionService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    /** The name of GeoData, as `has` takes it. */
    public val geoData: String
        get() = ClientExtensionService.GEO_DATA

    /** The name of PageImages, as `has` takes it. */
    public val pageImagesExtension: String
        get() = ClientExtensionService.PAGE_IMAGES

    /** The name of TextExtracts, as `has` takes it. */
    public val textExtracts: String
        get() = ClientExtensionService.TEXT_EXTRACTS

    /** The name of WikibaseClient, as `has` takes it. */
    public val wikibaseClient: String
        get() = ClientExtensionService.WIKIBASE_CLIENT

    /** The name of Linter, as `has` takes it. */
    public val linter: String
        get() = ClientExtensionService.LINTER

    /** The name of Echo, as `has` takes it. */
    public val echo: String
        get() = ClientExtensionService.ECHO

    /** The name of Thanks, as `has` takes it. */
    public val thanks: String
        get() = ClientExtensionService.THANKS

    /** The name of UrlShortener, as `has` takes it. */
    public val urlShortener: String
        get() = ClientExtensionService.URL_SHORTENER

    /** The name of FlaggedRevs, as `has` takes it. */
    public val flaggedRevs: String
        get() = ClientExtensionService.FLAGGED_REVS

    /** The name of AbuseFilter, as `has` takes it, which has a space. */
    public val abuseFilter: String
        get() = ClientExtensionService.ABUSE_FILTER

    /** The name of PageTriage, as `has` takes it. */
    public val pageTriage: String
        get() = ClientExtensionService.PAGE_TRIAGE

    /** The most accounts `newPages` and `newPageStats` can filter by at once. */
    public val maxCreators: Int
        get() = ClientExtensionService.MAX_CREATORS

    /** Whether [extension] is installed, by the name MediaWiki reports. */
    public fun has(extension: String): Boolean = wiki.extensions.has(extension)

    /** The coordinates of each of [titles] that has any, from GeoData. */
    public fun coordinates(titles: Array<String>): Promise<Array<PageCoordinates>> = scope.promise {
        wiki.extensions
            .coordinates(refs(titles))
            .map { (ref, found) -> PageCoordinates(wiki.titleOf(ref), found) }
            .toTypedArray()
    }

    /**
     * The titles of the pages near a point, nearest first, from GeoData.
     *
     * @param latitude degrees north of the equator.
     * @param longitude degrees east of the meridian.
     * @param radius in metres, 1000 unless given. The wiki caps it.
     * @param limit how many to return, 10 unless given.
     */
    public fun nearby(
        latitude: Double,
        longitude: Double,
        radius: Int? = null,
        limit: Int? = null,
    ): Promise<Array<String>> = scope.promise {
        wiki.nearbyWithDefaults(latitude, longitude, radius, limit).map(wiki::titleOf).toTypedArray()
    }

    /**
     * The lead image of each of [titles] that has one, from PageImages, as a file name without its namespace.
     */
    public fun pageImages(titles: Array<String>): Promise<Array<PageValue>> = scope.promise {
        values(wiki.extensions.pageImages(refs(titles)))
    }

    /**
     * The opening plain text of each of [titles], from TextExtracts: [sentences] sentences, 2 unless given,
     * or the whole lead section with `0`.
     */
    public fun extracts(titles: Array<String>, sentences: Int? = null): Promise<Array<PageValue>> =
        scope.promise {
            values(wiki.extractsWithDefaults(titles.asList(), sentences))
        }

    /** The Wikidata item each of [titles] is about, where it has one, from WikibaseClient. */
    public fun wikibaseItems(titles: Array<String>): Promise<Array<PageValue>> = scope.promise {
        values(wiki.extensions.wikibaseItems(refs(titles)))
    }

    /** The problems Linter found, of [category] when given, the first [limit] when given. */
    public fun lintErrors(category: String? = null, limit: Int? = null): AsyncIterable<LintError> = listing {
        wiki.extensions.lintErrors(category, limit)
    }
        .toAsyncIterable(scope) { LintError(it, wiki) }

    /** This session's notifications, from Echo, at most [limit], 25 unless given. */
    public fun notifications(unreadOnly: Boolean = false, limit: Int? = null): Promise<Array<Notification>> =
        scope.promise {
            wiki.notificationsWithDefaults(unreadOnly, limit).map(::Notification).toTypedArray()
        }

    /** Thanks the author of [revision], through Thanks. It cannot be taken back. */
    public fun thank(revision: Double, source: String? = null): Promise<Unit> = scope.promiseVoid {
        wiki.thankWithDefaults(revisionIdOf(revision), source)
    }

    /** A short URL for [url], from UrlShortener. */
    public fun shortenUrl(url: String): Promise<String> = scope.promise { wiki.extensions.shortenUrl(url) }

    /** The reviewed state of each of [titles] that has ever been reviewed, from FlaggedRevs. */
    public fun flagged(titles: Array<String>): Promise<Array<FlaggedInfo>> = scope.promise {
        wiki.extensions
            .flagged(refs(titles))
            .map { (ref, info) -> FlaggedInfo(wiki.titleOf(ref), info) }
            .toTypedArray()
    }

    /**
     * Marks [revision] reviewed. Needs the `review` right.
     *
     * @param revision the revision.
     * @param flags the review dimensions the wiki defines, by name. The wiki's defaults unless given.
     * @param comment the note to record with the review.
     */
    public fun review(
        revision: Double,
        flags: Record<String, Int>? = null,
        comment: String = "",
    ): Promise<Unit> = scope.promiseVoid {
        wiki.reviewWithDefaults(revisionIdOf(revision), flags?.toMap(), comment)
    }

    /** The abuse filters the wiki defines, from AbuseFilter. Needs the `abusefilter-view` right. */
    public fun abuseFilters(options: AbuseFiltersOptions? = null): AsyncIterable<AbuseFilter> = listing {
        wiki.abuseFiltersWithDefaults(
            options?.show?.map { it.unsafeCast<String>() },
            options?.properties?.map {
                enumArgument<ClientAbuseFilterProperty>("properties", it.unsafeCast<String>())
            },
            enumOf<ClientAbuseFilterOrder>("order", options?.order),
            options?.limit,
        )
    }
        .toAsyncIterable(scope, ::AbuseFilter)

    /** The pages in the new pages feed, from PageTriage: the list a patrolling bot works through. */
    public fun newPages(options: NewPagesOptions? = null): AsyncIterable<TriagePage> = listing {
        wiki.newPagesWithDefaults(
            options?.namespace,
            reviewedNamed(options?.reviewed?.unsafeCast<String>()),
            options?.redirects,
            options?.creators?.asList(),
            options?.hideOwnPages,
            enumOf<ClientNewPagesOrder>("order", options?.order),
            options?.limit,
        )
    }
        .toAsyncIterable(scope) { TriagePage(it, wiki) }

    /** How long the new pages backlog is, from PageTriage. */
    public fun newPageStats(options: NewPageStatsOptions? = null): Promise<TriageStats> = scope.promise {
        TriageStats(
            wiki.newPageStatsWithDefaults(
                options?.namespace,
                options?.creators?.asList(),
                options?.hideOwnPages,
            )
        )
    }

    private fun refs(titles: Array<String>): List<PageRef> = titles.map { wiki.ref(it) }

    private fun values(found: Map<PageRef, String>): Array<PageValue> =
        found.map { (ref, value) -> PageValue(wiki.titleOf(ref), value) }.toTypedArray()
}

/** One page, and one thing an extension says about it. */
@JsExport
public class PageValue
internal constructor(
    /** The page. */
    public val title: String,
    /** What the extension says. */
    public val value: String,
)

/** A point on the globe, as GeoData records it for a page. */
@JsExport
public class Coordinate internal constructor(private val coordinate: ClientCoordinate) {

    /** Degrees north, negative for south. */
    public val latitude: Double
        get() = coordinate.latitude

    /** Degrees east, negative for west. */
    public val longitude: Double
        get() = coordinate.longitude

    /** Which globe, `"earth"` unless the wiki says otherwise. */
    public val globe: String
        get() = coordinate.globe

    /** Whether this is the page's main coordinate rather than one of several it mentions. */
    public val isPrimary: Boolean
        get() = coordinate.isPrimary

    /** What is at the point, such as `"city"` or `"mountain"`, or `null`. */
    public val type: String?
        get() = coordinate.type
}

/** The coordinates of one page. */
@JsExport
public class PageCoordinates
internal constructor(
    /** The page. */
    public val title: String,
    private val found: List<ClientCoordinate>,
) {
    /** Its coordinates. */
    public val coordinates: Array<Coordinate>
        get() = found.map(::Coordinate).toTypedArray()
}

/** One problem the Linter extension found on a page. */
@JsExport
public class LintError
internal constructor(private val error: ClientLintError, private val wiki: ClientWiki) {

    /** The error's own id, stable while the problem is still on the page. */
    public val id: Double
        get() = error.id.toDouble()

    /** Its category, such as `"obsolete-tag"` or `"missing-end-tag"`. */
    public val category: String
        get() = error.category

    /** The page it was found on, or `null`. */
    public val page: String?
        get() = error.page?.let(wiki::titleOf)

    /** Where in the wikitext it starts, as a byte offset, or `null`. */
    public val rangeStart: Int?
        get() = error.range?.first

    /** Where in the wikitext it ends, as a byte offset, or `null`. */
    public val rangeEnd: Int?
        get() = error.range?.last

    /** Whatever else Linter recorded about it, which varies by category. */
    public val details: Record<String, String>
        get() = error.details.toRecord { it }
}

/** One notification from Echo. */
@JsExport
public class Notification internal constructor(private val notification: ClientNotification) {

    /** Its own id. */
    public val id: Double
        get() = notification.id.toDouble()

    /** Its type, such as `"edit-user-talk"`, `"mention"` or `"reverted"`. */
    public val type: String
        get() = notification.type

    /** The page it is about, or `null`. */
    public val title: String?
        get() = notification.title

    /** Who caused it, or `null` when the wiki did not say. */
    public val agent: String?
        get() = notification.agent

    /** When it happened, or `null`. */
    public val timestamp: Date?
        get() = notification.timestamp?.let(::dateOf)

    /** Whether it has already been seen. */
    public val isRead: Boolean
        get() = notification.isRead
}

/** What a wiki running FlaggedRevs says about a page's reviewed state. */
@JsExport
public class FlaggedInfo
internal constructor(
    /** The page. */
    public val title: String,
    private val info: ClientFlaggedInfo,
) {
    /** The revision readers are shown, which lags the newest while changes are pending. */
    public val stableRevisionId: Double
        get() = info.stableRevisionId.toDouble()

    /** The review level, as the wiki configures them. */
    public val level: Int
        get() = info.level

    /** The level as the wiki names it, such as `"stable"`, or `null`. */
    public val levelText: String?
        get() = info.levelText

    /** When the oldest unreviewed edit was made, or `null` when nothing is pending. */
    public val pendingSince: Date?
        get() = info.pendingSince?.let(::dateOf)

    /** Whether edits are waiting for review, so that the newest revision is not the one shown. */
    public val hasPendingChanges: Boolean
        get() = info.hasPendingChanges
}

/** The state of an abuse filter. */
@JsExport
public class AbuseFilterFlags internal constructor(private val flags: ClientAbuseFilterFlags) {

    /** Whether it checks edits at all. */
    public val isEnabled: Boolean
        get() = flags.isEnabled

    /** Whether it has been deleted, which leaves it listed but inert. */
    public val isDeleted: Boolean
        get() = flags.isDeleted

    /** Whether its rules are hidden from the public. */
    public val isPrivate: Boolean
        get() = flags.isPrivate

    /** Whether it reads protected variables, which hides its rules from more people still. */
    public val isProtected: Boolean
        get() = flags.isProtected

    /** Whether it is hidden even from administrators. Always `false` before MediaWiki 1.47. */
    public val isSuppressed: Boolean
        get() = flags.isSuppressed
}

/** One abuse filter: what was asked for, and what this account may see. */
@JsExport
public class AbuseFilter internal constructor(private val filter: ClientAbuseFilter) {

    /** The filter's number. */
    public val id: Double
        get() = filter.id.toDouble()

    /** The public description, or `null`. */
    public val description: String?
        get() = filter.description

    /** What it does when it matches, such as `"warn"` or `"disallow"`. */
    public val actions: Array<String>
        get() = filter.actions.toTypedArray()

    /** The rules it matches against, or `null`. */
    public val pattern: String?
        get() = filter.pattern

    /** Notes left by its editors, or `null`. */
    public val comments: String?
        get() = filter.comments

    /** How often it has matched, or `null`. */
    public val hits: Double?
        get() = filter.hits?.toDouble()

    /** Who last changed it, or `null`. */
    public val lastEditor: String?
        get() = filter.lastEditor

    /** When it was last changed, or `null`. */
    public val lastEdited: Date?
        get() = filter.lastEdited?.let(::dateOf)

    /** Its state, when `"flags"` was asked for. */
    public val flags: AbuseFilterFlags?
        get() = filter.flags?.let(::AbuseFilterFlags)

    /** The properties asked for that the wiki withheld from this account, as `properties` names them. */
    public val redacted: Array<AbuseFilterProperty>
        get() = filter.redacted.map { it.argumentName().unsafeCast<AbuseFilterProperty>() }.toTypedArray()
}

/** One page in the new pages feed. */
@JsExport
public class TriagePage
internal constructor(private val page: ClientTriagePage, private val wiki: ClientWiki) {

    /** The page's title. */
    public val title: String
        get() = wiki.titleOf(page.page)

    /** When it was created, or `null`. */
    public val created: Date?
        get() = page.created?.let(::dateOf)

    /** Who created it, or `null` when the account has been hidden. */
    public val creator: String?
        get() = page.creator

    /** Whether the creator is a temporary account. */
    public val creatorIsTemporary: Boolean
        get() = page.creatorIsTemporary

    /**
     * How far it has got through review: `"unreviewed"`, `"reviewed"`, `"patrolled"` or `"autopatrolled"`, or
     * `null` for a state this library does not know.
     */
    public val status: TriageStatus?
        get() = page.status?.argumentName().unsafeCast<TriageStatus?>()

    /** Who last reviewed it, or `null` when nobody has or the reviewer has been hidden. */
    public val reviewer: String?
        get() = page.reviewer

    /** When its review state last changed, or `null`. */
    public val reviewUpdated: Date?
        get() = page.reviewUpdated?.let(::dateOf)

    /** Whether it is a redirect. */
    public val isRedirect: Boolean
        get() = page.isRedirect

    /** Whether nothing links to it, as PageTriage last counted. */
    public val isOrphan: Boolean
        get() = page.isOrphan
}

/** A queue in the new pages feed. */
@JsExport
public class TriageBacklog internal constructor(private val backlog: ClientTriageBacklog) {

    /** How many pages are in it. */
    public val count: Double
        get() = backlog.count.toDouble()

    /**
     * When the longest-waiting entered the queue or last changed review state, or `null` when it is empty.
     */
    public val oldest: Date?
        get() = backlog.oldest?.let(::dateOf)
}

/** How long the new pages backlog is. */
@JsExport
public class TriageStats internal constructor(private val stats: ClientTriageStats) {

    /** Articles waiting for review. */
    public val unreviewedArticles: TriageBacklog
        get() = TriageBacklog(stats.unreviewedArticles)

    /** Redirects waiting for review. */
    public val unreviewedRedirects: TriageBacklog
        get() = TriageBacklog(stats.unreviewedRedirects)

    /** Drafts waiting for review, or `null` where the wiki does not review drafts in the feed. */
    public val unreviewedDrafts: TriageBacklog?
        get() = stats.unreviewedDrafts?.let(::TriageBacklog)

    /** Articles reviewed in the past week. */
    public val reviewedArticles: Double
        get() = stats.reviewedArticles.toDouble()

    /** Redirects reviewed in the past week. */
    public val reviewedRedirects: Double
        get() = stats.reviewedRedirects.toDouble()

    /** Articles, reviewed or not, created by the accounts the stats were asked about. */
    public val matching: Double
        get() = stats.matching.toDouble()
}
