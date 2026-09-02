package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.model.page.InterwikiLink
import com.fenakhay.kwikibot.model.page.LanguageLink
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import kotlinx.coroutines.flow.Flow

/** What a category query should return. */
public enum class CategoryMemberType(internal val apiValue: String) {
    /** Ordinary pages, which is what a category usually means. */
    PAGE("page"),

    /** Subcategories, for walking a category tree. */
    SUBCATEGORY("subcat"),

    /** Files filed in the category. */
    FILE("file"),
}

/** The order a category's members come back in. */
public enum class CategorySort(internal val apiValue: String) {
    /** By the sortkey the page was filed under, which is the order a reader sees. */
    SORTKEY("sortkey"),

    /** By when the page joined the category, newest last. The way to find recent additions. */
    TIMESTAMP("timestamp"),
}

/** Whether an enumeration should include redirects, exclude them, or not care. */
public enum class RedirectFilter(internal val apiValue: String) {
    /** Both, which is what the wiki does unasked. */
    ALL("all"),

    /** Only redirects, for a bot fixing where they point. */
    ONLY_REDIRECTS("redirects"),

    /** Only real pages, for a bot that would otherwise edit a redirect by mistake. */
    NO_REDIRECTS("nonredirects"),
}

/** How search results should be ordered. */
public enum class SearchSort(internal val apiValue: String) {
    /** What the search engine thinks is the best match. */
    RELEVANCE("relevance"),

    /** Most recently edited first. */
    LAST_EDIT_DESCENDING("last_edit_desc"),

    /** Oldest edited first. */
    LAST_EDIT_ASCENDING("last_edit_asc"),

    /** Newest page first. */
    CREATED_DESCENDING("create_timestamp_desc"),

    /** Oldest page first. */
    CREATED_ASCENDING("create_timestamp_asc"),

    /** By title, as a reader would sort them. */
    TITLE("title_natural_asc"),
}

/** Which part of a page a search should look at. */
public enum class SearchScope(internal val apiValue: String) {
    /** Full-text search, the default a reader gets. */
    TEXT("text"),

    /** Titles only. */
    TITLE("title"),

    /** Titles, treating the query as a prefix — the search box dropdown. */
    NEAR_MATCH("nearmatch"),
}

/**
 * Everything a wiki can be asked to list.
 *
 * Every method returns a cold [Flow] that pages through the API as it is collected, so taking ten results
 * from a category of a million costs one request, and abandoning a collection stops the paging.
 *
 * Namespaces are passed as a set rather than a pipe-joined string, because getting that string wrong is a
 * silent way to query the wrong part of a wiki.
 */
public interface ListService {

    /** Pages in a category. */
    public fun categoryMembers(
        category: PageRef,
        type: CategoryMemberType = CategoryMemberType.PAGE,
        namespaces: Set<Namespace> = emptySet(),
        sort: CategorySort = CategorySort.SORTKEY,
        startSortKey: String? = null,
        limit: Int? = null,
    ): Flow<PageRef>

    /** Pages that link to a page. */
    public fun backlinks(
        target: PageRef,
        namespaces: Set<Namespace> = emptySet(),
        includeRedirects: Boolean = true,
        limit: Int? = null,
    ): Flow<PageRef>

    /** Pages that transclude a page — how a bot finds every use of a template. */
    public fun transclusions(
        template: PageRef,
        namespaces: Set<Namespace> = emptySet(),
        limit: Int? = null,
    ): Flow<PageRef>

    /** Pages linked from a page. */
    public fun linksFrom(
        page: PageRef,
        namespaces: Set<Namespace> = emptySet(),
        limit: Int? = null,
    ): Flow<PageRef>

    /** Templates transcluded on a page. */
    public fun templatesOn(page: PageRef, limit: Int? = null): Flow<PageRef>

    /** Categories a page belongs to. */
    public fun categoriesOf(page: PageRef, limit: Int? = null): Flow<PageRef>

    /** Files used on a page, whether displayed or transcluded through a template. */
    public fun filesOn(page: PageRef, limit: Int? = null): Flow<PageRef>

    /**
     * Links from a page to the same subject on other languages' wikis.
     *
     * The titles are the other wikis', not this one's, so a bot working across languages cannot assume they
     * match.
     */
    public fun languageLinksOn(page: PageRef, limit: Int? = null): Flow<LanguageLink>

    /** Links from a page to other wikis through the interwiki map, such as `w:Etsy`. */
    public fun interwikiLinksOn(page: PageRef, limit: Int? = null): Flow<InterwikiLink>

    /**
     * External URLs a page links to.
     *
     * The other direction from [externalLinkUsage], which finds the pages linking to a URL. This is what a
     * link-rot bot reads.
     */
    public fun externalLinksOn(page: PageRef, limit: Int? = null): Flow<String>

    /**
     * Every page in a namespace, in title order.
     *
     * @param namespace which namespace to walk.
     * @param from the title to start at, inclusive.
     * @param to the title to stop at, inclusive. Without it the enumeration runs to the end of the namespace,
     *   so a bot working one alphabetical range pages past it and filters.
     * @param prefix only titles beginning with this.
     * @param descending walk from the end of the namespace backwards, in which case [from] is the later title
     *   and [to] the earlier one.
     * @param redirects whether to include redirects, exclude them, or not care.
     * @param limit how many to emit before stopping.
     */
    public fun allPages(
        namespace: Namespace = Namespace.MAIN,
        from: String? = null,
        to: String? = null,
        prefix: String? = null,
        descending: Boolean = false,
        redirects: RedirectFilter = RedirectFilter.ALL,
        limit: Int? = null,
    ): Flow<PageRef>

    /**
     * Every category on the wiki that has anything in it, in title order.
     *
     * Categories with no members do not exist as pages and are not enumerated, whether or not anything links
     * to them.
     *
     * @param from the category to start at, inclusive.
     * @param to the category to stop at, inclusive.
     * @param prefix only categories beginning with this.
     * @param min only categories with at least this many members.
     * @param max only categories with at most this many.
     * @param limit how many to emit before stopping.
     */
    public fun allCategories(
        from: String? = null,
        to: String? = null,
        prefix: String? = null,
        min: Int? = null,
        max: Int? = null,
        limit: Int? = null,
    ): Flow<PageRef>

    /**
     * The maintenance categories the wiki files pages into by itself.
     *
     * Broken file links, pages hitting the template expansion limit, and the rest: the wiki's own list of
     * what is wrong with it, which is a bot's work list.
     */
    public fun trackingCategories(limit: Int? = null): Flow<PageRef>

    /**
     * Every title anything links to, whether or not the target exists.
     *
     * The way to find red links across a namespace. [unique] is on by default: without it the wiki returns
     * one row per link rather than per target, so a title linked a thousand times arrives a thousand times.
     */
    public fun allLinkTargets(
        namespace: Namespace = Namespace.MAIN,
        from: String? = null,
        to: String? = null,
        prefix: String? = null,
        unique: Boolean = true,
        limit: Int? = null,
    ): Flow<PageRef>

    /** Every title anything redirects to, in the shape of [allLinkTargets]. */
    public fun allRedirectTargets(
        namespace: Namespace = Namespace.MAIN,
        from: String? = null,
        to: String? = null,
        prefix: String? = null,
        unique: Boolean = true,
        limit: Int? = null,
    ): Flow<PageRef>

    /** Every title anything transcludes, in the shape of [allLinkTargets]. */
    public fun allTransclusionTargets(
        namespace: Namespace = Namespace.TEMPLATE,
        from: String? = null,
        to: String? = null,
        prefix: String? = null,
        unique: Boolean = true,
        limit: Int? = null,
    ): Flow<PageRef>

    /** Every file anything uses, in the shape of [allLinkTargets]. */
    public fun allFileUsages(
        from: String? = null,
        to: String? = null,
        prefix: String? = null,
        unique: Boolean = true,
        limit: Int? = null,
    ): Flow<PageRef>

    /**
     * Titles beginning with [query], as the wiki's own search box completes them.
     *
     * Not a substring search: this matches the start of a title, which is what makes it cheap.
     */
    public fun prefixSearch(
        query: String,
        namespaces: Set<Namespace> = setOf(Namespace.MAIN),
        limit: Int? = null,
    ): Flow<PageRef>

    /** Search results. */
    public fun search(
        query: String,
        namespaces: Set<Namespace> = setOf(Namespace.MAIN),
        scope: SearchScope = SearchScope.TEXT,
        sort: SearchSort = SearchSort.RELEVANCE,
        limit: Int? = null,
    ): Flow<PageRef>

    /** Pages a wiki lists on a special page, such as `Wantedcategories` or `Lonelypages`. */
    public fun specialPage(name: String, limit: Int? = null): Flow<PageRef>

    /**
     * Pages linking to a URL.
     *
     * How a bot finds every page citing a dead domain. [url] may be a whole address or a bare host, and the
     * protocol is given separately because the API indexes them apart: a page linking to `http://example.org`
     * is not found by a search for the `https` form.
     */
    public fun externalLinkUsage(
        url: String,
        protocol: String? = null,
        namespaces: Set<Namespace> = emptySet(),
        limit: Int? = null,
    ): Flow<PageRef>

    /**
     * Random pages.
     *
     * The API returns a fresh set on every request, so this does not repeat itself the way paging through a
     * list does — and it cannot promise not to return the same page twice across requests.
     */
    public fun randomPages(
        namespaces: Set<Namespace> = setOf(Namespace.MAIN),
        limit: Int? = null,
    ): Flow<PageRef>

    /**
     * The pages on this session's watchlist.
     *
     * The watchlist of the account that logged in; the API exposes no other account's.
     */
    public fun watchlist(
        namespaces: Set<Namespace> = emptySet(),
        limit: Int? = null,
    ): Flow<PageRef>

    /**
     * Pages carrying a page property, such as `disambiguation` or `wikibase_item`.
     *
     * The properties are the ones parser functions and extensions set, which is how a wiki records "this is a
     * disambiguation page" in a way a bot can query.
     */
    public fun pagesWithProperty(property: String, limit: Int? = null): Flow<PageRef>

    /**
     * Titles that are protected against creation.
     *
     * A red link that cannot be turned blue. A bot creating pages should skip them rather than discover it
     * one refused edit at a time.
     */
    public fun protectedTitles(
        namespaces: Set<Namespace> = emptySet(),
        limit: Int? = null,
    ): Flow<PageRef>
}
