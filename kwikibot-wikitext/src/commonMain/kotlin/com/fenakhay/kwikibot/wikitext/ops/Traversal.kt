package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.internal.Rebuild
import com.fenakhay.kwikibot.wikitext.internal.TagNames
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag

/**
 * The page with every node rewritten by [transform], depth first: each node is given to [transform] with the
 * nodes inside it already rewritten.
 *
 * The traversal is here rather than on [Markup] because it is what a tidying pass needs and nothing else
 * does: an edit that knows which node it wants uses `replace`.
 *
 * It does not descend into a tag whose contents MediaWiki does not parse, one the parser marked
 * [Tag.verbatim]. What is inside `<nowiki>`, `<pre>` or `<syntaxhighlight>` is content: decoding an entity
 * there changes what the page displays, and stripping whitespace there alters preformatted content. `<code>`
 * is left alone for the same reason, though MediaWiki parses it.
 *
 * A node nothing inside of which changed is given to [transform] as it was, and whatever [transform] gives
 * back unchanged stays the same instance, as does the markup around it.
 */
public fun Markup.mapNodes(transform: (Node) -> Node): Markup =
    object : Rebuild() {
            override fun enters(node: Node): Boolean =
                node !is Tag || !(TagNames.isRaw(node) || node.name.equals(CODE, ignoreCase = true))

            override fun rebuilt(original: Node, node: Node): Node = transform(node)
        }
        .rebuild(this)

/** `<code>` is parsed by MediaWiki, but its contents are code to display, not prose to tidy. */
private const val CODE = "code"
