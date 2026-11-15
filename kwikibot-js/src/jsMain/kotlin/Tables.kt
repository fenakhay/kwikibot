import com.fenakhay.kwikibot.wikitext.ops.Table as ParsedTable
import com.fenakhay.kwikibot.wikitext.ops.TableBuilder as ParsedTableBuilder

/**
 * A wikitext table, `{| … |}`, as MediaWiki reads it. Each change returns the whole page it was found in,
 * with nothing outside the change touched, and is refused when it would change the table some other way.
 */
@JsExport
public class Table internal constructor(internal val table: ParsedTable) {

    /** The attributes on the `{|` line, comments left out and trimmed. */
    public val attributes: String
        get() = table.attributes

    /** The value of the attribute [name] on the `{|` line, or `null`. */
    public fun attribute(name: String): String? = table.attribute(name)

    /** How many `:` the table is indented by. */
    public val indent: Int
        get() = table.indent

    /** Whether a `|}` closes the table. */
    public val isClosed: Boolean
        get() = table.isClosed

    /** The caption, `|+ …`, or `null`. */
    public val caption: TableCell?
        get() = table.caption?.let(::TableCell)

    /** The rows, in order. */
    public val rows: Array<TableRow>
        get() = table.rows.map(::TableRow).toTypedArray()

    /** The first row when every cell in it is a header cell, or `null`. */
    public val headerRow: TableRow?
        get() = table.headerRow?.let(::TableRow)

    /** The text of the header row's cell over each column, or none without a header row. */
    public val headers: Array<String>
        get() = table.headers.toTypedArray()

    /** The cells by row and column, spans taken into account; `null` where no cell covers a place. */
    public val grid: Array<Array<TableCell?>>
        get() = table.grid.map { row -> row.map { it?.let(::TableCell) }.toTypedArray() }.toTypedArray()

    /** How many columns the grid has. */
    public val columnCount: Int
        get() = table.columnCount

    /** The cell covering [row] and [column] of the grid, or `null`. */
    public fun cell(row: Int, column: Int): TableCell? = table.cell(row, column)?.let(::TableCell)

    /** The cells under the header [header] in each row after the header row. */
    public fun column(header: String): Array<TableCell?> =
        table.column(header).map { it?.let(::TableCell) }.toTypedArray()

    /** The table as wikitext, from its `{|` to its `|}`. */
    public val markup: Wikitext
        get() = Wikitext(table.markup)

    /** The page with the attributes on the `{|` line replaced by [attributes]. */
    public fun withAttributes(attributes: String): Wikitext = Wikitext(table.withAttributes(attributes))

    /** The page with a row of data cells holding [cells] added last, written as the table writes rows. */
    public fun withRow(cells: Array<String>): Wikitext = Wikitext(table.withRow(*cells))

    /** The page with a row of [cells] added last. */
    public fun withRowOf(cells: Array<NewTableCell>): Wikitext =
        Wikitext(table.withRow(cells.map { it.cell }))

    /** The page with a row of data cells holding [cells] put at [index] among the rows. */
    public fun withRowAt(index: Int, cells: Array<String>): Wikitext =
        Wikitext(table.withRowAt(index, *cells))

    /** The page with a row of [cells] put at [index] among the rows. */
    public fun withRowOfAt(index: Int, cells: Array<NewTableCell>): Wikitext =
        Wikitext(table.withRowAt(index, cells.map { it.cell }))

    /** The page without the row at [index]. */
    public fun withoutRow(index: Int): Wikitext = Wikitext(table.withoutRow(index))

    /**
     * The page with a column added at the right, one of [cells] for each row, each of its row's last kind.
     */
    public fun withColumn(cells: Array<String>): Wikitext = Wikitext(table.withColumn(*cells))

    /** The page with a column of [cells] added at the right, one for each row. */
    public fun withColumnOf(cells: Array<NewTableCell>): Wikitext =
        Wikitext(table.withColumn(cells.map { it.cell }))

    /** The table as wikitext. */
    override fun toString(): String = table.serialize()
}

/** One row of a table: its `|-` line, when it has one, and its cells. */
@JsExport
public class TableRow internal constructor(internal val row: ParsedTable.Row) {

    /** Where the row is among the table's rows. */
    public val index: Int
        get() = row.index

    /** The attributes on the row's `|-` line, or `""`. */
    public val attributes: String
        get() = row.attributes

    /** The value of the attribute [name] on the row's `|-` line, or `null`. */
    public fun attribute(name: String): String? = row.attribute(name)

    /** The row's cells, in order. */
    public val cells: Array<TableCell>
        get() = row.cells.map(::TableCell).toTypedArray()

    /** Whether every cell in the row is a header cell. */
    public val isHeader: Boolean
        get() = row.isHeader

    /** The row as wikitext. */
    public val markup: Wikitext
        get() = Wikitext(row.markup)

    /** The page with the attributes on this row's `|-` line replaced by [attributes]. */
    public fun withAttributes(attributes: String): Wikitext = Wikitext(row.withAttributes(attributes))

    /** The row as wikitext. */
    override fun toString(): String = row.toString()
}

/** One cell of a table, `| …` or `! …`, or its caption, `|+ …`. */
@JsExport
public class TableCell internal constructor(internal val cell: ParsedTable.Cell) {

    /** Whether it is a header cell, `!`. */
    public val isHeader: Boolean
        get() = cell.isHeader

    /** Whether it is the caption, `|+`. */
    public val isCaption: Boolean
        get() = cell.isCaption

    /** The attributes before the cell's first `|`, or `""`. */
    public val attributes: String
        get() = cell.attributes

    /** The value of the attribute [name], or `null`. */
    public fun attribute(name: String): String? = cell.attribute(name)

    /** What the cell holds as written, its whitespace included. */
    public val content: Wikitext
        get() = Wikitext(cell.content)

    /** The cell's visible text, trimmed. */
    public val text: String
        get() = cell.text

    /** How many rows it spans; 0 for one reaching the last row. */
    public val rowspan: Int
        get() = cell.rowspan

    /** How many columns it spans. */
    public val colspan: Int
        get() = cell.colspan

    /** The tables nested in the cell, in order. */
    public fun tables(): Array<Table> = cell.tables().map(::Table).toTypedArray()

    /** The cell as wikitext, its marker included. */
    public val markup: Wikitext
        get() = Wikitext(cell.markup)

    /**
     * The page with this cell's content replaced by the wikitext [content], escaped where the table would
     * read it; its marker, attributes and whitespace kept.
     */
    public fun withContent(content: String): Wikitext = Wikitext(cell.withContent(content))

    /** The page with this cell's content replaced by [content], written as it is. */
    public fun withContentOf(content: Wikitext): Wikitext = Wikitext(cell.withContent(content.markup))

    /** The page with this cell's attributes replaced by [attributes]; blank ones take them off. */
    public fun withAttributes(attributes: String): Wikitext = Wikitext(cell.withAttributes(attributes))

    /** The cell as wikitext. */
    override fun toString(): String = cell.toString()
}

/** A cell for a new row or table: its content, its attributes, and whether it is a header cell. */
@JsExport
public class NewTableCell internal constructor(internal val cell: ParsedTableBuilder.Cell) {

    /** The cell's attributes. */
    public val attributes: String
        get() = cell.attributes

    /** Whether it is a header cell. */
    public val isHeader: Boolean
        get() = cell.isHeader

    /** What the cell holds, before any escaping. */
    public val content: Wikitext
        get() = Wikitext(cell.content)

    /** The content as wikitext. */
    override fun toString(): String = cell.toString()
}

/**
 * A cell holding the wikitext [content], escaped where the table would read it as markup: every `|` on its
 * first line, every `!` there in a header cell, and a `|` or `!` starting a later line. Throws for attributes
 * that cannot be a cell's.
 */
@JsExport
public fun createTableCell(
    content: String,
    attributes: String = "",
    isHeader: Boolean = false,
): NewTableCell = NewTableCell(ParsedTableBuilder.Cell(content, attributes, isHeader))

/** A cell holding [content], written as it is, and refused when it would be read as more than the cell. */
@JsExport
public fun createTableCellOf(
    content: Wikitext,
    attributes: String = "",
    isHeader: Boolean = false,
): NewTableCell = NewTableCell(ParsedTableBuilder.Cell(content.markup, attributes, isHeader))

/** Writes a new table: `class="wikitable"` unless made plain, one cell to a line unless made inline. */
@JsExport
public class TableBuilder internal constructor() {
    private val builder = ParsedTableBuilder()

    /** Leaves out `class="wikitable"`. */
    public fun plain(): TableBuilder = apply {
        val _ = builder.plain()
    }

    /** Makes the table sortable, or not. */
    public fun sortable(sortable: Boolean = true): TableBuilder = apply {
        val _ = builder.sortable(sortable)
    }

    /** Writes [attributes] on the `{|` line after the class. */
    public fun attributes(attributes: String): TableBuilder = apply {
        val _ = builder.attributes(attributes)
    }

    /** Writes each row's cells on one line, or each on its own. */
    public fun inline(inline: Boolean = true): TableBuilder = apply {
        val _ = builder.inline(inline)
    }

    /** Gives the table the caption [caption], escaped as a cell's text is. */
    public fun caption(caption: String): TableBuilder = apply {
        val _ = builder.caption(caption)
    }

    /** Gives the table the caption [caption], written as it is. */
    public fun captionOf(caption: Wikitext): TableBuilder = apply {
        val _ = builder.caption(caption.markup)
    }

    /** Adds a row of header cells holding [cells]. */
    public fun header(cells: Array<String>): TableBuilder = apply {
        val _ = builder.header(*cells)
    }

    /** Adds a row of data cells holding [cells]. */
    public fun row(cells: Array<String>): TableBuilder = apply {
        val _ = builder.row(*cells)
    }

    /** Adds a row of [cells]. */
    public fun rowOf(cells: Array<NewTableCell>): TableBuilder = apply {
        val _ = builder.row(cells.map { it.cell })
    }

    /** The table as wikitext. Throws when a cell cannot be written. */
    public fun build(): String = builder.build()

    /** The table as parsed wikitext. */
    public fun toWikitext(): Wikitext = Wikitext(builder.toMarkup())
}

/** A builder for a new table, empty. */
@JsExport public fun createTableBuilder(): TableBuilder = TableBuilder()
