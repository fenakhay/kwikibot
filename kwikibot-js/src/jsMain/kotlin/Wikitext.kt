import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.TextScope
import com.fenakhay.kwikibot.wikitext.Wikitext as Parser
import com.fenakhay.kwikibot.wikitext.node.Parameter as ParsedParameter
import com.fenakhay.kwikibot.wikitext.node.Template as ParsedTemplate
import com.fenakhay.kwikibot.wikitext.node.WikiLink as ParsedLink
import com.fenakhay.kwikibot.wikitext.ops.Section as ParsedSection
import com.fenakhay.kwikibot.wikitext.ops.addCategory
import com.fenakhay.kwikibot.wikitext.ops.categoryNames
import com.fenakhay.kwikibot.wikitext.ops.outline
import com.fenakhay.kwikibot.wikitext.ops.removeCategory
import com.fenakhay.kwikibot.wikitext.ops.replaceSection
import com.fenakhay.kwikibot.wikitext.ops.replaceText
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
    public fun replaceText(pattern: RegExp, replacement: String, scope: String = "prose"): Wikitext =
        Wikitext(markup.replaceText(pattern.toRegex(), replacement, textScope(scope)))

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

    /** The template as wikitext. */
    override fun toString(): String = template.serialize()
}

/** One parameter of a template. */
@JsExport
public class Parameter internal constructor(private val parameter: ParsedParameter) {

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

    /** The parameter as wikitext. */
    override fun toString(): String = parameter.serialize()
}

/** A link to another page of the wiki, `[[target|text]]`. */
@JsExport
public class Link internal constructor(private val link: ParsedLink) {

    /** The page linked to, as plain text. */
    public val target: String
        get() = link.title

    /** What is shown in place of the target when the link is piped, as visible text, or `null`. */
    public val text: String?
        get() = link.text?.text

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

    /** The section as wikitext, heading and subsections included. */
    override fun toString(): String = section.serialize()
}

private fun textScope(name: String): TextScope =
    when (name) {
        "prose" -> TextScope.PROSE
        "everywhere" -> TextScope.EVERYWHERE
        else -> throw IllegalArgumentException("unknown scope '$name'; use \"prose\" or \"everywhere\"")
    }

private fun RegExp.toRegex(): Regex {
    val flags: String = asDynamic().flags.unsafeCast<String>()
    val unsupported = flags.filter { it == 's' || it == 'y' }
    require(unsupported.isEmpty()) { "the regular expression flags '$unsupported' are not supported" }
    val options = buildSet {
        if (ignoreCase) add(RegexOption.IGNORE_CASE)
        if (multiline) add(RegexOption.MULTILINE)
    }
    return Regex(asDynamic().source.unsafeCast<String>(), options)
}
