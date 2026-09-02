package com.fenakhay.kwikibot.examples.compounds

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink
import java.text.Normalizer

/**
 * The contents of a derived terms section, however they were written.
 *
 * A list may be a `{{col}}` template, a bullet list, or nothing at all. All three are read into the same
 * shape so the section can be re-emitted as a single column template.
 */
public data class Container(
    /** How the list was written, which decides how it is written back. */
    val kind: Kind,
    /** The terms in it, in the order they appeared. */
    val entries: List<Entry> = emptyList(),
    /** Template parameters that are not terms, kept so the template rebuilds. */
    val namedParams: List<Pair<String, String>> = emptyList(),
    /** The language code the template was given, when it carried one. */
    val lang: String? = null,
) {
    /** The forms a derived-terms list is written in. */
    public enum class Kind {
        /** A column template, which is what most entries use. */
        TEMPLATE,

        /** A plain bulleted list. */
        BULLETS,

        /** A section with no list in it yet. */
        EMPTY,
    }

    /** Whether the template says its order is deliberate and must not be disturbed. */
    public val sortingDisabled: Boolean
        get() = namedParams.any { (name, value) ->
            name.trim().lowercase() == "sort" && value.trim().lowercase() in SORT_OFF
        }

    private companion object {
        val SORT_OFF = setOf("0", "n", "no", "false")
    }
}

/**
 * One listed term.
 *
 * @param term what dedupe and sorting work on.
 * @param raw what gets written back, indent prefix and inline modifiers included, so a term carrying a gloss
 *   survives a re-sort unchanged.
 * @param indent the sublist depth: the number of asterisks in a `* ` prefix. An indented item is a child of
 *   the one above it and must travel with it when the list is re-sorted.
 */
public data class Entry(val term: String, val raw: String, val indent: Int = 0)

/** Reads and writes the term lists a derived terms section can contain. */
public object Containers {

    /** `{{col}}`, `{{col3}}`, `{{der4}}` and friends: all "language, then terms". */
    private val CONTAINER_NAME =
        Regex(
            """^(?:col|der|rel)[0-9]*$|^col-auto$|^coln$""",
            RegexOption.IGNORE_CASE,
        )

    private val LINK_TEMPLATES = setOf("l", "link", "m", "mention")

    /** Markup that makes a bare term slot something other than a bare term. */
    private val MARKUP = listOf("[[", "]]", "|", "=")

    /**
     * A `{{col}}` sublist indent: asterisks followed by a space.
     *
     * The space is load-bearing. `*duxota` with no space is a reconstructed term, not an indented one, which
     * [Template:col](https://en.wiktionary.org/wiki/Template:col) documents.
     */
    private val INDENT = Regex("""^(\*+)[ \t]+""")

    /** Named parameters whose effect on ordering cannot be reproduced, so it is not attempted. */
    private val UNSUPPORTED = setOf("keepfirst", "keeplast")

    /** Whether a template name is one of the column containers. */
    public fun isContainerName(name: String): Boolean = CONTAINER_NAME.matches(name.trim())

    /** `"** foo"` becomes `(2, "foo")`; `"*foo"` stays `(0, "*foo")`, a reconstructed term. */
    public fun splitIndent(text: String): Pair<Int, String> {
        val match = INDENT.matchAt(text, 0) ?: return 0 to text
        return match.groupValues[1].length to text.substring(match.range.last + 1)
    }

    /**
     * Sorting the way `Module:columns` does: diacritic-insensitive and case-insensitive, so `é` sorts with
     * `e` rather than after `z`.
     */
    public fun sortKey(term: String): Pair<String, String> {
        val folded = Normalizer.normalize(term.lowercase(), Normalizer.Form.NFKD)
        val stripped = folded.filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        return stripped to term
    }

    /**
     * Reads a derived terms body, or `null` when it is written in a form that cannot safely be rewritten — in
     * which case the page is left alone.
     */
    public fun read(body: String): Container? {
        val stripped = body.trim()
        if (stripped.isEmpty()) return Container(Container.Kind.EMPTY)

        val code = Wikitext.parse(stripped)
        val containers = code.templates().filter { isContainerName(it.title) }

        if (containers.isNotEmpty()) {
            // Anything alongside the template is content this cannot account for.
            if (containers.size != 1 || containers.single().serialize().trim() != stripped) return null
            return readTemplate(containers.single())
        }
        return readBullets(stripped)
    }

    private fun readTemplate(template: Template): Container? {
        val positional = template.parameters.filter { !it.showKey }
        val named = template.parameters.filter { it.showKey }.map { it.key to it.value.serialize().trim() }

        if (positional.isEmpty()) return null
        // keepfirst and keeplast pin rows out of the sort; reproducing that is not worth guessing.
        if (named.any { it.first.trim().lowercase() in UNSUPPORTED }) return null

        val entries = mutableListOf<Entry>()
        for (parameter in positional.drop(1)) {
            val value = parameter.value.serialize().trim()
            // An empty slot carries no term; normalizing drops it.
            if (value.isEmpty()) continue
            entries += entryFromParameter(value) ?: return null
        }

        return Container(
            kind = Container.Kind.TEMPLATE,
            entries = entries,
            namedParams = named,
            lang = positional.first().value.serialize().trim(),
        )
    }

    /**
     * Reads one positional slot of a container template.
     *
     * `null` means the slot holds something this cannot rewrite safely, which makes the whole section
     * unrecognised rather than partly understood.
     */
    private fun entryFromParameter(value: String): Entry? {
        val text = value.trim()
        if (text.isEmpty()) return null

        val (indent, body) = splitIndent(text)
        plainLinkTarget(body)?.let { term ->
            // A plain link is emitted unwrapped; the indent prefix is rebuilt around it.
            return Entry(term, if (indent > 0) "${"*".repeat(indent)} $term" else term, indent)
        }

        // A bare term, possibly carrying inline modifiers such as foo<t:gloss>. Anything with
        // markup in it is a shape this cannot rewrite, which makes the whole list unrecognised.
        if (MARKUP.any { it in body }) return null
        val base = TodoList.normalizeTitle(body.substringBefore('<'))
        return if (base.isEmpty()) null else Entry(base, text, indent)
    }

    private fun readBullets(body: String): Container? {
        val entries = mutableListOf<Entry>()

        for (line in body.lines()) {
            val stripped = line.trim()
            if (stripped.isEmpty()) continue
            // A nested bullet list is a shape this does not reproduce.
            if (!stripped.startsWith("*") || stripped.startsWith("**")) return null
            entries += entryFromBullet(stripped.drop(1)) ?: return null
        }

        return if (entries.isEmpty()) null else Container(Container.Kind.BULLETS, entries)
    }

    private fun entryFromBullet(item: String): Entry? {
        val text = item.trim()
        if (text.isEmpty()) return null

        val templates = Wikitext.parse(text).templates()
        if (templates.isNotEmpty()) return entryFromLinkTemplate(text, templates)

        val term = plainLinkTarget(text) ?: return null
        return Entry(term, term)
    }

    /** A bullet holding a single `{{l|en|term}}`, which is the other way lists are written. */
    private fun entryFromLinkTemplate(text: String, templates: List<Template>): Entry? {
        val template = templates.singleOrNull() ?: return null
        val positional = template.parameters.filter { !it.showKey }

        val usable =
            template.serialize().trim() == text && // nothing else on the line
                template.title.lowercase() in LINK_TEMPLATES &&
                template.parameters.none { it.showKey } &&
                positional.size == LINK_TEMPLATE_ARITY
        if (!usable) return null

        val term = TodoList.normalizeTitle(positional[1].value.serialize().trim())
        return if (term.isEmpty()) null else Entry(term, term)
    }

    /** The target of a bare `[[link]]`, or `null` if the text is anything else. */
    private fun plainLinkTarget(text: String): String? {
        val code = Wikitext.parse(text.trim())
        val link = code.nodes.singleOrNull() as? WikiLink ?: return null
        // A piped or anchored link carries display text this would silently drop.
        if (link.text != null || '#' in link.title) return null
        return TodoList.normalizeTitle(link.title).ifEmpty { null }
    }

    /**
     * Orders a `{{col}}` body the way `Module:columns` does.
     *
     * Each indented sublist is tethered to the item above it and sorts as one unit, and sublists sort
     * internally, so a child never drifts away from its parent. With `|sort=0` the order is left exactly as
     * given, which for a merge means existing terms keep their sequence and new ones land at the end.
     */
    public fun order(entries: List<Entry>, container: Container? = null): List<Entry> {
        if (container?.sortingDisabled == true) return entries

        val roots = mutableListOf<Node>()
        val stack = mutableListOf<Node>()

        for (entry in entries) {
            val node = Node(entry)
            while (stack.isNotEmpty() && stack.last().entry.indent >= entry.indent) stack.removeLast()
            // An indented item with nothing above it is malformed; it lands at the top level.
            (stack.lastOrNull()?.children ?: roots) += node
            stack += node
        }

        return buildList { flatten(sortNodes(roots), this) }
    }

    /** Emits a single `{{col|<lang>|…}}`, ordered, with named parameters preserved. */
    public fun render(lang: String, entries: List<Entry>, container: Container? = null): String =
        buildString {
            append("{{col|").append(lang)
            order(entries, container).forEach { append('|').append(it.raw) }
            container?.namedParams?.forEach { (name, value) ->
                append('|').append(name).append('=').append(value)
            }
            append("}}")
        }

    /** Every term a derived or related terms body already lists, however it is written. */
    public fun listedTerms(body: String): Set<String> {
        val found = mutableSetOf<String>()
        val code = Wikitext.parse(body)

        code.wikilinks().forEach { link ->
            TodoList.linkTarget(link.title).takeIf { it.isNotEmpty() }?.let { found += it }
        }

        for (template in code.templates()) {
            val name = template.title.lowercase()
            val positional = template.parameters.filter { !it.showKey }.map { it.value.serialize().trim() }

            val values =
                when {
                    isContainerName(name) -> positional.drop(1)
                    name in LINK_TEMPLATES -> positional.drop(1).take(1)
                    else -> continue
                }

            values.forEach { value ->
                val bare = TodoList.normalizeTitle(splitIndent(value).second.substringBefore('<'))
                if (bare.isNotEmpty()) found += bare
            }
        }

        return found
    }

    private class Node(val entry: Entry, val children: MutableList<Node> = mutableListOf())

    private fun sortNodes(nodes: List<Node>): List<Node> {
        nodes.forEach { node ->
            val sorted = sortNodes(node.children)
            node.children.clear()
            node.children += sorted
        }
        return nodes.sortedWith(
            compareBy({ sortKey(it.entry.term).first }, { sortKey(it.entry.term).second })
        )
    }

    private fun flatten(nodes: List<Node>, into: MutableList<Entry>) {
        nodes.forEach { node ->
            into += node.entry
            flatten(node.children, into)
        }
    }

    private const val LINK_TEMPLATE_ARITY = 2
}

/** The wikicode of a term list, for callers that want the nodes rather than the text. */
internal fun Container.toMarkup(lang: String): Markup = Wikitext.parse(Containers.render(lang, entries, this))

internal fun textOf(nodes: List<Node>): String =
    nodes.joinToString("") { (it as? TextNode)?.text ?: it.serialize() }
