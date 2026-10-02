package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.wire.MediaWikiParameter
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

    /**
     * Parses what [request] names and reports the parts it asks for, all in one request.
     *
     * The general form of the methods above, and the one way to parse with a [TemplateSandbox]: a template or
     * module under development, tried against real pages before it is saved. Asking for [ParseProperty.TEXT]
     * and [ParseProperty.CATEGORIES] together gets the HTML and the categories from one parse.
     *
     * An implementation that does not override this answers through [resolve] and [resolveText], and refuses
     * a sandbox.
     */
    public suspend fun parse(request: RenderRequest): ParsedPage {
        check(request.sandbox == null) { "this RenderService cannot parse with a sandbox" }
        val page = request.page
        return if (page != null) {
            resolve(page, request.properties)
        } else {
            resolveText(request.text.orEmpty(), checkNotNull(request.context), request.properties)
        }
    }
}

/**
 * What to parse, how, and which parts of the result to report.
 *
 * Either a saved [page], or unsaved [text] parsed as if it were at [context].
 *
 * @param page a saved page to parse.
 * @param text unsaved wikitext to parse.
 * @param context the title to parse [text] as, which decides what relative links and `PAGENAME` mean.
 * @param properties which parts of the result to report.
 * @param sandbox unsaved templates or modules to parse with.
 */
public data class RenderRequest(
    val page: PageRef? = null,
    val text: String? = null,
    val context: PageRef? = null,
    val properties: Set<ParseProperty> = ParseProperty.CONTENTS,
    val sandbox: TemplateSandbox? = null,
) {
    init {
        require((page == null) != (text == null)) {
            "a request parses a saved page or a text, not both or neither"
        }
        require(text == null || context != null) { "a text needs a context to parse it as" }
        require(properties.isNotEmpty()) { "a parse needs at least one property" }
    }

    /** Requests for the two things a parse can be of. */
    public companion object {
        /** Parses the saved [page]. */
        public fun page(
            page: PageRef,
            properties: Set<ParseProperty> = ParseProperty.CONTENTS,
        ): RenderRequest = RenderRequest(page = page, properties = properties)

        /** Parses [text] as if it were saved at [context]. */
        public fun text(
            text: String,
            context: PageRef,
            properties: Set<ParseProperty> = ParseProperty.CONTENTS,
        ): RenderRequest = RenderRequest(text = text, context = context, properties = properties)
    }
}

/**
 * Unsaved templates and modules to parse with, through the TemplateSandbox extension Wikimedia's wikis run.
 *
 * @param prefixes pages under which sandboxed versions live. With `User:Me/sandbox`, a transclusion of
 *   `Template:Foo` uses `User:Me/sandbox/Template:Foo` where that page exists.
 * @param title one page to stand in for with [text], such as `Module:foo`, for this parse only.
 * @param text what [title] says for this parse.
 * @param contentModel the content model of [text], `Scribunto` for a module. The wiki goes by the title when
 *   it is absent.
 */
public data class TemplateSandbox(
    val prefixes: List<String> = emptyList(),
    val title: PageRef? = null,
    val text: String? = null,
    val contentModel: String? = null,
) {
    init {
        require((title == null) == (text == null)) {
            "a page to stand in for needs its text, and text needs a page"
        }
        require(prefixes.isNotEmpty() || title != null) {
            "a sandbox needs prefixes or a page to stand in for"
        }
    }
}

/** A part of a parse result, named as `action=parse` names it. */
@MediaWikiParameter("parse", "prop")
public enum class ParseProperty(internal val apiValue: String) {
    /** The rendered HTML. */
    TEXT("text"),

    /**
     * The headings, each carrying the index an edit takes.
     *
     * Asked for as [TOC_DATA] is, so it goes on working once a wiki removes `sections`.
     */
    @Deprecated(
        "MediaWiki 1.46 (T319141) deprecated parse prop=sections; use TOC_DATA. " +
            "Deprecated since kwikibot 1.2.0.",
        ReplaceWith("ParseProperty.TOC_DATA", "com.fenakhay.kwikibot.client.service.ParseProperty"),
    )
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
    EXTERNAL_LINKS("externallinks"),

    /**
     * The headings, each carrying the index an edit takes, read from the table of contents.
     *
     * Sent as `tocdata` where the wiki has it, which is MediaWiki 1.43.6, 1.44.3, 1.45 and later, and as
     * `sections` where it does not. Either way the headings arrive in
     * [com.fenakhay.kwikibot.model.render.ParsedPage.sections].
     */
    TOC_DATA("tocdata");

    /** The groupings worth asking for as a set. */
    public companion object {
        /** What a page points at, which is what a bot checking its own edit usually wants. */
        public val CONTENTS: Set<ParseProperty> = setOf(LINKS, TEMPLATES, CATEGORIES, IMAGES, EXTERNAL_LINKS)

        /** Everything this service models, leaving out the entries kept only for compatibility. */
        @Suppress("DEPRECATION") public val ALL: Set<ParseProperty> = entries.toSet() - SECTIONS
    }
}
