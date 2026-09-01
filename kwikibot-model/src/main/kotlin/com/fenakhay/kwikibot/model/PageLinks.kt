package com.fenakhay.kwikibot.model

/**
 * A link from a page to the same subject on another language's wiki.
 *
 * The title is that wiki's, not this one's. On a Wikipedia they usually differ, since `Volcano`
 * is `Volcan` in French; on a Wiktionary they usually match, since every edition documents the
 * same spelling. A bot working across languages has to read the title rather than assume either.
 */
public data class LanguageLink(
    /** The language code, as the wiki's interwiki map spells it. */
    val code: LangCode,
    /** The title on the other wiki. */
    val title: String,
    /** The full URL, when it was asked for. */
    val url: String? = null,
    /** The language's name in its own language, when it was asked for. */
    val autonym: String? = null,
    /** The language's name in the wiki's content language, when it was asked for. */
    val name: String? = null,
)

/**
 * A link to another wiki through the interwiki map.
 *
 * Distinct from a [LanguageLink]: an interwiki link points sideways at a different project,
 * `w:Etsy` rather than at the same subject in another language.
 */
public data class InterwikiLink(
    /** The interwiki prefix, `w` for Wikipedia. */
    val prefix: String,
    /** The title on the other wiki. */
    val title: String,
    /** The full URL, when it was asked for. */
    val url: String? = null,
)

/**
 * How much a category holds.
 *
 * MediaWiki counts these as it goes rather than on demand, so they are cheap to ask for and
 * occasionally a little behind. Close enough to decide whether a category needs splitting; not a
 * substitute for enumerating it when the exact set matters.
 */
public data class CategoryInfo(
    /** Everything in the category: pages, files and subcategories together. */
    val size: Int,
    /** Ordinary pages, excluding files and subcategories. */
    val pages: Int,
    /** Files in the category. */
    val files: Int,
    /** Subcategories. */
    val subcategories: Int,
    /** Whether the category is hidden from the box at the foot of its members. */
    val hidden: Boolean = false,
)
