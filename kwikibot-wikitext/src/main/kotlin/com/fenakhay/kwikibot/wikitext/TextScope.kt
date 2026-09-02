package com.fenakhay.kwikibot.wikitext

/**
 * Which text a replacement is allowed to touch.
 *
 * Exclusions are structural rather than a list the caller maintains: templates, comments, `<nowiki>` and link
 * targets are nodes in the parsed page, so avoiding them is the default and including one is an explicit
 * choice.
 */
public data class TextScope(
    /** Text inside `{{template}}` names and parameters. */
    val templates: Boolean = false,
    /** The target of a `[[link|text]]`, as opposed to its display text. */
    val linkTargets: Boolean = false,
    /** Text inside `<!-- comments -->`. */
    val comments: Boolean = false,
    /** Text inside `<nowiki>`, `<pre>` and other tags MediaWiki does not parse. */
    val rawTags: Boolean = false,
    /** Text in `== headings ==`. */
    val headings: Boolean = true,
    /** Text inside tag attributes. */
    val tagAttributes: Boolean = false,
) {
    /** The regions of a page a text pass must not touch. */
    public companion object {
        /** Prose a reader sees: not templates, link targets, comments or nowiki. */
        public val PROSE: TextScope = TextScope()

        /** Every piece of text, wherever it sits. */
        public val EVERYWHERE: TextScope =
            TextScope(
                templates = true,
                linkTargets = true,
                comments = true,
                rawTags = true,
                headings = true,
                tagAttributes = true,
            )
    }
}
