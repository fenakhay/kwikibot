package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.internal.CellText
import com.fenakhay.kwikibot.wikitext.internal.TableWriter
import com.fenakhay.kwikibot.wikitext.internal.WrittenCell

/**
 * Writes a new wikitext table.
 *
 * ```
 * val table =
 *     TableBuilder()
 *         .sortable()
 *         .caption("Volcanoes")
 *         .header("Name", "Height")
 *         .row("Etna", "3,357 m")
 *         .row("Vesuvius", "1,281 m")
 *         .build()
 * ```
 *
 * writes
 *
 * ```
 * {| class="wikitable sortable"
 * |+ Volcanoes
 * ! Name
 * ! Height
 * |-
 * | Etna
 * | 3,357 m
 * |-
 * | Vesuvius
 * | 1,281 m
 * |}
 * ```
 *
 * The table is `class="wikitable"` unless [plain] says otherwise, and each cell goes on a line of its own
 * unless [inline] puts a row's cells on one line, `| Etna || 3,357 m`. What each cell may hold, and how text
 * is escaped, is in [Cell].
 */
public class TableBuilder {
    private var wikitable = true
    private var sortable = false
    private var attributes = ""
    private var inline = false
    private var caption: Cell? = null
    private val rows = ArrayList<List<Cell>>()

    /** Leaves out `class="wikitable"`, for a table styled by [attributes] alone. */
    public fun plain(): TableBuilder = apply { wikitable = false }

    /** Makes the table sortable, `class="wikitable sortable"`, or not. */
    public fun sortable(sortable: Boolean = true): TableBuilder = apply { this.sortable = sortable }

    /**
     * Writes [attributes] on the `{|` line after the class, such as `style="width: 100%"`.
     *
     * @throws IllegalArgumentException if they run over more than one line.
     */
    public fun attributes(attributes: String): TableBuilder = apply {
        require('\n' !in attributes) { "a table's attributes are one line" }
        this.attributes = attributes.trim()
    }

    /** Writes each row's cells on one line, `| a || b`, or each on its own line, `| a` then `| b`. */
    public fun inline(inline: Boolean = true): TableBuilder = apply { this.inline = inline }

    /** Gives the table the caption [caption], wikitext escaped as [Cell] describes. */
    public fun caption(caption: String): TableBuilder = apply { this.caption = Cell(caption) }

    /** Gives the table the caption [caption], wikitext written as it is. */
    public fun caption(caption: Markup): TableBuilder = apply { this.caption = Cell(caption) }

    /** Adds a row of header cells, `!`, holding [cells]: a header row when it is the first. */
    public fun header(vararg cells: String): TableBuilder = apply {
        rows += cells.map { Cell(it, isHeader = true) }
    }

    /** Adds a row of data cells, `|`, holding [cells]. */
    public fun row(vararg cells: String): TableBuilder = apply { rows += cells.map { Cell(it) } }

    /** Adds a row of [cells], each a header or a data cell with attributes of its own as it says. */
    public fun row(cells: List<Cell>): TableBuilder = apply { rows += cells.toList() }

    /**
     * The table as wikitext.
     *
     * @throws IllegalArgumentException if a cell cannot be written, as [Cell] describes.
     */
    public fun build(): String {
        val written = write()
        val expected = rows.map { cells -> cells.joinToString("") { if (it.isHeader) "h" else "d" } }
        val table = Wikitext.parse(written).tables().singleOrNull()
        val shape = table?.rows?.map { row -> row.cells.joinToString("") { if (it.isHeader) "h" else "d" } }
        require(shape == expected && (table.caption != null) == (caption != null)) {
            "the cells would not make the table they describe"
        }
        return written
    }

    private fun write(): String = buildString {
        append("{|")
        val classes = listOfNotNull("wikitable".takeIf { wikitable }, "sortable".takeIf { sortable })
        if (classes.isNotEmpty()) append(" class=\"").append(classes.joinToString(" ")).append('"')
        if (attributes.isNotEmpty()) append(' ').append(attributes)
        caption?.let { append("\n|+").append(TableWriter.cell(it.written(), true, false)) }
        rows.forEachIndexed { index, cells ->
            if (index > 0) append("\n|-")
            append('\n').append(TableWriter.row(cells.map { it.written() }, inline, spaced = true))
        }
        append("\n|}")
    }

    /** The table as markup, [build] parsed. */
    public fun toMarkup(): Markup = Wikitext.parse(build())

    /**
     * A cell to write: its content, its attributes, and whether it is a header cell.
     *
     * Content given as a `String` is wikitext, escaped where MediaWiki would read it as part of the table: on
     * the cell's first line every `|`, and in a header cell every `!`, is written `&#124;` or `&#33;`, so
     * that `a || b` stays one cell; on a later line a `|` or `!` that starts it is written so too, so that it
     * starts no new cell or row. A nested table written complete in the content is left as it is. What
     * escaping cannot make safe is refused: a `{{!}}` or `{{!!}}` where it would be read, or a table opened
     * and not closed.
     *
     * Content given as [Markup] is written as it is, and refused where it would be read as more than this
     * cell.
     *
     * @param attributes the cell's attributes, such as `colspan="2"`, written before a `|`.
     * @param isHeader whether it is a header cell, `!`, rather than a data cell, `|`.
     */
    public class Cell
    private constructor(
        private val text: String?,
        private val markup: Markup?,
        public val attributes: String,
        public val isHeader: Boolean,
    ) {
        /** A cell holding the wikitext [content], escaped as described above. */
        public constructor(
            content: String,
            attributes: String = "",
            isHeader: Boolean = false,
        ) : this(content, null, attributes.trim(), isHeader)

        /** A cell holding [content], written as it is. */
        public constructor(
            content: Markup,
            attributes: String = "",
            isHeader: Boolean = false,
        ) : this(null, content, attributes.trim(), isHeader)

        init {
            CellText.attributeProblem(attributes, isHeader)?.let {
                throw IllegalArgumentException("the attributes cannot be a cell's: $it")
            }
        }

        /** What the cell holds: the wikitext as given, before any escaping, or the markup. */
        public val content: Markup
            get() = markup ?: Wikitext.parse(text.orEmpty())

        /** The cell as it is written: its content escaped when it is text, and refused when it cannot be. */
        internal fun written(): WrittenCell {
            val parsed = text?.let { CellText.escape(Wikitext.parse(it).nodes, isHeader) }
            val nodes = parsed ?: markup!!.nodes
            CellText.problem(nodes, isHeader)?.let {
                throw IllegalArgumentException("the cell cannot be written: $it")
            }
            val content = nodes.joinToString("") { it.serialize() }.trimStart(' ', '\t').trimEnd()
            return WrittenCell(content, attributes, isHeader)
        }

        /** The cell's content as wikitext. */
        override fun toString(): String = text ?: markup!!.serialize()
    }
}
