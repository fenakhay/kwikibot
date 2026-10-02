package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.TextNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Section headings an HTML tag holds, which MediaWiki counts as sections of the page. */
class SectionNodesTest {

    private fun heading(text: String): Heading = Wikitext.parse(text).nodes.single() as Heading

    private val first = heading("== a ==")
    private val second = heading("== b ==")

    /** `lead\n<div>x\n== a ==\nin a\n</div> after\n== b ==\nin b`, with the div around the first heading. */
    private val page =
        Markup(
            listOf(
                TextNode("lead\n"),
                Tag("div", Markup(listOf(TextNode("x\n"), first, TextNode("\nin a\n")))),
                TextNode(" after\n"),
                second,
                TextNode("\nin b"),
            )
        )

    @Test
    fun `a heading inside a div is a section heading`() {
        page.headings() shouldBe listOf(first, second)
        page.outline().subsections.map { it.heading } shouldBe listOf(first, second)
    }

    @Test
    fun `the outline cuts through the div and still writes back as the page`() {
        val outline = page.outline()

        outline.serialize() shouldBe page.serialize()
        outline.nodes.joinToString("") { it.serialize() } shouldBe "lead\n<div>x\n"
        outline.subsections[0].serialize() shouldBe "== a ==\nin a\n</div> after\n"
    }

    @Test
    fun `a section inside a div can be replaced`() {
        val outline = page.outline()
        val replacement = Section(heading("== a =="), Wikitext.parse("\nnew\n").nodes)

        page.replaceSection(outline.subsections[0], replacement).serialize() shouldBe
            "lead\n<div>x\n== a ==\nnew\n== b ==\nin b"
    }

    @Test
    fun `nested tags are opened all the way down to the heading`() {
        val span = Tag("span", Markup(listOf(TextNode("\n"), first)))
        val nested = Markup(listOf(Tag("div", Markup(listOf(span)))))

        nested.headings() shouldBe listOf(first)
        nested.outline().serialize() shouldBe nested.serialize()
    }

    @Test
    fun `a heading inside includeonly, a template or an extension tag is no section`() {
        Markup(listOf(Tag("includeonly", Markup(listOf(TextNode("\n"), first))))).headings().shouldBeEmpty()
        Markup(listOf(Tag("ref", Markup(listOf(TextNode("\n"), first))))).headings().shouldBeEmpty()
        Wikitext.parse("{{t|\n== a ==\n}}").headings().shouldBeEmpty()
        Markup(listOf(Tag("noinclude", Markup(listOf(TextNode("\n"), first))))).headings() shouldBe
            listOf(first)
    }

    @Test
    fun `a page with no tag around a heading keeps its nodes as they are`() {
        val plain = Wikitext.parse("<div>x</div>\n== a ==\ny")

        plain.outline().nodes shouldBe plain.nodes.take(2)
    }
}
