package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.TextScope
import com.fenakhay.kwikibot.wikitext.internal.NodeStack
import com.fenakhay.kwikibot.wikitext.internal.Rebuild
import com.fenakhay.kwikibot.wikitext.internal.TagNames
import com.fenakhay.kwikibot.wikitext.internal.children
import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink

/**
 * Replaces text matching [pattern], leaving markup alone.
 *
 * The replacement runs only on text nodes [scope] allows, so a rename that should not touch template
 * parameters or the inside of a `<nowiki>` block does not, without anyone writing a regex to describe where
 * those are.
 *
 * ```
 * code.replaceText(Regex("colour"), "color")            // prose only
 * code.replaceText(Regex("colour"), "color", TextScope.EVERYWHERE)
 * ```
 */
public fun Markup.replaceText(
    pattern: Regex,
    replacement: String,
    scope: TextScope = TextScope.PROSE,
): Markup = replaceText(pattern, scope) { replacement }

/** Replaces text matching [pattern], computing each replacement from the match. */
public fun Markup.replaceText(
    pattern: Regex,
    scope: TextScope = TextScope.PROSE,
    replacement: (MatchResult) -> String,
): Markup = TextReplacement(pattern, scope, replacement).rebuild(this)

/** Whether any text this [scope] allows matches [pattern]. */
public fun Markup.containsText(pattern: Regex, scope: TextScope = TextScope.PROSE): Boolean =
    textIn(scope).any { pattern.containsMatchIn(it) }

/** The text this [scope] allows, node by node. */
public fun Markup.textIn(scope: TextScope): List<String> = buildList {
    val pending = NodeStack().apply { push(nodes) }
    while (true) {
        when (val node = pending.next() ?: break) {
            is TextNode -> add(node.text)
            is Comment -> if (scope.comments) add(node.contents)
            else -> node.scoped(scope).asReversed().forEach { pending.push(it.nodes) }
        }
    }
}

/**
 * The text [scope] allows replaced, node by node, in the order it is written, and nothing else touched.
 *
 * A link's target, a template, a heading, an attribute and a comment are text to replace only when [scope]
 * says so, and the inside of a raw tag only when it says so too. An external link's URL never is.
 */
private class TextReplacement(
    private val pattern: Regex,
    private val scope: TextScope,
    private val replacement: (MatchResult) -> String,
) : Rebuild(chooses = true) {

    override fun enters(node: Node): Boolean =
        when (node) {
            is Template -> scope.templates
            is Heading -> scope.headings
            is Tag -> !node.isRaw || scope.rawTags
            else -> true
        }

    /** Rewriting a link's target silently repoints the link at a different page, so it is opt-in. */
    override fun visits(node: Node, slot: Int): Boolean =
        when (node) {
            is WikiLink -> slot > 0 || scope.linkTargets
            is ExternalLink -> slot > 0
            is Tag -> slot == 0 || scope.tagAttributes
            is Argument -> slot > 0 || scope.templates
            else -> true
        }

    override fun rebuilt(original: Node, node: Node): Node =
        when (node) {
            is TextNode -> replaced(node.text)?.let { TextNode(it) } ?: node
            is Comment ->
                if (scope.comments) replaced(node.contents)?.let { node.copy(contents = it) } ?: node
                else node
            else -> node
        }

    /** [text] with [pattern] replaced, or `null` when that changes nothing. */
    private fun replaced(text: String): String? = pattern.replace(text, replacement).takeIf { it != text }
}

/** The wikicode inside this node that [scope] allows reading. */
private fun Node.scoped(scope: TextScope): List<Markup> =
    when (this) {
        is Template -> if (scope.templates) children() else emptyList()
        is WikiLink -> listOfNotNull(text, target.takeIf { scope.linkTargets })
        is Heading -> if (scope.headings) listOf(title) else emptyList()
        is Tag -> scopedInTag(scope)
        is Comment -> if (scope.comments) listOf(Markup.of(contents)) else emptyList()
        is ExternalLink -> listOfNotNull(title)
        is Argument -> listOfNotNull(name.takeIf { scope.templates }, default)
        else -> children()
    }

/** The wikicode inside this tag, its attributes included, that [scope] allows reading. */
private fun Tag.scopedInTag(scope: TextScope): List<Markup> =
    when {
        isRaw && !scope.rawTags -> emptyList()
        scope.tagAttributes -> children()
        else -> listOfNotNull(contents)
    }

/**
 * Whether this tag's contents are not prose for a text operation, as the parser found or a caller built it.
 */
private val Tag.isRaw: Boolean
    get() = TagNames.isRaw(this)
