package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.Markup
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
 * How many levels of nodes a pass over a tree takes by recursion before it goes on with a stack of its own:
 * as deep as real pages go, so that they are passed over the fastest way, and few enough for any thread's
 * stack.
 */
internal const val LEVELS_ON_STACK = 32

/** Whether nothing is nested in this node: text, a comment or an entity. */
internal val Node.isLeaf: Boolean
    get() = this is TextNode || this is Comment || this is HtmlEntity

/** Whether two nodes are of the same class. */
internal fun sameKind(node: Node, other: Node): Boolean =
    when (node) {
        is TextNode -> other is TextNode
        is Comment -> other is Comment
        is HtmlEntity -> other is HtmlEntity
        is Template -> other is Template
        is Argument -> other is Argument
        is WikiLink -> other is WikiLink
        is ExternalLink -> other is ExternalLink
        is Heading -> other is Heading
        is Tag -> other is Tag
    }

/** How many places for nested wikicode this node has, each read with [slot]. */
internal val Node.slots: Int
    get() =
        when (this) {
            is Template -> 1 + 2 * parameters.size
            is Tag -> 1 + 2 * attributes.size
            is Argument,
            is WikiLink,
            is ExternalLink -> 2
            is Heading -> 1
            is TextNode,
            is Comment,
            is HtmlEntity -> 0
        }

/**
 * The wikicode in place [index] of this node, or `null` where that place is empty: a template's name, then
 * each parameter's name and value; a tag's contents, then each attribute's name and value; for the rest,
 * their parts in the order they are written.
 */
internal fun Node.slot(index: Int): Markup? = if (index == 0) firstSlot else laterSlot(index)

/** The wikicode nested directly inside this node, in the order of its [slots]. */
internal fun Node.children(): List<Markup> = (0 until slots).mapNotNull { slot(it) }

/** The wikicode in this node's first place. */
private val Node.firstSlot: Markup?
    get() =
        when (this) {
            is Template -> name
            is Tag -> contents
            is Argument -> name
            is WikiLink -> target
            is ExternalLink -> url
            is Heading -> title
            is TextNode,
            is Comment,
            is HtmlEntity -> null
        }

/** The wikicode in this node's place [index], past the first. */
private fun Node.laterSlot(index: Int): Markup? =
    when (this) {
        is Template -> parameters[(index - 1) / 2].let { if (index % 2 == 1) it.name else it.value }
        is Tag -> attributes[(index - 1) / 2].let { if (index % 2 == 1) it.name else it.value }
        is Argument -> default
        is WikiLink -> text
        is ExternalLink -> title
        is Heading,
        is TextNode,
        is Comment,
        is HtmlEntity -> null
    }
