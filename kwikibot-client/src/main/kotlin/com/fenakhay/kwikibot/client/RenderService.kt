package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.ParsedPage
import com.fenakhay.kwikibot.model.RenderedSection
import com.fenakhay.kwikibot.model.ResolvedLink
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.PageDecoder
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The wiki's own view of a page: what it renders, and what it resolves to.
 *
 * Everything here goes through `action=parse`, the only way to ask the wiki about wikitext it has
 * not been given to save. That is what makes it worth having beside the library's own parser in
 * `kwikibot-wikitext`: the local parser reads the text as written and puts it back byte for byte,
 * while this reports what the wiki produced from it, templates and all.
 *
 * Reached as `wiki.renderer`. Not `wiki.parse`, which parses a title.
 */
public interface RenderService {

    /**
     * The headings of a page, in the order they appear.
     *
     * [RenderedSection.index] is what an edit takes as its section, so this is the step before
     * editing one section of a page rather than the whole of it.
     */
    public suspend fun sections(page: PageRef): List<RenderedSection>

    /** The rendered HTML of a saved page. */
    public suspend fun render(page: PageRef): String

    /**
     * The rendered HTML of wikitext that has not been saved.
     *
     * @param wikitext the text to render, which need not be saved anywhere.
     * @param context the title to parse as, which decides what relative links and magic words
     *   such as `PAGENAME` resolve to.
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
     * The question a bot wants answered before it edits: whether the new text links where it
     * should, and whether any of those links would be red.
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
    EXTERNAL_LINKS("externallinks"),
    ;

    /** The groupings worth asking for as a set. */
    public companion object {
        /** What a page points at, which is what a bot checking its own edit usually wants. */
        public val CONTENTS: Set<ParseProperty> =
            setOf(LINKS, TEMPLATES, CATEGORIES, IMAGES, EXTERNAL_LINKS)

        /** Everything this service models. */
        public val ALL: Set<ParseProperty> = entries.toSet()
    }
}

internal class ApiRenderService(
    private val transport: MediaWikiTransport,
    private val decoder: PageDecoder,
    private val namespaces: NamespaceMap,
) : RenderService {

    override suspend fun sections(page: PageRef): List<RenderedSection> =
        parse(setOf(ParseProperty.SECTIONS), "page" to namespaces.format(page.title)).sections

    override suspend fun render(page: PageRef): String =
        parse(setOf(ParseProperty.TEXT), "page" to namespaces.format(page.title)).html.orEmpty()

    override suspend fun renderText(wikitext: String, context: PageRef): String =
        parse(setOf(ParseProperty.TEXT), *draft(wikitext, context)).html.orEmpty()

    override suspend fun resolve(page: PageRef, properties: Set<ParseProperty>): ParsedPage =
        parse(properties, "page" to namespaces.format(page.title))

    override suspend fun resolveText(
        wikitext: String,
        context: PageRef,
        properties: Set<ParseProperty>,
    ): ParsedPage = parse(properties, *draft(wikitext, context))

    /** Unsaved text needs a title to parse as, or relative links and magic words have no answer. */
    private fun draft(wikitext: String, context: PageRef): Array<Pair<String, String?>> = arrayOf(
        "text" to wikitext,
        "title" to namespaces.format(context.title),
        "contentmodel" to WIKITEXT,
    )

    private suspend fun parse(
        properties: Set<ParseProperty>,
        vararg params: Pair<String, String?>,
    ): ParsedPage {
        require(properties.isNotEmpty()) { "a parse needs at least one property" }

        val response = transport.call(
            ApiRequest.of(
                "parse",
                *params,
                "prop" to properties.joinToString("|") { it.apiValue },
                // Edit-section links and the limit report are markup for a reader, and noise to
                // anything reading the result.
                "disableeditsection" to "1",
                "disablelimitreport" to "1",
            ),
        ).throwOnError()

        val parsed = response["parse"]?.jsonObject ?: return ParsedPage()

        return ParsedPage(
            html = parsed["text"]?.jsonPrimitive?.content,
            sections = parsed.array("sections").map { it.jsonObject.toSection() },
            links = parsed.array("links").mapNotNull { it.jsonObject.toLink() },
            templates = parsed.array("templates").mapNotNull { it.jsonObject.toLink() },
            categories = parsed.array("categories").mapNotNull { it.jsonObject.toCategory() },
            images = parsed.array("images").mapNotNull { it.name()?.toFile() },
            externalLinks = parsed.array("externallinks").mapNotNull { it.name() },
            revision = parsed["revid"]?.jsonPrimitive?.longOrNull?.let { RevisionId(it) },
        )
    }

    private fun JsonObject.array(key: String): List<JsonElement> =
        (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.toSection() = RenderedSection(
        index = text("index"),
        heading = text("line"),
        level = text("level").toIntOrNull() ?: 0,
        tocLevel = this["toclevel"]?.jsonPrimitive?.intOrNull ?: 0,
        number = text("number"),
        anchor = text("anchor"),
        byteOffset = this["byteoffset"]?.jsonPrimitive?.intOrNull,
    )

    private fun JsonObject.toLink(): ResolvedLink? {
        val page = decoder.refOf(this) ?: return null
        // A real boolean, and false is sent rather than omitted. Testing for the key's presence
        // reports every red link as existing, which is the answer backwards.
        return ResolvedLink(page = page, exists = this["exists"]?.jsonPrimitive?.booleanOrNull == true)
    }

    /**
     * A category entry, which arrives as a database key rather than as a title.
     *
     * No namespace and underscores for spaces, unlike everywhere else the API names a page, so it
     * is normalised here rather than left for the caller to trip over.
     */
    private fun JsonObject.toCategory(): PageRef? =
        text("category").takeIf { it.isNotEmpty() }
            ?.let { decoder.refOf(it.replace('_', ' '), Namespace.CATEGORY.id) }

    private fun String.toFile(): PageRef? = decoder.refOf(this, Namespace.FILE.id)

    private fun JsonElement.name(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.text(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

    private companion object {
        const val WIKITEXT = "wikitext"
    }
}
