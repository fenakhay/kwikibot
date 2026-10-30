package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.TextNode

/**
 * These nodes with every HTML tag that holds a section heading opened up, so the heading is among them.
 *
 * MediaWiki's sections are its preprocessor's headings, and HTML does not enclose them: a heading inside a
 * `<div>` still divides the page, and a section edit takes the text from one heading to the next whatever
 * tags it cuts through. So such a tag becomes its opening tag, its contents and its closing tag, side by
 * side, which is how the parser would read each side of the cut on its own. The nodes still write back as the
 * same text. Templates, links, arguments and extension tags are never opened: a heading inside one is no
 * section.
 */
internal fun List<Node>.sectionNodes(): List<Node> {
    if (none { it is Tag && it.holdsSection() }) return this
    return SectionOpening(this).run()
}

/**
 * The section headings among these nodes, those inside an HTML tag included, in the order they are written.
 */
internal fun List<Node>.sectionHeadings(): List<Heading> =
    ArrayList<Heading>().also { collectHeadings(this, it, LEVELS_ON_STACK) }

/** Collects the headings among [nodes] by recursion for [onStack] levels of tags, and with a stack below. */
private fun collectHeadings(nodes: List<Node>, into: MutableList<Heading>, onStack: Int) {
    for (node in nodes) {
        val wrapped = node.wrapped()
        when {
            node is Heading -> into += node
            wrapped == null -> Unit
            onStack > 0 -> collectHeadings(wrapped, into, onStack - 1)
            else -> collectHeadingsBelow(wrapped, into)
        }
    }
}

private fun collectHeadingsBelow(nodes: List<Node>, into: MutableList<Heading>) {
    val pending = NodeStack().apply { push(nodes) }
    while (true) {
        val node = pending.next() ?: return
        if (node is Heading) into += node else node.wrapped()?.let { pending.push(it) }
    }
}

/** Whether a section heading sits in this tag, in it or in a tag it wraps. */
private fun Tag.holdsSection(): Boolean {
    val wrapped = wrapped() ?: return false
    if (wrapped.any { it is Heading }) return true
    if (wrapped.none { it.wrapped() != null }) return false
    val pending = NodeStack().apply { push(wrapped) }
    var node = pending.next()
    while (node != null && node !is Heading) {
        node.wrapped()?.let { pending.push(it) }
        node = pending.next()
    }
    return node != null
}

/** What this node wraps that a section heading can sit in, if it is a tag that can hold one. */
private fun Node.wrapped(): List<Node>? = if (this is Tag && isWrapper()) contents?.nodes else null

/**
 * The nodes of a page with every tag that holds a section heading opened up, in one pass.
 *
 * Each tag that could hold a heading is opened as it is reached, its contents added after it. When it ends,
 * it either held a heading, and its opening tag and closing tag stand on either side of what it held, or it
 * did not, and what was added for it is taken back and the tag added whole. Each node is added once and taken
 * back at most once, so a page costs the same however deeply its tags nest.
 */
private class SectionOpening(private val top: List<Node>) {
    private val opened = ArrayList<Node>(top.size + OPENED_EXTRA)
    private val tags = ArrayList<Tag>()
    private val lists = ArrayList<List<Node>>()
    private var positions = IntArray(INITIAL_DEPTH)
    private var starts = IntArray(INITIAL_DEPTH)
    private var headings = BooleanArray(INITIAL_DEPTH)

    fun run(): List<Node> {
        lists += top
        positions[0] = 0
        while (lists.isNotEmpty()) {
            val level = lists.lastIndex
            val nodes = lists[level]
            if (positions[level] == nodes.size) {
                close(level)
                continue
            }
            val node = nodes[positions[level]++]
            if (node is Heading && level > 0) headings[level] = true
            val inner = node.wrapped()
            if (inner == null) opened += node else open(node as Tag, inner)
        }
        return opened
    }

    private fun open(tag: Tag, inner: List<Node>) {
        val level = lists.size
        if (level == positions.size) {
            positions = positions.copyOf(level * 2)
            starts = starts.copyOf(level * 2)
            headings = headings.copyOf(level * 2)
        }
        tags += tag
        lists += inner
        positions[level] = 0
        starts[level] = opened.size
        headings[level] = false
        opened += tag
    }

    private fun close(level: Int) {
        lists.removeAt(level)
        if (level == 0) return
        val tag = tags.removeAt(tags.lastIndex)
        val start = starts[level]
        if (headings[level]) {
            opened[start] =
                tag.copy(contents = null, selfClosing = true, implicitClose = true, closing = null)
            opened += TextNode(tag.closingTag)
            if (level > 1) headings[level - 1] = true
        } else {
            opened.subList(start + 1, opened.size).clear()
        }
    }

    private companion object {
        const val INITIAL_DEPTH = 8
    }
}

/** Whether this is an HTML or transclusion tag with a body, which a section heading can sit inside. */
private fun Tag.isWrapper(): Boolean =
    contents != null && wikiMarkup == null && !verbatim && SECTION_WRAPPERS.contains(name, 0, name.length)

/**
 * The tags a section heading can sit inside: the HTML MediaWiki allows, less `pre`, which is an extension tag
 * there, and the transclusion wrappers whose contents a page shows. `<includeonly>` is not among them: its
 * contents are not part of the page MediaWiki numbers sections in.
 */
private val SECTION_WRAPPERS = NameSet(ParseOptions.HTML_TAGS - "pre" + setOf("noinclude", "onlyinclude"))

private const val OPENED_EXTRA = 8
