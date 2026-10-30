package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Attribute
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Parameter
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink
import com.fenakhay.kwikibot.wikitext.ops.containsText
import com.fenakhay.kwikibot.wikitext.ops.mapNodes
import com.fenakhay.kwikibot.wikitext.ops.outline
import com.fenakhay.kwikibot.wikitext.ops.replaceText
import com.fenakhay.kwikibot.wikitext.ops.textIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Trees nested far deeper than any page, which nothing here may walk with recursion: a thread's stack would
 * run out long before the heap does.
 */
class DeepNestingTest {

    /** One way of nesting: what a level is written as around the level inside it, and the node it is. */
    private class Kind(val name: String, val open: String, val close: String, val wrap: (Markup) -> Node)

    private val kinds =
        listOf(
            Kind("template parameter", "{{a|", "}}") {
                Template(Markup.of("a"), listOf(Parameter(Markup.of("1"), it, showKey = false)))
            },
            Kind("named parameter", "{{a|k=", "}}") {
                Template(Markup.of("a"), listOf(Parameter(Markup.of("k"), it, showKey = true)))
            },
            Kind("template name", "{{", "}}") { Template(it) },
            Kind("argument default", "{{{a|", "}}}") { Argument(Markup.of("a"), it) },
            Kind("argument name", "{{{", "}}}") { Argument(it) },
            Kind("link text", "[[a|", "]]") { WikiLink(Markup.of("a"), it) },
            Kind("link target", "[[", "]]") { WikiLink(it) },
            Kind("external link title", "[https://e.org ", "]") {
                ExternalLink(Markup.of("https://e.org"), it)
            },
            Kind("bare url", "", "") { ExternalLink(it, brackets = false) },
            Kind("heading", "==", "==") { Heading(it, 2) },
            Kind("tag contents", "<div>", "</div>") { Tag("div", it) },
            Kind("attribute value", "<span title=\"", "\"/>") {
                Tag("span", attributes = listOf(Attribute(Markup.of("title"), it, "\"")), selfClosing = true)
            },
            Kind("bold", "'''", "'''") { Tag("b", it, wikiMarkup = "'''") },
        )

    @Test
    fun `every kind of nesting writes back however deep it goes`() {
        for (kind in kinds) {
            val code = nest(DEPTH) { _, inner -> kind.wrap(inner) }

            code.serialize() shouldBe kind.open.repeat(DEPTH) + "x" + kind.close.repeat(DEPTH)
        }
    }

    @Test
    fun `every kind of nesting gives each node its range however deep it goes`() {
        for (kind in kinds) {
            val code = nest(DEPTH) { _, inner -> kind.wrap(inner) }
            val innermost = code.allNodes().last()
            val ranges = code.ranges()

            ranges[code] shouldBe (0 until DEPTH * (kind.open.length + kind.close.length) + 1)
            ranges[innermost] shouldBe DEPTH * kind.open.length..DEPTH * kind.open.length
        }
    }

    @Test
    fun `all the kinds nested inside one another write back and walk`() {
        val code = nest(DEPTH) { level, inner -> kinds[level % kinds.size].wrap(inner) }
        val expected = StringBuilder("x")
        for (level in 0 until DEPTH) {
            val kind = kinds[level % kinds.size]
            expected.insert(0, kind.open).append(kind.close)
        }

        code.serialize() shouldBe expected.toString()
        code.allNodes().count { it is TextNode && it.text == "x" } shouldBe 1
    }

    @Test
    fun `trees built alike are equal and hash alike however deep they go`() {
        for (kind in kinds) {
            val code = nest(DEPTH) { _, inner -> kind.wrap(inner) }
            val copy = nest(DEPTH) { _, inner -> kind.wrap(inner) }

            (code == copy) shouldBe true
            (code.nodes.single() == copy.nodes.single()) shouldBe true
            code.hashCode() shouldBe copy.hashCode()
            (code == copy) shouldBe true
        }
    }

    @Test
    fun `trees that differ only at the bottom are unequal however deep they go`() {
        for (kind in kinds) {
            val code = nest(DEPTH) { _, inner -> kind.wrap(inner) }
            val other = nest(DEPTH, bottom = "y") { _, inner -> kind.wrap(inner) }

            (code == other) shouldBe false
            (code.nodes.single() == other.nodes.single()) shouldBe false
            (code.hashCode() == other.hashCode()) shouldBe false
            (code == other) shouldBe false
        }
    }

    @Test
    fun `text is read out however deep it sits`() {
        nest(DEPTH) { _, inner -> WikiLink(Markup.of("a"), inner) }.text shouldBe "x"
        nest(DEPTH) { _, inner -> Tag("div", inner) }.text shouldBe "x"
        nest(DEPTH) { _, inner -> templateAround(inner) }.text shouldBe ""

        val everything = nest(DEPTH) { _, inner -> templateAround(inner) }.textIn(TextScope.EVERYWHERE)
        everything.size shouldBe 2 * DEPTH + 1
        everything.last() shouldBe "x"
        nest(DEPTH) { _, inner -> Tag("div", inner) }.containsText(Regex("x")) shouldBe true
    }

    @Test
    fun `text is replaced and nodes are mapped however deep they sit`() {
        val templates = nest(DEPTH) { _, inner -> templateAround(inner) }
        val divs = nest(DEPTH) { _, inner -> Tag("div", inner) }

        templates.replaceText(Regex("x"), "y", TextScope.EVERYWHERE).serialize() shouldBe
            "{{a|".repeat(DEPTH) + "y" + "}}".repeat(DEPTH)
        divs.mapNodes { if (it is TextNode) TextNode(it.text.uppercase()) else it }.serialize() shouldBe
            "<div>".repeat(DEPTH) + "X" + "</div>".repeat(DEPTH)
        divs.mapNodes { it } shouldBe divs
    }

    @Test
    fun `a node is replaced or removed however deep it sits`() {
        val code = nest(DEPTH) { _, inner -> templateAround(inner) }
        val middle = code.templates()[DEPTH / 2]

        code.replace(TextNode("x"), TextNode("y")).serialize() shouldBe
            "{{a|".repeat(DEPTH) + "y" + "}}".repeat(DEPTH)
        code.remove(TextNode("x")).serialize() shouldBe "{{a|".repeat(DEPTH) + "}}".repeat(DEPTH)
        code.replace(middle, TextNode("z")).serialize() shouldBe
            "{{a|".repeat(DEPTH / 2) + "z" + "}}".repeat(DEPTH / 2)
        code.replace(TextNode("absent"), TextNode("y")) shouldBeSameInstanceAs code
    }

    @Test
    fun `templates nested in templates are each edited`() {
        val code = nest(EDITED_DEPTH) { _, inner -> templateAround(inner) }

        code.mapTemplates("a") { it.withParameter("k", "v") }.serialize() shouldBe
            "{{a|".repeat(EDITED_DEPTH) + "x" + "|k=v}}".repeat(EDITED_DEPTH)
    }

    @Test
    fun `sections are found inside tags however deep the heading sits`() {
        val heading = Markup(listOf(TextNode("\n"), Heading(Markup.of("h"), 2), TextNode("\n")))
        var code = heading
        repeat(DEPTH) { code = Markup(listOf(Tag("div", code))) }

        code.headings().size shouldBe 1
        code.outline().serialize() shouldBe code.serialize()
        code.outline().subsections.single().title shouldBe "h"
        CosmeticChanges.SAFE.apply(code).serialize() shouldBe code.serialize()
        CosmeticChanges.of(CosmeticChanges.EMPTY_SECTIONS).apply(code).serialize() shouldBe code.serialize()
    }

    @Test
    fun `a template's name is read through wrappers however deep they go`() {
        val name = nest(DEPTH) { _, inner -> Tag("noinclude", inner) }

        Template(name).title shouldBe "x"
    }

    @Test
    fun `wikitext nested ten thousand deep parses and writes back`() {
        val nestings =
            listOf(
                "{{a|" to "}}",
                "{{a|k=" to "}}",
                "{{{a|" to "}}}",
                "[[File:a.png|" to "]]",
                "<div>" to "</div>",
                "<span title=\"{{a|" to "}}\"/>",
                "<ref>" to "</ref>",
                "{{a|[[File:a.png|<span>{{{b|''[https://example.org " to "]''}}}</span>]]}}",
            )
        for ((open, close) in nestings) {
            val wikitext = open.repeat(DEPTH) + "x" + close.repeat(DEPTH)

            Wikitext.parse(wikitext).serialize() shouldBe wikitext
        }
        Wikitext.parse("{{a|".repeat(DEPTH) + "x" + "}}".repeat(DEPTH)).templates().size shouldBe DEPTH
        Wikitext.parse("<div>".repeat(DEPTH) + "x" + "</div>".repeat(DEPTH)).tags("div").size shouldBe DEPTH
    }

    @Test
    fun `a value nested deep is put into a parameter`() {
        val value = "<span>".repeat(DEPTH) + "a|b" + "</span>".repeat(DEPTH)

        Template(Markup.of("t")).withParameter("1", value).serialize() shouldBe
            "{{t|" + "<span>".repeat(DEPTH) + "a{{!}}b" + "</span>".repeat(DEPTH) + "}}"
    }

    @Test
    fun `templates nested a hundred thousand deep are parsed and compared and read`() {
        val wikitext = "{{a|".repeat(PLATFORM_DEPTH) + "x" + "}}".repeat(PLATFORM_DEPTH)
        val code = Wikitext.parse(wikitext)
        val again = Wikitext.parse(wikitext)

        code.serialize() shouldBe wikitext
        (code == again) shouldBe true
        code.hashCode() shouldBe again.hashCode()
        code.allNodes().count() shouldBe 3 * PLATFORM_DEPTH + 1
        code.text shouldBe ""
    }

    @Test
    fun `real pages read the same below the depth where recursion stops`() {
        val pages =
            Json.parseToJsonElement(testResource("roundtrip-pages.json"))
                .jsonObject["pages"]!!
                .jsonArray
                .map {
                    it.jsonObject["text"]!!.jsonPrimitive.content
                }
        val upper: (Node) -> Node = { if (it is TextNode) TextNode(it.text.uppercase()) else it }
        val letters = Regex("[ae]")

        for (page in pages) {
            val code = Wikitext.parse(page)
            val wrapped = wrapped(code)
            val ranges = code.ranges()
            val wrappedRanges = wrapped.ranges()

            wrapped.serialize() shouldBe "<div>".repeat(WRAPPED) + page + "</div>".repeat(WRAPPED)
            code.allNodes().all {
                wrappedRanges[it] == ranges[it]?.let { r -> r.first + 5 * WRAPPED..r.last + 5 * WRAPPED }
            } shouldBe true
            unwrapped(wrapped.mapNodes(upper)) shouldBe code.mapNodes(upper)
            unwrapped(wrapped.replaceText(letters, "o", TextScope.EVERYWHERE)) shouldBe
                code.replaceText(letters, "o", TextScope.EVERYWHERE)
            wrapped.textIn(TextScope.EVERYWHERE) shouldBe code.textIn(TextScope.EVERYWHERE)
            wrapped.text shouldBe code.text
            (wrapped(Wikitext.parse(page)) == wrapped) shouldBe true
        }
    }

    /** [code] inside more `<div>`s than are walked by recursion. */
    private fun wrapped(code: Markup): Markup = nest(WRAPPED, code) { _, inner -> Tag("div", inner) }

    private fun unwrapped(code: Markup): Markup {
        var inner = code
        repeat(WRAPPED) { inner = checkNotNull((inner.nodes.single() as Tag).contents) }
        return inner
    }

    private fun templateAround(inner: Markup): Template =
        Template(Markup.of("a"), listOf(Parameter(Markup.of("1"), inner, showKey = false)))

    /**
     * A tree [depth] levels deep with [bottom] at the bottom, each level made by [wrap] from its number,
     * counted from the bottom, and the level inside it.
     */
    private fun nest(depth: Int, bottom: String = "x", wrap: (level: Int, inner: Markup) -> Node): Markup =
        nest(depth, Markup.of(bottom), wrap)

    private fun nest(depth: Int, bottom: Markup, wrap: (level: Int, inner: Markup) -> Node): Markup {
        var code = bottom
        for (level in 0 until depth) code = Markup(listOf(wrap(level, code)))
        return code
    }

    private companion object {
        const val DEPTH = 10_000

        /** More levels than are walked by recursion before a walk with a stack of its own takes over. */
        const val WRAPPED = 40

        /**
         * Deeper than recursion could go anywhere: a thread would need ten megabytes of stack or more, beyond
         * what a native thread or Node has.
         */
        const val PLATFORM_DEPTH = 100_000

        /** As deep as a chain of templates each edited in turn is made, which costs a pass per template. */
        const val EDITED_DEPTH = 2_000
    }
}
