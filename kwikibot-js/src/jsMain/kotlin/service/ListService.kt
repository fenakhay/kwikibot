import com.fenakhay.kwikibot.bindings.client.allFileUsagesWithDefaults
import com.fenakhay.kwikibot.bindings.client.allLinkTargetsWithDefaults
import com.fenakhay.kwikibot.bindings.client.allPagesWithDefaults
import com.fenakhay.kwikibot.bindings.client.allRedirectTargetsWithDefaults
import com.fenakhay.kwikibot.bindings.client.allTransclusionTargetsWithDefaults
import com.fenakhay.kwikibot.bindings.client.backlinksWithDefaults
import com.fenakhay.kwikibot.bindings.client.categoryMembersWithDefaults
import com.fenakhay.kwikibot.bindings.client.externalLinkUsageWithDefaults
import com.fenakhay.kwikibot.bindings.client.linksFromWithDefaults
import com.fenakhay.kwikibot.bindings.client.prefixSearchWithDefaults
import com.fenakhay.kwikibot.bindings.client.protectedTitlesWithDefaults
import com.fenakhay.kwikibot.bindings.client.randomPagesWithDefaults
import com.fenakhay.kwikibot.bindings.client.searchWithDefaults
import com.fenakhay.kwikibot.bindings.client.titleOf
import com.fenakhay.kwikibot.bindings.client.transclusionsWithDefaults
import com.fenakhay.kwikibot.bindings.client.watchlistWithDefaults
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.service.CategoryMemberType as ClientCategoryMemberType
import com.fenakhay.kwikibot.client.service.CategorySort as ClientCategorySort
import com.fenakhay.kwikibot.client.service.RedirectFilter as ClientRedirectFilter
import com.fenakhay.kwikibot.client.service.SearchScope as ClientSearchScope
import com.fenakhay.kwikibot.client.service.SearchSort as ClientSearchSort
import com.fenakhay.kwikibot.model.page.InterwikiLink as ClientInterwikiLink
import com.fenakhay.kwikibot.model.page.LanguageLink as ClientLanguageLink
import com.fenakhay.kwikibot.model.page.PageRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** The options of a listing that takes namespaces and a limit. */
@JsExport
public external interface ListOptions {
    /** Only pages in these namespaces, by number. The listing says what it reads without them. */
    public val namespaces: Array<Int>?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `categoryMembers`. */
@JsExport
public external interface CategoryMembersOptions {
    /** What to list: `"page"`, the default, `"subcategory"` or `"file"`. */
    public val type: CategoryMemberType?

    /** Only members in these namespaces, by number. Every namespace unless given. */
    public val namespaces: Array<Int>?

    /**
     * The order: `"sortkey"`, the default, which is what a reader sees, or `"timestamp"`, by when each joined
     * the category, newest last.
     */
    public val sort: CategorySort?

    /** The sort key to begin at. */
    public val startSortKey: String?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `backlinks`. */
@JsExport
public external interface BacklinksOptions {
    /** Only pages in these namespaces, by number. Every namespace unless given. */
    public val namespaces: Array<Int>?

    /**
     * Whether to read pages that link through a redirect too, as well as the redirect, `true` unless given.
     */
    public val includeRedirects: Boolean?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `allPages`. */
@JsExport
public external interface AllPagesOptions {
    /** Which namespace to walk, by number, the main one unless given. */
    public val namespace: Int?

    /** The title to start at, inclusive. */
    public val from: String?

    /** The title to stop at, inclusive. Without it the walk runs to the end of the namespace. */
    public val to: String?

    /** Only titles beginning with this. */
    public val prefix: String?

    /** Whether to walk backwards, in which case [from] is the later title and [to] the earlier one. */
    public val descending: Boolean?

    /** `"all"`, the default, `"only-redirects"` or `"no-redirects"`. */
    public val redirects: RedirectFilter?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `allCategories`. */
@JsExport
public external interface AllCategoriesOptions {
    /** The category to start at, inclusive. */
    public val from: String?

    /** The category to stop at, inclusive. */
    public val to: String?

    /** Only categories beginning with this. */
    public val prefix: String?

    /** Only categories with at least this many members. */
    public val min: Int?

    /** Only categories with at most this many members. */
    public val max: Int?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `allLinkTargets`, `allRedirectTargets` and `allTransclusionTargets`. */
@JsExport
public external interface AllTargetsOptions {
    /**
     * Which namespace to walk, by number. The main one unless given, or the template one for
     * `allTransclusionTargets`.
     */
    public val namespace: Int?

    /** The title to start at, inclusive. */
    public val from: String?

    /** The title to stop at, inclusive. */
    public val to: String?

    /** Only titles beginning with this. */
    public val prefix: String?

    /**
     * Whether to give each target once, `true` unless given. Otherwise a title linked a thousand times
     * arrives a thousand times.
     */
    public val unique: Boolean?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `allFileUsages`, whose targets are all files. */
@JsExport
public external interface AllFileUsagesOptions {
    /** The file to start at, inclusive. */
    public val from: String?

    /** The file to stop at, inclusive. */
    public val to: String?

    /** Only files beginning with this. */
    public val prefix: String?

    /** Whether to give each file once, `true` unless given. */
    public val unique: Boolean?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `search`. */
@JsExport
public external interface SearchOptions {
    /** Which namespaces to search, by number, the main one unless given. */
    public val namespaces: Array<Int>?

    /** Which part of a page to look at: `"text"`, the default, `"title"` or `"near-match"`. */
    public val scope: SearchScope?

    /**
     * The order: `"relevance"`, the default, `"last-edit-descending"`, `"last-edit-ascending"`,
     * `"created-descending"`, `"created-ascending"` or `"title"`.
     */
    public val sort: SearchSort?

    /** How many to give before stopping. */
    public val limit: Int?
}

/** The options of `externalLinkUsage`. */
@JsExport
public external interface ExternalLinkUsageOptions {
    /**
     * The protocol, such as `"https"`, which the wiki indexes apart: a page linking to `http://example.org`
     * is not found by a search for the `https` form.
     */
    public val protocol: String?

    /** Only pages in these namespaces, by number. Every namespace unless given. */
    public val namespaces: Array<Int>?

    /** How many to give before stopping. */
    public val limit: Int?
}

/**
 * Everything a wiki can be asked to list, reached as `wiki.lists`.
 *
 * Each listing pages through the API as it is walked, so taking ten results from a category of a million
 * costs one request, and ending a walk early stops the paging. An option that names none of its values throws
 * `IllegalArgumentException` when the listing is asked for, as does a title the wiki would not accept.
 */
@JsExport
public class ListService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    /** The titles in [category], which needs no `Category:` prefix. */
    public fun categoryMembers(
        category: String,
        options: CategoryMembersOptions? = null,
    ): AsyncIterable<String> = listing {
        wiki.categoryMembersWithDefaults(
            category,
            enumOf<ClientCategoryMemberType>("type", options?.type),
            options?.namespaces?.asList(),
            enumOf<ClientCategorySort>("sort", options?.sort),
            options?.startSortKey,
            options?.limit,
        )
    }
        .titles()

    /** The titles of the pages that link to [target]. */
    public fun backlinks(target: String, options: BacklinksOptions? = null): AsyncIterable<String> = listing {
        wiki.backlinksWithDefaults(
            target,
            options?.namespaces?.asList(),
            options?.includeRedirects,
            options?.limit,
        )
    }
        .titles()

    /**
     * The titles of the pages that transclude [template], which needs no `Template:` prefix: how a bot finds
     * every use of a template. Every namespace unless told.
     */
    public fun transclusions(template: String, options: ListOptions? = null): AsyncIterable<String> =
        listing {
            wiki.transclusionsWithDefaults(template, options?.namespaces?.asList(), options?.limit)
        }
        .titles()

    /** The titles [page] links to. Every namespace unless told. */
    public fun linksFrom(page: String, options: ListOptions? = null): AsyncIterable<String> = listing {
        wiki.linksFromWithDefaults(page, options?.namespaces?.asList(), options?.limit)
    }
        .titles()

    /** The titles of the templates transcluded on [page]. */
    public fun templatesOn(page: String, limit: Int? = null): AsyncIterable<String> = listing {
        wiki.lists.templatesOn(wiki.ref(page), limit)
    }
        .titles()

    /** The titles of the categories [page] belongs to. */
    public fun categoriesOf(page: String, limit: Int? = null): AsyncIterable<String> = listing {
        wiki.lists.categoriesOf(wiki.ref(page), limit)
    }
        .titles()

    /** The titles of the files used on [page], whether displayed or transcluded through a template. */
    public fun filesOn(page: String, limit: Int? = null): AsyncIterable<String> = listing {
        wiki.lists.filesOn(wiki.ref(page), limit)
    }
        .titles()

    /** The links from [page] to the same subject on other languages' wikis. */
    public fun languageLinksOn(page: String, limit: Int? = null): AsyncIterable<LanguageLink> = listing {
        wiki.lists.languageLinksOn(wiki.ref(page), limit)
    }
        .toAsyncIterable(scope, ::LanguageLink)

    /** The links from [page] to other wikis through the interwiki map, such as `w:Etsy`. */
    public fun interwikiLinksOn(page: String, limit: Int? = null): AsyncIterable<InterwikiLink> = listing {
        wiki.lists.interwikiLinksOn(wiki.ref(page), limit)
    }
        .toAsyncIterable(scope, ::InterwikiLink)

    /** The external URLs [page] links to, the other direction from [externalLinkUsage]. */
    public fun externalLinksOn(page: String, limit: Int? = null): AsyncIterable<String> = listing {
        wiki.lists.externalLinksOn(wiki.ref(page), limit)
    }
        .toAsyncIterable(scope) { it }

    /** The title of every page in a namespace, in title order. */
    public fun allPages(options: AllPagesOptions? = null): AsyncIterable<String> = listing {
        wiki.allPagesWithDefaults(
            options?.namespace,
            options?.from,
            options?.to,
            options?.prefix,
            options?.descending,
            enumOf<ClientRedirectFilter>("redirects", options?.redirects),
            options?.limit,
        )
    }
        .titles()

    /**
     * The title of every category that has anything in it, in title order. Categories with no members are not
     * enumerated, whether or not anything links to them.
     */
    public fun allCategories(options: AllCategoriesOptions? = null): AsyncIterable<String> = listing {
        wiki.lists.allCategories(
            options?.from,
            options?.to,
            options?.prefix,
            options?.min,
            options?.max,
            options?.limit,
        )
    }
        .titles()

    /** The titles of the maintenance categories the wiki files pages into by itself. */
    public fun trackingCategories(limit: Int? = null): AsyncIterable<String> = listing {
        wiki.lists.trackingCategories(limit)
    }
        .titles()

    /** Every title anything links to, whether or not it exists: the way to find red links. */
    public fun allLinkTargets(options: AllTargetsOptions? = null): AsyncIterable<String> = listing {
        wiki.allLinkTargetsWithDefaults(
            options?.namespace,
            options?.from,
            options?.to,
            options?.prefix,
            options?.unique,
            options?.limit,
        )
    }
        .titles()

    /** Every title anything redirects to, in the shape of [allLinkTargets]. */
    public fun allRedirectTargets(options: AllTargetsOptions? = null): AsyncIterable<String> = listing {
        wiki.allRedirectTargetsWithDefaults(
            options?.namespace,
            options?.from,
            options?.to,
            options?.prefix,
            options?.unique,
            options?.limit,
        )
    }
        .titles()

    /** Every title anything transcludes, in the shape of [allLinkTargets]. */
    public fun allTransclusionTargets(options: AllTargetsOptions? = null): AsyncIterable<String> = listing {
        wiki.allTransclusionTargetsWithDefaults(
            options?.namespace,
            options?.from,
            options?.to,
            options?.prefix,
            options?.unique,
            options?.limit,
        )
    }
        .titles()

    /** Every file anything uses, in the shape of [allLinkTargets]. */
    public fun allFileUsages(options: AllFileUsagesOptions? = null): AsyncIterable<String> = listing {
        wiki.allFileUsagesWithDefaults(
            options?.from,
            options?.to,
            options?.prefix,
            options?.unique,
            options?.limit,
        )
    }
        .titles()

    /**
     * The titles beginning with [query], as the wiki's own search box completes them. Not a substring search.
     * The main namespace unless told.
     */
    public fun prefixSearch(query: String, options: ListOptions? = null): AsyncIterable<String> = listing {
        wiki.prefixSearchWithDefaults(query, options?.namespaces?.asList(), options?.limit)
    }
        .titles()

    /** The titles of the pages a search for [query] finds. */
    public fun search(query: String, options: SearchOptions? = null): AsyncIterable<String> = listing {
        wiki.searchWithDefaults(
            query,
            options?.namespaces?.asList(),
            enumOf<ClientSearchScope>("scope", options?.scope),
            enumOf<ClientSearchSort>("sort", options?.sort),
            options?.limit,
        )
    }
        .titles()

    /** The titles the wiki lists on the special page [name], such as `"Wantedcategories"`. */
    public fun specialPage(name: String, limit: Int? = null): AsyncIterable<String> = listing {
        wiki.lists.specialPage(name, limit)
    }
        .titles()

    /**
     * The titles of the pages linking to [url], a whole address or a bare host: how a bot finds every page
     * citing a dead domain.
     */
    public fun externalLinkUsage(
        url: String,
        options: ExternalLinkUsageOptions? = null,
    ): AsyncIterable<String> = listing {
        wiki.externalLinkUsageWithDefaults(
            url,
            options?.protocol,
            options?.namespaces?.asList(),
            options?.limit,
        )
    }
        .titles()

    /**
     * The titles of random pages, the main namespace unless told. Each request gets a fresh set, so the same
     * page may come twice.
     */
    public fun randomPages(options: ListOptions? = null): AsyncIterable<String> = listing {
        wiki.randomPagesWithDefaults(options?.namespaces?.asList(), options?.limit)
    }
        .titles()

    /** The titles on the watchlist of the account logged in. Every namespace unless told. */
    public fun watchlist(options: ListOptions? = null): AsyncIterable<String> = listing {
        wiki.watchlistWithDefaults(options?.namespaces?.asList(), options?.limit)
    }
        .titles()

    /** The titles of the pages carrying a page property, such as `"disambiguation"` or `"wikibase_item"`. */
    public fun pagesWithProperty(property: String, limit: Int? = null): AsyncIterable<String> = listing {
        wiki.lists.pagesWithProperty(property, limit)
    }
        .titles()

    /** The titles protected against creation. Every namespace unless told. */
    public fun protectedTitles(options: ListOptions? = null): AsyncIterable<String> = listing {
        wiki.protectedTitlesWithDefaults(options?.namespaces?.asList(), options?.limit)
    }
        .titles()

    private fun Flow<PageRef>.titles(): AsyncIterable<String> = toAsyncIterable(scope, wiki::titleOf)
}

/**
 * A link from a page to the same subject on another language's wiki. The title is that wiki's, which may
 * differ from this one's.
 */
@JsExport
public class LanguageLink internal constructor(private val link: ClientLanguageLink) {

    /** The language code, as the wiki's interwiki map spells it. */
    public val code: String
        get() = link.code.code

    /** The title on the other wiki. */
    public val title: String
        get() = link.title

    /** The full URL, or `null` when the wiki did not say. */
    public val url: String?
        get() = link.url

    /** The language's name in its own language, or `null` when the wiki did not say. */
    public val autonym: String?
        get() = link.autonym

    /** The language's name in the wiki's content language, or `null` when the wiki did not say. */
    public val name: String?
        get() = link.name
}

/** A link to another wiki through the interwiki map. */
@JsExport
public class InterwikiLink internal constructor(private val link: ClientInterwikiLink) {

    /** The interwiki prefix, `"w"` for Wikipedia. */
    public val prefix: String
        get() = link.prefix

    /** The title on the other wiki. */
    public val title: String
        get() = link.title

    /** The full URL, or `null` when the wiki did not say. */
    public val url: String?
        get() = link.url
}
