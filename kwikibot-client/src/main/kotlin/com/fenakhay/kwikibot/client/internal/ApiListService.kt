package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.service.CategoryMemberType
import com.fenakhay.kwikibot.client.service.CategorySort
import com.fenakhay.kwikibot.client.service.ListService
import com.fenakhay.kwikibot.client.service.RedirectFilter
import com.fenakhay.kwikibot.client.service.SearchScope
import com.fenakhay.kwikibot.client.service.SearchSort
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.page.InterwikiLink
import com.fenakhay.kwikibot.model.page.LanguageLink
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class ApiListService(
    transport: MediaWikiTransport,
    private val decoder: PageDecoder,
    private val namespaces: NamespaceMap,
) : ListService {

    private val continuation = Continuation(transport)

    override fun categoryMembers(
        category: PageRef,
        type: CategoryMemberType,
        namespaces: Set<Namespace>,
        sort: CategorySort,
        startSortKey: String?,
        limit: Int?,
    ): Flow<PageRef> =
        list(
            "categorymembers",
            limit,
            "cmtitle" to title(category),
            "cmtype" to type.apiValue,
            "cmnamespace" to namespaces.toParam(),
            "cmsort" to sort.apiValue,
            "cmstartsortkeyprefix" to startSortKey,
            "cmlimit" to apiLimit(limit),
        )

    override fun backlinks(
        target: PageRef,
        namespaces: Set<Namespace>,
        includeRedirects: Boolean,
        limit: Int?,
    ): Flow<PageRef> =
        list(
            "backlinks",
            limit,
            "bltitle" to title(target),
            "blnamespace" to namespaces.toParam(),
            // Without this a page linked only through a redirect is invisible to the query.
            "blredirect" to if (includeRedirects) "1" else null,
            "bllimit" to apiLimit(limit),
        )

    override fun transclusions(
        template: PageRef,
        namespaces: Set<Namespace>,
        limit: Int?,
    ): Flow<PageRef> =
        list(
            "embeddedin",
            limit,
            "eititle" to title(template),
            "einamespace" to namespaces.toParam(),
            "eilimit" to apiLimit(limit),
        )

    override fun linksFrom(page: PageRef, namespaces: Set<Namespace>, limit: Int?): Flow<PageRef> =
        prop(
            "links",
            limit,
            "titles" to title(page),
            "plnamespace" to namespaces.toParam(),
            "pllimit" to apiLimit(limit),
        )

    override fun templatesOn(page: PageRef, limit: Int?): Flow<PageRef> =
        prop(
            "templates",
            limit,
            "titles" to title(page),
            "tllimit" to apiLimit(limit),
        )

    override fun categoriesOf(page: PageRef, limit: Int?): Flow<PageRef> =
        prop(
            "categories",
            limit,
            "titles" to title(page),
            "cllimit" to apiLimit(limit),
        )

    override fun filesOn(page: PageRef, limit: Int?): Flow<PageRef> =
        prop(
            "images",
            limit,
            "titles" to title(page),
            "imlimit" to apiLimit(limit),
        )

    override fun languageLinksOn(page: PageRef, limit: Int?): Flow<LanguageLink> =
        entries(
                "langlinks",
                "titles" to title(page),
                "lllimit" to apiLimit(limit),
                "llprop" to "url|autonym|langname",
            ) { entry ->
                LanguageLink(
                    code = LangCode(entry.text("lang")),
                    title = entry.text("title"),
                    url = entry.textOrNull("url"),
                    autonym = entry.textOrNull("autonym"),
                    name = entry.textOrNull("langname"),
                )
            }
            .applyLimit(limit)

    override fun interwikiLinksOn(page: PageRef, limit: Int?): Flow<InterwikiLink> =
        entries(
                "iwlinks",
                "titles" to title(page),
                "iwlimit" to apiLimit(limit),
                "iwprop" to "url",
            ) { entry ->
                InterwikiLink(
                    prefix = entry.text("prefix"),
                    title = entry.text("title"),
                    url = entry.textOrNull("url"),
                )
            }
            .applyLimit(limit)

    override fun externalLinksOn(page: PageRef, limit: Int?): Flow<String> =
        entries(
                "extlinks",
                "titles" to title(page),
                "ellimit" to apiLimit(limit),
            ) { entry ->
                entry.text("url")
            }
            .applyLimit(limit)

    override fun allPages(
        namespace: Namespace,
        from: String?,
        to: String?,
        prefix: String?,
        descending: Boolean,
        redirects: RedirectFilter,
        limit: Int?,
    ): Flow<PageRef> =
        list(
            "allpages",
            limit,
            "apnamespace" to namespace.id.toString(),
            "apfrom" to from,
            "apto" to to,
            "apprefix" to prefix,
            "apdir" to if (descending) "descending" else null,
            "apfilterredir" to redirects.apiValue.takeIf { redirects != RedirectFilter.ALL },
            "aplimit" to apiLimit(limit),
        )

    override fun allCategories(
        from: String?,
        to: String?,
        prefix: String?,
        min: Int?,
        max: Int?,
        limit: Int?,
    ): Flow<PageRef> =
        categoryNames(
            "allcategories",
            limit,
            "acfrom" to from,
            "acto" to to,
            "acprefix" to prefix,
            "acmin" to min?.toString(),
            "acmax" to max?.toString(),
            "aclimit" to apiLimit(limit),
        )

    override fun trackingCategories(limit: Int?): Flow<PageRef> =
        categoryNames(
            "trackingcategories",
            limit,
            "tclimit" to apiLimit(limit),
        )

    override fun allLinkTargets(
        namespace: Namespace,
        from: String?,
        to: String?,
        prefix: String?,
        unique: Boolean,
        limit: Int?,
    ): Flow<PageRef> = allTargets("alllinks", "al", namespace, from, to, prefix, unique, limit)

    override fun allRedirectTargets(
        namespace: Namespace,
        from: String?,
        to: String?,
        prefix: String?,
        unique: Boolean,
        limit: Int?,
    ): Flow<PageRef> = allTargets("allredirects", "ar", namespace, from, to, prefix, unique, limit)

    override fun allTransclusionTargets(
        namespace: Namespace,
        from: String?,
        to: String?,
        prefix: String?,
        unique: Boolean,
        limit: Int?,
    ): Flow<PageRef> = allTargets("alltransclusions", "at", namespace, from, to, prefix, unique, limit)

    override fun allFileUsages(
        from: String?,
        to: String?,
        prefix: String?,
        unique: Boolean,
        limit: Int?,
    ): Flow<PageRef> = allTargets("allfileusages", "af", null, from, to, prefix, unique, limit)

    override fun prefixSearch(
        query: String,
        namespaces: Set<Namespace>,
        limit: Int?,
    ): Flow<PageRef> =
        list(
            "prefixsearch",
            limit,
            "pssearch" to query,
            "psnamespace" to namespaces.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
            "pslimit" to apiLimit(limit),
        )

    /**
     * A list whose entries name a category instead of describing a page.
     *
     * Both modules that do this answer with a bare name: no namespace, no "title". Decoding them like every
     * other list drops every entry, and a generator that emits nothing does not stop - it pages to the end of
     * the wiki looking for a limit it will never reach.
     */
    private fun categoryNames(
        module: String,
        limit: Int?,
        vararg params: Pair<String, String?>,
    ): Flow<PageRef> =
        continuation
            .list(ApiRequest.of("query", "list" to module, *params), module)
            .mapNotNull { entry ->
                entry["category"]
                    ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?.takeIf { it.isNotEmpty() }
                    // Spaces here, underscores from action=parse, and MediaWiki treats the two as the
                    // same character in a title, so both are normalised to the spelling used elsewhere.
                    ?.let { decoder.refOf(it.replace('_', ' '), Namespace.CATEGORY.id) }
            }
            .applyLimit(limit)

    /**
     * The four link-target enumerations, which differ only in module, prefix and whether a namespace applies.
     *
     * `allfileusages` has no namespace: its targets are always files.
     */
    @Suppress("LongParameterList")
    private fun allTargets(
        module: String,
        prefix: String,
        namespace: Namespace?,
        from: String?,
        to: String?,
        titlePrefix: String?,
        unique: Boolean,
        limit: Int?,
    ): Flow<PageRef> =
        list(
            module,
            limit,
            "${prefix}namespace" to namespace?.id?.toString(),
            "${prefix}from" to from,
            "${prefix}to" to to,
            "${prefix}prefix" to titlePrefix,
            "${prefix}unique" to if (unique) "1" else null,
            "${prefix}limit" to apiLimit(limit),
        )

    override fun search(
        query: String,
        namespaces: Set<Namespace>,
        scope: SearchScope,
        sort: SearchSort,
        limit: Int?,
    ): Flow<PageRef> =
        list(
            "search",
            limit,
            "srsearch" to query,
            "srnamespace" to namespaces.toParam(),
            "srwhat" to scope.apiValue,
            "srsort" to sort.apiValue,
            "srlimit" to apiLimit(limit),
        )

    override fun specialPage(name: String, limit: Int?): Flow<PageRef> =
        list(
            "querypage",
            limit,
            "qppage" to name,
            "qplimit" to apiLimit(limit),
        )

    override fun externalLinkUsage(
        url: String,
        protocol: String?,
        namespaces: Set<Namespace>,
        limit: Int?,
    ): Flow<PageRef> =
        list(
            "exturlusage",
            limit,
            "euquery" to url,
            "euprotocol" to protocol,
            "eunamespace" to namespaces.toParam(),
            "eulimit" to apiLimit(limit),
        )

    override fun randomPages(namespaces: Set<Namespace>, limit: Int?): Flow<PageRef> =
        list(
            "random",
            limit,
            "rnnamespace" to namespaces.toParam(),
            // "max" is not accepted here: the module caps a request at ten for everybody, and the
            // continuation is what gets more.
            "rnlimit" to (limit?.coerceAtMost(RANDOM_BATCH) ?: RANDOM_BATCH).toString(),
        )

    override fun watchlist(namespaces: Set<Namespace>, limit: Int?): Flow<PageRef> =
        list(
            "watchlistraw",
            limit,
            "wrnamespace" to namespaces.toParam(),
            "wrlimit" to apiLimit(limit),
        )

    override fun pagesWithProperty(property: String, limit: Int?): Flow<PageRef> =
        list(
            "pageswithprop",
            limit,
            "pwppropname" to property,
            "pwplimit" to apiLimit(limit),
        )

    override fun protectedTitles(namespaces: Set<Namespace>, limit: Int?): Flow<PageRef> =
        list(
            "protectedtitles",
            limit,
            "ptnamespace" to namespaces.toParam(),
            "ptlimit" to apiLimit(limit),
        )

    /** A `list=` query, flattened into references. */
    private fun list(
        module: String,
        limit: Int?,
        vararg params: Pair<String, String?>,
    ): Flow<PageRef> =
        continuation
            .list(ApiRequest.of("query", "list" to module, *params), module)
            .mapNotNull { decoder.refOf(it) }
            .applyLimit(limit)

    /**
     * A `prop=` query whose results hang off the queried page.
     *
     * These arrive nested under the page rather than in a top-level list, so the entries are pulled out of
     * each page entry instead.
     */
    private fun prop(
        module: String,
        limit: Int?,
        vararg params: Pair<String, String?>,
    ): Flow<PageRef> = entries(module, *params) { it }.mapNotNull { decoder.refOf(it) }.applyLimit(limit)

    /**
     * A `prop=` query whose entries are not page references.
     *
     * The same shape as [prop], but the entries describe something other than a page - a language link, a
     * URL - so the caller says how to read one.
     */
    private fun <T> entries(
        module: String,
        vararg params: Pair<String, String?>,
        read: (JsonObject) -> T,
    ): Flow<T> = flow {
        continuation.pages(ApiRequest.of("query", "prop" to module, *params)).collect { page ->
            val found = page[module] as? JsonArray ?: return@collect
            found.forEach { emit(read(it.jsonObject)) }
        }
    }

    private fun JsonObject.text(key: String): String = textOrNull(key).orEmpty()

    private fun JsonObject.textOrNull(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun <T> Flow<T>.applyLimit(limit: Int?): Flow<T> = if (limit == null) this else take(limit)

    private fun title(ref: PageRef): String = namespaces.format(ref.title)

    private fun Set<Namespace>.toParam(): String? = takeIf {
        it.isNotEmpty()
    }
        ?.joinToString("|") { it.id.toString() }

    /**
     * How many results to ask for per request.
     *
     * `max` lets the wiki decide, which is 500 for a bot account and 50 for anyone else. A small explicit
     * limit avoids fetching five hundred results to use three.
     */
    private fun apiLimit(limit: Int?): String =
        if (limit != null && limit < MAX_BATCH) limit.toString() else "max"

    private companion object {
        const val MAX_BATCH = 500

        /** What `list=random` returns at most in one request, for any account. */
        const val RANDOM_BATCH = 10
    }
}
