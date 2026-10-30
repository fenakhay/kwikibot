package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Attribute
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
 * Two trees compared level by level, with the pairs of wikicode still to compare kept on a stack.
 *
 * The answer is the one the nodes' own `equals` gives: each node is compared field by field, its wikicode
 * last. The wikicode is compared by recursion for the first [LEVELS_ON_STACK] levels, as deep as real pages
 * go and the fastest way through them; below that it goes on the stack, which is made only then.
 *
 * A field added to a node class has to be compared here. A JVM test holds each class's fields to that.
 */
internal class TreeComparison {
    private var pending: ArrayList<Markup>? = null

    /** How many more levels are compared by recursion before pairs go on the stack. */
    private var onStack = LEVELS_ON_STACK

    fun same(first: Markup, second: Markup): Boolean {
        if (first.hasHash && second.hasHash && first.hashCode() != second.hashCode()) return false
        var left = first
        var right = second
        while (true) {
            if (!sameNodes(left.nodes, right.nodes)) return false
            val stack = pending
            if (stack.isNullOrEmpty()) return true
            right = stack.removeAt(stack.lastIndex)
            left = stack.removeAt(stack.lastIndex)
        }
    }

    private fun sameNodes(nodes: List<Node>, others: List<Node>): Boolean {
        if (nodes.size != others.size) return false
        for (index in nodes.indices) if (!sameNode(nodes[index], others[index])) return false
        return true
    }

    private fun sameNode(node: Node, other: Node): Boolean =
        node === other ||
            when (node) {
                is TextNode -> other is TextNode && node.text == other.text
                is Template -> sameTemplate(node, other)
                is Tag -> sameTag(node, other)
                is WikiLink -> sameLink(node, other)
                is Argument -> sameArgument(node, other)
                is ExternalLink -> sameExternalLink(node, other)
                is Heading -> sameHeading(node, other)
                is Comment,
                is HtmlEntity -> node == other
            }

    private fun sameTemplate(template: Template, other: Node): Boolean =
        other is Template && defer(template.name, other.name) && sameParameters(template, other)

    private fun sameLink(link: WikiLink, other: Node): Boolean =
        other is WikiLink && defer(link.target, other.target) && deferOrAbsent(link.text, other.text)

    private fun sameArgument(argument: Argument, other: Node): Boolean =
        other is Argument &&
            defer(argument.name, other.name) &&
            deferOrAbsent(argument.default, other.default)

    private fun sameHeading(heading: Heading, other: Node): Boolean =
        other is Heading && heading.level == other.level && defer(heading.title, other.title)

    private fun sameParameters(template: Template, other: Template): Boolean {
        val parameters = template.parameters
        val others = other.parameters
        if (parameters.size != others.size) return false
        for (index in parameters.indices) {
            val parameter = parameters[index]
            val otherParameter = others[index]
            val same =
                parameter === otherParameter ||
                    (parameter.showKey == otherParameter.showKey &&
                        defer(parameter.name, otherParameter.name) &&
                        defer(parameter.value, otherParameter.value))
            if (!same) return false
        }
        return true
    }

    private fun sameExternalLink(link: ExternalLink, other: Node): Boolean =
        other is ExternalLink &&
            link.brackets == other.brackets &&
            link.separator == other.separator &&
            defer(link.url, other.url) &&
            deferOrAbsent(link.title, other.title)

    private fun sameTag(tag: Tag, other: Node): Boolean =
        other is Tag &&
            tag.name == other.name &&
            tag.selfClosing == other.selfClosing &&
            tag.wikiMarkup == other.wikiMarkup &&
            tag.padding == other.padding &&
            tag.implicitClose == other.implicitClose &&
            tag.closing == other.closing &&
            tag.verbatim == other.verbatim &&
            deferOrAbsent(tag.contents, other.contents) &&
            sameAttributes(tag.attributes, other.attributes)

    private fun sameAttributes(attributes: List<Attribute>, others: List<Attribute>): Boolean {
        if (attributes.size != others.size) return false
        for (index in attributes.indices) if (!sameAttribute(attributes[index], others[index])) return false
        return true
    }

    private fun sameAttribute(attribute: Attribute, other: Attribute): Boolean =
        attribute === other ||
            (attribute.quote == other.quote &&
                attribute.padFirst == other.padFirst &&
                attribute.padBeforeEq == other.padBeforeEq &&
                attribute.padAfterEq == other.padAfterEq &&
                defer(attribute.name, other.name) &&
                deferOrAbsent(attribute.value, other.value))

    private fun deferOrAbsent(markup: Markup?, other: Markup?): Boolean =
        if (markup == null || other == null) markup === other else defer(markup, other)

    /**
     * Whether [markup] and [other] can still be the same: settled here when they are the same instance or
     * have different hashes already known, compared by recursion while fewer than [LEVELS_ON_STACK] levels
     * are, and put on the stack to compare later otherwise.
     */
    private fun defer(markup: Markup, other: Markup): Boolean {
        if (markup === other) return true
        if (markup.hasHash && other.hasHash && markup.hashCode() != other.hashCode()) return false
        if (onStack > 0) {
            onStack--
            val same = sameNodes(markup.nodes, other.nodes)
            onStack++
            return same
        }
        val stack = pending ?: ArrayList<Markup>().also { pending = it }
        stack += markup
        stack += other
        return true
    }
}
