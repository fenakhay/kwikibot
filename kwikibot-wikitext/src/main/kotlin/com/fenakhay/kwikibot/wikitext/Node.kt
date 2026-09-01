package com.fenakhay.kwikibot.wikitext

/**
 * One piece of parsed wikitext.
 *
 * Every node writes itself back exactly as it was read, insignificant whitespace included:
 * `{{ col | en }}` keeps its spaces. That is what lets a bot change one template parameter and
 * leave the rest of the page untouched, so the diff shows the intended change rather than a
 * reformatting of the whole entry.
 */
public sealed interface Node {

    /** This node as wikitext, byte for byte as it was parsed. */
    public fun serialize(): String
}

/** Literal text with no markup meaning. */
public data class TextNode(
    /** The text itself, unescaped and unmodified. */
    val text: String,
) : Node {
    override fun serialize(): String = text
}

/** `<!-- … -->` */
public data class Comment(
    /** What sits between the markers, without them. */
    val contents: String,
) : Node {
    override fun serialize(): String = "<!--$contents-->"
}

/**
 * One parameter of a template.
 *
 * @param name the parameter name; for a positional parameter this is the number it was given,
 *   which is not written out.
 * @param value what it was given.
 * @param showKey whether the parameter was written as `name=value` rather than
 *   positionally.
 */
public data class Parameter(
    /** The name as written, which for a positional parameter is its number. */
    val name: Markup,
    /** The value as written, markup and all. */
    val value: Markup,
    val showKey: Boolean,
) {
    /** The parameter name as plain text, trimmed — what a caller means by "the second one". */
    val key: String get() = name.text.trim()

    /** This parameter as wikitext, positional or named as it was written. */
    public fun serialize(): String =
        if (showKey) "${name.serialize()}=${value.serialize()}" else value.serialize()
}

/** `{{name|params}}` */
public data class Template(
    /** The name as written, which may itself contain markup. */
    val name: Markup,
    /** Its parameters, in the order written. */
    val parameters: List<Parameter> = emptyList(),
) : Node {

    /** The template name as plain text, trimmed and with the first letter left as written. */
    val title: String get() = name.text.trim()

    override fun serialize(): String = buildString {
        append("{{").append(name.serialize())
        parameters.forEach { append('|').append(it.serialize()) }
        append("}}")
    }

    /** The parameter called [key], or `null` if the template does not have one. */
    public fun parameter(key: String): Parameter? = parameters.lastOrNull { it.key == key }

    /** The value of parameter [key] as plain text, or `null`. */
    public fun value(key: String): String? = parameter(key)?.value?.text?.trim()

    /** Whether the template has a parameter called [key]. */
    public operator fun contains(key: String): Boolean = parameter(key) != null

    /**
     * This template with [key] set to [value].
     *
     * Replaces the parameter in place when it exists, keeping its position, its `showKey` form
     * and the whitespace around its old value — so editing one parameter of
     * `{{ col | en | title=Terms }}` does not quietly reformat the template. Appends the
     * parameter otherwise. Returns a new template: nodes are values.
     *
     * A key that is a number produces a positional parameter, matching how templates are
     * usually written.
     */
    public fun withParameter(key: String, value: String): Template {
        val existing = parameter(key)
        val replacement = Parameter(
            name = existing?.name ?: Markup.of(key),
            value = Markup.of(existing?.value?.spacedLike(value) ?: value),
            showKey = existing?.showKey ?: (key.toIntOrNull() == null),
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
    override fun serialize(): String = buildString {
        append("{{{").append(name.serialize())
        default?.let { append('|').append(it.serialize()) }
        append("}}}")
    }
}

/** `[[target|text]]` */
public data class WikiLink(
    /** Where the link points, before the pipe. */
    val target: Markup,
    /** What is shown instead of the target, when the link is piped. */
    val text: Markup? = null,
) : Node {

    /** The link target as plain text. */
    val title: String get() = target.text.trim()

    override fun serialize(): String = buildString {
        append("[[").append(target.serialize())
        text?.let { append('|').append(it.serialize()) }
        append("]]")
    }
}

/** `[url title]`, or a bare URL in running text. */
public data class ExternalLink(
    /** The URL itself. */
    val url: Markup,
    /** The label after the URL, when the link has one. */
    val title: Markup? = null,
    /** Whether it was bracketed. A bare URL in running text was not. */
    val brackets: Boolean = true,
) : Node {
    override fun serialize(): String = buildString {
        if (!brackets) {
            append(url.serialize())
            return@buildString
        }
        append('[').append(url.serialize())
        title?.let { append(' ').append(it.serialize()) }
        append(']')
    }
}

/** `== Heading ==` */
public data class Heading(
    /** The heading text between the equals signs. */
    val title: Markup,
    /** How many equals signs on each side: 2 for a top-level section. */
    val level: Int,
) : Node {
    override fun serialize(): String {
        val marker = "=".repeat(level)
        return "$marker${title.serialize()}$marker"
    }
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
    override fun serialize(): String = buildString {
        append('&')
        if (numeric) append('#')
        hexChar?.let { append(it) }
        append(value).append(';')
    }
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
    public fun serialize(): String = buildString {
        append(padFirst).append(name.serialize())
        if (value == null) return@buildString
        append(padBeforeEq).append('=').append(padAfterEq)
        quote?.let { append(it) }
        append(value.serialize())
        quote?.let { append(it) }
    }
}

/**
 * A tag: `<ref>…</ref>`, `<br />`, or the wiki markup that stands in for one.
 *
 * @param name the tag name, lowercased as MediaWiki treats it.
 * @param contents what sits between the opening and closing tags, absent when there is
 *   nothing or the tag is self-closing.
 * @param attributes its attributes, in the order written.
 * @param selfClosing whether it was written as `<br />` rather than as a pair.
 * @param wikiMarkup the markup that produced the tag when it was not written as HTML —
 *   `'''` for bold, `*` for a list item. Present means the tag must be written back as
 *   that markup, not as `<b>`.
 * @param padding the whitespace before the closing `>`, kept so the tag rebuilds exactly.
 * @param implicitClose whether the closing tag was absent and MediaWiki inferred it.
 */
public data class Tag(
    val name: String,
    val contents: Markup? = null,
    val attributes: List<Attribute> = emptyList(),
    val selfClosing: Boolean = false,
    val wikiMarkup: String? = null,
    val padding: String = "",
    val implicitClose: Boolean = false,
) : Node {

    override fun serialize(): String = if (wikiMarkup != null) serializeWikiMarkup() else serializeHtml()

    private fun serializeWikiMarkup(): String =
        if (selfClosing) wikiMarkup.orEmpty() else "$wikiMarkup${contents?.serialize().orEmpty()}$wikiMarkup"

    private fun serializeHtml(): String = buildString {
        append('<').append(name)
        attributes.forEach { append(it.serialize()) }
        append(padding)

        if (selfClosing) {
            // "<br>" and "<br/>" are both self-closing, and a bot must not turn one into the
            // other, so which was written is remembered rather than inferred from the name.
            append(if (implicitClose) ">" else "/>")
            return@buildString
        }

        append('>').append(contents?.serialize().orEmpty())
        append("</").append(name).append('>')
    }
}
