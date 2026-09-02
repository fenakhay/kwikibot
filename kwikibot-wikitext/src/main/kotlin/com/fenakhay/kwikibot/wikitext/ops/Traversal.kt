package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.WikiLink

/**
 * The page with every node rewritten by [transform], depth first.
 *
 * The traversal is here rather than on [Markup] because it is what a tidying pass needs and nothing else
 * does: an edit that knows which node it wants uses `replace`.
 *
 * It does not descend into a tag whose contents MediaWiki does not parse. What is inside `<nowiki>`, `<pre>`
 * or `<syntaxhighlight>` is content: decoding an entity there changes what the page displays, and stripping
 * whitespace there alters preformatted content.
 */
public fun Markup.mapNodes(transform: (Node) -> Node): Markup =
    Markup(nodes.map { transform(it.mapChildren(transform)) })

/** Tags whose contents are text to display, not wikitext to rewrite. */
private val RAW_TAGS = setOf("nowiki", "pre", "syntaxhighlight", "source", "code", "math", "score")

private fun Node.mapChildren(transform: (Node) -> Node): Node =
    when (this) {
        is Template ->
            copy(
                name = name.mapNodes(transform),
                parameters =
                    parameters.map {
                        it.copy(name = it.name.mapNodes(transform), value = it.value.mapNodes(transform))
                    },
            )

        is WikiLink -> copy(target = target.mapNodes(transform), text = text?.mapNodes(transform))
        is ExternalLink -> copy(title = title?.mapNodes(transform))
        is Heading -> copy(title = title.mapNodes(transform))
        is Tag -> if (name.lowercase() in RAW_TAGS) this else copy(contents = contents?.mapNodes(transform))
        is Argument -> copy(name = name.mapNodes(transform), default = default?.mapNodes(transform))
        else -> this
    }
