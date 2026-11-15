package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.testResource
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The table view over the real pages of `roundtrip-pages.json`: every table found writes back as the part of
 * the page it was found in, every piece of it writes back as the table, and an edit changes the table it is
 * made to and leaves every node outside it the instance it was.
 */
class RealPageTablesTest {

    private val pages by lazy {
        Json.parseToJsonElement(testResource("roundtrip-pages.json")).jsonObject["pages"]!!.jsonArray.map {
            val page = it.jsonObject
            (page["wiki"]!!.jsonPrimitive.content + ": " + page["title"]!!.jsonPrimitive.content) to
                page["text"]!!.jsonPrimitive.content
        }
    }

    /** Every table on [page], nested ones included, outermost first. */
    private fun all(page: Markup): List<Table> {
        val out = ArrayList<Table>()
        fun visit(table: Table) {
            out += table
            table.rows.flatMap { it.cells }.forEach { cell -> cell.tables().forEach(::visit) }
        }
        page.tables().forEach(::visit)
        return out
    }

    @Test
    fun `every table on a real page writes back as the part of the page it was found in`() {
        var tables = 0
        var cells = 0
        val failures = pages.flatMap { (where, text) ->
            all(Wikitext.parse(text)).mapNotNull { table ->
                tables++
                cells += table.rows.sumOf { it.cells.size }
                when {
                    text.substring(table.pageRange.first, table.pageRange.last + 1) != table.serialize() ->
                        "$where: the table at ${table.pageRange.first} is not what the page has there"
                    table.markup.serialize() != table.serialize() ->
                        "$where: the table's markup is not the table"
                    table.rows.any { it.markup.serialize() !in table.serialize() } ->
                        "$where: a row is not in its table"
                    table.rows.any { row ->
                        row.cells.any { it.markup.serialize() !in row.markup.serialize() }
                    } -> "$where: a cell is not inside its row"
                    else -> null
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        println("real pages: $tables tables, $cells cells")
        tables shouldBeGreaterThan MINIMUM_TABLES
        cells shouldBeGreaterThan MINIMUM_CELLS
    }

    @Test
    fun `putting each cell's own content back changes nothing on a real page`() {
        val failures = pages.flatMap { (where, text) ->
            val page = Wikitext.parse(text)
            all(page).flatMap { table ->
                (listOfNotNull(table.caption) + table.rows.flatMap { it.cells }).mapNotNull { cell ->
                    val rewritten = cell.withContent(cell.content).serialize()
                    if (rewritten == text) null else "$where: putting back \"${cell.text}\" changed the page"
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.take(LISTED).joinToString("\n"))
    }

    @Test
    fun `an edit to a table on a real page changes that table and no node outside it`() {
        val failures = pages.flatMap { (where, text) ->
            val page = Wikitext.parse(text)
            all(page).flatMap { table ->
                val range = table.pageRange
                val edits =
                    listOfNotNull(
                        table.rows.firstOrNull()?.cells?.firstOrNull()?.let { it.withContent("Zqx") },
                        table.withRow("Zqx", "Zqy"),
                        table.rows.indices.lastOrNull()?.let { table.withoutRow(it) },
                        table.withAttributes("class=\"zqx\""),
                    )
                edits.mapNotNull { edited -> outside(where, page, edited, range) }
            }
        }
        if (failures.isNotEmpty()) fail(failures.take(LISTED).joinToString("\n"))
    }

    /** What is wrong with [edited], an edit to the table at [range] of [page], outside that table. */
    private fun outside(where: String, page: Markup, edited: Markup, range: IntRange): String? {
        val before = page.serialize()
        val after = edited.serialize()
        val tail = before.length - range.last - 1
        val kept = page.nodes.filter { node -> edited.nodes.any { it === node } }
        val ranges = page.ranges()
        val lost =
            page.nodes.filter { node ->
                val at = ranges[node]!!
                (at.last < range.first || at.first > range.last) && kept.none { it === node }
            }
        return when {
            after.take(range.first) != before.take(range.first) -> "$where: the text before the table changed"
            after.takeLast(tail) != before.takeLast(tail) -> "$where: the text after the table changed"
            lost.isNotEmpty() -> "$where: ${lost.size} nodes outside the table are new instances"
            else -> null
        }
    }

    @Test
    fun `the real pages hold tables of every kind the view reads`() {
        val tables = pages.flatMap { (_, text) -> all(Wikitext.parse(text)) }
        tables.any { table -> table.rows.any { row -> row.cells.any { it.isHeader } } } shouldBe true
        tables.any { table ->
            table.rows.any { row -> row.cells.any { it.rowspan > 1 || it.colspan > 1 } }
        } shouldBe true
        tables.any { it.caption != null } shouldBe true
        tables.any { table -> table.rows.any { row -> row.cells.any { it.tables().isNotEmpty() } } } shouldBe
            true
    }

    private companion object {
        const val MINIMUM_TABLES = 40
        const val MINIMUM_CELLS = 400
        const val LISTED = 20
    }
}
