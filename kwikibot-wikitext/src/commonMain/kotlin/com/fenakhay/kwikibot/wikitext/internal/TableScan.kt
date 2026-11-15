package com.fenakhay.kwikibot.wikitext.internal

/**
 * Where one wikitext table sits in a [TableSource], as MediaWiki's `Parser::handleTables` reads it. Every
 * position is an offset into the source's text.
 *
 * @param start where the table starts: at the first `:` of an indented one, or at its `{|`.
 * @param indent how many `:` it is indented by.
 * @param attributesStart where the attributes on its `{|` line start, just past the `{|`.
 * @param attributesEnd where they end, at the end of that line.
 */
internal class ScannedTable(
    val start: Int,
    val indent: Int,
    val attributesStart: Int,
    val attributesEnd: Int,
) {
    /** Where the table ends: past its `|}`, or at the end of the source when nothing closes it. */
    var end = -1

    /** Where the line holding the `|}` starts, or -1 when nothing closes the table. */
    var closeLine = -1

    /** Each `|+` caption, the first of them the one a reader takes for the caption. */
    val captions = ArrayList<ScannedCell>()

    val rows = ArrayList<ScannedRow>()

    /** Tables opened where this one had no cell open, which belong to no cell. */
    val loose = ArrayList<ScannedTable>()

    val closed: Boolean
        get() = closeLine >= 0

    /** What the scan is in the middle of. */
    internal var openCell: ScannedCell? = null
    internal var openRow: ScannedRow? = null
    internal var pendingRow: ScannedRow? = null

    /** The rows, cells and captions, as a string that changes when any of them comes or goes. */
    fun shape(): String = buildString {
        append(captions.size).append(if (closed) 'c' else 'o')
        for (row in rows) {
            append('/')
            for (cell in row.cells) append(if (cell.header) 'h' else 'd')
        }
    }
}

/**
 * One row: the `|-` line that opened it, when one did, and its cells.
 *
 * @param start where its first line starts, the `|-` line or that of its first cell.
 * @param markerStart where its `|-` starts, or -1 for a row no `|-` opened.
 * @param attributesStart where the attributes after the dashes start.
 * @param attributesEnd where they end, at the end of the line.
 */
internal class ScannedRow(
    val start: Int,
    val markerStart: Int,
    val attributesStart: Int,
    val attributesEnd: Int,
) {
    val cells = ArrayList<ScannedCell>()

    /** Where the line after the row's last starts: the next row, a caption, the `|}`, or the source's end. */
    var end = -1
}

/**
 * One cell or caption.
 *
 * @param header whether it is a `!` cell.
 * @param caption whether it is a `|+` caption.
 * @param inline whether it was opened by `||` or `!!` after another cell on its line.
 * @param markerStart where the marker that opened it starts.
 * @param markerEnd where that marker ends.
 * @param attributesStart where its attributes start, or -1 when it has none.
 * @param attributesEnd where they end, at the `|` that follows them.
 * @param contentStart where its content starts, past that `|` or past the marker.
 * @param lineStart where the line holding it starts.
 */
internal class ScannedCell(
    val header: Boolean,
    val caption: Boolean,
    val inline: Boolean,
    val markerStart: Int,
    val markerEnd: Int,
    val attributesStart: Int,
    val attributesEnd: Int,
    val contentStart: Int,
    val lineStart: Int,
) {
    /** Where its content ends: before the next cell's marker, or before the line break ahead of it. */
    var contentEnd = -1

    /** Whether another cell follows it on its line. */
    var followed = false

    /** Tables opened inside it, in order. */
    val nested = ArrayList<ScannedTable>()

    val hasAttributes: Boolean
        get() = attributesStart >= 0
}

/** Reads the tables in a [TableSource] line by line, as `Parser::handleTables` does. */
internal class TableScan(private val source: TableSource) {

    private val found = ArrayList<ScannedTable>()
    private val open = ArrayList<ScannedTable>()

    /** The tables the source holds at its own level, in order, each with those nested in it. */
    fun tables(): List<ScannedTable> {
        var lineStart = 0
        var first = true
        while (lineStart <= source.length) {
            val lineEnd = source.lineEnd(lineStart)
            if (!first || source.lineStart) line(lineStart, lineEnd)
            first = false
            lineStart = lineEnd + 1
        }
        while (open.isNotEmpty()) {
            val table = open.removeAt(open.lastIndex)
            closeCell(table, source.text.length)
            closeRow(table, source.text.length)
            table.end = source.text.length
        }
        return found
    }

    private fun line(lineStart: Int, lineEnd: Int) {
        var start = lineStart
        var end = lineEnd
        while (start < end && TableSource.isTrimmed(source[start])) start++
        while (end > start && TableSource.isTrimmed(source[end - 1])) end--
        if (start == end || tableStart(start, lineEnd, end)) return
        val table = open.lastOrNull() ?: return
        val pipe = source[start] == '|'
        when (if (start + 1 < end) source[start + 1] else ' ') {
            '}' if pipe -> endTable(table, lineStart, start)
            '-' if pipe -> row(table, lineStart, start, lineEnd)
            else -> if (pipe || source[start] == '!') cells(table, lineStart, start, end)
        }
    }

    /** Opens a table when the line, from [start] until [end] once trimmed, is `{|` after any `:`. */
    private fun tableStart(start: Int, lineEnd: Int, end: Int): Boolean {
        var at = start
        while (at < end && source[at] == ':') at++
        val indent = at - start
        while (at < end && isPcreSpace(source[at])) at++
        if (at + 1 >= end || source[at] != '{' || source[at + 1] != '|') return false
        val table =
            ScannedTable(
                start = source.startOf(start),
                indent = indent,
                attributesStart = source.endOf(at + 1),
                attributesEnd = source.startOf(lineEnd),
            )
        val parent = open.lastOrNull()
        val cell = parent?.openCell
        when {
            parent == null -> found += table
            cell != null -> cell.nested += table
            else -> parent.loose += table
        }
        open += table
        return true
    }

    private fun endTable(table: ScannedTable, lineStart: Int, start: Int) {
        closeCell(table, source.startOf(lineStart - 1))
        closeRow(table, source.lineStartOf(lineStart))
        table.closeLine = source.lineStartOf(lineStart)
        table.end = source.endOf(start + 1)
        val _ = open.removeAt(open.lastIndex)
    }

    private fun row(table: ScannedTable, lineStart: Int, start: Int, lineEnd: Int) {
        closeCell(table, source.startOf(lineStart - 1))
        closeRow(table, source.lineStartOf(lineStart))
        var dashes = start + 1
        while (dashes < lineEnd && source[dashes] == '-') dashes++
        table.pendingRow =
            ScannedRow(
                start = source.lineStartOf(lineStart),
                markerStart = source.startOf(start),
                attributesStart = source.endOf(dashes - 1),
                attributesEnd = source.startOf(lineEnd),
            )
    }

    private fun cells(table: ScannedTable, lineStart: Int, start: Int, end: Int) {
        val caption = source[start] == '|' && start + 1 < end && source[start + 1] == '+'
        val header = source[start] == '!'
        val markerLength = if (caption) 2 else 1
        closeCell(table, source.startOf(lineStart - 1))
        val row =
            if (caption) {
                closeRow(table, source.lineStartOf(lineStart))
                null
            } else {
                table.openRow ?: openRow(table, lineStart)
            }

        val line = Line(start + markerLength, end, header)
        val separators = line.separators()
        var markerStart = start
        var markerEnd = start + markerLength
        for (piece in 0..separators.size) {
            val last = piece == separators.size
            val pieceEnd = if (last) end else separators[piece]
            val cell = cell(line, header, caption, piece > 0, markerStart, markerEnd, pieceEnd, lineStart)
            if (last) {
                table.openCell = cell
            } else {
                cell.contentEnd = source.startOf(pieceEnd)
                cell.followed = true
                markerStart = pieceEnd
                markerEnd = pieceEnd + 2
            }
            if (row == null) table.captions += cell else row.cells += cell
        }
    }

    private fun openRow(table: ScannedTable, lineStart: Int): ScannedRow {
        val row = table.pendingRow ?: ScannedRow(source.lineStartOf(lineStart), -1, -1, -1)
        table.pendingRow = null
        table.rows += row
        table.openRow = row
        return row
    }

    /**
     * One cell whose marker runs from [markerStart] until [markerEnd] and whose text runs on to [pieceEnd],
     * split into attributes and content at its first `|`, as `explode('|', $cell, 2)` splits it.
     *
     * A link, `[[` or `-{` before that `|` means it is not one, and the cell has no attributes: MediaWiki has
     * not read links yet, and takes the `|` for part of one.
     */
    @Suppress("LongParameterList")
    private fun cell(
        line: Line,
        header: Boolean,
        caption: Boolean,
        inline: Boolean,
        markerStart: Int,
        markerEnd: Int,
        pieceEnd: Int,
        lineStart: Int,
    ): ScannedCell {
        var pipe = markerEnd
        while (pipe < pieceEnd && line[pipe] != '|') pipe++
        val split = pipe < pieceEnd && !opensLink(markerEnd, pipe)
        val afterMarker = source.endOf(markerEnd - 1)
        return ScannedCell(
            header = header,
            caption = caption,
            inline = inline,
            markerStart = source.startOf(markerStart),
            markerEnd = afterMarker,
            attributesStart = if (split) afterMarker else -1,
            attributesEnd = if (split) source.startOf(pipe) else -1,
            contentStart = if (split) source.endOf(pipe) else afterMarker,
            lineStart = source.lineStartOf(lineStart),
        )
    }

    private fun opensLink(from: Int, to: Int): Boolean {
        for (at in from until to) {
            if (source.isLink(at)) return true
            val pair = at + 1 < to && source.isText(at) && source.isText(at + 1)
            if (pair && source[at] == '[' && source[at + 1] == '[') return true
            if (pair && source[at] == '-' && source[at + 1] == '{') return true
        }
        return false
    }

    /**
     * A cell line's text after its marker, from [from] until [to]. On a header line each `!!` is made `||`,
     * left to right, as `StringUtils::replaceMarkup` makes it before the line is split.
     *
     * `replaceMarkup` leaves a `!!` inside `<…>` alone, but by then the only `<…>` left are the HTML tags the
     * wiki allows, and each of those is a node here, which the line holds as one character.
     */
    private inner class Line(private val from: Int, to: Int, header: Boolean) {
        private val chars = CharArray(to - from) { source[from + it] }

        init {
            var at = 0
            while (header && at + 1 < chars.size) {
                if (chars[at] == '!' && chars[at + 1] == '!') {
                    chars[at] = '|'
                    chars[at + 1] = '|'
                    at += 2
                } else {
                    at++
                }
            }
        }

        operator fun get(at: Int): Char = chars[at - from]

        /** Where each `||` starts, taken from the left as `explode` takes them. */
        fun separators(): List<Int> {
            val out = ArrayList<Int>()
            var at = 0
            while (at + 1 < chars.size) {
                if (chars[at] == '|' && chars[at + 1] == '|') {
                    out += from + at
                    at += 2
                } else {
                    at++
                }
            }
            return out
        }
    }

    private fun closeCell(table: ScannedTable, end: Int) {
        table.openCell?.contentEnd = end
        table.openCell = null
    }

    private fun closeRow(table: ScannedTable, end: Int) {
        table.openRow?.end = end
        table.openRow = null
    }

    private fun isPcreSpace(char: Char): Boolean =
        char == ' ' || char == '\t' || char == '\n' || char == '\r' || char == '\u000B' || char == '\u000C'
}
