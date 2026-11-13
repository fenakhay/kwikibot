import com.fenakhay.kwikibot.bindings.bot.repeatingWithDefaults
import com.fenakhay.kwikibot.bindings.bot.withContentWithDefaults
import com.fenakhay.kwikibot.bindings.client.namespacesOf
import com.fenakhay.kwikibot.bindings.client.titleOf
import com.fenakhay.kwikibot.bot.source.PageSource as ClientPageSource
import com.fenakhay.kwikibot.bot.source.PageSourceSpec
import com.fenakhay.kwikibot.bot.source.distinctPages
import com.fenakhay.kwikibot.bot.source.excluding
import com.fenakhay.kwikibot.bot.source.inNamespaces
import com.fenakhay.kwikibot.bot.source.intersect
import com.fenakhay.kwikibot.bot.source.redirects
import com.fenakhay.kwikibot.bot.source.subpageDepthAtMost
import com.fenakhay.kwikibot.bot.source.textMatching
import com.fenakhay.kwikibot.bot.source.textNotMatching
import com.fenakhay.kwikibot.bot.source.titleMatching
import com.fenakhay.kwikibot.bot.source.titleNotMatching
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import kotlin.js.RegExp
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Where a bot run gets its pages: a recipe that names them once it has a wiki. Each narrowing makes a new
 * source and leaves this one as it was. None of them fetches a page's text.
 */
@JsExport
public class PageSource internal constructor(internal val source: ClientPageSource) {

    /** The titles this source names on [wiki], prefix included, fetched as the walk asks. */
    public fun pages(wiki: Wiki): AsyncIterable<String> = listing {
        source.pages(wiki.wiki)
    }
        .toAsyncIterable(wiki.scope) { wiki.wiki.titleOf(it) }

    /**
     * Repeats this source, naming only what it has not named before, for a bot that watches a wiki. The walk
     * never ends on its own.
     *
     * @param every how long to wait between rounds, in milliseconds; a minute unless given.
     * @param remember how many titles to keep in the seen set; 5000 unless given.
     */
    public fun repeating(every: Double? = null, remember: Int? = null): PageSource =
        PageSource(source.repeatingWithDefaults(every?.milliseconds, remember))

    /** Only pages in the namespaces numbered [namespaces]. Every namespace passes when it is empty. */
    public fun inNamespaces(namespaces: Array<Int>): PageSource {
        val wanted = namespacesOf(namespaces.asList())
        return narrowed { it.inNamespaces(wanted) }
    }

    /** Only pages whose title without its prefix matches [pattern]. */
    public fun titleMatching(pattern: RegExp): PageSource {
        val regex = pattern.toRegex()
        return narrowed { it.titleMatching(regex) }
    }

    /** Only pages whose title without its prefix does not match [pattern]. */
    public fun titleNotMatching(pattern: RegExp): PageSource {
        val regex = pattern.toRegex()
        return narrowed { it.titleNotMatching(regex) }
    }

    /** Drops pages already named. The set of those grows with the walk. */
    public fun distinctPages(): PageSource = narrowed { it.distinctPages() }

    /** Only pages no deeper than [maxDepth] slashes below their base page; `0` keeps base pages only. */
    public fun subpageDepthAtMost(maxDepth: Int): PageSource = narrowed { it.subpageDepthAtMost(maxDepth) }

    /** Only pages [other] also names. [other] is walked in full first, so put the smaller one second. */
    public fun intersect(other: PageSource): PageSource = sourceOf { wiki ->
        source.pages(wiki).intersect(other.source.pages(wiki))
    }

    /** Only pages [other] does not name. [other] is walked in full first. */
    public fun excluding(other: PageSource): PageSource = sourceOf { wiki ->
        source.pages(wiki).excluding(other.source.pages(wiki))
    }

    /**
     * The text of the pages of [wiki] this source names, fetched in batches as the walk asks.
     *
     * @param wiki the wiki whose pages are read.
     * @param batch how many pages one request names; as many as the account may ask for unless given.
     * @param concurrency how many batches are fetched at once; 1 unless given. Pages still come out in order.
     */
    public fun withContent(wiki: Wiki, batch: Int? = null, concurrency: Int = 1): PageContents =
        PageContents(
            listing { source.pages(wiki.wiki) }.withContentWithDefaults(wiki.wiki, batch, concurrency),
            wiki.wiki,
            wiki.scope,
        )

    private fun narrowed(narrow: (Flow<PageRef>) -> Flow<PageRef>): PageSource = sourceOf { wiki ->
        narrow(source.pages(wiki))
    }
}

/** A source naming what [pages] gives for a wiki. */
internal fun sourceOf(pages: (ClientWiki) -> Flow<PageRef>): PageSource = PageSource(ClientPageSource(pages))

/** Pages with their text. Each narrowing makes a new stream and leaves this one as it was. */
@JsExport
public class PageContents
internal constructor(
    private val contents: Flow<PageContent>,
    private val wiki: ClientWiki,
    private val scope: CoroutineScope,
) {

    /** The pages, fetched as the walk asks. */
    public fun pages(): AsyncIterable<Page> = contents.toAsyncIterable(scope) { Page(it, wiki.namespaces) }

    /** Only pages whose text matches [pattern]. */
    public fun textMatching(pattern: RegExp): PageContents =
        PageContents(contents.textMatching(pattern.toRegex()), wiki, scope)

    /** Only pages whose text does not match [pattern]. */
    public fun textNotMatching(pattern: RegExp): PageContents =
        PageContents(contents.textNotMatching(pattern.toRegex()), wiki, scope)

    /** Only pages that are redirects, or with [keep] `false`, only pages that are not. */
    public fun redirects(keep: Boolean = true): PageContents =
        PageContents(contents.redirects(keep), wiki, scope)
}

/** A source that names nothing. */
@JsExport public val EMPTY_PAGE_SOURCE: PageSource = PageSource(ClientPageSource.EMPTY)

/** A source naming [titles], resolved against the wiki when it runs. */
@JsExport
public fun pageSourceOfTitles(titles: Array<String>): PageSource =
    PageSource(ClientPageSource.titles(titles.toList()))

/**
 * A source naming the titles [titles] gives, such as a listing of a wiki or a generator. It may be walked
 * once for each time the source is, so one that can be walked only once makes a source that can be used only
 * once.
 */
@JsExport
public fun pageSourceOf(titles: AsyncIterable<String>): PageSource = sourceOf { wiki ->
    titles.asFlow().map { wiki.ref(it) }
}

/** A source naming what [pages] gives for a wiki, for one worked out from the wiki it runs on. */
@JsExport
public fun pageSourceFrom(pages: (wiki: Wiki) -> AsyncIterable<String>): PageSource = sourceOf { wiki ->
    flow {
        val scope = CoroutineScope(currentCoroutineContext())
        emitAll(pages(Wiki(wiki, scope, null)).asFlow().map { wiki.ref(it) })
    }
}

/** Every one of [sources] in turn, in the order given. */
@JsExport
public fun concatPageSources(sources: Array<PageSource>): PageSource =
    PageSource(ClientPageSource.concat(sources.map { it.source }))

/**
 * The source a command line names, as `kind:argument`: `cat:English lemmas`, `page:volcano`,
 * `transcludes:Template:col`. `pageSourceKinds` lists the kinds, and a `file:` one needs a filesystem.
 *
 * @throws IllegalArgumentException when the kind is not one of them, listing them.
 */
@JsExport public fun parsePageSource(spec: String): PageSource = PageSource(PageSourceSpec.parse(spec))

/** Every source in a list of [specs], in order. */
@JsExport
public fun parsePageSources(specs: Array<String>): PageSource =
    PageSource(PageSourceSpec.parseAll(specs.toList()))

/** The kinds `parsePageSource` understands. */
@JsExport public fun pageSourceKinds(): Array<String> = PageSourceSpec.kinds.toTypedArray()

/** What this iterable gives as a flow, walking it afresh for each collection. A plain iterable will do. */
internal fun <T> AsyncIterable<T>.asFlow(): Flow<T> {
    val iterable: dynamic = this
    return flow {
        val asyncFactory: dynamic = iterable[js("Symbol.asyncIterator")]
        val iterator: dynamic =
            if (asyncFactory != null) iterable[js("Symbol.asyncIterator")]()
            else iterable[js("Symbol.iterator")]()
        var finished = false
        try {
            while (true) {
                val step: dynamic = settledCall { iterator.next() }
                if (step.done == true) {
                    finished = true
                    break
                }
                emit(step.value.unsafeCast<T>())
            }
        } finally {
            if (!finished && iterator["return"] != null) iterator["return"]()
        }
    }
}
