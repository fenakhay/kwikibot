package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.client.CategoryMemberType
import com.fenakhay.kwikibot.client.SearchScope
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.PageRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * Where a bot run gets its pages.
 *
 * A function of a wiki rather than a ready-made [Flow], so a source can be named on a command
 * line before a session exists to run it against.
 */
public fun interface PageSource {

    /** The pages this source names, as a cold flow. */
    public fun pages(wiki: Wiki): Flow<PageRef>

    /** The sources a bot most often reads its work from. */
    public companion object {

        /** A source that yields the given pages, resolved against the wiki when it runs. */
        public fun titles(titles: List<String>): PageSource = PageSource { wiki ->
            titles.asFlow().map { wiki.ref(it) }
        }

        /** Every source in turn, in the order given. */
        public fun concat(sources: List<PageSource>): PageSource = PageSource { wiki ->
            flow { sources.forEach { source -> emitAll(source.pages(wiki)) } }
        }

        /** A source that yields nothing, for the case where nothing was asked for. */
        public val EMPTY: PageSource = PageSource { emptyList<PageRef>().asFlow() }
    }
}

/**
 * Reads the page sources a command line names.
 *
 * The syntax is `kind:argument`: `cat:English lemmas`, `page:volcano`,
 * `transcludes:Template:col`.
 *
 * ```
 * val source = PageSourceSpec.parse("cat:English lemmas")
 * ```
 */
public object PageSourceSpec {

    /**
     * The source [spec] names.
     *
     * @throws IllegalArgumentException if the kind is not one of [kinds], listing them — a
     *   mistyped source should say what was available, not fail silently on an empty run.
     */
    public fun parse(spec: String): PageSource {
        val kind = spec.substringBefore(':', missingDelimiterValue = "").lowercase()
        val argument = spec.substringAfter(':', missingDelimiterValue = "")

        require(kind.isNotEmpty()) {
            "a page source looks like 'kind:argument'; got '$spec'"
        }
        require(kind in kinds) {
            "unknown page source '$kind'; available: ${kinds.joinToString(", ")}"
        }
        require(argument.isNotEmpty() || kind in ARGUMENTLESS) {
            "the '$kind' page source needs an argument"
        }

        return build(kind, argument)
    }

    /** Every source in a list of specs, in order. */
    public fun parseAll(specs: List<String>): PageSource =
        PageSource.concat(specs.map { parse(it) })

    /** The source kinds [parse] understands. */
    public val kinds: List<String> = listOf(
        "page",
        "cat",
        "subcats",
        "catfiles",
        "links",
        "backlinks",
        "transcludes",
        "search",
        "prefix",
        "allpages",
        "special",
        "usercontribs",
        "recentchanges",
        "file",
    )

    private val ARGUMENTLESS = setOf("allpages", "recentchanges")

    @Suppress("CyclomaticComplexMethod")
    private fun build(kind: String, argument: String): PageSource = when (kind) {
        "page" -> PageSource.titles(listOf(argument))

        "cat" -> PageSource { wiki ->
            wiki.lists.categoryMembers(wiki.category(argument))
        }

        "subcats" -> PageSource { wiki ->
            wiki.lists.categoryMembers(wiki.category(argument), CategoryMemberType.SUBCATEGORY)
        }

        "catfiles" -> PageSource { wiki ->
            wiki.lists.categoryMembers(wiki.category(argument), CategoryMemberType.FILE)
        }

        "links" -> PageSource { wiki -> wiki.lists.linksFrom(wiki.ref(argument)) }
        "backlinks" -> PageSource { wiki -> wiki.lists.backlinks(wiki.ref(argument)) }
        "transcludes" -> PageSource { wiki -> wiki.lists.transclusions(wiki.ref(argument)) }

        "search" -> PageSource { wiki ->
            wiki.lists.search(argument, scope = SearchScope.TEXT)
        }

        "prefix" -> PageSource { wiki -> wiki.lists.allPages(prefix = argument) }

        "allpages" -> PageSource { wiki ->
            wiki.lists.allPages(from = argument.takeIf { it.isNotEmpty() })
        }

        "special" -> PageSource { wiki -> wiki.lists.specialPage(argument) }

        "usercontribs" -> PageSource { wiki ->
            wiki.users.contributions(argument).map { it.page }
        }

        "recentchanges" -> PageSource { wiki ->
            wiki.logs.recentChanges(limit = argument.toIntOrNull() ?: DEFAULT_RECENT)
                .mapNotNull { it.page }
        }

        // A file of titles, one per line, which is how a bot run is resumed or a list from
        // somewhere else is fed in. Blank lines and "#" comments are ignored.
        "file" -> PageSource { wiki ->
            Path.of(argument).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .asFlow()
                .map { wiki.ref(it) }
        }

        else -> error("unhandled page source '$kind'")
    }

    // "cat:Foo" and "cat:Category:Foo" name the same category; resolving against the category
    // namespace by default means a bot author does not have to remember which one to write.
    private fun Wiki.category(name: String): PageRef = ref(name, Namespace.CATEGORY)

    private const val DEFAULT_RECENT = 100
}
