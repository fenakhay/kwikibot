package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.node.Tag

/**
 * The tag names the parser and the passes over its trees treat specially, in one place.
 *
 * The names that vary between wikis (extension tags and allowed HTML) are in
 * [com.fenakhay.kwikibot.wikitext.ParseOptions]. These do not.
 */
internal object TagNames {

    /** Elements with no body, so `<br>` needs no `</br>` to be complete. */
    val VOID = NameSet(listOf("br", "wbr", "hr", "meta", "link"))

    /**
     * The transclusion tags, read here as wrappers: a bot reads a template's source, and keeps them.
     *
     * MediaWiki treats them as instructions to its preprocessor, which mean one thing when a page is viewed
     * and another when it is transcluded.
     */
    val TRANSCLUSION = NameSet(listOf("noinclude", "includeonly", "onlyinclude"))

    /**
     * Tags whose body is not prose for a text operation to change: code, markup of its own, or configuration.
     *
     * A parsed tag says it was kept as written through `verbatim`; this also covers a tag built by hand, and
     * the configuration of `inputbox` and `dynamicpagelist`, where templates are expanded but the rest is
     * settings.
     */
    val RAW =
        NameSet(
            listOf(
                "nowiki",
                "pre",
                "syntaxhighlight",
                "source",
                "math",
                "score",
                "inputbox",
                "dynamicpagelist",
            )
        )

    /**
     * The extension tags that render a block (a `<div>`, a list, a table) where they stand, which ends an
     * open paragraph. Wiki-dependent, like the tags themselves; any not named here renders inline.
     */
    val BLOCK_EXTENSIONS =
        NameSet(
            listOf("references", "gallery", "pre", "syntaxhighlight", "source", "poem", "categorytree") +
                listOf("inputbox", "imagemap", "timeline", "hiero", "templatedata", "graph", "mapframe") +
                listOf("dynamicpagelist", "score")
        )

    /** The extension tags that render nothing where they stand. */
    val INVISIBLE_EXTENSIONS = NameSet(listOf("indicator", "section"))

    /** The extension tags that render a style sheet, which opens and closes no paragraph. */
    val STYLE_EXTENSIONS = NameSet(listOf("templatestyles"))

    /** `<pre>`, which MediaWiki reads as wikitext when it says `format="wikitext"`. */
    const val PRE = "pre"

    /** The intersection extension's tag, whose body is read a line at a time. */
    const val PAGE_LIST = "dynamicpagelist"

    /**
     * The `<DynamicPageList>` keys whose values the extension expands, compared exactly as it compares them.
     */
    val PAGE_LIST_EXPANDED = setOf("category", "notcategory", "gallerycaption")

    /** Whether text operations leave [tag]'s body alone, as code, markup of its own or configuration. */
    fun isRaw(tag: Tag): Boolean =
        tag.verbatim || (tag.name in RAW && !(tag.name.equals(PRE, ignoreCase = true) && isWikitextPre(tag)))

    private fun isWikitextPre(tag: Tag): Boolean {
        val attributes = tag.attributes.joinToString("") { it.serialize() }
        return TagAttributes.isWikitextPre(attributes, 0, attributes.length)
    }
}
