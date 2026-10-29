package com.fenakhay.kwikibot.bot.fix

import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink
import com.fenakhay.kwikibot.wikitext.ops.mapNodes

/**
 * Removes links to one page, keeping the words that were linked.
 *
 * Run before a page is deleted, so the wiki is not left with red links. `[[volcano]]` becomes `volcano` and
 * `[[volcano|volcanoes]]` becomes `volcanoes`: the rendered text is unchanged, only whether it is a link.
 *
 * Done on the parsed page rather than with a regex, which matters more here than almost anywhere: a regex for
 * `[[volcano]]` also matches inside `[[File:volcano.jpg|thumb|A [[volcano]]]]`, and unlinking there rewrites
 * a caption from the outside in.
 *
 * ```
 * val cleaned = Unlinker(wiki.namespaces).unlink(Wikitext.parse(page.text), target)
 * ```
 */
public class Unlinker(
    /**
     * Whether the wiki capitalises the first letter of a title.
     *
     * `$wgCapitalLinks`, and true nearly everywhere except Wiktionary. It decides whether `[[Volcano]]` and
     * `[[volcano]]` are the same link, and getting it wrong on a Wiktionary unlinks the wrong entry.
     */
    private val capitalLinks: Boolean = true
) {

    /**
     * The page with every link to [target] replaced by the words it was showing.
     *
     * Every link, at any depth: one inside a file caption or a template parameter is still a link, and
     * leaving it behind would delete the page while the red link stayed.
     */
    public fun unlink(code: Markup, target: Title.Local): Markup = code.mapNodes { node ->
        unlinkIn(node, target)
    }

    /** The wikitext with every link to [target] unlinked. */
    public fun unlink(wikitext: String, target: Title.Local): String =
        unlink(Wikitext.parse(wikitext), target).serialize()

    /** Whether the page links to [target] at all, which decides whether an edit is needed. */
    public fun linksTo(code: Markup, target: Title.Local): Boolean =
        code.wikilinks().any { matches(it, target) }

    /** Whether the wikitext links to [target]. */
    public fun linksTo(wikitext: String, target: Title.Local): Boolean =
        linksTo(Wikitext.parse(wikitext), target)

    private fun unlinkIn(node: Node, target: Title.Local): Node =
        when {
            node is WikiLink && matches(node, target) -> TextNode(node.displayText())
            else -> node
        }

    /**
     * Whether a link points at [target].
     *
     * Titles are compared the way MediaWiki compares them: underscores are spaces, surrounding whitespace
     * does not count, and the first letter is case-insensitive where the wiki capitalises it. A section
     * anchor is ignored — `[[volcano#English]]` is still a link to `volcano`.
     */
    private fun matches(link: WikiLink, target: Title.Local): Boolean {
        val linked = normalise(link.target.text.substringBefore('#'))
        val wanted = normalise(target.text)

        if (linked == wanted) return true

        // Only the first letter is case-insensitive, and only where the wiki capitalises it.
        // The rest of the title is case-sensitive on every wiki.
        return capitalLinks && capitalised(linked) == capitalised(wanted)
    }

    private fun normalise(raw: String): String = raw.trim().replace('_', ' ')

    private fun capitalised(title: String): String = title.replaceFirstChar { it.uppercaseChar() }

    /**
     * The words the link was showing.
     *
     * A piped link shows its text; a plain one shows its target. `[[volcano|]]` — the pipe trick — is written
     * by an editor and expanded by MediaWiki, so the empty text means the target.
     */
    private fun WikiLink.displayText(): String {
        val shown = text?.serialize()?.takeIf { it.isNotEmpty() }
        return shown ?: target.serialize()
    }
}
