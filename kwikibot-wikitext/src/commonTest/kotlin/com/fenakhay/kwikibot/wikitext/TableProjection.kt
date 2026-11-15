package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.ops.Table
import com.fenakhay.kwikibot.wikitext.ops.tables

/**
 * Whether the table view reads each table as MediaWiki rendered it.
 *
 * Both sides are written as the same outline: each table with its caption and the attributes MediaWiki keeps,
 * each row with its attributes, each cell as `td` or `th` with its attributes, its spans and its text, and
 * the tables nested in it. The view's tables are those [tables] finds and those nested in their cells; the
 * page's are its `<table>` elements, nested ones inside the cell that holds them.
 *
 * Text is compared with its whitespace collapsed, and on the view's side without a closing tag the parser
 * left as text, which MediaWiki drops, for a cell with no template in it, which MediaWiki shows as a red link
 * and the view as nothing, and no table nested in it, whose markup is text to the view.
 *
 * MediaWiki gives a table with neither a `|-` nor a cell one empty cell, and the view no row; the view's side
 * adds that cell. A `rowspan` or `colspan` is read from the page by HTML's own rules, written out here.
 */
internal object TableProjection {

    /** The outline of the tables in [input] and in [html], which agree when they are equal. */
    fun outlines(input: String, html: String): Pair<String, String> {
        val parsed = Wikitext.parse(input).tables()
        val rendered = HtmlTables(html).tables
        val view = buildString { parsed.forEach { table(it, this) } }
        val page = buildString { rendered.forEach { table(it, this) } }
        return view to page
    }

    /** How many tables and cells, nested ones included, the view finds in [input]. */
    fun count(input: String): Pair<Int, Int> {
        var tables = 0
        var cells = 0
        fun visit(table: Table) {
            tables++
            for (cell in table.rows.flatMap { it.cells }) {
                cells++
                cell.tables().forEach(::visit)
            }
        }
        Wikitext.parse(input).tables().forEach(::visit)
        return tables to cells
    }

    private fun table(table: Table, out: StringBuilder) {
        out.append("⟨table").append(attributes { table.attribute(it) })
        table.caption?.let { out.append(" caption=").append(normal(it.text)) }
        val filler = table.rows.isEmpty() && !ROW_MARKER.containsMatchIn(table.serialize())
        if (filler) out.append(" [tr{} td{} 1x1 \"\"]")
        for (row in table.rows) {
            out.append(" [tr").append(attributes { row.attribute(it) })
            for (cell in row.cells) {
                out.append(' ').append(if (cell.isHeader) "th" else "td")
                out.append(attributes { cell.attribute(it) })
                out.append(' ').append(cell.rowspan).append('x').append(cell.colspan)
                val nested = cell.tables()
                val templated = cell.content.allNodes().any { it is Template || it is Argument }
                val text = normal(cell.text.replace(STRAY_CLOSER, ""))
                out.append(' ').append(if (nested.isEmpty() && !templated) "\"$text\"" else "…")
                nested.forEach { table(it, out) }
            }
            out.append(']')
        }
        out.append('⟩')
    }

    private fun table(table: HtmlTable, out: StringBuilder) {
        out.append("⟨table").append(attributes { table.attributes[it] })
        table.caption?.let { out.append(" caption=").append(normal(it.toString())) }
        for (row in table.rows) {
            out.append(" [tr").append(attributes { row.attributes[it] })
            for (cell in row.cells) {
                out.append(' ').append(if (cell.header) "th" else "td")
                out.append(attributes { cell.attributes[it] })
                val rowspan = span(cell.attributes["rowspan"], zero = 0)
                out.append(' ').append(rowspan).append('x').append(span(cell.attributes["colspan"], zero = 1))
                val comparable = cell.nested.isEmpty() && !cell.templated
                out.append(' ').append(if (comparable) "\"${normal(cell.text.toString())}\"" else "…")
                cell.nested.forEach { table(it, out) }
            }
            out.append(']')
        }
        out.append('⟩')
    }

    private fun attributes(value: (String) -> String?): String =
        ATTRIBUTES.mapNotNull { name -> value(name)?.let { "$name=$it" } }.joinToString(",", "{", "}")

    /** HTML's rules for parsing a non-negative integer, as a browser reads a span: 1 when there is none. */
    private fun span(value: String?, zero: Int): Int {
        val digits = value.orEmpty().trimStart().removePrefix("+").takeWhile { it.isDigit() }
        val number = digits.toIntOrNull() ?: return 1
        return if (number == 0) zero else number
    }

    private fun normal(text: String): String = text.replace('\u00A0', ' ').replace(WHITESPACE, " ").trim()

    private val ATTRIBUTES = listOf("class", "style", "title", "rowspan", "colspan")
    private val ROW_MARKER = Regex("^[ \t]*\\|-", RegexOption.MULTILINE)
    private val STRAY_CLOSER = Regex("</[A-Za-z]+>")
    private val WHITESPACE = Regex("\\s+")

    private class HtmlTable(val attributes: Map<String, String>) {
        var caption: StringBuilder? = null
        val rows = ArrayList<HtmlRow>()
        var cell: HtmlCell? = null
        var inCaption = false
    }

    private class HtmlRow(val attributes: Map<String, String>) {
        val cells = ArrayList<HtmlCell>()
    }

    private class HtmlCell(val header: Boolean, val attributes: Map<String, String>) {
        val text = StringBuilder()
        val nested = ArrayList<HtmlTable>()
        var templated = false
    }

    /** The tables of a page MediaWiki rendered, read from its well-formed HTML with a stack. */
    private class HtmlTables(html: String) {
        val tables = ArrayList<HtmlTable>()
        private val open = ArrayList<HtmlTable>()

        init {
            var at = 0
            for (match in TAG.findAll(html)) {
                text(html.substring(at, match.range.first))
                tag(match.groupValues[1] == "/", match.groupValues[2].lowercase(), match.groupValues[3])
                at = match.range.last + 1
            }
        }

        private fun text(raw: String) {
            val table = open.lastOrNull() ?: return
            val decoded = decode(raw)
            when {
                table.inCaption -> table.caption?.append(decoded)
                else -> table.cell?.text?.append(decoded)
            }
        }

        /** One tag of the page: a branch for each element of a table, and for a red link to a template. */
        @Suppress("CyclomaticComplexMethod")
        private fun tag(closing: Boolean, name: String, rest: String) {
            val table = open.lastOrNull()
            when {
                name == "table" && !closing -> {
                    val inner = HtmlTable(attributes(rest))
                    val cell = table?.cell
                    if (table == null) tables += inner else cell?.nested?.add(inner)
                    open += inner
                }
                name == "table" -> open.removeLastOrNull()
                table == null -> Unit
                name == "tr" && !closing -> table.rows += HtmlRow(attributes(rest))
                (name == "td" || name == "th") && !closing -> {
                    if (table.rows.isEmpty()) table.rows += HtmlRow(emptyMap())
                    val cell = HtmlCell(name == "th", attributes(rest))
                    table.rows.last().cells += cell
                    table.cell = cell
                }
                name == "td" || name == "th" -> table.cell = null
                name == "caption" -> {
                    table.inCaption = !closing
                    if (!closing && table.caption == null) table.caption = StringBuilder()
                }
                name == "a" && "title=\"Template:" in rest -> table.cell?.templated = true
                else -> Unit
            }
        }

        private fun attributes(text: String): Map<String, String> =
            ATTRIBUTE.findAll(text).associate { it.groupValues[1].lowercase() to decode(it.groupValues[2]) }

        private fun decode(text: String): String =
            ENTITY.replace(text) { match ->
                val (name, decimal, hex) = match.destructured
                when {
                    decimal.isNotEmpty() -> decimal.toInt().toChar().toString()
                    hex.isNotEmpty() -> hex.toInt(16).toChar().toString()
                    else -> NAMED[name] ?: match.value
                }
            }

        private companion object {
            val TAG = Regex("<(/?)([A-Za-z][A-Za-z0-9]*)([^>]*)>")
            val ATTRIBUTE = Regex("([A-Za-z-]+)=\"([^\"]*)\"")
            val ENTITY = Regex("&(?:([a-z]+)|#([0-9]+)|#x([0-9A-Fa-f]+));")
            val NAMED = mapOf("lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "nbsp" to "\u00A0")
        }
    }
}
