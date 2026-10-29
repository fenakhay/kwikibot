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
    val opened = ArrayList<Node>(size + OPENED_EXTRA)
    for (node in this) opened.addOpened(node)
    return opened
}

/**
 * The section headings among these nodes, those inside an HTML tag included, in the order they are written.
 */
internal fun List<Node>.sectionHeadings(): List<Heading> =
    ArrayList<Heading>().also { collectHeadings(this, it) }

private fun collectHeadings(nodes: List<Node>, into: MutableList<Heading>) {
    for (node in nodes) {
        if (node is Heading) {
            into += node
        } else if (node is Tag && node.isWrapper()) {
            node.contents?.let { collectHeadings(it.nodes, into) }
        }
    }
}

private fun MutableList<Node>.addOpened(node: Node) {
    if (node !is Tag || !node.holdsSection()) {
        add(node)
        return
    }
    add(node.copy(contents = null, selfClosing = true, implicitClose = true, closing = null))
    node.contents?.nodes?.forEach { addOpened(it) }
    add(TextNode(node.closingTag))
}

private fun Tag.holdsSection(): Boolean =
    isWrapper() && contents?.nodes?.any { it is Heading || (it is Tag && it.holdsSection()) } == true

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
