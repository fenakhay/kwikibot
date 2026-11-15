package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TableBuilderTest {

    @Test
    fun `a table is a wikitable one cell to a line a row marker between rows`() {
        val text =
            TableBuilder()
                .sortable()
                .caption("Volcanoes")
                .header("Name", "Height")
                .row("Etna", "3,357 m")
                .row("Vesuvius", "1,281 m")
                .build()

        text shouldBe
            """
            {| class="wikitable sortable"
            |+ Volcanoes
            ! Name
            ! Height
            |-
            | Etna
            | 3,357 m
            |-
            | Vesuvius
            | 1,281 m
            |}
            """
                .trimIndent()
    }

    @Test
    fun `inline rows put their cells on one line`() {
        TableBuilder().inline().header("A", "B").row("1", "2").build() shouldBe
            "{| class=\"wikitable\"\n! A !! B\n|-\n| 1 || 2\n|}"
    }

    @Test
    fun `the class can be left out and attributes added`() {
        TableBuilder().plain().row("a").build() shouldBe "{|\n| a\n|}"
        TableBuilder().plain().sortable().attributes(" style=\"width:100%\" ").row("a").build() shouldBe
            "{| class=\"sortable\" style=\"width:100%\"\n| a\n|}"
        TableBuilder().sortable(false).build() shouldBe "{| class=\"wikitable\"\n|}"
        assertFailsWith<IllegalArgumentException> { TableBuilder().attributes("a\nb") }
    }

    @Test
    fun `each cell can be a header and carry attributes`() {
        val cells =
            listOf(
                TableBuilder.Cell("Etna", "scope=\"row\"", isHeader = true),
                TableBuilder.Cell("3,357", "colspan=\"2\""),
                TableBuilder.Cell(""),
            )

        TableBuilder().inline().row(cells).build() shouldBe
            "{| class=\"wikitable\"\n! scope=\"row\" | Etna\n| colspan=\"2\" | 3,357 ||\n|}"
    }

    @Test
    fun `a cell over several lines is written on lines of its own and the next cell after it`() {
        TableBuilder().inline().row("a", "b\nc", "d").build() shouldBe
            "{| class=\"wikitable\"\n| a || b\nc\n| d\n|}"
    }

    @Test
    fun `markup cells are written as they are`() {
        val cell = TableBuilder.Cell(Wikitext.parse("[[Etna|the mountain]]"))

        TableBuilder().row(listOf(cell)).build() shouldBe
            "{| class=\"wikitable\"\n| [[Etna|the mountain]]\n|}"
        TableBuilder().caption(Markup.of("''c''")).build() shouldBe "{| class=\"wikitable\"\n|+ ''c''\n|}"
        cell.content.serialize() shouldBe "[[Etna|the mountain]]"
        cell.toString() shouldBe "[[Etna|the mountain]]"
        TableBuilder.Cell("x | y").toString() shouldBe "x | y"
        TableBuilder.Cell("x | y").content.serialize() shouldBe "x | y"
    }

    @Test
    fun `text that would break the table is escaped`() {
        val text =
            TableBuilder().inline().caption("a || b").header("x !! y", "z").row("a || b", "c\n| d").build()

        text shouldBe
            "{| class=\"wikitable\"\n|+ a &#124;&#124; b\n! x &#33;&#33; y !! z\n|-\n| a &#124;&#124; b || c\n&#124; d\n|}"
        val table = Wikitext.parse(text).tables().single()
        table.caption!!.text shouldBe "a &#124;&#124; b"
        table.rows.map { row -> row.cells.size } shouldBe listOf(2, 2)
    }

    @Test
    fun `what escaping cannot make safe is refused`() {
        assertFailsWith<IllegalArgumentException> { TableBuilder().row("a {{!}} b").build() }
            .message shouldContain "first line"
        assertFailsWith<IllegalArgumentException> { TableBuilder().row("a\n{|\n| b").build() }
        assertFailsWith<IllegalArgumentException> {
            TableBuilder().row(listOf(TableBuilder.Cell(Markup.of("a | b")))).build()
        }
        assertFailsWith<IllegalArgumentException> { TableBuilder.Cell("a", "x | y") }
        assertFailsWith<IllegalArgumentException> { TableBuilder.Cell("a", "x !! y", isHeader = true) }
    }

    @Test
    fun `a nested table written whole goes in its cell`() {
        val text = TableBuilder().row("\n{|\n| inner\n|}").build()

        text shouldBe "{| class=\"wikitable\"\n|\n{|\n| inner\n|}\n|}"
        Wikitext.parse(text)
            .tables()
            .single()
            .rows[0]
            .cells[0]
            .tables()
            .single()
            .rows[0]
            .cells[0]
            .text shouldBe "inner"
    }

    @Test
    fun `a caption escapes pipes and leaves bangs alone`() {
        TableBuilder().caption("Wow!! a | b").build() shouldBe
            "{| class=\"wikitable\"\n|+ Wow!! a &#124; b\n|}"
    }

    @Test
    fun `the markup is the table parsed`() {
        val markup = TableBuilder().header("A").row("1").toMarkup()

        markup.tables().single().headers shouldBe listOf("A")
        markup.serialize() shouldBe "{| class=\"wikitable\"\n! A\n|-\n| 1\n|}"
    }

    @Test
    fun `content a row marker would be made of keeps its space`() {
        TableBuilder().row("-1", "}").build() shouldBe "{| class=\"wikitable\"\n| -1\n| }\n|}"
    }
}
