package com.fenakhay.kwikibot.bindings.wikitext

import com.fenakhay.kwikibot.wikitext.TextScope

/** The prefixes a category link is read by unless others are given: only `Category`. */
private val CATEGORY_PREFIXES = setOf("Category")

/** Where text is searched: [TextScope.PROSE], with each part given in its place. */
@Suppress("LongParameterList")
public fun textScopeOf(
    templates: Boolean? = null,
    linkTargets: Boolean? = null,
    comments: Boolean? = null,
    rawTags: Boolean? = null,
    headings: Boolean? = null,
    tagAttributes: Boolean? = null,
): TextScope {
    val prose = TextScope.PROSE
    return TextScope(
        templates ?: prose.templates,
        linkTargets ?: prose.linkTargets,
        comments ?: prose.comments,
        rawTags ?: prose.rawTags,
        headings ?: prose.headings,
        tagAttributes ?: prose.tagAttributes,
    )
}

/**
 * The scope [name] names: `"prose"` or `"everywhere"`.
 *
 * @throws IllegalArgumentException when it names neither.
 */
public fun textScopeNamed(name: String): TextScope =
    when (name) {
        "prose" -> TextScope.PROSE
        "everywhere" -> TextScope.EVERYWHERE
        else -> throw IllegalArgumentException("unknown scope '$name'; use \"prose\" or \"everywhere\"")
    }

/** The prefixes to read category links by: [prefixes], or `Category` alone unless given. */
public fun categoryPrefixesOf(prefixes: Collection<String>?): Set<String> =
    prefixes?.toSet() ?: CATEGORY_PREFIXES
