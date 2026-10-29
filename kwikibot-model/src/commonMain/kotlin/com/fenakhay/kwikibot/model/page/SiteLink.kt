package com.fenakhay.kwikibot.model.page

import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title

/**
 * A link to a page on another wiki: `[[fr:Volcan]]`, `[[w:en:Volcano]]`.
 *
 * The counterpart of [Title.Interwiki] for building rather than parsing. It is a separate type from a local
 * link for the reason [Title] is sealed at all: a bot that renders one where a local link was meant sends
 * readers to another project.
 */
public data class SiteLink(
    /** The interwiki prefix: `fr`, `w`, `commons`. */
    val prefix: String,
    /** The title on that wiki, with its own namespace prefix if it has one. */
    val title: String,
    /** The text to show, or `null` to show the target. */
    val text: String? = null,
) {
    init {
        require(prefix.isNotBlank()) { "an interwiki link needs a prefix" }
        require(title.isNotBlank()) { "an interwiki link needs a title" }
    }

    /**
     * The wikitext of this link.
     *
     * @param forced whether to write the leading colon that makes it a visible link rather than an
     *   interlanguage link filed at the bottom of the page. The difference is invisible in the source and
     *   total in the result: `[[fr:Volcan]]` puts a language link in the sidebar, `[[:fr:Volcan]]` puts a
     *   link in the sentence.
     */
    public fun render(forced: Boolean = false): String = buildString {
        append("[[")
        if (forced) append(':')
        append(prefix).append(':').append(title)
        text?.let { append('|').append(it) }
        append("]]")
    }

    override fun toString(): String = render()
}

/**
 * The wikitext of a link to this page.
 *
 * @param namespaces the wiki's namespaces, which decide how the prefix is spelled.
 * @param text what to show, or `null` to show the title.
 * @param forced whether to write the leading colon a category or file needs to be linked rather than used.
 *   `[[Category:X]]` files the page in the category; `[[:Category:X]]` links to it, and getting that wrong on
 *   a template files every page using it.
 */
public fun Title.Local.render(
    namespaces: NamespaceMap,
    text: String? = null,
    forced: Boolean = false,
): String = buildString {
    append("[[")
    if (forced) append(':')
    append(namespaces.format(this@render))
    text?.let { append('|').append(it) }
    append("]]")
}

/**
 * The wikitext of a link that shows only the part of the title before a comma or bracket.
 *
 * The pipe trick, which MediaWiki expands on save: `[[Volcano (film)|]]` becomes `[[Volcano
 * (film)|Volcano]]`. Written out here rather than left to the wiki, because a bot that leaves the trick in
 * the text produces an unreadable diff.
 */
public fun Title.Local.renderPiped(namespaces: NamespaceMap): String {
    val shown = text.substringBefore(" (").substringBefore(", ").trim()

    return render(namespaces, text = shown.takeIf { it != text })
}
