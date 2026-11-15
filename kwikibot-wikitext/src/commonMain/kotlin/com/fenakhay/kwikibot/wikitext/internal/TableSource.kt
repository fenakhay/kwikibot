package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink

/**
 * One list of nodes as MediaWiki's table pass, `Parser::handleTables`, reads it: the text it is written as,
 * and the characters the pass sees in it.
 *
 * The pass runs after templates are expanded and comments removed, and before links and HTML are read. So a
 * comment is nothing here, `{{!}}` is a `|` and `{{!!}}` is `||`, a list marker the parser made a tag of is
 * its character again, and every other node is one character that is neither whitespace nor markup, with a
 * link marked as one, since a link before a cell's first `|` means the cell has no attributes.
 *
 * Each character the pass sees is kept with the part of [text] it came from, so what the pass decides about
 * characters can be cut out of the nodes: [startOf] and [endOf] give where a character starts and ends.
 *
 * @param nodes the nodes, in order.
 * @param lineStart whether the first node starts a line, which it does at the start of a page or of an
 *   extension tag's body, and not just after an HTML tag such as `<div>`.
 */
internal class TableSource(val nodes: List<Node>, val lineStart: Boolean) {

    /** Where each node starts in [text], and its length last. */
    val starts = IntArray(nodes.size + 1)

    /** The nodes written out. */
    val text: String

    /** Whether each node starts a line as the pass reads it: nothing but whitespace before it on its line. */
    val atLineStart = BooleanArray(nodes.size)

    private val chars: CharArray
    private val from: IntArray
    private val to: IntArray
    private val kinds: ByteArray

    /** How many characters the pass sees. */
    var length = 0
        private set

    init {
        val out = StringBuilder()
        for ((index, node) in nodes.withIndex()) {
            starts[index] = out.length
            Writer.append(out, node, null)
        }
        starts[nodes.size] = out.length
        text = out.toString()
        chars = CharArray(text.length)
        from = IntArray(text.length)
        to = IntArray(text.length)
        kinds = ByteArray(text.length)
        read()
    }

    private fun read() {
        var onLine = lineStart
        for ((index, node) in nodes.withIndex()) {
            atLineStart[index] = onLine
            val start = starts[index]
            val end = starts[index + 1]
            val before = length
            val marker = (node as? Tag)?.listMarker
            when {
                node is TextNode ->
                    node.text.forEachIndexed { at, char -> add(char, start + at, start + at + 1) }
                node is Comment -> Unit
                node.isPipe -> add(PIPE, start, end)
                node.isDoublePipe -> {
                    add(PIPE, start, end)
                    add(PIPE, end, end)
                }
                marker != null -> add(marker, start, end)
                node is WikiLink -> add(OPAQUE, start, end, LINK)
                else -> add(OPAQUE, start, end, OTHER)
            }
            for (at in before until length) {
                onLine = if (chars[at] == '\n') true else onLine && isTrimmed(chars[at])
            }
        }
    }

    private fun add(char: Char, start: Int, end: Int, kind: Byte = TEXT) {
        chars[length] = char
        from[length] = start
        to[length] = end
        kinds[length] = kind
        length++
    }

    /** The character the pass sees at [index]. */
    operator fun get(index: Int): Char = chars[index]

    /** Whether the character at [index] is written as text, not a node standing for one. */
    fun isText(index: Int): Boolean = kinds[index] == TEXT

    /** Whether the character at [index] is a link. */
    fun isLink(index: Int): Boolean = kinds[index] == LINK

    /** Where the character at [index] starts in [text]; the end of [text] past the last one. */
    fun startOf(index: Int): Int = if (index < length) from[index] else text.length

    /** Where the character at [index] ends in [text]; the start of [text] before the first one. */
    fun endOf(index: Int): Int = if (index >= 0) to[index] else 0

    /** Where the line starting at character [index] starts in [text]: just past the line break before it. */
    fun lineStartOf(index: Int): Int = endOf(index - 1)

    /** Where the line starting at [from] ends: at its line break, or at the end. */
    fun lineEnd(from: Int): Int {
        var at = from
        while (at < length && chars[at] != '\n') at++
        return at
    }

    /** The index of the first node that ends past [offset] in [text]. */
    fun nodeAt(offset: Int): Int {
        var low = 0
        var high = nodes.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle + 1] <= offset) low = middle + 1 else high = middle
        }
        return low
    }

    /**
     * The nodes written from [start] until [end] in [text], a text node cut where either falls inside one,
     * every other node the same instance.
     */
    fun slice(start: Int, end: Int): List<Node> = ArrayList<Node>().also { sliceInto(it, null, start, end) }

    private fun sliceInto(out: MutableList<Node>, kept: MutableList<Boolean>?, start: Int, end: Int) {
        if (start >= end) return
        var index = nodeAt(start)
        while (index < nodes.size && starts[index] < end) {
            val node = nodes[index]
            val nodeStart = starts[index]
            val nodeEnd = starts[index + 1]
            val whole = (start <= nodeStart && nodeEnd <= end) || node !is TextNode
            out +=
                if (whole) node
                else
                    TextNode(
                        node.text.substring(
                            maxOf(start, nodeStart) - nodeStart,
                            minOf(end, nodeEnd) - nodeStart,
                        )
                    )
            kept?.add(whole)
            index++
        }
    }

    /**
     * What is written from [start] until [end], less comments and the whitespace around it; `""` before 0.
     */
    fun attributes(start: Int, end: Int): String =
        if (start < 0) ""
        else slice(start, end).filterNot { it is Comment }.joinToString("") { it.serialize() }.trim()

    /**
     * These nodes with each of [edits] made: what is written from its start until its end replaced by its
     * nodes. The edits are in order and do not overlap.
     *
     * Every node outside the edits keeps its identity. A text node an edit cuts is joined to the text of the
     * edit next to it, so no two text nodes end up side by side unless one of them was left as it was.
     */
    fun splice(edits: List<Splice>): List<Node> {
        val out = ArrayList<Node>()
        val kept = ArrayList<Boolean>()
        var at = 0
        for (edit in edits) {
            sliceInto(out, kept, at, edit.start)
            for (node in edit.nodes) {
                out += node
                kept += false
            }
            at = edit.end
        }
        sliceInto(out, kept, at, text.length)
        return joined(out, kept)
    }

    private fun joined(nodes: List<Node>, kept: List<Boolean>): List<Node> {
        val out = ArrayList<Node>(nodes.size)
        var lastKept = true
        for ((index, node) in nodes.withIndex()) {
            val last = out.lastOrNull()
            when {
                node is TextNode && !kept[index] && node.text.isEmpty() -> Unit
                node is TextNode && last is TextNode && !kept[index] && !lastKept -> {
                    out[out.lastIndex] = TextNode(last.text + node.text)
                }
                else -> {
                    out += node
                    lastKept = kept[index]
                }
            }
        }
        return out
    }

    companion object {
        /** What the pass sees for a node other than text. */
        const val OPAQUE = '\uFFFC'
        const val PIPE = '|'

        private const val TEXT: Byte = 0
        private const val LINK: Byte = 1
        private const val OTHER: Byte = 2

        /** The characters PHP's `trim` removes, other than the line feed, which ends a line here. */
        fun isTrimmed(char: Char): Boolean =
            char == ' ' || char == '\t' || char == '\r' || char == '\u0000' || char == '\u000B'
    }
}

/** Whether this is `{{!}}`, which MediaWiki expands to `|` before it reads tables. */
internal val Node.isPipe: Boolean
    get() = this is Template && parameters.isEmpty() && title == "!"

/** Whether this is `{{!!}}`, which stands for `||` wherever a wiki has the template. */
internal val Node.isDoublePipe: Boolean
    get() = this is Template && parameters.isEmpty() && title == "!!"

/** The character of a list marker the parser read as a tag, `:` for `<dd>`, or `null` for any other tag. */
private val Tag.listMarker: Char?
    get() {
        val markup = wikiMarkup ?: return null
        return if (selfClosing && markup.length == 1 && markup[0] in LIST_MARKERS) markup[0] else null
    }

private const val LIST_MARKERS = ":*#;"

/** One edit to a [TableSource]: what is written from [start] until [end] replaced by [nodes]. */
internal class Splice(val start: Int, val end: Int, val nodes: List<Node>)
