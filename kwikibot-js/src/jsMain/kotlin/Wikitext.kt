import com.fenakhay.kwikibot.bindings.wikitext.categoryPrefixesOf
import com.fenakhay.kwikibot.bindings.wikitext.textScopeNamed
import com.fenakhay.kwikibot.bindings.wikitext.textScopeOf
import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.TextScope
import com.fenakhay.kwikibot.wikitext.TitleRules as ClientTitleRules
import com.fenakhay.kwikibot.wikitext.Wikitext as Parser
import com.fenakhay.kwikibot.wikitext.node.Parameter as ParsedParameter
import com.fenakhay.kwikibot.wikitext.node.Template as ParsedTemplate
import com.fenakhay.kwikibot.wikitext.node.WikiLink as ParsedLink
import com.fenakhay.kwikibot.wikitext.ops.Section as ParsedSection
import com.fenakhay.kwikibot.wikitext.ops.addCategory
import com.fenakhay.kwikibot.wikitext.ops.categories
import com.fenakhay.kwikibot.wikitext.ops.categoryNames
import com.fenakhay.kwikibot.wikitext.ops.changeCategory
import com.fenakhay.kwikibot.wikitext.ops.containsText
import com.fenakhay.kwikibot.wikitext.ops.languageLinks
import com.fenakhay.kwikibot.wikitext.ops.mapNodes
import com.fenakhay.kwikibot.wikitext.ops.outline
import com.fenakhay.kwikibot.wikitext.ops.removeCategory
import com.fenakhay.kwikibot.wikitext.ops.replaceSection
import com.fenakhay.kwikibot.wikitext.ops.replaceText
import com.fenakhay.kwikibot.wikitext.ops.sortLanguageLinks
import com.fenakhay.kwikibot.wikitext.ops.textIn
import kotlin.js.RegExp

/**
 * Parses [text] as MediaWiki does on Wikimedia's wikis.
 *
 * Parsing touches no network, and the result writes back as exactly [text] until something in it is changed.
 */
@JsExport public fun parse(text: String): Wikitext = Wikitext(Parser.parse(text))

/** Parsed wikitext. Every change returns a new one and leaves this one as it was. */
@JsExport
public class Wikitext internal constructor(internal val markup: Markup) {

    /**
     * The visible text, with markup removed: what a reader sees. For comparing content, never for writing
     * back.
     */
    public val text: String
        get() = markup.text

    /** The templates, outermost first, or only those whose name is [name] when one is given. */
    public fun templates(name: String? = null): Array<Template> =
        markup.templates(name).map(::Template).toTypedArray()

    /** The links to other pages of the wiki, in the order written. */
    public fun links(): Array<Link> = markup.wikilinks().map(::Link).toTypedArray()

    /** The sections, as a tree under the lead, which is everything before the first heading. */
    public fun outline(): Section = Section(markup.outline())

    /** The names of the categories the page is in, without the namespace prefix. */
    public fun categories(): Array<String> = markup.categoryNames().toTypedArray()

    /** This text in category [name] as well, sorted under [sortKey] when one is given. */
    public fun addCategory(name: String, sortKey: String? = null): Wikitext =
        Wikitext(markup.addCategory(name, sortKey))

    /** This text out of category [name]. */
    public fun removeCategory(name: String): Wikitext = Wikitext(markup.removeCategory(name))

    /** This text with each template named [name] replaced by what [transform] makes of it. */
    public fun mapTemplates(name: String, transform: (template: Template) -> Template): Wikitext =
        Wikitext(markup.mapTemplates(name) { transform(Template(it)).template })

    /** This text with [old], one of its templates, replaced by [replacement]. */
    public fun replaceTemplate(old: Template, replacement: Template): Wikitext =
        Wikitext(markup.replace(old.template, replacement.template))

    /** This text with [old], one of its sections, replaced by [replacement]. */
    public fun replaceSection(old: Section, replacement: Section): Wikitext =
        Wikitext(markup.replaceSection(old.section, replacement.section))

    /**
     * This text with every match of [pattern] replaced by [replacement], which is taken as it is: `$1` in it
     * is a dollar sign and a one.
     *
     * [scope] is `"prose"`, the text a reader sees, which leaves templates, link targets, comments and nowiki
     * alone; or `"everywhere"`. The pattern's `i` and `m` flags apply; `s` and `y` have no equivalent here
     * and are refused.
     */
    public fun replaceText(
        pattern: RegExp,
        replacement: String,
        scope: TextScopeName = "prose".unsafeCast<TextScopeName>(),
    ): Wikitext =
        Wikitext(
            markup.replaceText(pattern.toRegex(), replacement, textScopeNamed(scope.unsafeCast<String>()))
        )

    /**
     * This text with every match of [pattern] replaced by [replacement], taken as it is, in the regions
     * [scope] opens up. The pattern's `i` and `m` flags apply; `s` and `y` are refused.
     */
    public fun replaceTextIn(pattern: RegExp, replacement: String, scope: TextScopeOptions): Wikitext =
        Wikitext(markup.replaceText(pattern.toRegex(), replacement, scopeOf(scope)))

    /**
     * This text with every match of [pattern] replaced by what [replacement] makes of it, in the regions
     * [scope] opens up, the prose a reader sees unless given. The pattern's `i` and `m` flags apply; `s` and
     * `y` are refused.
     */
    public fun replaceTextWith(
        pattern: RegExp,
        replacement: (match: TextMatch) -> String,
        scope: TextScopeOptions? = null,
    ): Wikitext =
        Wikitext(markup.replaceText(pattern.toRegex(), scopeOf(scope)) { replacement(TextMatch(it)) })

    /** Whether any text in the regions [scope] opens up, the prose unless given, matches [pattern]. */
    public fun containsText(pattern: RegExp, scope: TextScopeOptions? = null): Boolean =
        markup.containsText(pattern.toRegex(), scopeOf(scope))

    /** The text in the regions [scope] opens up, the prose unless given, node by node. */
    public fun textIn(scope: TextScopeOptions? = null): Array<String> =
        markup.textIn(scopeOf(scope)).toTypedArray()

    /** The top-level nodes, in the order written. */
    public val nodes: Array<Node>
        get() = markup.nodes.map(::Node).toTypedArray()

    /** Every node, those nested inside templates, links and tags included. */
    public fun allNodes(): Array<Node> = markup.allNodes().map(::Node).toList().toTypedArray()

    /**
     * The templates that transclude one of [names] under [rules], nested ones included, Wikimedia's rules
     * unless given. Each name is read as a page, so `l`, `Template:l` and `{{subst:l}}` all name
     * `Template:L`.
     */
    public fun templatesNamed(names: Array<String>, rules: TitleRules? = null): Array<Template> =
        markup
            .templates(names.toList(), rules?.rules ?: ClientTitleRules.DEFAULT)
            .map(::Template)
            .toTypedArray()

    /** The headings that open sections, not those inside a template parameter, a link or a `<ref>`. */
    public fun headings(): Array<Heading> = markup.headings().map(::Heading).toTypedArray()

    /** The tags, nested ones included, or only those called [name] when one is given. */
    public fun tags(name: String? = null): Array<Tag> = markup.tags(name).map(::Tag).toTypedArray()

    /** The comments, nested ones included. */
    public fun comments(): Array<Comment> = markup.comments().map(::Comment).toTypedArray()

    /** This text with every occurrence of [target], nested ones included, replaced by [replacement]. */
    public fun replace(target: Node, replacement: Node): Wikitext =
        Wikitext(markup.replace(target.node, replacement.node))

    /** This text without [target]. */
    public fun remove(target: Node): Wikitext = Wikitext(markup.remove(target.node))

    /** This text with every template replaced by what [transform] makes of it. */
    public fun mapAllTemplates(transform: (template: Template) -> Template): Wikitext =
        Wikitext(markup.mapTemplates(null as String?) { transform(Template(it)).template })

    /** This text with every template transcluding one of [names] under [rules] replaced by [transform]'s. */
    public fun mapTemplatesNamed(
        names: Array<String>,
        transform: (template: Template) -> Template,
        rules: TitleRules? = null,
    ): Wikitext =
        Wikitext(
            markup.mapTemplates(names.toList(), rules?.rules ?: ClientTitleRules.DEFAULT) {
                transform(Template(it)).template
            }
        )

    /**
     * This text with every node rewritten by [transform], those inside it first. It does not go into a tag
     * MediaWiki does not parse, nor `<code>`.
     */
    public fun mapNodes(transform: (node: Node) -> Node): Wikitext =
        Wikitext(markup.mapNodes { transform(Node(it)).node })

    /** This text followed by [other]. */
    public fun plus(other: Wikitext): Wikitext = Wikitext(markup + other.markup)

    /** Where each piece of this text sits in what it writes back as. Ask with what a query here returned. */
    public fun ranges(): SourceRanges = SourceRanges(markup.ranges())

    /**
     * The links filing the page in a category, or naming one, under [prefixes], `"Category"` unless given.
     */
    public fun categoryLinks(prefixes: Array<String>? = null): Array<Link> =
        markup.categories(categoryPrefixesOf(prefixes?.asList())).map(::Link).toTypedArray()

    /** The names of the page's categories under [prefixes], without the prefix or a sort key. */
    public fun categoryNamesIn(prefixes: Array<String>): Array<String> =
        markup.categoryNames(prefixes.toSet()).toTypedArray()

    /** The language links, `[[fr:Foo]]` and friends, among the language [codes] this wiki recognises. */
    public fun languageLinks(codes: Array<String>): Array<Link> =
        markup.languageLinks(codes.toSet()).map(::Link).toTypedArray()

    /** This text in category [name] as well, written with the namespace name [prefix] the wiki uses. */
    public fun addCategoryAs(name: String, prefix: String, sortKey: String? = null): Wikitext =
        Wikitext(markup.addCategory(name, sortKey, prefix))

    /** This text out of category [name], which it names with the namespace name [prefix]. */
    public fun removeCategoryAs(name: String, prefix: String): Wikitext =
        Wikitext(markup.removeCategory(name, prefix))

    /** This text with category [from] replaced by [to], keeping the sort key. */
    public fun changeCategory(from: String, to: String, prefix: String = "Category"): Wikitext =
        Wikitext(markup.changeCategory(from, to, prefix))

    /**
     * This text with the language links among [codes] sorted, by [order] when given and else by code. Only
     * the links move.
     */
    public fun sortLanguageLinks(
        codes: Array<String>,
        order: ((a: String, b: String) -> Int)? = null,
    ): Wikitext =
        Wikitext(
            if (order == null) markup.sortLanguageLinks(codes.toSet())
            else markup.sortLanguageLinks(codes.toSet(), Comparator(order))
        )

    /** This text with [old] replaced by [replacement], read back as a wiki with [options] reads it. */
    public fun replaceSectionWith(old: Section, replacement: Section, options: ParseOptions): Wikitext =
        Wikitext(markup.replaceSection(old.section, replacement.section, options.options))

    /** Whether [other] is the same wikitext, node for node. */
    override fun equals(other: Any?): Boolean = other is Wikitext && markup == other.markup

    /** A hash that agrees with [equals]. */
    override fun hashCode(): Int = markup.hashCode()

    /** The wikitext, byte for byte as it was parsed apart from what was changed. */
    override fun toString(): String = markup.serialize()
}

/** A template, or a parser function, and the parameters it was given. */
@JsExport
public class Template internal constructor(internal val template: ParsedTemplate) {

    /**
     * The name as MediaWiki reads it: comments removed and whitespace trimmed, the first letter as written.
     */
    public val name: String
        get() = template.title

    /**
     * The name normalised the way MediaWiki resolves it, for comparing: `{{foo_bar}}` and `{{Template:Foo
     * bar}}` are both `"Foo bar"`, and a template from another namespace keeps its prefix, `":Foo"` for the
     * main one. `null` for a parser function or a variable.
     */
    public val key: String?
        get() = template.key

    /** The parameters, in the order written. */
    public val parameters: Array<Parameter>
        get() = template.parameters.map(::Parameter).toTypedArray()

    /** The value of parameter [name] as visible text, trimmed, or `null` when it is not given. */
    public fun get(name: String): String? = template.value(name)

    /** The value of parameter [name] as written, or `null` when it is not given. */
    public fun getRaw(name: String): String? = template.rawValue(name)

    /** Whether parameter [name] is given. */
    public fun has(name: String): Boolean = name in template

    /** This template with parameter [name] set to [value], in place when it was given and last when not. */
    public fun withParameter(name: String, value: String): Template =
        Template(template.withParameter(name, value))

    /** This template without parameter [name]. */
    public fun withoutParameter(name: String): Template = Template(template.withoutParameter(name))

    /** The name as written, comments and whitespace included. */
    public val rawName: String
        get() = template.rawName

    /** The name as wikitext, which may itself contain markup. */
    public val nameMarkup: Wikitext
        get() = Wikitext(template.name)

    /** The parameter [name], or `null` when it is not given. */
    public fun parameter(name: String): Parameter? = template.parameter(name)?.let(::Parameter)

    /** Whether this is a parser function or a variable, such as `{{#if:...}}` or `{{PAGENAME}}`. */
    public val isParserFunction: Boolean
        get() = template.isParserFunction

    /** Whether this is a parser function or a variable under [rules]. */
    public fun isParserFunctionIn(rules: TitleRules): Boolean = template.isParserFunction(rules.rules)

    /** The parser function or variable this calls, such as `#switch` or `PAGENAME`; `null` for a template. */
    public val functionName: String?
        get() = template.functionName

    /** The parser function or variable this calls, under [rules]. */
    public fun functionNameIn(rules: TitleRules): String? = template.functionName(rules.rules)

    /** What a parser function is given after its colon, such as `sa-decl` in `{{#invoke:sa-decl|show}}`. */
    public val functionArgument: String?
        get() = template.functionArgument

    /** What a parser function is given after its colon, under [rules]. */
    public fun functionArgumentIn(rules: TitleRules): String? = template.functionArgument(rules.rules)

    /** The page this transcludes, normalised under [rules], or `null` when it transcludes none. */
    public fun keyIn(rules: TitleRules): String? = template.key(rules.rules)

    /** This template as a node, to give to `Wikitext.replace` and the like. */
    public val node: Node
        get() = Node(template)

    /** The template as wikitext. */
    override fun toString(): String = template.serialize()
}

/** One parameter of a template. */
@JsExport
public class Parameter internal constructor(internal val parameter: ParsedParameter) {

    /** The name as MediaWiki reads it, or for a positional parameter its number. */
    public val name: String
        get() = parameter.key

    /** Whether the name was written, as in `name=value`, rather than implied by position. */
    public val named: Boolean
        get() = parameter.showKey

    /** The value as visible text, trimmed. */
    public val value: String
        get() = parameter.value.text.trim()

    /** The value as written, whitespace, comments and markup included. */
    public val rawValue: String
        get() = parameter.rawValue

    /** The name as written, or `null` for a positional parameter, which has none written. */
    public val rawName: String?
        get() = parameter.rawKey

    /** The name as wikitext, which for a positional parameter is its number. */
    public val nameMarkup: Wikitext
        get() = Wikitext(parameter.name)

    /** The value as wikitext. */
    public val valueMarkup: Wikitext
        get() = Wikitext(parameter.value)

    /** The parameter as wikitext. */
    override fun toString(): String = parameter.serialize()
}

/** A link to another page of the wiki, `[[target|text]]`. */
@JsExport
public class Link internal constructor(internal val link: ParsedLink) {

    /** The page linked to, as plain text. */
    public val target: String
        get() = link.title

    /** What is shown in place of the target when the link is piped, as visible text, or `null`. */
    public val text: String?
        get() = link.text?.text

    /** The page linked to, as wikitext. */
    public val targetMarkup: Wikitext
        get() = Wikitext(link.target)

    /** What is shown in place of the target, as wikitext, or `null`. */
    public val textMarkup: Wikitext?
        get() = link.text?.let(::Wikitext)

    /** This link as a node, to give to `Wikitext.replace` and the like. */
    public val node: Node
        get() = Node(link)

    /** The link as wikitext. */
    override fun toString(): String = link.serialize()
}

/** A section: its heading, what is directly under it, and the sections nested under it. */
@JsExport
public class Section internal constructor(internal val section: ParsedSection) {

    /** The heading text, trimmed, or `null` for the lead. */
    public val title: String?
        get() = section.title

    /** The heading level, from 1 to 6, or 0 for the lead. */
    public val level: Int
        get() = section.level

    /** What is directly under the heading, before any subsection. */
    public val content: Wikitext
        get() = Wikitext(section.content)

    /** The sections nested directly under this one. */
    public val subsections: Array<Section>
        get() = section.subsections.map(::Section).toTypedArray()

    /** The first section under this one, at any depth, headed [title], and at [level] when one is given. */
    public fun find(title: String, level: Int? = null): Section? = section.find(title, level)?.let(::Section)

    /** This section with what is directly under its heading replaced by [content]. */
    public fun withContent(content: Wikitext): Section = Section(section.withContent(content.markup))

    /** The heading that opened this section, or `null` for the lead. */
    public val heading: Heading?
        get() = section.heading?.let(::Heading)

    /** The nodes directly under the heading, before any subsection. */
    public val nodes: Array<Node>
        get() = section.nodes.map(::Node).toTypedArray()

    /** This section and every section nested inside it, depth first. */
    public fun all(): Array<Section> = section.all().map(::Section).toList().toTypedArray()

    /** The sections directly under this one headed [title]. */
    public fun children(title: String): Array<Section> = section.children(title).map(::Section).toTypedArray()

    /** This section with [subsection] added at the end. */
    public fun withSubsection(subsection: Section): Section =
        Section(section.withSubsection(subsection.section))

    /** This section with [subsection] added at [index] among its direct children. Throws outside them. */
    public fun withSubsectionAt(index: Int, subsection: Section): Section =
        Section(section.withSubsectionAt(index, subsection.section))

    /** This section with [target] replaced by [replacement] wherever it is nested. */
    public fun replace(target: Section, replacement: Section): Section =
        Section(section.replace(target.section, replacement.section))

    /** The section as wikitext, heading and subsections included. */
    override fun toString(): String = section.serialize()
}

/** The prose a reader sees: not templates, link targets, comments or nowiki. */
@JsExport public val TEXT_SCOPE_PROSE: TextScopeOptions = textScopeOptions(TextScope.PROSE)

/** Every piece of text, wherever it sits. */
@JsExport public val TEXT_SCOPE_EVERYWHERE: TextScopeOptions = textScopeOptions(TextScope.EVERYWHERE)

internal fun textScopeOptions(scope: TextScope): TextScopeOptions {
    val options: dynamic = js("({})")
    options.templates = scope.templates
    options.linkTargets = scope.linkTargets
    options.comments = scope.comments
    options.rawTags = scope.rawTags
    options.headings = scope.headings
    options.tagAttributes = scope.tagAttributes
    return options.unsafeCast<TextScopeOptions>()
}

internal fun scopeOf(options: TextScopeOptions?): TextScope =
    textScopeOf(
        options?.templates,
        options?.linkTargets,
        options?.comments,
        options?.rawTags,
        options?.headings,
        options?.tagAttributes,
    )

/** This regular expression as Kotlin's, refusing the flags that have no equivalent. */
internal fun RegExp.toRegex(): Regex {
    val flags: String = asDynamic().flags.unsafeCast<String>()
    val unsupported = flags.filter { it == 's' || it == 'y' }
    require(unsupported.isEmpty()) { "the regular expression flags '$unsupported' are not supported" }
    val options = buildSet {
        if (ignoreCase) add(RegexOption.IGNORE_CASE)
        if (multiline) add(RegexOption.MULTILINE)
    }
    return Regex(asDynamic().source.unsafeCast<String>(), options)
}
