package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.TextNode

/**
 * Where a table's nodes are in the page it was found in: the page, the tags that lead down to the list of
 * nodes holding it, by their places, and that list as the table pass reads it.
 *
 * An edit replaces part of that list and rebuilds the tags above it, so that everything else in the page is
 * the instance it was.
 */
internal class TableContext(val root: Markup, private val path: IntArray, val source: TableSource) {

    /** The context of the tables inside the tag at [index] in this one's nodes, whose body is [tag]'s. */
    fun inside(index: Int, tag: Tag, lineStart: Boolean): TableContext =
        TableContext(root, path + index, TableSource(tag.contents!!.nodes, lineStart))

    /** Where this context's nodes start in what [root] writes. */
    fun offset(): Int {
        if (path.isEmpty()) return 0
        var markup = root
        for (index in path) markup = (markup.nodes[index] as Tag).contents!!
        return root.ranges()[markup]!!.first
    }

    /** What is written from [start] until [end], as markup. */
    fun markup(start: Int, end: Int): Markup = Markup.owning(source.slice(start, end))

    /** What is written from [start] until [end], less comments and the whitespace around it. */
    fun attributes(start: Int, end: Int): String = source.attributes(start, end)

    /** The text from [start] until [end]. */
    fun text(start: Int, end: Int): String = source.text.substring(start, end)

    /**
     * The page with [edits] made, as long as the table that starts at [tableStart] reads back as [accept]
     * wants, given the list of nodes it is now in.
     *
     * @throws IllegalArgumentException with [reason] when it does not: the edit would have changed the table
     *   in a way it was not meant to.
     */
    fun edit(
        edits: List<Splice>,
        tableStart: Int,
        reason: () -> String,
        accept: (ScannedTable, TableSource) -> Boolean,
    ): Markup {
        val nodes = source.splice(edits)
        val changed = TableSource(nodes, source.lineStart)
        val found = find(TableScan(changed).tables(), tableStart)
        require(found != null && accept(found, changed)) { reason() }
        return rebuild(root, 0, nodes)
    }

    private fun find(tables: List<ScannedTable>, start: Int): ScannedTable? {
        for (table in tables) {
            if (table.start == start) return table
            val inner = table.captions + table.rows.flatMap { it.cells }
            val nested = inner.flatMap { it.nested } + table.loose
            if (table.start < start && start < table.end) return find(nested, start)
        }
        return null
    }

    private fun rebuild(markup: Markup, depth: Int, nodes: List<Node>): Markup {
        if (depth == path.size) return Markup.owning(nodes)
        val index = path[depth]
        val tag = markup.nodes[index] as Tag
        val inner = rebuild(tag.contents!!, depth + 1, nodes)
        val copy = ArrayList(markup.nodes)
        copy[index] = tag.copy(contents = inner)
        return Markup.owning(copy)
    }
}

/**
 * What a cell's content must not hold, and how text is made safe to put in one.
 *
 * MediaWiki reads a table before anything else on a line: a `|` on a cell's first line can end the cell or
 * make what is before it the cell's attributes, `!!` does the same on a header line, and a line starting with
 * `|` or `!` starts a new cell, row or the end of the table. Text is escaped with the character references
 * `&#124;` and `&#33;`, which read the same on the page; a nested table, complete in the content, is left as
 * it is.
 */
internal object CellText {

    /** [nodes] with each `|`, and `!` in a header, that the table pass would read written as a reference. */
    fun escape(nodes: List<Node>, header: Boolean): List<Node> {
        val source = TableSource(nodes, lineStart = false)
        val marked = HashSet<Int>()
        val _ =
            forEachLine(source) { line, start, end, depth ->
                when {
                    line == 0 -> (start until end).filterTo(marked) { read(source, it, header) }
                    depth == 0 && start < end && opensCell(source, start) -> marked += start
                }
            }
        if (marked.isEmpty()) return nodes
        val offsets = marked.mapTo(HashSet()) { source.startOf(it) }
        return nodes.mapIndexed { index, node ->
            if (node is TextNode) escaped(node, source.starts[index], offsets) else node
        }
    }

    /** Whether the character at [at] on a cell's first line is one the table pass reads there. */
    private fun read(source: TableSource, at: Int, header: Boolean): Boolean =
        source.isText(at) && (source[at] == '|' || (header && source[at] == '!'))

    private fun opensCell(source: TableSource, at: Int): Boolean = source.isText(at) && source[at] in MARKERS

    /** [node], which starts at [begin], with the characters at [offsets] written as references. */
    private fun escaped(node: TextNode, begin: Int, offsets: Set<Int>): TextNode {
        if (node.text.indices.none { begin + it in offsets }) return node
        return TextNode(
            buildString {
                node.text.forEachIndexed { at, char ->
                    when {
                        begin + at !in offsets -> append(char)
                        char == '|' -> append(PIPE)
                        else -> append(BANG)
                    }
                }
            }
        )
    }

    /** Why [nodes] cannot be a cell's content, or `null` when they can. */
    fun problem(nodes: List<Node>, header: Boolean): String? {
        val source = TableSource(nodes, lineStart = false)
        var problem: String? = null
        val open =
            forEachLine(source) { line, start, end, depth ->
                problem = problem ?: lineProblem(source, line, start, end, depth, header)
            }
        return problem ?: if (open > 0) "it opens a table it does not close" else null
    }

    @Suppress("LongParameterList")
    private fun lineProblem(
        source: TableSource,
        line: Int,
        start: Int,
        end: Int,
        depth: Int,
        header: Boolean,
    ) =
        when {
            line == 0 && (start until end).any { source[it] == '|' } ->
                "a | on its first line would end the cell or take what is before it for attributes"
            line == 0 && header && (start until end - 1).any { source[it] == '!' && source[it + 1] == '!' } ->
                "a !! on its first line would end the header cell"
            line > 0 && depth == 0 && start < end && source[start] in MARKERS ->
                "a line starting with ${source[start]} would start a new cell, a row or the end of the table"
            else -> null
        }

    /** Whether [nodes] run over more than one line, as the table pass reads lines. */
    fun multiline(nodes: List<Node>): Boolean {
        val source = TableSource(nodes, lineStart = false)
        return (0 until source.length).any { source[it] == '\n' }
    }

    /** Why [attributes] cannot be written as a cell's, or `null` when they can. */
    fun attributeProblem(attributes: String, header: Boolean): String? =
        when {
            '\n' in attributes -> "attributes are one line"
            '|' in attributes -> "a | in attributes would end them"
            "[[" in attributes || "-{" in attributes ->
                "a [[ or -{ in attributes makes them the cell's content"
            header && "!!" in attributes -> "a !! in a header cell's attributes would end the cell"
            else -> null
        }

    /**
     * Each line of [source], with its number, where its trimmed text starts and ends, and how many tables are
     * open on it, a table's own first and last lines counting as inside it. Returns how many are left open.
     */
    private fun forEachLine(source: TableSource, line: (Int, Int, Int, Int) -> Unit): Int {
        var number = 0
        var start = 0
        var depth = 0
        while (start <= source.length) {
            val lineEnd = source.lineEnd(start)
            var from = start
            var to = lineEnd
            while (from < to && TableSource.isTrimmed(source[from])) from++
            while (to > from && TableSource.isTrimmed(source[to - 1])) to--
            val opens = number > 0 && opensTable(source, from, to)
            val closes =
                number > 0 && depth > 0 && to - from >= 2 && source[from] == '|' && source[from + 1] == '}'
            if (opens) depth++
            line(number, from, to, depth)
            if (closes) depth--
            number++
            start = lineEnd + 1
        }
        return depth
    }

    private fun opensTable(source: TableSource, from: Int, to: Int): Boolean {
        var at = from
        while (at < to && source[at] == ':') at++
        while (at < to && (source[at] == ' ' || source[at] == '\t')) at++
        return at + 1 < to && source[at] == '{' && source[at + 1] == '|'
    }

    private const val MARKERS = "|!"
    private const val PIPE = "&#124;"
    private const val BANG = "&#33;"
}

/** A cell about to be written: its content as wikitext already made safe, its attributes, and its kind. */
internal class WrittenCell(val content: String, val attributes: String, val header: Boolean) {
    val multiline: Boolean
        get() = '\n' in content
}

/** How new cells are written. */
internal object TableWriter {

    /**
     * [cells] as the lines of one row, no `|-` before them: each cell on a line of its own, or after the one
     * before it on its line when [inline]. A cell goes on a new line all the same when it is of the other
     * kind than the one before it, since a line's marker decides that for every cell on it, or when the one
     * before it runs over several lines. [spaced] puts a space between each marker and what follows it, as in
     * `| a || b`.
     */
    fun row(cells: List<WrittenCell>, inline: Boolean, spaced: Boolean): String = buildString {
        var previous: WrittenCell? = null
        for (cell in cells) {
            val follows = inline && previous != null && previous.header == cell.header && !previous.multiline
            if (follows) {
                if (spaced) append(' ')
                append(if (cell.header) "!!" else "||")
            } else {
                if (previous != null) append('\n')
                append(if (cell.header) '!' else '|')
            }
            append(cell(cell, spaced, lineStart = !follows))
            previous = cell
        }
    }

    /** What follows a cell's marker: its attributes and a `|` when it has some, then its content. */
    fun cell(cell: WrittenCell, spaced: Boolean, lineStart: Boolean): String = buildString {
        if (cell.attributes.isNotEmpty()) append(' ').append(cell.attributes).append(" |")
        val opensLine = lineStart && !cell.header && cell.attributes.isEmpty()
        val space = spaced || (opensLine && cell.content.firstOrNull()?.let { it in ROW_MARKERS } == true)
        if (space && cell.content.firstOrNull()?.isWhitespace() == false) append(' ')
        append(cell.content)
    }

    /** What makes `|-`, `|}` and `|+` of a `|` that starts a line. */
    private const val ROW_MARKERS = "-}+"
}
