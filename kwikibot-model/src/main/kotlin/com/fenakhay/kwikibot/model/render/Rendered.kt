package com.fenakhay.kwikibot.model.render

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef

/**
 * One heading in a page, as MediaWiki renders it.
 *
 * Not the same view as the wikitext parser's own section tree: this is what the wiki produced, so a heading a
 * template generated appears here and not there. Only this view carries [index], and only [index] can be
 * given to an edit, so this is the step before changing one section of a page.
 */
public data class RenderedSection(
    /** What an edit takes as its section. A string: the wiki numbers some subsections `T-1`. */
    val index: String,
    /** The heading text, with markup already resolved. */
    val heading: String,
    /** The heading level, `2` for a second-level heading. */
    val level: Int,
    /** Depth in the contents list, which starts at 1 whatever the heading level is. */
    val tocLevel: Int,
    /** The section number as the contents list shows it, `1.2`. */
    val number: String,
    /** The fragment that links to this section. */
    val anchor: String,
    /** Where the section starts in the wikitext, or `null` for one a template produced. */
    val byteOffset: Int?,
)

/**
 * A link a page resolves to once its templates have expanded.
 *
 * [exists] is the part worth having: `prop=links` on a query does not report it, so finding the red links on
 * a page otherwise costs a second request.
 */
public data class ResolvedLink(
    /** Where the link points. */
    val page: PageRef,
    /** Whether that page exists. False is a red link. */
    val exists: Boolean,
)

/**
 * What a page or a draft turns into once the wiki has parsed it.
 *
 * Only what was asked for is filled in; the rest is empty. Every list is what the wiki resolved *after*
 * templates expanded, which is why asking about unsaved wikitext is useful: it answers what an edit would
 * produce before the edit is made.
 */
public data class ParsedPage(
    /** The rendered HTML, when it was asked for. */
    val html: String? = null,
    /** The headings, each carrying the index an edit takes. */
    val sections: List<RenderedSection> = emptyList(),
    /** Every page the text links to, red links included. */
    val links: List<ResolvedLink> = emptyList(),
    /** Every template it transcludes, after expansion rather than as written. */
    val templates: List<ResolvedLink> = emptyList(),
    /** Categories the page files itself under, in the order the wiki lists them. */
    val categories: List<PageRef> = emptyList(),
    /** Files the page uses. */
    val images: List<PageRef> = emptyList(),
    /** External URLs the page links to. */
    val externalLinks: List<String> = emptyList(),
    /** The revision parsed, absent when unsaved wikitext was. */
    val revision: RevisionId? = null,
)
