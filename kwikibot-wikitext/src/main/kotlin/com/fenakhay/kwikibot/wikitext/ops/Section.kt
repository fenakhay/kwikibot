package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.Node

/**
 * A page's headings and what sits under each of them.
 *
 * The shape most wiki work has: find `==English==`, then `===Noun===` inside it, then put something in the
 * right place. A section owns the content directly beneath its heading and the subsections nested under it,
 * so moving or replacing one carries its children with it.
 *
 * @param heading the heading that opened this section; `null` only for the lead, which is everything before
 *   the first heading.
 * @param nodes the content directly under the heading, before any subsection.
 * @param subsections the sections nested under this one.
 */
public data class Section(
    val heading: Heading?,
    val nodes: List<Node>,
    /** The sections nested under this one. */
    val subsections: List<Section> = emptyList(),
) {
    /** The heading level, or 0 for the lead. */
    val level: Int
        get() = heading?.level ?: 0

    /** The heading text, trimmed, or `null` for the lead. */
    val title: String?
        get() = heading?.title?.text?.trim()

    /** This section as wikitext, byte for byte as it was parsed. */
    public fun serialize(): String = buildString {
        heading?.let { append(it.serialize()) }
        nodes.forEach { append(it.serialize()) }
        subsections.forEach { append(it.serialize()) }
    }

    /** The content directly under this heading, as wikicode. */
    public val content: Markup
        get() = Markup(nodes)

    /** This section and every section nested inside it, depth first. */
    public fun all(): Sequence<Section> = sequence {
        yield(this@Section)
        subsections.forEach { yieldAll(it.all()) }
    }

    /**
     * The first section with this heading, at any depth.
     *
     * Headings are compared trimmed, since `== English ==` and `==English==` are the same section to a reader
     * and to MediaWiki.
     *
     * @param title the heading to find, compared trimmed.
     * @param level when given, only a heading at this level matches — the way to tell a language section from
     *   a part-of-speech section that happens to share a name.
     */
    public fun find(title: String, level: Int? = null): Section? =
        all().firstOrNull { it.title == title.trim() && (level == null || it.level == level) }

    /** Every direct child section with this heading. */
    public fun children(title: String): List<Section> = subsections.filter { it.title == title.trim() }

    /** This section with its direct content replaced. */
    public fun withContent(content: Markup): Section = copy(nodes = content.nodes)

    /** This section with [subsection] added at the end. */
    public fun withSubsection(subsection: Section): Section = copy(subsections = subsections + subsection)

    /**
     * This section with [subsection] added at [index] among its direct children.
     *
     * Wikis order sections by convention rather than by rule — an entry layout, an infobox before the lead,
     * references last — so a bot that adds a section usually knows where it has to go and not just that it
     * has to exist. [withSubsection] appends, which is only correct when the new section sorts last.
     *
     * @param index where among the existing children it goes; [subsections].size appends.
     * @param subsection the section to add.
     * @throws IndexOutOfBoundsException if [index] is outside the existing children.
     */
    public fun withSubsectionAt(index: Int, subsection: Section): Section {
        if (index !in 0..subsections.size) {
            throw IndexOutOfBoundsException(
                "index $index is outside the ${subsections.size} subsections of ${title ?: "the lead"}"
            )
        }

        return copy(subsections = subsections.toMutableList().apply { add(index, subsection) })
    }

    /** This section with [target] replaced by [replacement] wherever it is nested. */
    public fun replace(target: Section, replacement: Section): Section =
        when {
            this == target -> replacement
            else -> copy(subsections = subsections.map { it.replace(target, replacement) })
        }
}

/**
 * The page as a tree of sections.
 *
 * The root is the lead — everything before the first heading — with the top-level sections beneath it. A
 * heading deeper than the one before it opens a subsection; one at the same level or shallower closes as many
 * as it needs to.
 */
public fun Markup.outline(): Section {
    val root = SectionBuilder(null)
    var stack = listOf(root)

    for (node in nodes) {
        if (node !is Heading) {
            stack.last().nodes += node
            continue
        }

        // Close every section this heading is not nested inside.
        while (stack.size > 1 && stack.last().level >= node.level) {
            val finished = stack.last()
            stack = stack.dropLast(1)
            stack.last().subsections += finished.build()
        }

        stack = stack + SectionBuilder(node)
    }

    while (stack.size > 1) {
        val finished = stack.last()
        stack = stack.dropLast(1)
        stack.last().subsections += finished.build()
    }

    return root.build()
}

/** Replaces a section anywhere in the page, keeping everything else exactly as it was. */
public fun Markup.replaceSection(target: Section, replacement: Section): Markup {
    val updated = outline().replace(target, replacement)
    return Wikitext.parse(updated.serialize())
}

/** Accumulates a section while the outline is being walked. */
private class SectionBuilder(private val heading: Heading?) {
    val nodes = mutableListOf<Node>()
    val subsections = mutableListOf<Section>()

    val level: Int
        get() = heading?.level ?: 0

    fun build(): Section = Section(heading, nodes.toList(), subsections.toList())
}
