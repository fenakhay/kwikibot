@file:Suppress("TooManyFunctions")

package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.CategoryMemberType
import com.fenakhay.kwikibot.client.service.CategorySort
import com.fenakhay.kwikibot.client.service.RedirectFilter
import com.fenakhay.kwikibot.client.service.SearchScope
import com.fenakhay.kwikibot.client.service.SearchSort
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import kotlinx.coroutines.flow.Flow

private val MAIN_ONLY = setOf(Namespace.MAIN)

/**
 * The pages in [category], which needs no `Category:` prefix: pages rather than files or subcategories, in
 * every namespace and in sort-key order unless told.
 */
@Suppress("LongParameterList")
public fun Wiki.categoryMembersWithDefaults(
    category: String,
    type: CategoryMemberType? = null,
    namespaces: Collection<Int>? = null,
    sort: CategorySort? = null,
    startSortKey: String? = null,
    limit: Int? = null,
): Flow<PageRef> =
    lists.categoryMembers(
        ref(category, Namespace.CATEGORY),
        type ?: CategoryMemberType.PAGE,
        namespacesOf(namespaces),
        sort ?: CategorySort.SORTKEY,
        startSortKey,
        limit,
    )

/** The pages that link to [target], in every namespace and through redirects unless told. */
public fun Wiki.backlinksWithDefaults(
    target: String,
    namespaces: Collection<Int>? = null,
    includeRedirects: Boolean? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.backlinks(ref(target), namespacesOf(namespaces), includeRedirects ?: true, limit)

/**
 * The pages that transclude [template], which needs no `Template:` prefix, in every namespace unless told.
 */
public fun Wiki.transclusionsWithDefaults(
    template: String,
    namespaces: Collection<Int>? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.transclusions(ref(template, Namespace.TEMPLATE), namespacesOf(namespaces), limit)

/** The pages [page] links to, in every namespace unless told. */
public fun Wiki.linksFromWithDefaults(
    page: String,
    namespaces: Collection<Int>? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.linksFrom(ref(page), namespacesOf(namespaces), limit)

/** Every page of the namespace numbered [namespace], the main one unless given, ascending, of any kind. */
@Suppress("LongParameterList")
public fun Wiki.allPagesWithDefaults(
    namespace: Int? = null,
    from: String? = null,
    to: String? = null,
    prefix: String? = null,
    descending: Boolean? = null,
    redirects: RedirectFilter? = null,
    limit: Int? = null,
): Flow<PageRef> =
    lists.allPages(
        namespaceOf(namespace),
        from,
        to,
        prefix,
        descending ?: false,
        redirects ?: RedirectFilter.ALL,
        limit,
    )

/** Every title anything links to, in the main namespace and once each unless told. */
@Suppress("LongParameterList")
public fun Wiki.allLinkTargetsWithDefaults(
    namespace: Int? = null,
    from: String? = null,
    to: String? = null,
    prefix: String? = null,
    unique: Boolean? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.allLinkTargets(namespaceOf(namespace), from, to, prefix, unique ?: true, limit)

/** Every title anything redirects to, in the shape of [allLinkTargetsWithDefaults]. */
@Suppress("LongParameterList")
public fun Wiki.allRedirectTargetsWithDefaults(
    namespace: Int? = null,
    from: String? = null,
    to: String? = null,
    prefix: String? = null,
    unique: Boolean? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.allRedirectTargets(namespaceOf(namespace), from, to, prefix, unique ?: true, limit)

/** Every title anything transcludes, in the Template namespace and once each unless told. */
@Suppress("LongParameterList")
public fun Wiki.allTransclusionTargetsWithDefaults(
    namespace: Int? = null,
    from: String? = null,
    to: String? = null,
    prefix: String? = null,
    unique: Boolean? = null,
    limit: Int? = null,
): Flow<PageRef> =
    lists.allTransclusionTargets(
        namespaceOf(namespace, Namespace.TEMPLATE),
        from,
        to,
        prefix,
        unique ?: true,
        limit,
    )

/** Every file anything uses, once each unless told. */
public fun Wiki.allFileUsagesWithDefaults(
    from: String? = null,
    to: String? = null,
    prefix: String? = null,
    unique: Boolean? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.allFileUsages(from, to, prefix, unique ?: true, limit)

/** The titles beginning with [query], in the main namespace unless told. */
public fun Wiki.prefixSearchWithDefaults(
    query: String,
    namespaces: Collection<Int>? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.prefixSearch(query, namespacesOf(namespaces, MAIN_ONLY), limit)

/** The pages a search for [query] finds, in the main namespace, by text and by relevance unless told. */
public fun Wiki.searchWithDefaults(
    query: String,
    namespaces: Collection<Int>? = null,
    scope: SearchScope? = null,
    sort: SearchSort? = null,
    limit: Int? = null,
): Flow<PageRef> =
    lists.search(
        query,
        namespacesOf(namespaces, MAIN_ONLY),
        scope ?: SearchScope.TEXT,
        sort ?: SearchSort.RELEVANCE,
        limit,
    )

/** The pages linking to [url], in every namespace unless told. */
public fun Wiki.externalLinkUsageWithDefaults(
    url: String,
    protocol: String? = null,
    namespaces: Collection<Int>? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.externalLinkUsage(url, protocol, namespacesOf(namespaces), limit)

/** Random pages, in the main namespace unless told. */
public fun Wiki.randomPagesWithDefaults(
    namespaces: Collection<Int>? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.randomPages(namespacesOf(namespaces, MAIN_ONLY), limit)

/** The pages on the account's watchlist, in every namespace unless told. */
public fun Wiki.watchlistWithDefaults(
    namespaces: Collection<Int>? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.watchlist(namespacesOf(namespaces), limit)

/** The titles protected against creation, in every namespace unless told. */
public fun Wiki.protectedTitlesWithDefaults(
    namespaces: Collection<Int>? = null,
    limit: Int? = null,
): Flow<PageRef> = lists.protectedTitles(namespacesOf(namespaces), limit)
