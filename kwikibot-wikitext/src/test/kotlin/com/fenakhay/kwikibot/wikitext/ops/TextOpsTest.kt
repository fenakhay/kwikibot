package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.TextScope
import com.fenakhay.kwikibot.wikitext.Wikitext
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TextOpsTest {

    @Test
    fun `prose is replaced and templates are left alone`() {
        val code = Wikitext.parse("The colour is red. {{colour|colour=red}}")

        val updated = code.replaceText(Regex("colour"), "color")

        updated.serialize() shouldBe "The color is red. {{colour|colour=red}}"
    }

    @Test
    fun `templates can be included deliberately`() {
        val code = Wikitext.parse("The colour is red. {{colour|colour=red}}")

        val updated = code.replaceText(Regex("colour"), "color", TextScope.EVERYWHERE)

        updated.serialize() shouldBe "The color is red. {{color|color=red}}"
    }

    @Test
    fun `nowiki content is never touched by a prose replacement`() {
        val code = Wikitext.parse("colour <nowiki>colour</nowiki> colour")

        val updated = code.replaceText(Regex("colour"), "color")

        updated.serialize() shouldBe "color <nowiki>colour</nowiki> color"
    }

    @Test
    fun `comments are left alone unless asked for`() {
        val code = Wikitext.parse("colour <!-- colour -->")

        code.replaceText(Regex("colour"), "color").serialize() shouldBe "color <!-- colour -->"
        code.replaceText(Regex("colour"), "color", TextScope.EVERYWHERE).serialize() shouldBe
            "color <!-- color -->"
    }

    @Test
    fun `a link's display text is prose but its target is not`() {
        val code = Wikitext.parse("[[colour|the colour]]")

        code.replaceText(Regex("colour"), "color").serialize() shouldBe "[[colour|the color]]"
    }

    @Test
    fun `headings are prose, since a rename usually means to include them`() {
        val code = Wikitext.parse("==colour==\ncolour")

        code.replaceText(Regex("colour"), "color").serialize() shouldBe "==color==\ncolor"
    }

    @Test
    fun `a replacement can be computed from the match`() {
        val code = Wikitext.parse("volcano and vog")

        val shouted = code.replaceText(Regex("""\bv\w+""")) { it.value.uppercase() }

        shouted.serialize() shouldBe "VOLCANO and VOG"
    }

    @Test
    fun `containsText respects the same scope`() {
        val code = Wikitext.parse("{{colour}}")

        code.containsText(Regex("colour")) shouldBe false
        code.containsText(Regex("colour"), TextScope.EVERYWHERE) shouldBe true
    }

    @Test
    fun `categories are the links in the category namespace`() {
        val code =
            Wikitext.parse("text [[Category:English lemmas]] [[volcano]] [[Category:English nouns|volcano]]")

        code.categoryNames() shouldBe listOf("English lemmas", "English nouns")
    }

    @Test
    fun `a leading colon still names the category it points at`() {
        val code = Wikitext.parse("[[:Category:English lemmas]]")

        code.categoryNames() shouldBe listOf("English lemmas")
    }

    @Test
    fun `category detection uses the prefixes the wiki actually has`() {
        val code = Wikitext.parse("[[Kategorie:Deutsch]]")

        code.categoryNames() shouldBe emptyList()
        code.categoryNames(prefixes = setOf("Kategorie")) shouldBe listOf("Deutsch")
    }

    @Test
    fun `language links are found by the codes the wiki recognises`() {
        val code = Wikitext.parse("[[fr:volcan]] [[de:Vulkan]] [[volcano]]")

        val links = code.languageLinks(codes = setOf("fr", "de"))

        links.map { it.title } shouldBe listOf("fr:volcan", "de:Vulkan")
    }

    @Test
    fun `replacement leaves everything it did not match byte for byte`() {
        val page = "==English==\n\n{{col|en|vog}}\n\ncolour here\n\n[[Category:English lemmas]]\n"

        val updated = Wikitext.parse(page).replaceText(Regex("colour"), "color")

        updated.serialize() shouldBe page.replace("colour here", "color here")
    }
}
