package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.TextNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TablesTest {

    private fun tables(text: String): List<Table> = Wikitext.parse(text).tables()

    private fun table(text: String): Table = tables(text).single()

    private fun Table.cells(): List<List<String>> = rows.map { row -> row.cells.map { it.text } }

    private fun Table.kinds(): List<String> = rows.map { row ->
        row.cells.joinToString("") { if (it.isHeader) "h" else "d" }
    }

    @Test
    fun `the README's sample reads edits and builds tables`() {
        val text = "{| class=\"wikitable\"\n! Name !! Height\n|-\n| Etna || 3,357 m\n|}"

        val page = Wikitext.parse(text)
        val table = page.tables().first()

        val heights = table.column("Height").map { it?.text }
        val etna = table.rows[1].cells[0]
        val renamed = etna.withContent("[[Mount Etna]]")
        val longer = renamed.tables().first().withRow("Fuji", "3,776 m")

        val written =
            TableBuilder()
                .sortable()
                .caption("Volcanoes")
                .header("Name", "Height")
                .row("Etna", "3,357 m")
                .build()

        heights shouldBe listOf("3,357 m")
        longer.serialize() shouldBe
            "{| class=\"wikitable\"\n! Name !! Height\n|-\n| [[Mount Etna]] || 3,357 m\n|-\n| Fuji || 3,776 m\n|}"
        written shouldBe
            "{| class=\"wikitable sortable\"\n|+ Volcanoes\n! Name\n! Height\n|-\n| Etna\n| 3,357 m\n|}"
    }

    @Test
    fun `a table's rows hold its cells in order`() {
        val table = table("{|\n|a\n|b\n|-\n|c\n|}")

        table.cells() shouldBe listOf(listOf("a", "b"), listOf("c"))
        table.isClosed shouldBe true
        table.caption.shouldBeNull()
    }

    @Test
    fun `the first row needs no row marker`() {
        val table = table("{|\n| a\n|-\n| b\n|}")

        table.cells() shouldBe listOf(listOf("a"), listOf("b"))
        table.rows[0].markup.serialize() shouldBe "| a\n"
        table.rows[1].markup.serialize() shouldBe "|-\n| b\n"
    }

    @Test
    fun `markup counts only at the start of a line whitespace before it allowed`() {
        table("  {|\n  | a\n\t| b\n  |}").cells() shouldBe listOf(listOf("a", "b"))
        tables("x {|\n| a\n|}").shouldBeEmpty()
        table("{|\n| a | b\n|}").cells() shouldBe listOf(listOf("b"))
        table("{|\n| a\nx | b\n|}").cells() shouldBe listOf(listOf("a\nx | b"))
    }

    @Test
    fun `the start line's attributes are the table's and colons indent it`() {
        val table = table("::{| class=\"wikitable\" style=\"width:50%\"\n|a\n|}")

        table.attributes shouldBe "class=\"wikitable\" style=\"width:50%\""
        table.attribute("CLASS") shouldBe "wikitable"
        table.attribute("id").shouldBeNull()
        table.indent shouldBe 2
        table.serialize() shouldBe "::{| class=\"wikitable\" style=\"width:50%\"\n|a\n|}"
    }

    @Test
    fun `a caption is its own line with attributes of its own`() {
        val table = table("{|\n|+ style=\"color:red\" | The caption\n|a\n|}")

        val caption = table.caption!!
        caption.text shouldBe "The caption"
        caption.isCaption shouldBe true
        caption.attribute("style") shouldBe "color:red"
        table.cells() shouldBe listOf(listOf("a"))
    }

    @Test
    fun `a caption after a row ends it and only the first caption is the caption`() {
        val table = table("{|\n|a\n|+ late\n|b\n|+ later || again\n|}")

        table.caption!!.text shouldBe "late"
        table.cells() shouldBe listOf(listOf("a"), listOf("b"))
    }

    @Test
    fun `a row marker carries attributes and the last of several before a cell wins`() {
        val table = table("{|\n|- class=\"a\"\n|----  class=\"b\"  \n| x\n|- style=\"s\"\n|}")

        table.rows shouldHaveSize 1
        table.rows[0].attributes shouldBe "class=\"b\""
        table.rows[0].attribute("class") shouldBe "b"
        table.rows[0].index shouldBe 0
    }

    @Test
    fun `double pipes put cells on one line`() {
        table("{|\n| a || b ||c\n|}").cells() shouldBe listOf(listOf("a", "b", "c"))
    }

    @Test
    fun `a header line takes double bangs and double pipes alike a data line neither bang`() {
        table("{|\n! A !! B || C\n|}").cells() shouldBe listOf(listOf("A", "B", "C"))
        table("{|\n! A !! B\n|}").kinds() shouldBe listOf("hh")
        table("{|\n| a !! b\n|}").cells() shouldBe listOf(listOf("a !! b"))
    }

    @Test
    fun `runs of pipes and bangs are cut from the left as MediaWiki cuts them`() {
        table("{|\n|a|||b\n|}").cells() shouldBe listOf(listOf("a", "b"))
        table("{|\n!a!!!b\n|}").cells() shouldBe listOf(listOf("a", "!b"))
        table("{|\n|| a ||\n|}").cells() shouldBe listOf(listOf("a", ""))
    }

    @Test
    fun `a cell's attributes are what comes before its first single pipe`() {
        val cell = table("{|\n| style=\"color:red\" <!-- c --> | a | b\n|}").rows[0].cells[0]

        cell.attributes shouldBe "style=\"color:red\""
        cell.content.serialize() shouldBe " a | b"
        cell.text shouldBe "a | b"
    }

    @Test
    fun `a pipe inside a link or a template is not the cell's`() {
        val cells = table("{|\n| title=\"t\" | [[Page|shown]] || {{t|a|b}} x\n|}").rows[0].cells

        cells.map { it.attributes } shouldBe listOf("title=\"t\"", "")
        cells.map { it.text } shouldBe listOf("shown", "x")
    }

    @Test
    fun `a link an open link or a conversion before the first pipe leaves the cell without attributes`() {
        val texts = listOf("[[Page|shown]] | x", "[[Page | x", "-{a}- | x")

        for (text in texts) {
            val cell = table("{|\n| $text\n|}").rows[0].cells[0]
            cell.attributes shouldBe ""
            cell.content.serialize() shouldBe " $text"
        }
    }

    @Test
    fun `the pipe templates are pipes`() {
        val table = table("{|\n{{!}} a {{!}}{{!}} b\n{{!}}-\n{{!}} style=\"s\" {{!}} c {{!!}} d\n{{!}}}")

        table.cells() shouldBe listOf(listOf("a", "b"), listOf("c", "d"))
        table.rows[1].cells[0].attribute("style") shouldBe "s"
        table.isClosed shouldBe true
    }

    @Test
    fun `a double pipe template at the start of a line is a marker and an empty attribute part`() {
        val cell = table("{|\n{{!!}}a\n|}").rows[0].cells[0]

        cell.attributes shouldBe ""
        cell.text shouldBe "a"
    }

    @Test
    fun `a cell runs over every line up to the next line of table markup`() {
        val cell = table("{|\n| a\nmore\n\n* item\n| b\n|}").rows[0].cells[0]

        cell.content.serialize() shouldBe " a\nmore\n\n* item"
    }

    @Test
    fun `a comment is gone before the table is read`() {
        table("{|\n<!-- c -->| a\n<!-- d -->|-\n| b\n|}").cells() shouldBe listOf(listOf("a"), listOf("b"))
    }

    @Test
    fun `a table nested in a cell is reached from the cell not from the page`() {
        val page = Wikitext.parse("{|\n| a\n{|\n! x || y\n|}\nafter\n| b\n|}")
        val outer = page.tables().single()

        outer.cells().map { it.size } shouldBe listOf(2)
        val cell = outer.rows[0].cells[0]
        val nested = cell.tables().single()
        nested.cells() shouldBe listOf(listOf("x", "y"))
        nested.kinds() shouldBe listOf("hh")
        cell.content.serialize() shouldBe " a\n{|\n! x || y\n|}\nafter"
        outer.rows[0].cells[1].tables().shouldBeEmpty()
    }

    @Test
    fun `a table opened where no cell is open belongs to none`() {
        val outer = table("{|\n{|\n| x\n|}\n| b\n|}")

        outer.cells() shouldBe listOf(listOf("b"))
        outer.rows[0].cells[0].tables().shouldBeEmpty()
    }

    @Test
    fun `tables inside a tag's body are found starting a line where the body does`() {
        tables("<div>\n{|\n| a\n|}\n</div>").single().cells() shouldBe listOf(listOf("a"))
        tables("<div>{|\n| a\n|}</div>").shouldBeEmpty()
        tables("<ref>{|\n| a\n|}</ref>").single().cells() shouldBe listOf(listOf("a"))
        tables("<noinclude>{|\n| a\n|}</noinclude>").single().cells() shouldBe listOf(listOf("a"))
        tables("x<noinclude>{|\n| a\n|}</noinclude>").shouldBeEmpty()
        tables("<nowiki>\n{|\n| a\n|}\n</nowiki>").shouldBeEmpty()
    }

    @Test
    fun `tables are in page order those in tags among those that are not`() {
        val page = "{|\n| one\n|}\n<div>\n{|\n| two\n|}\n</div>\n{|\n| three\n|}"

        tables(page).map { it.rows[0].cells[0].text } shouldBe listOf("one", "two", "three")
    }

    @Test
    fun `a table in a tag in a cell is nested in the cell`() {
        val cell = table("{|\n| <div>\n{|\n| inner\n|}\n</div>\n|}").rows[0].cells[0]

        cell.tables().single().rows[0].cells[0].text shouldBe "inner"
    }

    @Test
    fun `a close with no table open is text and a table nothing closes runs to the end`() {
        tables("a\n|}\nb").shouldBeEmpty()
        val unclosed = table("{|\n| a\n| b\nmore")
        unclosed.isClosed shouldBe false
        unclosed.cells() shouldBe listOf(listOf("a", "b\nmore"))
        unclosed.serialize() shouldBe "{|\n| a\n| b\nmore"
    }

    @Test
    fun `what follows the close on its line is outside the table`() {
        table("{|\n| a\n|} after").serialize() shouldBe "{|\n| a\n|}"
    }

    @Test
    fun `an empty table has no rows`() {
        table("{|\n|}").rows.shouldBeEmpty()
        table("{|\n|-\n|}").rows.shouldBeEmpty()
        table("{|\n|}").grid.shouldBeEmpty()
        table("{|\n|}").columnCount shouldBe 0
    }

    @Test
    fun `a table a template opens is not found`() {
        tables("{{table start}}\n| a\n|}").shouldBeEmpty()
    }

    @Test
    fun `a cell's text is its visible text trimmed`() {
        val cell = table("{|\n|  '''bold''' [[Page|link]] {{t}}<!-- c -->  \n|}").rows[0].cells[0]

        cell.text shouldBe "bold link"
    }

    @Test
    fun `spans lay cells out over the places they cover`() {
        val table = table("{|\n| rowspan=\"2\" | a || b\n|-\n| c\n|-\n| colspan=\"2\" | d\n|}")

        table.grid.map { row -> row.map { it?.text } } shouldBe
            listOf(listOf("a", "b"), listOf("a", "c"), listOf("d", "d"))
        table.columnCount shouldBe 2
        table.cell(1, 0)!!.text shouldBe "a"
        table.cell(5, 0).shouldBeNull()
        table.cell(0, 5).shouldBeNull()
    }

    @Test
    fun `spans are read as HTML reads them`() {
        val cells =
            table("{|\n| colspan=\"x\" | a\n| colspan=\" +3px\" | b\n| colspan=0 | c\n| colspan=5000 | d\n|}")
                .rows[0]
                .cells

        cells.map { it.colspan } shouldBe listOf(1, 3, 1, 1000)
        table("{|\n| rowspan=\"0\" | a\n|}").rows[0].cells[0].rowspan shouldBe 0
        table("{|\n| rowspan=99999999 | a\n|}").rows[0].cells[0].rowspan shouldBe 65534
        table("{|\n| rowspan=00002 | a\n|}").rows[0].cells[0].rowspan shouldBe 2
    }

    @Test
    fun `a rowspan of zero reaches the last row and one past it stops there`() {
        val zero = table("{|\n| rowspan=\"0\" | a || b\n|-\n| c\n|-\n| d\n|}")
        zero.grid.map { row -> row.map { it?.text } } shouldBe
            listOf(listOf("a", "b"), listOf("a", "c"), listOf("a", "d"))

        val past = table("{|\n| rowspan=\"9\" | a\n|-\n| b\n|}")
        past.grid.map { row -> row.map { it?.text } } shouldBe listOf(listOf("a", null), listOf("a", "b"))
    }

    @Test
    fun `where two cells would cover a place the first keeps it`() {
        val table = table("{|\n| a || rowspan=\"2\" | b\n|-\n| colspan=\"2\" | c\n|}")

        table.grid.map { row -> row.map { it?.text } } shouldBe listOf(listOf("a", "b"), listOf("c", "b"))
    }

    @Test
    fun `a first row of header cells is the header row and a column is found by its header`() {
        val table = table("{|\n! Name !! colspan=\"2\" | Height\n|-\n| Etna || 3357 || m\n|-\n| Fuji\n|}")

        table.headerRow!!.index shouldBe 0
        table.headers shouldBe listOf("Name", "Height", "Height")
        table.column("Name").map { it?.text } shouldBe listOf("Etna", "Fuji")
        table.column(" Height ").map { it?.text } shouldBe listOf("3357", null)
        table.column("Width").shouldBeEmpty()
    }

    @Test
    fun `without a header row there are no headers`() {
        val table = table("{|\n! Name\n| Etna\n|}")

        table.headerRow.shouldBeNull()
        table.headers.shouldBeEmpty()
        table.column("Name").shouldBeEmpty()
    }

    private val sample =
        """
        {{intro}}
        {| class="wikitable"
        |+ Volcanoes
        ! Name !! Height
        |-
        | [[Etna]] || 3,357 m <!-- 2021 -->
        |-
        | style="color:red" | Vesuvius
        | 1,281 m
        |}
        [[Category:Volcanoes]]
        """
            .trimIndent()

    @Test
    fun `every piece of a table writes back as it was`() {
        val page = Wikitext.parse(sample)
        val table = page.tables().single()

        page.serialize() shouldBe sample
        sample shouldContain table.serialize()
        table.markup.serialize() shouldBe table.serialize()
        table.toString() shouldBe table.serialize()
        table.rows.joinToString("") { it.markup.serialize() } shouldBe
            "! Name !! Height\n|-\n| [[Etna]] || 3,357 m <!-- 2021 -->\n|-\n| style=\"color:red\" | Vesuvius\n| 1,281 m\n"
        table.rows[1].cells.map { it.toString() } shouldBe listOf("| [[Etna]] ", "|| 3,357 m <!-- 2021 -->")
        table.rows[1].toString() shouldBe table.rows[1].markup.serialize()
        table.rows[2].cells[0].attributes shouldBe "style=\"color:red\""
    }

    @Test
    fun `reading tables leaves the page as it was`() {
        val page = Wikitext.parse(sample)
        val nodes = page.nodes.toList()

        val _ = page.tables()

        page.nodes.zip(nodes).all { (a, b) -> a === b } shouldBe true
    }

    @Test
    fun `a cell's content is replaced its marker attributes and whitespace kept`() {
        val table = Wikitext.parse(sample).tables().single()

        table.rows[1].cells[0].withContent("[[Mount Etna]]").serialize() shouldBe
            sample.replace("| [[Etna]] ||", "| [[Mount Etna]] ||")
        table.rows[2].cells[0].withContent("Somma").serialize() shouldBe
            sample.replace("| style=\"color:red\" | Vesuvius", "| style=\"color:red\" | Somma")
        table.caption!!.withContent("Peaks").serialize() shouldBe sample.replace("|+ Volcanoes", "|+ Peaks")
    }

    @Test
    fun `an edit leaves every node outside the table the instance it was`() {
        val page = Wikitext.parse(sample)
        val edited = page.tables().single().rows[1].cells[1].withContent("3,403 m")

        edited.nodes.first() shouldBeSameInstanceAs page.nodes.first()
        edited.nodes.last() shouldBeSameInstanceAs page.nodes.last()
        edited.nodes.filter { node -> page.nodes.any { it === node } } shouldHaveSize page.nodes.size - 2
    }

    @Test
    fun `putting a cell's own content back changes nothing`() {
        val page = Wikitext.parse(sample)
        for (cell in page.tables().single().rows.flatMap { it.cells }) {
            cell.withContent(cell.content).serialize() shouldBe sample
        }
    }

    @Test
    fun `content brings its own whitespace when it starts or ends with some`() {
        val cell = table("{|\n| a\n|}").rows[0].cells[0]

        cell.withContent("\n{|\n| x\n|}\n").serialize() shouldBe "{|\n|\n{|\n| x\n|}\n\n|}"
    }

    @Test
    fun `an empty cell gets a space before new content and after when a cell follows`() {
        table("{|\n| || b\n|}").rows[0].cells[0].withContent("a").serialize() shouldBe "{|\n| a || b\n|}"
        table("{|\n|a||||b\n|}").rows[0].cells[1].withContent("x").serialize() shouldBe "{|\n|a||x||b\n|}"
        table("{|\n|\n| b\n|}").rows[0].cells[0].withContent("a").serialize() shouldBe "{|\n|a\n| b\n|}"
        table("{|\n|\n\n| b\n|}").rows[0].cells[0].withContent("a").serialize() shouldBe "{|\n|\na\n| b\n|}"
    }

    @Test
    fun `emptying a cell keeps the whitespace before its content`() {
        table("{|\n| a || b\n|}").rows[0].cells[0].withContent("").serialize() shouldBe "{|\n| || b\n|}"
        table("{|\n| || b\n|}").rows[0].cells[0].withContent("").serialize() shouldBe "{|\n| || b\n|}"
    }

    @Test
    fun `content a line-opening pipe would make markup of gets a space`() {
        val cell = table("{|\n|a\n|}").rows[0].cells[0]

        cell.withContent("-1").serialize() shouldBe "{|\n| -1\n|}"
        cell.withContent("}").serialize() shouldBe "{|\n| }\n|}"
        cell.withContent("+").serialize() shouldBe "{|\n| +\n|}"
        cell.withContent("1").serialize() shouldBe "{|\n|1\n|}"
    }

    @Test
    fun `text the table would read as its own markup is escaped`() {
        val data = table("{|\n| a\n|}").rows[0].cells[0]
        val header = table("{|\n! a\n|}").rows[0].cells[0]

        data.withContent("x || y | z !! w").serialize() shouldBe "{|\n| x &#124;&#124; y &#124; z !! w\n|}"
        header.withContent("x !! y").serialize() shouldBe "{|\n! x &#33;&#33; y\n|}"
        data.withContent("x\n| y\n! z\nw | v").serialize() shouldBe "{|\n| x\n&#124; y\n&#33; z\nw | v\n|}"
        data.withContent("[[a|b]] {{t|c}}").serialize() shouldBe "{|\n| [[a|b]] {{t|c}}\n|}"
    }

    @Test
    fun `a nested table written whole in the content is left as it is`() {
        val cell = table("{|\n| a\n|}").rows[0].cells[0]
        val nested = "x\n{|\n| y || z\n|-\n! w\n|}\nafter"

        val edited = cell.withContent(nested)

        edited.serialize() shouldBe "{|\n| $nested\n|}"
        edited.tables().single().rows[0].cells[0].tables().single().cells() shouldBe
            listOf(listOf("y", "z"), listOf("w"))
    }

    @Test
    fun `content that escaping cannot make safe is refused`() {
        val cell = table("{|\n| a\n|}").rows[0].cells[0]

        assertFailsWith<IllegalArgumentException> { cell.withContent("x {{!}} y") }.message shouldContain
            "first line"
        assertFailsWith<IllegalArgumentException> { cell.withContent("x\n{{!}} y") }.message shouldContain
            "new cell"
        assertFailsWith<IllegalArgumentException> { cell.withContent("x\n{|\n| y") }.message shouldContain
            "does not close"
        assertFailsWith<IllegalArgumentException> { cell.withContent(Wikitext.parse("x | y")) }
        assertFailsWith<IllegalArgumentException> {
            table("{|\n! a\n|}").rows[0].cells[0].withContent(Wikitext.parse("x !! y"))
        }
    }

    @Test
    fun `content over several lines is refused in a cell another follows on its line`() {
        val table = table("{|\n| a || b\n|}")

        assertFailsWith<IllegalArgumentException> { table.rows[0].cells[0].withContent("x\ny") }
            .message shouldContain "several lines"
        table.rows[0].cells[1].withContent("x\ny").serialize() shouldBe "{|\n| a || x\ny\n|}"
    }

    @Test
    fun `markup content is written as it is`() {
        val cell = table("{|\n| a\n|}").rows[0].cells[0]

        cell.withContent(Markup.of("x &#124; y")).serialize() shouldBe "{|\n| x &#124; y\n|}"
    }

    @Test
    fun `a cell's attributes are replaced added and taken off`() {
        val styled = table("{|\n| style=\"a\" | x\n|}").rows[0].cells[0]
        val plain = table("{|\n| x || y\n|}").rows[0].cells[1]

        styled.withAttributes("style=\"b\"").serialize() shouldBe "{|\n| style=\"b\" | x\n|}"
        styled.withAttributes("").serialize() shouldBe "{|\n| x\n|}"
        plain.withAttributes(" colspan=\"2\" ").serialize() shouldBe "{|\n| x || colspan=\"2\" | y\n|}"
    }

    @Test
    fun `blank attributes leave a cell's content unchanged`() {
        val page = Wikitext.parse("{|\n| x\n|}")
        val cell = page.tables().single().rows[0].cells[0]

        cell.withAttributes("") shouldBeSameInstanceAs page
    }

    @Test
    fun `taking attributes off keeps the pipe where the content needs it`() {
        val cell = table("{|\n| style=\"a\" | x | y\n|}").rows[0].cells[0]

        cell.withAttributes("").serialize() shouldBe "{|\n| | x | y\n|}"
    }

    @Test
    fun `attributes that cannot be a cell's are refused`() {
        val cell = table("{|\n! x\n|}").rows[0].cells[0]

        for (bad in listOf("a\nb", "a | b", "[[x]]", "-{x}-", "a !! b")) {
            assertFailsWith<IllegalArgumentException> { cell.withAttributes(bad) }
        }
    }

    @Test
    fun `a row's attributes are replaced and a row without a marker gets one`() {
        val table = table("{|\n| a\n|- class=\"x\"  \n| b\n|}")

        table.rows[1].withAttributes("class=\"y\"").serialize() shouldBe "{|\n| a\n|- class=\"y\"  \n| b\n|}"
        table.rows[1].withAttributes("").serialize() shouldBe "{|\n| a\n|-\n| b\n|}"
        table.rows[0].withAttributes("style=\"s\"").serialize() shouldBe
            "{|\n|- style=\"s\"\n| a\n|- class=\"x\"  \n| b\n|}"
        assertFailsWith<IllegalArgumentException> { table.rows[0].withAttributes("a\nb") }
    }

    @Test
    fun `blank attributes for a row without a marker leave the page as it was`() {
        val page = Wikitext.parse("{|\n| a\n|}")

        page.tables().single().rows[0].withAttributes(" ") shouldBeSameInstanceAs page
    }

    @Test
    fun `the table's attributes are replaced`() {
        val table = table("{| class=\"wikitable\"\n| a\n|}")

        table.withAttributes("class=\"plain\"").serialize() shouldBe "{| class=\"plain\"\n| a\n|}"
        table.withAttributes("").serialize() shouldBe "{|\n| a\n|}"
        table("{|\n| a\n|}").withAttributes("style=\"s\"").serialize() shouldBe "{| style=\"s\"\n| a\n|}"
        assertFailsWith<IllegalArgumentException> { table.withAttributes("a\nb") }
    }

    @Test
    fun `a new row is written as the table writes its rows`() {
        table("{|\n| a\n| b\n|}").withRow("c", "d").serialize() shouldBe "{|\n| a\n| b\n|-\n| c\n| d\n|}"
        table("{|\n| a || b\n|}").withRow("c", "d").serialize() shouldBe "{|\n| a || b\n|-\n| c || d\n|}"
        table("{|\n|a||b\n|}").withRow("c", "-d").serialize() shouldBe "{|\n|a||b\n|-\n|c||-d\n|}"
        table("{|\n|a\n|b\n|}").withRow("-c").serialize() shouldBe "{|\n|a\n|b\n|-\n| -c\n|}"
    }

    @Test
    fun `a new row goes where it is put among the rows`() {
        val table = table("{|\n! A\n|-\n| b\n|-\n|}")

        table.withRowAt(1, "x").serialize() shouldBe "{|\n! A\n|-\n| x\n|-\n| b\n|-\n|}"
        table.withRowAt(0, "x").serialize() shouldBe "{|\n| x\n|-\n! A\n|-\n| b\n|-\n|}"
        table.withRow("x").serialize() shouldBe "{|\n! A\n|-\n| b\n|-\n| x\n|-\n|}"
        table("{|\n|-\n| a\n|}").withRowAt(0, "x").serialize() shouldBe "{|\n|-\n| x\n|-\n| a\n|}"
    }

    @Test
    fun `a new row in an empty table opens with a row marker`() {
        table("{| class=\"t\"\n|}").withRow("a").serialize() shouldBe "{| class=\"t\"\n|-\n| a\n|}"
        table("{|\n|+ c\n  |}").withRow("a").serialize() shouldBe "{|\n|+ c\n|-\n| a\n  |}"
        table("{|").withRow("a").serialize() shouldBe "{|\n|-\n| a"
    }

    @Test
    fun `a new row's cells can be header cells with attributes`() {
        val cells = listOf(TableBuilder.Cell("H", "scope=\"row\"", isHeader = true), TableBuilder.Cell("v"))

        table("{|\n| a || b\n|}").withRow(cells).serialize() shouldBe
            "{|\n| a || b\n|-\n! scope=\"row\" | H\n| v\n|}"
    }

    @Test
    fun `a new row's text is escaped as a new cell's is`() {
        table("{|\n| a\n|}").withRow("x || y").serialize() shouldBe "{|\n| a\n|-\n| x &#124;&#124; y\n|}"
    }

    @Test
    fun `a row is put only among the rows there are`() {
        val table = table("{|\n| a\n|}")

        assertFailsWith<IndexOutOfBoundsException> { table.withRowAt(2, "x") }
        assertFailsWith<IndexOutOfBoundsException> { table.withRowAt(-1, listOf(TableBuilder.Cell("x"))) }
        assertFailsWith<IndexOutOfBoundsException> { table.withoutRow(1) }
    }

    @Test
    fun `a row is removed with its marker and its cells`() {
        val table = table("{|\n| a\n|-\n| b\n<!-- c -->\n|-\n| c\n|}")

        table.withoutRow(0).serialize() shouldBe "{|\n|-\n| b\n<!-- c -->\n|-\n| c\n|}"
        table.withoutRow(1).serialize() shouldBe "{|\n| a\n|-\n| c\n|}"
        table.withoutRow(2).serialize() shouldBe "{|\n| a\n|-\n| b\n<!-- c -->\n|}"
    }

    @Test
    fun `a column is added at the right of each row`() {
        val table = table("{|\n! A !! B\n|-\n| 1 || 2\n|-\n| 3\n| 4\n|}")

        table.withColumn("C", "x", "y").serialize() shouldBe
            "{|\n! A !! B !! C\n|-\n| 1 || 2 || x\n|-\n| 3\n| 4\n| y\n|}"
    }

    @Test
    fun `a new column's cells follow their own kind`() {
        val table = table("{|\n! A !! B\n|-\n| 1 || 2\n|}")
        val cells = listOf(TableBuilder.Cell("C"), TableBuilder.Cell("x", isHeader = true))

        table.withColumn(cells).serialize() shouldBe "{|\n! A !! B\n| C\n|-\n| 1 || 2\n! x\n|}"
        table.withColumn("C\nmore", "x").serialize() shouldBe "{|\n! A !! B\n! C\nmore\n|-\n| 1 || 2 || x\n|}"
    }

    @Test
    fun `a column is added only to a table whose rows line up`() {
        assertFailsWith<IllegalArgumentException> { table("{|\n| a\n|-\n| b\n|}").withColumn("x") }
        assertFailsWith<IllegalStateException> { table("{|\n| a || b\n|-\n| c\n|}").withColumn("x", "y") }
        assertFailsWith<IllegalStateException> {
            table("{|\n| rowspan=\"2\" | a\n|-\n| b\n|}").withColumn("x", "y")
        }
    }

    @Test
    fun `an edit to a nested table returns the whole page`() {
        val page = Wikitext.parse("before\n{|\n| a\n{|\n| b\n|}\n|}\nafter")
        val nested = page.tables().single().rows[0].cells[0].tables().single()

        nested.rows[0].cells[0].withContent("c").serialize() shouldBe
            "before\n{|\n| a\n{|\n| c\n|}\n|}\nafter"
        nested.withRow("d").serialize() shouldBe "before\n{|\n| a\n{|\n| b\n|-\n| d\n|}\n|}\nafter"
    }

    @Test
    fun `an edit to a table inside a tag rebuilds the tag and nothing else`() {
        val page = Wikitext.parse("{{a}}\n<div class=\"d\">\n<span>\n{|\n| x\n|}\n</span>\n</div>\n{{b}}")
        val table = page.tables().single()

        val edited = table.rows[0].cells[0].withContent("y")

        edited.serialize() shouldBe "{{a}}\n<div class=\"d\">\n<span>\n{|\n| y\n|}\n</span>\n</div>\n{{b}}"
        edited.nodes.first() shouldBeSameInstanceAs page.nodes.first()
        edited.nodes.last() shouldBeSameInstanceAs page.nodes.last()
        val div = edited.nodes.filterIsInstance<Tag>().single()
        div.attributes shouldBeSameInstanceAs page.nodes.filterIsInstance<Tag>().single().attributes
    }

    @Test
    fun `an edit that would change the table some other way is refused`() {
        val cell = table("{|\n| a\n|}").rows[0].cells[0]

        assertFailsWith<IllegalArgumentException> { cell.withContent(Markup(listOf(TextNode("x\n|}")))) }
    }
}
