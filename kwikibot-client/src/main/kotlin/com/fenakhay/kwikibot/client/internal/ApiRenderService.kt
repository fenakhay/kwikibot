package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.service.ParseProperty
import com.fenakhay.kwikibot.client.service.RenderService
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.render.ParsedPage
import com.fenakhay.kwikibot.model.render.RenderedSection
import com.fenakhay.kwikibot.model.render.ResolvedLink
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
    private fun draft(wikitext: String, context: PageRef): Array<Pair<String, String?>> =
        arrayOf(
            "text" to wikitext,
            "title" to namespaces.format(context.title),
            "contentmodel" to WIKITEXT,
        )

    private suspend fun parse(
        properties: Set<ParseProperty>,
        vararg params: Pair<String, String?>,
    ): ParsedPage {
        require(properties.isNotEmpty()) { "a parse needs at least one property" }

        val response =
            transport
                .call(
                    ApiRequest.of(
                        "parse",
                        *params,
                        "prop" to properties.joinToString("|") { it.apiValue },
                        // Edit-section links and the limit report are markup for a reader, and noise to
                        // anything reading the result.
                        "disableeditsection" to "1",
                        "disablelimitreport" to "1",
                    )
                )
                .throwOnError()

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

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.toSection() =
        RenderedSection(
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
     * No namespace and underscores for spaces, unlike everywhere else the API names a page, so it is
     * normalised here rather than left for the caller to trip over.
     */
    private fun JsonObject.toCategory(): PageRef? =
        text("category")
            .takeIf { it.isNotEmpty() }
            ?.let { decoder.refOf(it.replace('_', ' '), Namespace.CATEGORY.id) }

    private fun String.toFile(): PageRef? = decoder.refOf(this, Namespace.FILE.id)

    private fun JsonElement.name(): String? = runCatching {
        jsonPrimitive.content
    }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.text(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

    private companion object {
        const val WIKITEXT = "wikitext"
    }
}
