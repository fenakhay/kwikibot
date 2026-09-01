package com.fenakhay.kwikibot.examples.compounds

import com.fenakhay.kwikibot.examples.compounds.TodoList.Layout
import com.fenakhay.kwikibot.examples.compounds.TodoList.Task
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TodoListTest {

    private fun snippet(target: String, vararg terms: String, lang: String = "en", closed: Boolean = false) =
        "* '''[[Special:Edit/$target|$target]]''' <br><br>====Derived terms====<br>" +
            "{{col|$lang|${terms.joinToString("|") { "[[$it]]" }}${if (closed) "}}" else ""}"

    private fun bare(target: String, vararg terms: String) =
        "* '''[[Special:Edit/$target|$target]]''': ${terms.joinToString("|") { "[[$it]]" }}"

    @Test
    fun `an unterminated col is parsed`() {
        TodoList.parseLine(snippet("volborthite", "calciovolborthite")) shouldBe
            Task("volborthite", "en", listOf("calciovolborthite"))
    }

    @Test
    fun `a terminated col parses identically`() {
        TodoList.parseLine(snippet("volborthite", "calciovolborthite", closed = true)) shouldBe
            TodoList.parseLine(snippet("volborthite", "calciovolborthite"))
    }

    @Test
    fun `terms keep the order the list gave them`() {
        TodoList.parseLine(snippet("volcano", "hypervolcano", "paleovolcano", "vog"))?.terms shouldBe
            listOf("hypervolcano", "paleovolcano", "vog")
    }

    @Test
    fun `surrounding whitespace does not matter`() {
        TodoList.parseLine("   " + snippet("vole", "volepox") + "   \n")?.title shouldBe "vole"
    }

    @Test
    fun `a piped link uses its target, not its display text`() {
        TodoList.parseLine(snippet("vole", "x").replace("[[x]]", "[[volepox|the pox]]"))?.terms shouldBe
            listOf("volepox")
    }

    @Test
    fun `an anchored link drops the anchor`() {
        TodoList.parseLine(snippet("vole", "x").replace("[[x]]", "[[volepox#English]]"))?.terms shouldBe
            listOf("volepox")
    }

    @Test
    fun `underscores become spaces`() {
        TodoList.parseLine(snippet("vole", "x").replace("[[x]]", "[[vole_pox]]"))?.terms shouldBe
            listOf("vole pox")
    }

    @Test
    fun `html entities are unescaped`() {
        TodoList.parseLine(snippet("T", "AT&amp;T"))?.terms shouldBe listOf("AT&T")
    }

    @Test
    fun `titles with apostrophes survive the bold markup around them`() {
        TodoList.parseLine(snippet("'s", "let's"))?.title shouldBe "'s"
    }

    @Test
    fun `diacritics and non-latin titles are kept as written`() {
        TodoList.parseLine(snippet("café", "caffè"))?.title shouldBe "café"
        TodoList.parseLine(snippet("中文", "中文字"))?.terms shouldBe listOf("中文字")
    }

    @Test
    fun `lines that are not entries are rejected`() {
        TodoList.parseLine("").shouldBeNull()
        TodoList.parseLine("not a bullet").shouldBeNull()
        TodoList.parseLine("* '''[[volcano]]''' no Special:Edit target").shouldBeNull()
        TodoList.parseLine("* '''[[Special:Edit/vole|vole]]''' no payload marker").shouldBeNull()
        TodoList.parseLine(snippet("vole")).shouldBeNull()
    }

    @Test
    fun `links before the marker are not terms`() {
        TodoList.parseLine(snippet("vole", "volepox"))?.terms shouldBe listOf("volepox")
    }

    @Test
    fun `duplicates within a line are dropped`() {
        TodoList.parseLine(snippet("vole", "volepox", "volepox"))?.terms shouldBe listOf("volepox")
    }

    @Test
    fun `a term that is the target itself is dropped`() {
        TodoList.parseLine(snippet("vole", "vole", "volepox"))?.terms shouldBe listOf("volepox")
    }

    @Test
    fun `a line whose only term is self-referential is not work`() {
        TodoList.parseLine(snippet("blah", "blah")).shouldBeNull()
    }

    @Test
    fun `a non-english container language is captured`() {
        TodoList.parseLine(snippet("Haus", "Hausbau", lang = "de"))?.lang shouldBe "de"
    }

    @Test
    fun `the container may be any of the column templates`() {
        for (container in listOf("col", "col3", "col4", "der3", "rel4")) {
            val line = snippet("vole", "volepox").replace("{{col|", "{{$container|")
            TodoList.parseLine(line)?.terms shouldBe listOf("volepox")
        }
    }

    @Test
    fun `off-wiki targets are rejected`() {
        for (title in listOf("w:Etsy", "commons:Foo", "fr:volcan", "Category:X", "User talk:Y")) {
            TodoList.isOffWiki(title) shouldBe true
            TodoList.parseLine(snippet(title, "term")).shouldBeNull()
        }
    }

    @Test
    fun `titles that merely contain a colon are kept`() {
        for (title in listOf(":-)", "Nineteen Eighty-Four: A Novel", "volcano")) {
            TodoList.isOffWiki(title) shouldBe false
        }
    }

    @Test
    fun `the bare layout parses and defaults to english`() {
        val task = TodoList.parseLine(bare("Amerindian", "Amerindianism", "Amerindianist"))

        task shouldBe Task("Amerindian", "en", listOf("Amerindianism", "Amerindianist"))
    }

    @Test
    fun `the bare layout drops self-references and duplicates too`() {
        TodoList.parseLine(bare("blah", "blah", "blahs", "blahs"))?.terms shouldBe listOf("blahs")
    }

    @Test
    fun `a bare line with no terms is not work`() {
        TodoList.parseLine("* '''[[Special:Edit/blah|blah]]''': ").shouldBeNull()
    }

    @Test
    fun `a snippet line is never mistaken for a bare one`() {
        TodoList.extractLine(snippet("vole", "volepox"))?.layout shouldBe Layout.SNIPPET
        TodoList.extractLine(bare("vole", "volepox"))?.layout shouldBe Layout.BARE
    }

    @Test
    fun `a page counts the bullet lines it could not use`() {
        val page = """
            Some prose that is not a list.
            ${snippet("vole", "volepox")}
            * a bullet that is not an entry
            ${snippet("blah", "blah")}
        """.trimIndent()

        val report = TodoList.parsePage(page)

        report.tasks.size shouldBe 1
        report.skippedLines shouldBe 2
    }

    @Test
    fun `repeated targets merge, keeping first-seen order`() {
        val page = listOf(
            snippet("vole", "volepox"),
            snippet("volcano", "vog"),
            snippet("vole", "volery"),
        ).joinToString("\n")

        val tasks = TodoList.parsePage(page).tasks

        tasks.map { it.title } shouldBe listOf("vole", "volcano")
        tasks.first().terms shouldBe listOf("volepox", "volery")
    }

    @Test
    fun `both layouts merge in one page`() {
        val page = "${snippet("vole", "volepox")}\n${bare("vole", "volery")}"

        TodoList.parsePage(page).tasks.single().terms shouldBe listOf("volepox", "volery")
    }

    @Test
    fun `a line renders back into the layout it came from`() {
        val bareLine = bare("blah", "blahs")
        val snippetLine = snippet("vole", "volepox")

        TodoList.renderLine("blah", listOf("blahs")) shouldBe bareLine
        TodoList.renderLine("vole", listOf("volepox"), layout = Layout.SNIPPET) shouldBe snippetLine
    }

    @Test
    fun `the rendered snippet leaves its col unterminated`() {
        val rendered = TodoList.renderLine("vole", listOf("volepox"), layout = Layout.SNIPPET)

        rendered.endsWith("{{col|en|[[volepox]]") shouldBe true
    }

    @Test
    fun `extract reports what parse filters out`() {
        val offWiki = TodoList.extractLine(snippet("w:Etsy", "term"))

        offWiki?.title shouldBe "w:Etsy"
        TodoList.parseLine(snippet("w:Etsy", "term")).shouldBeNull()
    }
}
