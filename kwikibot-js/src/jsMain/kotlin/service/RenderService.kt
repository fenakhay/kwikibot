import com.fenakhay.kwikibot.bindings.client.sandboxOf
import com.fenakhay.kwikibot.bindings.client.titleOf
import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.common.enumArgument
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.service.ParseProperty as ClientParseProperty
import com.fenakhay.kwikibot.client.service.Parser
import com.fenakhay.kwikibot.client.service.RenderRequest
import com.fenakhay.kwikibot.client.service.TemplateSandbox
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.render.LimitReport as ClientLimitReport
import com.fenakhay.kwikibot.model.render.ParsedPage as ClientParsedPage
import com.fenakhay.kwikibot.model.render.RenderedSection as ClientRenderedSection
import com.fenakhay.kwikibot.model.render.ResolvedLink as ClientResolvedLink
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise

/**
 * Unsaved templates and modules to parse with, through the TemplateSandbox extension. Needs [prefixes] or a
 * [title] with its [text].
 */
@JsExport
public external interface TemplateSandboxOptions {
    /**
     * Pages under which sandboxed versions live. With `User:Me/sandbox`, a transclusion of `Template:Foo`
     * uses `User:Me/sandbox/Template:Foo` where that page exists.
     */
    public val prefixes: Array<String>?

    /** One page to stand in for with [text], such as `Module:foo`, for this parse only. */
    public val title: String?

    /** What [title] says for this parse. */
    public val text: String?

    /** The content model of [text], `Scribunto` for a module. The wiki goes by the title unless given. */
    public val contentModel: String?
}

/**
 * What to parse with `wiki.render.parse`, how, and which parts of the result to report: a saved [page], or
 * unsaved [text] parsed as if it were at [context].
 */
@JsExport
public external interface ParseRequest {
    /** A saved page to parse. */
    public val page: String?

    /** Unsaved wikitext to parse. */
    public val text: String?

    /** The title to parse [text] as, which decides what relative links and `PAGENAME` mean. */
    public val context: String?

    /**
     * Which parts of the result to report, from `"text"`, `"links"`, `"templates"`, `"categories"`,
     * `"images"`, `"external-links"`, `"limit-report"`, `"toc-data"` and the deprecated `"sections"`.
     * `contents` unless given.
     */
    public val properties: Array<ParseProperty>?

    /** Unsaved templates or modules to parse with. */
    public val sandbox: TemplateSandboxOptions?

    /** The revision to parse [text] as, which `{{REVISIONID}}` reads. As a preview unless given. */
    public val revision: Double?

    /** Which of the wiki's parsers to use, `"legacy"` or `"parsoid"`. Left to the wiki unless given. */
    public val parser: ParserName?
}

/** [options] as a sandbox on [wiki]. */
internal fun sandboxFrom(wiki: ClientWiki, options: TemplateSandboxOptions): TemplateSandbox =
    wiki.sandboxOf(options.prefixes?.asList(), options.title, options.text, options.contentModel)

/**
 * The wiki's own view of a page, reached as `wiki.render`: what it renders, and what it resolves to,
 * templates and all. Where a property is taken, it is one of those [ParseRequest.properties] lists.
 */
@JsExport
public class RenderService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    /** What a page points at, which is what asking without properties reports. */
    public val contents: Array<ParseProperty>
        get() =
            ClientParseProperty.CONTENTS.map { it.argumentName().unsafeCast<ParseProperty>() }.toTypedArray()

    /** Every property, leaving out those kept only for compatibility. */
    public val all: Array<ParseProperty>
        get() = ClientParseProperty.ALL.map { it.argumentName().unsafeCast<ParseProperty>() }.toTypedArray()

    /** The headings of [page], in order, each with the `index` an edit takes as its section. */
    public fun sections(page: String): Promise<Array<RenderedSection>> = scope.promise {
        wiki.renderer.sections(wiki.ref(page)).map(::RenderedSection).toTypedArray()
    }

    /** The rendered HTML of the saved [page]. */
    public fun render(page: String): Promise<String> = scope.promise { wiki.renderer.render(wiki.ref(page)) }

    /** The rendered HTML of unsaved [wikitext], parsed as if it were at the title [context]. */
    public fun renderText(wikitext: String, context: String): Promise<String> = scope.promise {
        wiki.renderer.renderText(wikitext, wiki.ref(context))
    }

    /** What the saved [page] resolves to: only [properties], `contents` unless given. */
    public fun resolve(page: String, properties: Array<ParseProperty>? = null): Promise<ParsedPage> =
        scope.promise {
            ParsedPage(wiki.renderer.resolve(wiki.ref(page), parseProperties(properties)), wiki)
        }

    /** What unsaved [wikitext] would resolve to at the title [context], without saving it. */
    public fun resolveText(
        wikitext: String,
        context: String,
        properties: Array<ParseProperty>? = null,
    ): Promise<ParsedPage> = scope.promise {
        ParsedPage(wiki.renderer.resolveText(wikitext, wiki.ref(context), parseProperties(properties)), wiki)
    }

    /**
     * Parses what [request] names and reports the parts it asks for, all in one request. The one way to parse
     * with a sandbox. Rejects when it names both a page and a text, or neither, or a text without a context.
     */
    public fun parse(request: ParseRequest): Promise<ParsedPage> = scope.promise {
        val parsed =
            wiki.renderer.parse(
                RenderRequest(
                    ref(request.page),
                    request.text,
                    ref(request.context),
                    parseProperties(request.properties),
                    request.sandbox?.let { sandboxFrom(wiki, it) },
                    request.revision?.let(::revisionIdOf),
                    request.parser?.let { enumArgument<Parser>("parser", it.unsafeCast<String>()) },
                )
            )
        ParsedPage(parsed, wiki)
    }

    private fun ref(title: String?): PageRef? = title?.let { wiki.ref(it) }

    private fun parseProperties(properties: Array<ParseProperty>?): Set<ClientParseProperty> =
        properties?.map { enumArgument<ClientParseProperty>("properties", it.unsafeCast<String>()) }?.toSet()
            ?: ClientParseProperty.CONTENTS
}

/** One heading of a page, as the wiki renders it. */
@JsExport
public class RenderedSection internal constructor(private val section: ClientRenderedSection) {

    /** What an edit takes as its `section`. A string: the wiki numbers some subsections `T-1`. */
    public val index: String
        get() = section.index

    /** The heading text, with markup already resolved. */
    public val heading: String
        get() = section.heading

    /** The heading level, `2` for a second-level heading. */
    public val level: Int
        get() = section.level

    /** Depth in the contents list, which starts at 1 whatever the heading level is. */
    public val tocLevel: Int
        get() = section.tocLevel

    /** The section number as the contents list shows it, such as `"1.2"`. */
    public val number: String
        get() = section.number

    /** The fragment that links to this section. */
    public val anchor: String
        get() = section.anchor

    /** Where the section starts in the wikitext, or `null` for one a template produced. */
    public val byteOffset: Int?
        get() = section.byteOffset
}

/** A link a page resolves to once its templates have expanded. */
@JsExport
public class ResolvedLink
internal constructor(private val link: ClientResolvedLink, private val wiki: ClientWiki) {

    /** Where it points. */
    public val page: String
        get() = wiki.titleOf(link.page)

    /** Whether that page exists. `false` is a red link. */
    public val exists: Boolean
        get() = link.exists
}

/** How much of each parser limit a parse used. */
@JsExport
public class LimitReport internal constructor(private val report: ClientLimitReport) {

    /**
     * Every row of the report by name, such as `limitreport-expensivefunctioncount`, each with its values in
     * order: for a limit, what the parse used and then the limit.
     */
    public val entries: Record<String, Array<String>>
        get() = report.entries.toRecord { it.toTypedArray() }

    /** How long Lua ran, in milliseconds, or `null` when no module did. */
    public val luaTime: Double?
        get() = report.luaTime?.inWholeMilliseconds?.toDouble()

    /** How much memory Lua took at most, in bytes, or `null` when no module ran. */
    public val luaMemory: Double?
        get() = report.luaMemory?.toDouble()

    /** How many expensive parser functions the parse called, or `null`. */
    public val expensiveFunctions: Int?
        get() = report.expensiveFunctions
}

/** What a page or a draft turns into once the wiki has parsed it: what was asked for, the rest empty. */
@JsExport
public class ParsedPage
internal constructor(private val page: ClientParsedPage, private val wiki: ClientWiki) {

    /** The rendered HTML, when it was asked for. */
    public val html: String?
        get() = page.html

    /** The headings, each carrying the `index` an edit takes. */
    public val sections: Array<RenderedSection>
        get() = page.sections.map(::RenderedSection).toTypedArray()

    /** Every page the text links to, red links included. */
    public val links: Array<ResolvedLink>
        get() = page.links.map { ResolvedLink(it, wiki) }.toTypedArray()

    /** Every template it transcludes, after expansion rather than as written. */
    public val templates: Array<ResolvedLink>
        get() = page.templates.map { ResolvedLink(it, wiki) }.toTypedArray()

    /** The titles of the categories it files itself under, in the order the wiki lists them. */
    public val categories: Array<String>
        get() = page.categories.map(wiki::titleOf).toTypedArray()

    /** The titles of the files it uses. */
    public val images: Array<String>
        get() = page.images.map(wiki::titleOf).toTypedArray()

    /** The external URLs it links to. */
    public val externalLinks: Array<String>
        get() = page.externalLinks.toTypedArray()

    /** The revision parsed, or `null` when unsaved wikitext was. */
    public val revision: Double?
        get() = page.revision?.value?.toDouble()

    /** How much of each parser limit the parse used, when that was asked for. */
    public val limitReport: LimitReport?
        get() = page.limitReport?.let(::LimitReport)
}
