import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.ParseOptions as ClientParseOptions
import com.fenakhay.kwikibot.wikitext.Wikitext as Parser
import com.fenakhay.kwikibot.wikitext.node.Parameter as ParsedParameter
import com.fenakhay.kwikibot.wikitext.node.Template as ParsedTemplate
import com.fenakhay.kwikibot.wikitext.ops.Section as ParsedSection

/**
 * A parameter of a template. [showKey] says whether it is written as `name=value` rather than by position,
 * its [name] then being the number it has.
 */
@JsExport
public fun createParameter(name: Wikitext, value: Wikitext, showKey: Boolean): Parameter =
    Parameter(ParsedParameter(name.markup, value.markup, showKey))

/** A template called [name] with [parameters], which can go on with `withParameter`. */
@JsExport
public fun createTemplate(name: Wikitext, parameters: Array<Parameter> = emptyArray()): Template =
    Template(ParsedTemplate(name.markup, parameters.map { it.parameter }))

/** A section with [heading], `null` for a lead, the [nodes] directly under it and [subsections]. */
@JsExport
public fun createSection(
    heading: Heading?,
    nodes: Array<Node>,
    subsections: Array<Section> = emptyArray(),
): Section = Section(ParsedSection(heading?.heading, nodes.map { it.node }, subsections.map { it.section }))

/** Wikitext made of [nodes], as they are. */
@JsExport public fun wikitextOf(nodes: Array<Node>): Wikitext = Wikitext(Markup(nodes.map { it.node }))

/** Wikitext holding [text] as one piece of literal text, parsed as nothing else. */
@JsExport public fun literalText(text: String): Wikitext = Wikitext(Markup.of(text))

/** Wikitext with nothing in it. */
@JsExport public fun emptyWikitext(): Wikitext = Wikitext(Markup.EMPTY)

/**
 * Parses [text], the way a wiki with [options] reads it when they are given, and checks that writing it back
 * gives the same text. Throws otherwise; `describeError` says from which offset.
 */
@JsExport
public fun parseExact(text: String, options: ParseOptions? = null): Wikitext =
    Wikitext(Parser.parseExact(text, options?.options ?: ClientParseOptions.DEFAULT))

/**
 * Whether [text] parses, the way a wiki with [options] reads it, into wikitext that writes back unchanged.
 */
@JsExport
public fun roundTrips(text: String, options: ParseOptions? = null): Boolean =
    Parser.roundTrips(text, options?.options ?: ClientParseOptions.DEFAULT)
