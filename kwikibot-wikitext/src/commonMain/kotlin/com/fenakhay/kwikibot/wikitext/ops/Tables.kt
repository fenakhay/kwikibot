package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.internal.CellText
import com.fenakhay.kwikibot.wikitext.internal.ScannedCell
import com.fenakhay.kwikibot.wikitext.internal.ScannedRow
import com.fenakhay.kwikibot.wikitext.internal.ScannedTable
import com.fenakhay.kwikibot.wikitext.internal.Splice
import com.fenakhay.kwikibot.wikitext.internal.TableContext
import com.fenakhay.kwikibot.wikitext.internal.TableScan
import com.fenakhay.kwikibot.wikitext.internal.TableSource
import com.fenakhay.kwikibot.wikitext.internal.TableWriter
import com.fenakhay.kwikibot.wikitext.internal.TagAttributes
import com.fenakhay.kwikibot.wikitext.internal.TagNames
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.TextNode

/**
 * The wikitext tables in this wikitext, `{| … |}`, in page order: those written at its top level and those
 * inside a tag's body, such as a `<div>` around a table. A table nested in another's cell is not among them;
 * it is reached from that cell, through [Table.Cell.tables].
 *
 * A table is a view over the parsed nodes, read as MediaWiki's table pass, `Parser::handleTables`, reads the
 * page. The parser keeps a table's markup as text, interleaved with the templates, links and tags written in
 * its cells, and nothing changes in the tree for a table to be read: it is worked out here, each time this is
 * called, and costs nothing otherwise.
 *
 * Some tables are not here, because they are not in the page as written:
 * - a table, row or cell a template writes, such as `{{table start}}` or `{{row|…}}`, since what a template
 *   expands to is not in the tree. A table a template opens and the page closes is not found, and its `|}` is
 *   text; rows and cells the page writes for a table it opens are found as usual, whatever a template puts
 *   between them.
 * - an HTML `<table>`, which parses as [Tag] nodes and is read through those.
 *
 * `{{!}}` is a `|` and `{{!!}}` is `||`, as MediaWiki expands them before it reads a table, so a table
 * written with them is found and edited like any other.
 */
public fun Markup.tables(): List<Table> {
    val context = TableContext(this, IntArray(0), TableSource(nodes, lineStart = true))
    return Table.discover(context, TableScan(context.source).tables(), 0, context.source.text.length)
}

/**
 * A wikitext table, `{| … |}`, as MediaWiki reads it, over the nodes of the page it was found in.
 *
 * Each edit returns the whole page, every node outside the part it changed the same instance as before, so
 * the diff shows the change and nothing else. An edit that would change the table in a way it was not asked
 * to, such as a cell's text starting a new cell, is refused with an [IllegalArgumentException] rather than
 * written.
 *
 * What MediaWiki makes of markup out of place is kept as it makes it:
 * - a `|}` with no table open is text, and no table;
 * - a `{|` nothing closes runs to the end of the page, or of the tag it is in, and [isClosed] says so;
 * - a table with no rows has an empty [rows]. MediaWiki shows it with one empty cell when it has neither a
 *   `|-` nor a cell, and with none when it has a `|-`;
 * - a `|-` with no cell after it before the next `|-`, caption or `|}` makes no row, as MediaWiki writes no
 *   `<tr>` for it; the attributes of the last `|-` before a row's first cell are the row's;
 * - a caption after the first row ends the row it follows, and the cells after it start a new one, as the
 *   page's HTML has them. A second caption, after another `|+` or a `||` on a caption line, is in the source
 *   but not [caption];
 * - a table opened where the table around it has no cell open, directly after its `{|` or a `|-`, belongs to
 *   no cell, and is not reached from this one; MediaWiki's HTML ends the outer table there.
 *
 * A cell's `||`, `!!` or first `|` inside bold or italic text, a link, an external link or an HTML tag is not
 * read as one here, though MediaWiki, reading tables before any of those, would split the cell there and
 * break what it splits.
 */
public class Table internal constructor(private val context: TableContext, private val data: ScannedTable) {

    /** The attributes on the `{|` line, comments left out and trimmed: `class="wikitable"`. */
    public val attributes: String
        get() = context.attributes(data.attributesStart, data.attributesEnd)

    /** The value of the attribute [name] on the `{|` line, as MediaWiki reads it, or `null`. */
    public fun attribute(name: String): String? = valueOf(attributes, name)

    /** How many `:` the table is indented by, as in `:{|`. */
    public val indent: Int
        get() = data.indent

    /** Whether a `|}` closes the table. One that nothing closes runs to the end of what it is in. */
    public val isClosed: Boolean
        get() = data.closed

    /** The caption, `|+ …`, or `null`. */
    public val caption: Cell? by lazy { data.captions.firstOrNull()?.let { Cell(this, CAPTION, 0, it) } }

    /** The rows, in order, the first one included when no `|-` opens it. */
    public val rows: List<Row> by lazy { data.rows.mapIndexed { index, row -> Row(this, index, row) } }

    /** The first row, when every cell in it is a header cell; `null` otherwise. */
    public val headerRow: Row?
        get() = rows.firstOrNull()?.takeIf { it.isHeader }

    /**
     * The text of the header row's cell over each column of [grid], a cell spanning columns repeated for
     * each, or an empty list when there is no [headerRow].
     */
    public val headers: List<String>
        get() = if (headerRow == null) emptyList() else grid.first().map { it?.text.orEmpty() }

    /**
     * The cells laid out by row and column as a browser lays them out, `rowspan` and `colspan` taken into
     * account: each cell is at every place it covers, and a place no cell covers is `null`. Every row has
     * [columnCount] places.
     *
     * A `rowspan` reaching past the last row stops there, and `rowspan="0"` reaches to the last row. Where
     * two cells would cover the same place, the one that came first keeps it.
     */
    public val grid: List<List<Cell?>> by lazy { layOut() }

    /** How many columns [grid] has. */
    public val columnCount: Int
        get() = grid.firstOrNull()?.size ?: 0

    /** The cell covering row [row] and column [column] of [grid], or `null`. */
    public fun cell(row: Int, column: Int): Cell? = grid.getOrNull(row)?.getOrNull(column)

    /**
     * The cells under the header [header] in each row after the [headerRow], in order, `null` for a row with
     * nothing in that column. The header is compared with each header's [Cell.text]; an empty list comes back
     * when no header has that text.
     */
    public fun column(header: String): List<Cell?> {
        val index = headers.indexOf(header.trim())
        if (index < 0) return emptyList()
        return grid.drop(1).map { it[index] }
    }

    /** The table as written, from its `{|`, or the first `:` before it, to its `|}`. */
    public val markup: Markup
        get() = context.markup(data.start, data.end)

    /** The table as wikitext, byte for byte as it was parsed. */
    public fun serialize(): String = context.text(data.start, data.end)

    /** Where the table is written in the page it was found in. */
    internal val pageRange: IntRange
        get() = context.offset().let { it + data.start until it + data.end }

    /**
     * The page with the attributes on the `{|` line replaced by [attributes], the space before them kept.
     *
     * @throws IllegalArgumentException if [attributes] run over more than one line.
     */
    public fun withAttributes(attributes: String): Markup {
        require('\n' !in attributes) { "a table's attributes are one line" }
        val replacement = attributeText(context.text(data.attributesStart, data.attributesEnd), attributes)
        return edit(Splice(data.attributesStart, data.attributesEnd, nodes(replacement))) { found, source ->
            found.shape() == data.shape() &&
                source.attributes(found.attributesStart, found.attributesEnd) == attributes.trim()
        }
    }

    /**
     * The page with a row of data cells holding [cells] added after the last row, written as the table writes
     * its rows: each cell on its own line, or after the one before it with `||`.
     *
     * Each cell is wikitext, escaped as [TableBuilder.Cell] describes.
     */
    public fun withRow(vararg cells: String): Markup =
        withRowAt(rows.size, cells.map { TableBuilder.Cell(it) })

    /** The page with a row of [cells] added after the last row, as [withRow] adds one. */
    public fun withRow(cells: List<TableBuilder.Cell>): Markup = withRowAt(rows.size, cells)

    /** The page with a row of data cells holding [cells] put at [index] among the rows. */
    public fun withRowAt(index: Int, vararg cells: String): Markup =
        withRowAt(index, cells.map { TableBuilder.Cell(it) })

    /**
     * The page with a row of [cells] put at [index] among the rows, the rows from there on after it. [index]
     * equal to the number of rows adds it last. The row is written as the table writes its rows, opened by a
     * `|-`; put first in a table whose first row has none, it takes that place, and the row after it gets
     * one.
     *
     * @throws IndexOutOfBoundsException if [index] is outside `0..rows.size`.
     * @throws IllegalArgumentException if a cell cannot be written, as [TableBuilder.Cell] describes.
     */
    public fun withRowAt(index: Int, cells: List<TableBuilder.Cell>): Markup {
        if (index !in 0..rows.size) {
            throw IndexOutOfBoundsException("row $index is outside the ${rows.size} rows of the table")
        }
        val (inline, spaced) = style()
        val text = TableWriter.row(cells.map { it.written() }, inline, spaced)
        val splice =
            when {
                index < rows.size && data.rows[index].markerStart >= 0 ->
                    data.rows[index].start.let { Splice(it, it, nodes("|-\n$text\n")) }
                index < rows.size -> data.rows[index].start.let { Splice(it, it, nodes("$text\n|-\n")) }
                rows.isNotEmpty() ->
                    data.rows.last().cells.last().contentEnd.let { Splice(it, it, nodes("\n|-\n$text")) }
                data.closed -> Splice(data.closeLine, data.closeLine, nodes("|-\n$text\n"))
                else -> Splice(data.end, data.end, nodes("\n|-\n$text"))
            }
        val expected =
            rowShapes().toMutableList().apply { add(index, cells.joinToString("") { kind(it.isHeader) }) }
        return edit(splice) { found, _ ->
            rowShapes(found) == expected && found.captions.size == data.captions.size
        }
    }

    /**
     * The page without the row at [index]: its `|-` line, its cells and whatever is written between them and
     * the next row, caption or `|}`.
     *
     * @throws IndexOutOfBoundsException if there is no row at [index].
     */
    public fun withoutRow(index: Int): Markup {
        if (index !in rows.indices) {
            throw IndexOutOfBoundsException("row $index is outside the ${rows.size} rows of the table")
        }
        val row = data.rows[index]
        val expected = rowShapes().toMutableList().apply { removeAt(index) }
        return edit(Splice(row.start, row.end, emptyList())) { found, _ -> rowShapes(found) == expected }
    }

    /**
     * The page with a column added at the right: [cells] are the new cells, one for each row in order, the
     * header row's included, each a data cell unless it says otherwise. A cell goes after the last cell of
     * its row, on its line with `||` or `!!` when that cell was written after another on its line, and on a
     * line of its own otherwise.
     *
     * @throws IllegalArgumentException if there is not one cell for each row, or a cell cannot be written.
     * @throws IllegalStateException if the rows do not all have the same number of cells, or a cell spans
     *   rows or columns, since a column is then not one more cell in each row.
     */
    public fun withColumn(cells: List<TableBuilder.Cell>): Markup {
        require(cells.size == rows.size) { "a new column needs one cell for each of the ${rows.size} rows" }
        check(rows.all { it.cells.size == rows.first().cells.size }) {
            "the rows have different numbers of cells, so a new last cell is not one column"
        }
        check(rows.all { row -> row.cells.all { it.rowspan == 1 && it.colspan == 1 } }) {
            "a cell spans rows or columns, so a new last cell is not one column"
        }
        val spaced = style().second
        val edits =
            data.rows.mapIndexed { index, row ->
                val last = row.cells.last()
                val cell = cells[index].written()
                val inline =
                    last.inline &&
                        last.header == cell.header &&
                        !cell.multiline &&
                        !context.text(last.contentStart, last.contentEnd).contains('\n')
                val text =
                    if (inline) {
                        (if (spaced) " " else "") +
                            (if (cell.header) "!!" else "||") +
                            TableWriter.cell(cell, spaced, lineStart = false)
                    } else {
                        "\n" + TableWriter.row(listOf(cell), inline = false, spaced = spaced)
                    }
                Splice(last.contentEnd, last.contentEnd, nodes(text))
            }
        val expected = rowShapes().mapIndexed { index, shape -> shape + kind(cells[index].isHeader) }
        return edit(edits) { found, _ -> rowShapes(found) == expected }
    }

    /**
     * The page with a column holding [cells] added at the right, as the other overload adds it, each cell of
     * the kind of the last cell in its row: a header cell after a header cell.
     */
    public fun withColumn(vararg cells: String): Markup =
        withColumn(
            cells.mapIndexed { index, text ->
                TableBuilder.Cell(text, isHeader = data.rows.getOrNull(index)?.cells?.last()?.header == true)
            }
        )

    /** The table as wikitext. */
    override fun toString(): String = serialize()

    /**
     * Whether rows are written with their cells on one line, and whether a space follows each marker, as the
     * last row with more than one cell writes them, data rows first. A table with no such row is written one
     * cell to a line, with spaces.
     */
    private fun style(): Pair<Boolean, Boolean> {
        val sample =
            data.rows.lastOrNull { row -> row.cells.size > 1 && row.cells.none { it.header } }
                ?: data.rows.lastOrNull { it.cells.size > 1 }
        val inline = sample?.cells?.get(1)?.inline ?: false
        val first = (sample ?: data.rows.firstOrNull())?.cells?.firstOrNull()
        val spaced =
            first == null ||
                context.text(first.contentStart, first.contentEnd).let {
                    it.isEmpty() || it.first().isWhitespace()
                }
        return inline to spaced
    }

    private fun rowShapes(table: ScannedTable = data): List<String> =
        table.rows.map { row -> row.cells.joinToString("") { kind(it.header) } }

    private fun kind(header: Boolean): String = if (header) "h" else "d"

    private fun edit(vararg edits: Splice, accept: (ScannedTable, TableSource) -> Boolean): Markup =
        edit(edits.toList(), accept)

    private fun edit(edits: List<Splice>, accept: (ScannedTable, TableSource) -> Boolean): Markup =
        context.edit(edits, data.start, { "the edit would change the table's structure" }) { found, source ->
            (found.closed == data.closed) && accept(found, source)
        }

    private fun layOut(): List<List<Cell?>> {
        val places = ArrayList<ArrayList<Cell?>>()
        repeat(rows.size) { places += ArrayList<Cell?>() }
        for ((y, row) in rows.withIndex()) {
            var x = 0
            for (cell in row.cells) {
                while (x < places[y].size && places[y][x] != null) x++
                val down = if (cell.rowspan == 0) rows.size - y else minOf(cell.rowspan, rows.size - y)
                for (dy in 0 until down) {
                    val line = places[y + dy]
                    while (line.size < x + cell.colspan) line += null
                    for (dx in 0 until cell.colspan) if (line[x + dx] == null) line[x + dx] = cell
                }
                x += cell.colspan
            }
        }
        val width = places.maxOfOrNull { it.size } ?: 0
        for (line in places) while (line.size < width) line += null
        return places
    }

    /** One row: the `|-` line that opened it, when one did, and its cells. */
    public class Row
    internal constructor(
        private val table: Table,
        /** Where the row is among the table's rows. */
        public val index: Int,
        private val data: ScannedRow,
    ) {
        /** The attributes on the row's `|-` line, comments left out and trimmed, or `""` without one. */
        public val attributes: String
            get() = table.context.attributes(data.attributesStart, data.attributesEnd)

        /** The value of the attribute [name] on the row's `|-` line, as MediaWiki reads it, or `null`. */
        public fun attribute(name: String): String? = valueOf(attributes, name)

        /** The row's cells, in order. */
        public val cells: List<Cell> by lazy {
            data.cells.mapIndexed { position, cell -> Cell(table, index, position, cell) }
        }

        /** Whether every cell in the row is a header cell, `!`. */
        public val isHeader: Boolean
            get() = data.cells.all { it.header }

        /** The row as written, its `|-` line included when it has one. */
        public val markup: Markup
            get() = table.context.markup(data.start, data.end)

        /**
         * The page with the attributes on this row's `|-` line replaced by [attributes]; a row with no `|-`
         * gets a `|-` line that carries them. Blank attributes take them off, and leave a row with no `|-` as
         * it is.
         *
         * @throws IllegalArgumentException if [attributes] run over more than one line.
         */
        public fun withAttributes(attributes: String): Markup {
            require('\n' !in attributes) { "a row's attributes are one line" }
            val trimmed = attributes.trim()
            val splice =
                when {
                    data.markerStart >= 0 -> {
                        val old = table.context.text(data.attributesStart, data.attributesEnd)
                        Splice(data.attributesStart, data.attributesEnd, nodes(attributeText(old, trimmed)))
                    }
                    trimmed.isEmpty() -> return table.context.root
                    else -> Splice(data.start, data.start, nodes("|- $trimmed\n"))
                }
            val expected = table.rowShapes()
            return table.edit(splice) { found, source ->
                table.rowShapes(found) == expected &&
                    found.rows[index].let { source.attributes(it.attributesStart, it.attributesEnd) } ==
                        trimmed
            }
        }

        /** The row as wikitext. */
        override fun toString(): String = markup.serialize()
    }

    /** One cell, `| …` or `! …`, or the caption, `|+ …`. */
    public class Cell
    internal constructor(
        private val table: Table,
        private val row: Int,
        private val position: Int,
        private val data: ScannedCell,
    ) {
        /** Whether this is a header cell, written with `!`. */
        public val isHeader: Boolean
            get() = data.header

        /** Whether this is the table's caption, written with `|+`. */
        public val isCaption: Boolean
            get() = data.caption

        /** The attributes before the cell's first `|`, comments left out and trimmed, or `""` for none. */
        public val attributes: String
            get() = table.context.attributes(data.attributesStart, data.attributesEnd)

        /** The value of the attribute [name], as MediaWiki reads it, or `null`. */
        public fun attribute(name: String): String? = valueOf(attributes, name)

        /**
         * What the cell holds as written, its whitespace included: after its marker, or after its attributes'
         * `|`, up to the next cell or the line break before the next line of table markup.
         */
        public val content: Markup by lazy { table.context.markup(data.contentStart, data.contentEnd) }

        /**
         * The cell's visible text, trimmed, as [Markup.text] has it: templates and comments contribute
         * nothing, and a link its display text.
         */
        public val text: String
            get() = content.text.trim()

        /**
         * How many rows the cell spans: its `rowspan`, 1 without one, and 0 for one reaching the last row.
         */
        public val rowspan: Int
            get() = span(attribute(ROWSPAN), ROWSPAN_LIMIT, zero = 0)

        /** How many columns the cell spans: its `colspan`, 1 without one. */
        public val colspan: Int
            get() = span(attribute(COLSPAN), COLSPAN_LIMIT, zero = 1)

        /**
         * The tables nested in this cell, in order: those written in its content, and those inside a tag
         * there. Each one's edits return the whole page, as this one's do.
         */
        public fun tables(): List<Table> =
            discover(table.context, data.nested, data.contentStart, data.contentEnd)

        /** The cell as written, its marker and attributes included. */
        public val markup: Markup
            get() = table.context.markup(data.markerStart, data.contentEnd)

        /**
         * The page with this cell's content replaced by [content], which is wikitext: its marker, its
         * attributes and the whitespace around its content stay as they were written, unless [content] starts
         * or ends with whitespace of its own.
         *
         * Text that MediaWiki would read as table markup is escaped, as [TableBuilder.Cell] describes.
         *
         * @throws IllegalArgumentException if [content] holds what escaping cannot make safe, a `{{!}}` or a
         *   table it does not close, or runs over several lines in a cell another one follows on its line.
         */
        public fun withContent(content: String): Markup =
            replaceContent(CellText.escape(Wikitext.parse(content).nodes, data.header))

        /**
         * The page with this cell's content replaced by [content], written as it is, the whitespace around
         * the old content kept unless [content] starts or ends with its own: a cell's own [content] put back
         * changes nothing.
         *
         * @throws IllegalArgumentException if [content] would be read as more than this cell: a `|` on its
         *   first line, or `!!` in a header cell, a line starting with `|` or `!`, or a table it does not
         *   close; or if it runs over several lines in a cell another one follows on its line.
         */
        public fun withContent(content: Markup): Markup = replaceContent(content.nodes)

        /**
         * The page with this cell's attributes replaced by [attributes], with a `|` after them if it had
         * none. Blank attributes take them off, and the `|` with them where the content does not need it.
         *
         * @throws IllegalArgumentException if [attributes] cannot be a cell's: a line break, a `|`, `[[` or
         *   `-{`, or `!!` for a header cell.
         */
        public fun withAttributes(attributes: String): Markup {
            CellText.attributeProblem(attributes, data.header)?.let { throw IllegalArgumentException(it) }
            val trimmed = attributes.trim()
            val source = table.context
            return when {
                data.hasAttributes && trimmed.isEmpty() ->
                    runCatching { attributesEdit(Splice(data.markerEnd, data.contentStart, emptyList()), "") }
                        .getOrElse {
                            attributesEdit(Splice(data.attributesStart, data.attributesEnd, nodes(" ")), "")
                        }
                data.hasAttributes -> {
                    val old = source.text(data.attributesStart, data.attributesEnd)
                    val text = attributeText(old, trimmed).let { if (it.endsWith(' ')) it else "$it " }
                    attributesEdit(Splice(data.attributesStart, data.attributesEnd, nodes(text)), trimmed)
                }
                trimmed.isEmpty() -> source.root
                else -> attributesEdit(Splice(data.markerEnd, data.markerEnd, nodes(" $trimmed |")), trimmed)
            }
        }

        /** The cell as wikitext, its marker included. */
        override fun toString(): String = markup.serialize()

        private fun attributesEdit(edit: Splice, expected: String): Markup {
            val content = this.content.serialize()
            return table.edit(edit) { found, source ->
                val cell = locate(found)
                found.shape() == table.data.shape() &&
                    cell != null &&
                    source.attributes(cell.attributesStart, cell.attributesEnd) == expected &&
                    source.text.substring(cell.contentStart, cell.contentEnd) == content
            }
        }

        private fun replaceContent(nodes: List<Node>): Markup {
            CellText.problem(nodes, data.header)?.let {
                throw IllegalArgumentException("the content cannot go in this cell: $it")
            }
            require(!data.followed || !CellText.multiline(nodes)) {
                "the content runs over several lines, and another cell follows this one on its line"
            }
            val old = table.context.text(data.contentStart, data.contentEnd)
            val written = nodes.joinToString("") { it.serialize() }
            val (lead, trail) = padding(old, written)
            val replacement = listOf(TextNode(lead)) + nodes + TextNode(trail)
            val expected = lead + written + trail
            val length = table.data.end - table.data.start + expected.length - old.length
            return table.edit(Splice(data.contentStart, data.contentEnd, replacement)) { found, source ->
                val cell = locate(found)
                found.shape() == table.data.shape() &&
                    found.end - found.start == length &&
                    cell != null &&
                    source.text.substring(cell.contentStart, cell.contentEnd) == expected
            }
        }

        /**
         * The whitespace to put around new content [written]: the old content's own, unless [written] starts
         * or ends with its own. Where the old content was only spaces, a space goes before, and after when
         * another cell follows on the line; where it was blank and [written] is empty, it stays as it was. A
         * space goes before content a `|` starting the line would otherwise make `|-`, `|}` or `|+` of.
         */
        private fun padding(old: String, written: String): Pair<String, String> {
            val blank = old.all { it.isWhitespace() }
            if (written.isEmpty()) return (if (blank) old else old.takeWhile { it.isWhitespace() }) to ""
            val lead =
                when {
                    written.first().isWhitespace() -> ""
                    !blank -> old.takeWhile { it.isWhitespace() }
                    '\n' in old -> old
                    else -> old.take(1)
                }
            val trail =
                when {
                    written.last().isWhitespace() -> ""
                    !blank -> old.takeLastWhile { it.isWhitespace() }
                    data.followed && '\n' !in old -> old.take(1)
                    else -> ""
                }
            return (lead.ifEmpty { if (opensLine() && written.first() in ROW_MARKERS) " " else "" }) to trail
        }

        /** Whether a `|` starting the line is all that comes before this cell's content. */
        private fun opensLine(): Boolean =
            !data.inline && !data.header && !data.caption && !data.hasAttributes

        private fun locate(found: ScannedTable): ScannedCell? =
            if (row == CAPTION) found.captions.getOrNull(position)
            else found.rows.getOrNull(row)?.cells?.getOrNull(position)
    }

    internal companion object {
        const val CAPTION = -1
        private const val ROWSPAN = "rowspan"
        private const val COLSPAN = "colspan"
        private const val ROWSPAN_LIMIT = 65534
        private const val COLSPAN_LIMIT = 1000

        /** What makes `|-`, `|}` and `|+` of a `|` that starts a line. */
        private const val ROW_MARKERS = "-}+"

        /**
         * The tables in [context] from [from] until [to], in order: [scanned], the ones its own table pass
         * found there, and those inside the bodies of the tags there that none of them holds.
         *
         * An extension tag's body, such as a `<ref>`'s, is parsed on its own, so it starts a line; that of
         * `<noinclude>` and the other transclusion tags starts where the tag did, since the tag is not there
         * when the page is read; any other tag's starts after it on its line.
         */
        fun discover(context: TableContext, scanned: List<ScannedTable>, from: Int, to: Int): List<Table> {
            val source = context.source
            val out = ArrayList<Table>()
            var next = 0
            var covered = from
            var index = source.nodeAt(from)
            while (index < source.nodes.size && source.starts[index] < to) {
                val start = source.starts[index]
                while (next < scanned.size && scanned[next].start <= start) {
                    out += Table(context, scanned[next])
                    covered = maxOf(covered, scanned[next].end)
                    next++
                }
                val node = source.nodes[index]
                if (start >= covered && node is Tag && holdsTables(node)) {
                    val inner = context.inside(index, node, lineStart(node, source.atLineStart[index]))
                    out += discover(inner, TableScan(inner.source).tables(), 0, inner.source.text.length)
                }
                index++
            }
            while (next < scanned.size) out += Table(context, scanned[next++])
            return out
        }

        /** Whether [tag]'s body is wikitext MediaWiki reads tables in. */
        private fun holdsTables(tag: Tag): Boolean = tag.contents != null && !TagNames.isRaw(tag)

        private fun lineStart(tag: Tag, atLineStart: Boolean): Boolean {
            val name = tag.name.lowercase()
            return when {
                name in TagNames.TRANSCLUSION -> atLineStart
                name in ParseOptions.DEFAULT.extensionTags -> true
                else -> false
            }
        }

        /** The value of [name] among [attributes], read as `Sanitizer::decodeTagAttributes` reads them. */
        fun valueOf(attributes: String, name: String): String? =
            TagAttributes.value(attributes, 0, attributes.length, name.lowercase())

        /**
         * A span as HTML reads one, by its rules for parsing a non-negative integer: leading whitespace and a
         * `+` skipped, then digits, anything after them ignored. No value, or none that parses, is 1; zero is
         * [zero]; past [limit] is [limit].
         */
        fun span(value: String?, limit: Int, zero: Int): Int {
            val digits = value?.trimStart()?.removePrefix("+")?.takeWhile { it in '0'..'9' }.orEmpty()
            if (digits.isEmpty()) return 1
            val number =
                digits.trimStart('0').let {
                    if (it.length > LIMIT_DIGITS) limit else it.ifEmpty { "0" }.toInt()
                }
            return when {
                number == 0 -> zero
                number > limit -> limit
                else -> number
            }
        }

        private const val LIMIT_DIGITS = 6

        /**
         * [replacement] in place of [old] attributes: the space before them kept, or one where there was
         * none.
         */
        fun attributeText(old: String, replacement: String): String {
            val trimmed = replacement.trim()
            if (trimmed.isEmpty()) return ""
            val lead = old.takeWhile { it.isWhitespace() }.ifEmpty { " " }
            val trail = if (old.isBlank()) "" else old.takeLastWhile { it.isWhitespace() }
            return lead + trimmed + trail
        }

        fun nodes(text: String): List<Node> = Wikitext.parse(text).nodes
    }
}
