package com.fenakhay.kwikibot.wikitext.node

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.TitleRules
import com.fenakhay.kwikibot.wikitext.internal.ParameterValue
import com.fenakhay.kwikibot.wikitext.internal.Writer
import com.fenakhay.kwikibot.wikitext.internal.templateKey

/**
 * One piece of parsed wikitext.
 *
 * Every node writes itself back exactly as it was read, insignificant whitespace included: `{{ col | en }}`
 * keeps its spaces. That is what lets a bot change one template parameter and leave the rest of the page
 * untouched, so the diff shows the intended change rather than a reformatting of the whole entry.
 */
public sealed interface Node {

    /** This node as wikitext, byte for byte as it was parsed. */
    public fun serialize(): String
}

/** Literal text with no markup meaning. */
public data class TextNode(
    /** The text itself, unescaped and unmodified. */
    val text: String
) : Node {
    override fun serialize(): String = text
}

/**
 * `<!-- … -->`
 *
 * @param contents what sits between the markers, without them.
 * @param closed whether the comment ends with `-->`. One that does not runs to the end of the page, which is
 *   how MediaWiki reads it: everything after an unclosed `<!--` is hidden.
 */
public data class Comment(val contents: String, val closed: Boolean = true) : Node {

    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(contents: String) : this(contents, closed = true)

    override fun serialize(): String = if (closed) "<!--$contents-->" else "<!--$contents"
}

/**
 * One parameter of a template.
 *
 * @param name the parameter name; for a positional parameter this is the number it was given, which is not
 *   written out.
 * @param value what it was given.
 * @param showKey whether the parameter was written as `name=value` rather than positionally.
 */
public data class Parameter(
    /** The name as written, which for a positional parameter is its number. */
    val name: Markup,
    /** The value as written, markup and all. */
    val value: Markup,
    val showKey: Boolean,
) {
    /**
     * The parameter's name as MediaWiki reads it, comments removed and whitespace trimmed, except that a
     * nested template is kept as written where MediaWiki would expand it. For a positional parameter, its
     * number.
     */
    val key: String
        get() = name.withoutComments().trim()

    /** The name as written, or `null` for a positional parameter, which has none written. */
    val rawKey: String?
        get() = if (showKey) name.serialize() else null

    /** The value as written, whitespace, comments and markup included. */
    val rawValue: String
        get() = value.serialize()

    /** This parameter as wikitext, positional or named as it was written. */
    public fun serialize(): String = Writer.write(this)
}

/** `{{name|params}}` */
public data class Template(
    /** The name as written, which may itself contain markup. */
    val name: Markup,
    /** Its parameters, in the order written. */
    val parameters: List<Parameter> = emptyList(),
) : Node {

    /**
     * The name as MediaWiki reads it before expanding anything: comments removed, whitespace trimmed, the
     * first letter as written. A template inside the name is kept, so `{{ {{lang}}-noun }}` is
     * `{{lang}}-noun` rather than `-noun`. [key] is the name normalised for comparing.
     */
    val title: String
        get() = name.withoutComments().trim()

    /** The name as written, comments and whitespace included. */
    val rawName: String
        get() = name.serialize()

    /**
     * The page this template transcludes, normalised the way MediaWiki normalises it, under
     * [TitleRules.DEFAULT]; `null` for a parser function, a variable, or a name that is itself built from a
     * template. See [key] with rules for what is done to the name.
     */
    val key: String?
        get() = key(TitleRules.DEFAULT)

    /**
     * The page this template transcludes, normalised under [rules], or `null` when it transcludes none.
     *
     * `subst:`, `safesubst:` and the other modifiers are dropped, spaces and underscores collapse to one
     * space, and the first letter is upper-cased unless [rules] say the namespace keeps case. A template in
     * the Template namespace is keyed by its name alone, `{{Template:Foo}}` and `{{foo}}` both as `Foo`; one
     * elsewhere keeps its prefix, and one in the main namespace, `{{:Foo}}`, keeps its colon so it never
     * collides with the template of the same name.
     */
    public fun key(rules: TitleRules): String? = templateKey(this, rules)

    /** Whether this is a parser function or a variable, such as `{{#if:…}}` or `{{PAGENAME}}`. */
    val isParserFunction: Boolean
        get() = isParserFunction(TitleRules.DEFAULT)

    /**
     * Whether this is a parser function or a variable under [rules]. A variable's name with parameters, such
     * as `{{PAGENAME|x}}`, is neither: MediaWiki transcludes the page of that name.
     */
    public fun isParserFunction(rules: TitleRules): Boolean =
        rules.isParserFunction(title, hasArguments = parameters.isNotEmpty())

    override fun serialize(): String = Writer.write(this)

    /** The parameter called [key], or `null` if the template does not have one. */
    public fun parameter(key: String): Parameter? = parameters.lastOrNull { it.key == key }

    /**
     * The value of parameter [key] as visible text, or `null`.
     *
     * Visible text drops templates and comments, which is what a reader sees and what most comparisons want;
     * [rawValue] is the value as written.
     */
    public fun value(key: String): String? = parameter(key)?.value?.text?.trim()

    /** The value of parameter [key] as written, or `null`. */
    public fun rawValue(key: String): String? = parameter(key)?.rawValue

    /** Whether the template has a parameter called [key]. */
    public operator fun contains(key: String): Boolean = parameter(key) != null

    /**
     * This template with [key] set to [value].
     *
     * Replaces the parameter in place when it exists, keeping its position, its `showKey` form and the
     * whitespace around its old value — so editing one parameter of `{{ col | en | title=Terms }}` does not
     * quietly reformat the template. Appends the parameter otherwise. Returns a new template: nodes are
     * values.
     *
     * A key that is a number produces a positional parameter, matching how templates are usually written.
     *
     * [value] is wikitext, and is written so that it stays one parameter when the wiki reads it back. A `|`
     * that would split the template there, one inside an external link or plain text, is written `{{!}}`,
     * which the wiki turns back into a `|` after splitting. A positional value whose `=` would make it a
     * named one is written with its position as the name, `2=a=b`.
     *
     * @throws IllegalArgumentException if [value] opens or closes a template or link it does not also close
     *   or open, which would change the template it is put into rather than one of its parameters.
     */
    public fun withParameter(key: String, value: String): Template {
        val existing = parameter(key)
        val safe = ParameterValue.of(value)
        val positional = existing?.let { !it.showKey } ?: (key.toIntOrNull() != null)
        val named = !positional || safe.splitsAtEquals
        val replacement =
            Parameter(
                name = existing?.name?.takeIf { existing.showKey == named } ?: Markup.of(key),
                value = Markup.of(existing?.value?.spacedLike(safe.text) ?: safe.text),
                showKey = named,
            )
        return if (existing == null) {
            copy(parameters = parameters + replacement)
        } else {
            copy(parameters = parameters.map { if (it == existing) replacement else it })
        }
    }

    /** [value] wrapped in the whitespace this wikicode had around its own content. */
    private fun Markup.spacedLike(value: String): String {
        val old = serialize()
        val lead = old.takeWhile { it.isWhitespace() }
        // A value that was nothing but whitespace has no inside to take padding from.
        if (lead.length == old.length) return value
        val trail = old.takeLastWhile { it.isWhitespace() }
        return lead + value + trail
    }

    /** This template without the parameter called [key]. */
    public fun withoutParameter(key: String): Template =
        copy(parameters = parameters.filterNot { it.key == key })
}

/** `{{{name|default}}}` */
public data class Argument(
    /** The argument name, as written between the braces. */
    val name: Markup,
    /** What to use when the argument is not supplied, if the markup names one. */
    val default: Markup? = null,
) : Node {
    override fun serialize(): String = Writer.write(this)
}

/** `[[target|text]]` */
public data class WikiLink(
    /** Where the link points, before the pipe. */
    val target: Markup,
    /** What is shown instead of the target, when the link is piped. */
    val text: Markup? = null,
) : Node {

    /** The link target as plain text. */
    val title: String
        get() = target.text.trim()

    override fun serialize(): String = Writer.write(this)
}

/**
 * `[url title]`, or a bare URL in running text.
 *
 * @param url the URL itself, which may hold a template its expansion completes.
 * @param title the label after the URL, when the link has one.
 * @param brackets whether it was bracketed. A bare URL in running text was not.
 * @param separator what was written between the URL and the label: usually one space, but any run of spaces,
 *   a full-width one included, or nothing at all when the URL ends at a character no URL may hold.
 */
public data class ExternalLink(
    val url: Markup,
    val title: Markup? = null,
    val brackets: Boolean = true,
    val separator: String = " ",
) : Node {

    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        url: Markup,
        title: Markup? = null,
        brackets: Boolean = true,
    ) : this(url, title, brackets, " ")

    override fun serialize(): String = Writer.write(this)
}

/** `== Heading ==` */
public data class Heading(
    /** The heading text between the equals signs. */
    val title: Markup,
    /** How many equals signs on each side: 2 for a top-level section. */
    val level: Int,
) : Node {
    override fun serialize(): String = Writer.write(this)
}

/**
 * `&amp;`, `&#65;` or `&#x41;`.
 *
 * @param value the entity body: a name, or the digits of a numeric entity.
 * @param numeric whether it was written as a number rather than as a name.
 * @param hexChar the `x` or `X` as written, so the entity round-trips in its own case.
 */
public data class HtmlEntity(
    val value: String,
    val numeric: Boolean = false,
    val hexChar: String? = null,
) : Node {
    override fun serialize(): String = Writer.write(this)
}

/** One attribute of a tag, with the whitespace that surrounded it. */
public data class Attribute(
    /** The attribute name as written. */
    val name: Markup,
    /** Its value, absent for a bare attribute such as `nowrap`. */
    val value: Markup? = null,
    /** The quote character used, or `null` where the value was unquoted. */
    val quote: String? = null,
    /** The whitespace before the name, kept so the tag rebuilds byte for byte. */
    val padFirst: String = " ",
    /** The whitespace before the equals sign. */
    val padBeforeEq: String = "",
    /** The whitespace after the equals sign. */
    val padAfterEq: String = "",
) {
    /** This attribute as it was written, whitespace and quoting included. */
    public fun serialize(): String = Writer.write(this)
}

/**
 * A tag: `<ref>…</ref>`, `<br />`, or the wiki markup that stands in for one.
 *
 * @param name the tag name as written; MediaWiki compares it ignoring case.
 * @param contents what sits between the opening and closing tags, absent when there is nothing or the tag is
 *   self-closing.
 * @param attributes its attributes, in the order written.
 * @param selfClosing whether it was written as `<br />` rather than as a pair.
 * @param wikiMarkup the markup that produced the tag when it was not written as HTML — `'''` for bold, `*`
 *   for a list item. Present means the tag must be written back as that markup, not as `<b>`.
 * @param padding the whitespace before the closing `>`, kept so the tag rebuilds exactly.
 * @param implicitClose whether the closing tag was absent: a `<br>`, or an opening tag MediaWiki ends
 *   somewhere other than at a closing tag, such as a `<span>` at a paragraph break, or that nothing in its
 *   segment closes.
 * @param closing the closing tag as written when it differs from `</name>`, such as `</i >` or `</REF>`. It
 *   is written back only while it still names the tag, so renaming the tag rewrites it.
 * @param verbatim whether [contents] is the body as written rather than parsed wikitext, which is how the
 *   body of `<nowiki>`, `<pre>`, `<math>` and most other extension tags is kept.
 */
public data class Tag(
    val name: String,
    val contents: Markup? = null,
    val attributes: List<Attribute> = emptyList(),
    val selfClosing: Boolean = false,
    val wikiMarkup: String? = null,
    val padding: String = "",
    val implicitClose: Boolean = false,
    val closing: String? = null,
    val verbatim: Boolean = false,
) : Node {

    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        name: String,
        contents: Markup? = null,
        attributes: List<Attribute> = emptyList(),
        selfClosing: Boolean = false,
        wikiMarkup: String? = null,
        padding: String = "",
        implicitClose: Boolean = false,
    ) : this(name, contents, attributes, selfClosing, wikiMarkup, padding, implicitClose, null, false)

    /** The closing tag to write: as written while it still names this tag, `</name>` otherwise. */
    internal val closingTag: String
        get() = closing?.takeIf { namesThisTag(it) } ?: "</$name>"

    private fun namesThisTag(closer: String): Boolean {
        val after = 2 + name.length
        if (closer.length < after + 1 || !closer.startsWith("</")) return false
        if (!closer.regionMatches(2, name, 0, name.length, ignoreCase = true)) return false
        val next = closer[after]
        return next == '>' || next == '/' || next.isWhitespace()
    }

    override fun serialize(): String = Writer.write(this)
}
