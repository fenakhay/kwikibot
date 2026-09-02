package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.internal.Builder
import com.fenakhay.kwikibot.wikitext.internal.Tokenizer
import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.HtmlEntity
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink

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
public class Markup(nodes: List<Node>) {

    /** The nodes, in source order. */
    public val nodes: List<Node> = nodes.toList()

    /** This wikitext, byte for byte as it was parsed. */
    public fun serialize(): String = nodes.joinToString("") { it.serialize() }

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
     *   and treating spaces and underscores alike — the way MediaWiki compares them.
     */
    public fun templates(name: String? = null): List<Template> =
        allNodes()
            .filterIsInstance<Template>()
            .filter { name == null || it.title.matchesTemplateName(name) }
            .toList()

    /** The wikilinks in this wikitext, including nested ones. */
    public fun wikilinks(): List<WikiLink> = allNodes().filterIsInstance<WikiLink>().toList()

    /** The headings in this wikitext. */
    public fun headings(): List<Heading> = allNodes().filterIsInstance<Heading>().toList()

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
    public fun mapTemplates(name: String? = null, transform: (Template) -> Template): Markup {
        var result = this
        for (template in templates(name)) {
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
    }
}

/** Parses and serializes wikitext. */
public object Wikitext {

    /**
     * Parses [wikitext] into an editable tree.
     *
     * Total: no input is rejected. Markup that does not parse — an unclosed template, a stray bracket — stays
     * literal text, which is what MediaWiki renders.
     */
    public fun parse(wikitext: String): Markup = Markup(Builder(Tokenizer().tokenize(wikitext)).build())
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

        is Tag -> copy(contents = contents?.rewrite(target, replacement))

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
