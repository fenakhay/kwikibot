package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.internal.wire.Capabilities
import com.fenakhay.kwikibot.client.internal.wire.Registry
import com.fenakhay.kwikibot.client.internal.wire.Wire
import com.fenakhay.kwikibot.client.service.ParseProperty
import com.fenakhay.kwikibot.client.service.RenderRequest
import com.fenakhay.kwikibot.client.service.RenderService
import com.fenakhay.kwikibot.client.service.TemplateSandbox
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.render.ParsedPage
import com.fenakhay.kwikibot.model.render.RenderedSection
import com.fenakhay.kwikibot.model.render.ResolvedLink
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ParamInfo
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class ApiRenderService(
    private val transport: MediaWikiTransport,
    private val decoder: PageDecoder,
    private val namespaces: NamespaceMap,
    private val capabilities: Capabilities = Capabilities(ParamInfo(transport), transport.endpoint.server),
) : RenderService {

    override suspend fun sections(page: PageRef): List<RenderedSection> =
        parse(RenderRequest.page(page, setOf(ParseProperty.TOC_DATA))).sections

    override suspend fun render(page: PageRef): String =
        parse(RenderRequest.page(page, setOf(ParseProperty.TEXT))).html.orEmpty()

    override suspend fun renderText(wikitext: String, context: PageRef): String =
        parse(RenderRequest.text(wikitext, context, setOf(ParseProperty.TEXT))).html.orEmpty()

    override suspend fun resolve(page: PageRef, properties: Set<ParseProperty>): ParsedPage =
        parse(RenderRequest.page(page, properties))

    override suspend fun resolveText(
        wikitext: String,
        context: PageRef,
        properties: Set<ParseProperty>,
    ): ParsedPage = parse(RenderRequest.text(wikitext, context, properties))

    override suspend fun parse(request: RenderRequest): ParsedPage {
        val properties = request.properties
        val response =
            transport
                .call(
                    ApiRequest.of(
                        "parse",
                        *source(request),
                        *sandbox(request.sandbox),
                        "prop" to properties.flatMap { wire(it) }.distinct().joinToString("|"),
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
            sections = parsed.sections(),
            links = parsed.array("links").mapNotNull { it.jsonObject.toLink() },
            templates = parsed.array("templates").mapNotNull { it.jsonObject.toLink() },
            categories = parsed.array("categories").mapNotNull { it.jsonObject.toCategory() },
            images = parsed.array("images").mapNotNull { it.name()?.toFile() },
            externalLinks = parsed.array("externallinks").mapNotNull { it.name() },
            revision = parsed["revid"]?.jsonPrimitive?.longOrNull?.let { RevisionId(it) },
        )
    }

    /** What is parsed: a saved page, or text, which needs a title for relative links to resolve against. */
    private fun source(request: RenderRequest): Array<Pair<String, String?>> {
        val page = request.page
        return if (page != null) {
            arrayOf("page" to namespaces.format(page.title))
        } else {
            arrayOf(
                "text" to request.text,
                "title" to request.context?.let { namespaces.format(it.title) },
                "contentmodel" to WIKITEXT,
            )
        }
    }

    private fun sandbox(sandbox: TemplateSandbox?): Array<Pair<String, String?>> =
        if (sandbox == null) {
            emptyArray()
        } else {
            arrayOf(
                "templatesandboxprefix" to sandbox.prefixes.takeIf { it.isNotEmpty() }?.joinToString("|"),
                "templatesandboxtitle" to sandbox.title?.let { namespaces.format(it.title) },
                "templatesandboxtext" to sandbox.text,
                "templatesandboxcontentmodel" to sandbox.contentModel,
            )
        }

    /** What [property] goes out as on this wiki: its own value, or the spelling this wiki takes. */
    private suspend fun wire(property: ParseProperty): List<String> =
        property.choice?.let { capabilities.resolve(it) } ?: listOf(property.apiValue)

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    /**
     * The headings, from whichever of the two shapes the wiki answered with.
     *
     * `tocdata` is the table of contents MediaWiki added in 1.43.6, and `sections` the list it replaced. They
     * carry the same headings under different keys, and `tocdata` leaves out every key that is at its
     * default.
     */
    private fun JsonObject.sections(): List<RenderedSection> {
        val toc = (this["tocdata"] as? JsonObject)?.array("sections")
        return toc?.map { it.jsonObject.fromTocData() } ?: array("sections").map { it.jsonObject.toSection() }
    }

    private fun JsonObject.fromTocData() =
        RenderedSection(
            index = text("index"),
            heading = text("line"),
            // -1 is how the table of contents says it does not know, and it then leaves the key out.
            level = int("hLevel")?.takeIf { it >= 0 } ?: 0,
            tocLevel = int("tocLevel") ?: 0,
            number = text("number"),
            anchor = text("anchor"),
            byteOffset = int("codepointOffset"),
        )

    private fun JsonObject.toSection() =
        RenderedSection(
            index = text("index"),
            heading = text("line"),
            level = text("level").toIntOrNull() ?: 0,
            tocLevel = int("toclevel") ?: 0,
            number = text("number"),
            anchor = text("anchor"),
            byteOffset = int("byteoffset"),
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

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        const val WIKITEXT = "wikitext"

        /** The choice an entry goes out through, for the entries MediaWiki has respelled. */
        @Suppress("DEPRECATION")
        val ParseProperty.choice: Wire.Choice?
            get() =
                when (this) {
                    ParseProperty.TOC_DATA,
                    ParseProperty.SECTIONS -> Registry.TABLE_OF_CONTENTS
                    else -> null
                }
    }
}
