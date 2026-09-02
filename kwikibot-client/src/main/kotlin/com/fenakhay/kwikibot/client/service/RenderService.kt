package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.render.ParsedPage
import com.fenakhay.kwikibot.model.render.RenderedSection

/**
 * The wiki's own view of a page: what it renders, and what it resolves to.
 *
 * Everything here goes through `action=parse`, the only way to ask the wiki about wikitext it has not been
 * given to save. That is what makes it worth having beside the library's own parser in `kwikibot-wikitext`:
 * the local parser reads the text as written and puts it back byte for byte, while this reports what the wiki
 * produced from it, templates and all.
 *
 * Reached as `wiki.renderer`. Not `wiki.parse`, which parses a title.
 */
public interface RenderService {

    /**
     * The headings of a page, in the order they appear.
     *
     * [RenderedSection.index] is what an edit takes as its section, so this is the step before editing one
     * section of a page rather than the whole of it.
     */
    public suspend fun sections(page: PageRef): List<RenderedSection>

    /** The rendered HTML of a saved page. */
    public suspend fun render(page: PageRef): String

    /**
     * The rendered HTML of wikitext that has not been saved.
     *
     * @param wikitext the text to render, which need not be saved anywhere.
     * @param context the title to parse as, which decides what relative links and magic words such as
     *   `PAGENAME` resolve to.
     */
    public suspend fun renderText(wikitext: String, context: PageRef): String

    /**
     * What a saved page resolves to: its links, templates, categories, files and external URLs.
     *
     * @param page the saved page to read.
     * @param properties which of them to ask for. Asking for fewer is cheaper.
     */
    public suspend fun resolve(
        page: PageRef,
        properties: Set<ParseProperty> = ParseProperty.CONTENTS,
    ): ParsedPage

    /**
     * What unsaved wikitext would resolve to, without saving it.
     *
     * The question a bot wants answered before it edits: whether the new text links where it should, and
     * whether any of those links would be red.
     */
    public suspend fun resolveText(
        wikitext: String,
        context: PageRef,
        properties: Set<ParseProperty> = ParseProperty.CONTENTS,
    ): ParsedPage
}

/** A part of a parse result, named as `action=parse` names it. */
public enum class ParseProperty(internal val apiValue: String) {
    /** The rendered HTML. */
    TEXT("text"),

    /** The headings, each carrying the index an edit takes. */
    SECTIONS("sections"),

    /** Pages the text links to, each saying whether it exists. */
    LINKS("links"),

    /** Templates it transcludes, after expansion rather than as written. */
    TEMPLATES("templates"),

    /** Categories it files itself under. */
    CATEGORIES("categories"),

    /** Files it uses. */
    IMAGES("images"),

    /** External URLs it points at. */
    EXTERNAL_LINKS("externallinks");

    /** The groupings worth asking for as a set. */
    public companion object {
        /** What a page points at, which is what a bot checking its own edit usually wants. */
        public val CONTENTS: Set<ParseProperty> = setOf(LINKS, TEMPLATES, CATEGORIES, IMAGES, EXTERNAL_LINKS)

        /** Everything this service models. */
        public val ALL: Set<ParseProperty> = entries.toSet()
    }
}
