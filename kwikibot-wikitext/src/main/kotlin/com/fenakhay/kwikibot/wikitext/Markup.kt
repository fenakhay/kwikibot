package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.internal.Builder
import com.fenakhay.kwikibot.wikitext.internal.TagNames
import com.fenakhay.kwikibot.wikitext.internal.Tokenizer
import com.fenakhay.kwikibot.wikitext.internal.Writer
import com.fenakhay.kwikibot.wikitext.internal.sectionHeadings
import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Attribute
import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.HtmlEntity
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Parameter
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink
import java.util.IdentityHashMap

/**
 * Parsed wikitext: a sequence of nodes that can be queried, edited and written back.
 *
 * Immutable. Every edit returns a new `Markup`, and every node that was not touched keeps the exact text it
 * was parsed from — so a bot that changes one template parameter produces a diff of one template parameter,
 * not of the whole page.
 *
 * ```
 * val code = Wikitext.parse(page.text)
 * val updated = code.mapTemplates("col") { it.withParameter("2", "hypervolcano") }
 * updated.serialize()
 * ```
 */
public class Markup private constructor(nodes: List<Node>, owned: Boolean) {

    /**
     * The nodes, in source order.
     *
     * A list passed in from outside is copied; one the parser hands over is used as it is.
     */
    public val nodes: List<Node> = if (owned) nodes else nodes.toList()

    /** Takes a copy of [nodes]. */
    public constructor(nodes: List<Node>) : this(nodes, owned = false)

    /** This wikitext, byte for byte as it was parsed. */
    public fun serialize(): String = Writer.write(this)

    /**
     * Where each node, parameter, attribute and piece of markup in this wikitext sits in [serialize]'s
     * output.
     *
     * Worked out in one pass over the tree when asked, and kept nowhere else: nodes carry no offsets, so two
     * equal nodes stay equal wherever they were parsed from. Lookups are by identity, so ask with the node a
     * query returned.
     */
    public fun ranges(): SourceRanges {
        val found = IdentityHashMap<Any, IntRange>()
        Writer.append(StringBuilder(), this) { item, start, end -> found[item] = start until end }
        return SourceRanges(found)
    }

    /**
     * The visible text, with markup removed.
     *
     * Templates and comments contribute nothing, since neither is text a reader sees; a wikilink contributes
     * its display text. Use this for comparing content, never for writing back.
     */
    public val text: String
        get() =
            nodes.joinToString("") { node ->
                when (node) {
                    is TextNode -> node.text
                    is WikiLink -> (node.text ?: node.target).text
                    is ExternalLink -> node.title?.text.orEmpty()
                    is Heading -> node.title.text
                    is Tag -> node.contents?.text.orEmpty()
                    is HtmlEntity -> node.serialize()
                    is Template,
                    is Argument,
                    is Comment -> ""
                }
            }

    /**
     * This wikitext as written, less its comments and `<noinclude>`-style markers: what MediaWiki compares a
     * name or a key by, since its preprocessor drops both before anything reads the name.
     */
    internal fun withoutComments(): String = buildString { appendWithoutComments(this@Markup, this) }

    private fun appendWithoutComments(markup: Markup, out: StringBuilder) {
        for (node in markup.nodes) {
            when {
                node is Comment -> Unit
                node is Tag && node.wikiMarkup == null && node.name in TagNames.TRANSCLUSION ->
                    node.contents?.let { appendWithoutComments(it, out) }
                else -> Writer.append(out, node, null)
            }
        }
    }

    /** Every node in the tree, including those nested inside templates, links and tags. */
    public fun allNodes(): Sequence<Node> = sequence {
        for (node in nodes) {
            yield(node)
            yieldAll(node.children().flatMap { it.allNodes() })
        }
    }

    /**
     * The templates in this wikitext, including nested ones.
     *
     * @param name when given, only templates with this name, compared ignoring the case of the first letter
     *   and treating spaces and underscores alike. For the comparison MediaWiki makes (namespaces, `subst:`,
     *   wikis whose templates keep case), pass names and [TitleRules] to the other overload.
     */
    public fun templates(name: String? = null): List<Template> =
        allNodes()
            .filterIsInstance<Template>()
            .filter { name == null || it.title.matchesTemplateName(name) }
            .toList()

    /**
     * The templates in this wikitext, nested ones included, that transclude one of [names] under [rules].
     *
     * Each name is read as a page (`l`, `Template:l` and `{{subst:l}}` all name `Template:L` unless the rules
     * keep case), so a bot can pass a template's name together with the names of every redirect to it and
     * find each way it is written. A name such as `PAGENAME` finds `{{PAGENAME|x}}`, which transcludes that
     * page, and not the variable `{{PAGENAME}}`.
     */
    public fun templates(names: Collection<String>, rules: TitleRules = TitleRules.DEFAULT): List<Template> {
        val keys = names.mapNotNullTo(HashSet()) { rules.key(it, hasArguments = true) }
        return allNodes().filterIsInstance<Template>().filter { it.key(rules) in keys }.toList()
    }

    /** The wikilinks in this wikitext, including nested ones. */
    public fun wikilinks(): List<WikiLink> = allNodes().filterIsInstance<WikiLink>().toList()

    /**
     * The headings that open sections: those at the top of this wikitext, and those inside an HTML tag there,
     * such as a `<div>`, which MediaWiki also counts as sections.
     *
     * A heading inside a template parameter, a link or a `<ref>` is still a heading, and is among [allNodes],
     * but it does not divide the page into sections.
     */
    public fun headings(): List<Heading> = nodes.sectionHeadings()

    /** The tags in this wikitext, including nested ones. */
    public fun tags(name: String? = null): List<Tag> =
        allNodes()
            .filterIsInstance<Tag>()
            .filter { name == null || it.name.equals(name, ignoreCase = true) }
            .toList()

    /** The comments in this wikitext. */
    public fun comments(): List<Comment> = allNodes().filterIsInstance<Comment>().toList()

    /**
     * This wikitext with every occurrence of [target] replaced by [replacement].
     *
     * Nested occurrences are replaced too, so a template inside a link can be edited without the caller
     * having to find and rebuild the link.
     */
    public fun replace(target: Node, replacement: Node): Markup =
        Markup(nodes.map { it.rewrite(target, replacement) })

    /** This wikitext with [target] removed. */
    public fun remove(target: Node): Markup =
        Markup(nodes.filterNot { it == target }.map { it.rewrite(target, null) })

    /**
     * This wikitext with every template named [name] passed through [transform].
     *
     * The common shape of a bot edit: find the templates that matter, change them, leave everything else
     * exactly as it was.
     */
    public fun mapTemplates(name: String? = null, transform: (Template) -> Template): Markup =
        mapEach(templates(name), transform)

    /**
     * This wikitext with every template transcluding one of [names] under [rules] passed through [transform].
     */
    public fun mapTemplates(
        names: Collection<String>,
        rules: TitleRules = TitleRules.DEFAULT,
        transform: (Template) -> Template,
    ): Markup = mapEach(templates(names, rules), transform)

    private fun mapEach(templates: List<Template>, transform: (Template) -> Template): Markup {
        var result = this
        for (template in templates) {
            val updated = transform(template)
            if (updated != template) result = result.replace(template, updated)
        }
        return result
    }

    /** This wikitext followed by [other]. */
    public operator fun plus(other: Markup): Markup = Markup(nodes + other.nodes)

    override fun toString(): String = serialize()

    override fun equals(other: Any?): Boolean = other is Markup && other.nodes == nodes

    override fun hashCode(): Int = nodes.hashCode()

    /** Building wikitext from nodes, and the empty document. */
    public companion object {
        /** Markup holding one piece of literal text. */
        public fun of(text: String): Markup = Markup(listOf(TextNode(text)))

        /** Empty wikicode. */
        public val EMPTY: Markup = Markup(emptyList())

        /**
         * Markup over [nodes] without copying it.
         *
         * The caller must not keep the list or change it after this returns.
         */
        internal fun owning(nodes: List<Node>): Markup = Markup(nodes, owned = true)
    }
}

/**
 * Where the pieces of a parsed page sit in its text, from [Markup.ranges].
 *
 * Every range is half-open, `start until end`, and is looked up by identity: ask about the node a query on
 * the same [Markup] returned, not an equal one built elsewhere.
 */
public class SourceRanges internal constructor(private val ranges: IdentityHashMap<Any, IntRange>) {

    /** Where [node] was written, or `null` if it is not part of the markup these ranges came from. */
    public operator fun get(node: Node): IntRange? = ranges[node]

    /** Where [parameter] was written, its name and `=` included for a named one. */
    public operator fun get(parameter: Parameter): IntRange? = ranges[parameter]

    /** Where [attribute] was written, the whitespace before it included. */
    public operator fun get(attribute: Attribute): IntRange? = ranges[attribute]

    /** Where [markup], such as a parameter's value, was written. */
    public operator fun get(markup: Markup): IntRange? = ranges[markup]
}

/** A parse that would not write back the text it was given, from [Wikitext.parseExact]. */
public class WikitextRoundTripException(
    /** The first offset where the text written back differs from the text parsed. */
    public val offset: Int,
    message: String,
) : IllegalStateException(message)

/** Parses and serializes wikitext. */
public object Wikitext {

    /**
     * Parses [wikitext] into an editable tree, the way Wikimedia's wikis read it.
     *
     * Total: no input is rejected. Markup that does not parse — an unclosed template, a stray bracket — stays
     * literal text, which is what MediaWiki renders.
     */
    public fun parse(wikitext: String): Markup = parse(wikitext, ParseOptions.DEFAULT)

    /** Parses [wikitext] the way a wiki configured with [options] reads it. */
    public fun parse(wikitext: String, options: ParseOptions): Markup =
        Markup.owning(Builder(Tokenizer(options).tokenize(wikitext)).build())

    /**
     * Parses [wikitext], and checks that writing the tree back gives the same text.
     *
     * Every parse is meant to round-trip, and the parser is tested for it against a corpus of real pages and
     * a fuzzer. This checks one page, for a bot about to save an edit built on the tree.
     *
     * @throws WikitextRoundTripException naming the first offset that would change.
     */
    public fun parseExact(wikitext: String, options: ParseOptions = ParseOptions.DEFAULT): Markup {
        val markup = parse(wikitext, options)
        val written = markup.serialize()
        if (written != wikitext) {
            val offset =
                written
                    .zip(wikitext)
                    .indexOfFirst { (a, b) -> a != b }
                    .let {
                        if (it < 0) minOf(written.length, wikitext.length) else it
                    }
            throw WikitextRoundTripException(
                offset,
                "the parsed page would not write back as it was, from $offset",
            )
        }
        return markup
    }

    /** Whether [wikitext] parses into a tree that writes back unchanged. */
    public fun roundTrips(wikitext: String, options: ParseOptions = ParseOptions.DEFAULT): Boolean =
        parse(wikitext, options).serialize() == wikitext
}

/** The wikicode nested directly inside a node. */
internal fun Node.children(): List<Markup> =
    when (this) {
        is Template -> listOf(name) + parameters.flatMap { listOf(it.name, it.value) }
        is Argument -> listOfNotNull(name, default)
        is WikiLink -> listOfNotNull(target, text)
        is ExternalLink -> listOfNotNull(url, title)
        is Heading -> listOf(title)
        is Tag -> listOfNotNull(contents) + attributes.flatMap { listOfNotNull(it.name, it.value) }
        is TextNode,
        is Comment,
        is HtmlEntity -> emptyList()
    }

/**
 * This node with [target] replaced by [replacement] wherever it appears inside it.
 *
 * Returns the node unchanged when it contains no occurrence, so untouched subtrees keep their identity and
 * their exact text.
 */
private fun Node.rewrite(target: Node, replacement: Node?): Node {
    if (this == target) return replacement ?: TextNode("")

    return when (this) {
        is Template ->
            copy(
                name = name.rewrite(target, replacement),
                parameters =
                    parameters.map {
                        it.copy(
                            name = it.name.rewrite(target, replacement),
                            value = it.value.rewrite(target, replacement),
                        )
                    },
            )

        is Argument ->
            copy(
                name = name.rewrite(target, replacement),
                default = default?.rewrite(target, replacement),
            )

        is WikiLink ->
            copy(
                target = this.target.rewrite(target, replacement),
                text = text?.rewrite(target, replacement),
            )

        is ExternalLink ->
            copy(
                url = url.rewrite(target, replacement),
                title = title?.rewrite(target, replacement),
            )

        is Heading -> copy(title = title.rewrite(target, replacement))

        is Tag ->
            copy(
                contents = contents?.rewrite(target, replacement),
                attributes =
                    attributes.map {
                        it.copy(
                            name = it.name.rewrite(target, replacement),
                            value = it.value?.rewrite(target, replacement),
                        )
                    },
            )

        is TextNode,
        is Comment,
        is HtmlEntity -> this
    }
}

private fun Markup.rewrite(target: Node, replacement: Node?): Markup {
    if (target !in allNodes()) return this
    val rewritten = nodes.mapNotNull { node ->
        if (node == target) replacement else node.rewrite(target, replacement)
    }
    return Markup(rewritten)
}

private operator fun Sequence<Node>.contains(target: Node): Boolean = any { it == target }

/**
 * Whether two template names refer to the same template.
 *
 * MediaWiki ignores the case of the first letter and treats underscores as spaces, so `{{col}}`, `{{Col}}`
 * and `{{c ol}}` are not all the same but the first two are.
 */
private fun String.matchesTemplateName(other: String): Boolean {
    val left = trim().replace('_', ' ')
    val right = other.trim().replace('_', ' ')
    return left.equals(right, ignoreCase = false) ||
        left.replaceFirstChar { it.uppercaseChar() } == right.replaceFirstChar { it.uppercaseChar() }
}
