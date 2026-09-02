package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Wikitext
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PageFurnitureTest {

    private fun parse(text: String) = Wikitext.parse(text)

    @Test
    fun `a category is added after the ones already there`() {
        val before = "Text.\n\n[[Category:English lemmas]]\n"

        parse(before).addCategory("English nouns").serialize() shouldBe
            "Text.\n\n[[Category:English lemmas]]\n[[Category:English nouns]]\n"
    }

    @Test
    fun `a page with no categories gains one at the end`() {
        parse("Text.").addCategory("English lemmas").serialize() shouldBe
            "Text.\n[[Category:English lemmas]]\n"
    }

    @Test
    fun `a category already there is not added twice`() {
        val before = "Text.\n\n[[Category:English lemmas]]\n"

        parse(before).addCategory("English lemmas").serialize() shouldBe before
        parse(before).addCategory("english lemmas").serialize() shouldBe before
    }

    @Test
    fun `a sort key is kept when a category is added`() {
        parse("Text.").addCategory("People", sortKey = "Smith, John").serialize() shouldBe
            "Text.\n[[Category:People|Smith, John]]\n"
    }

    @Test
    fun `removing a category takes the blank line it was sitting on`() {
        val before = "Text.\n[[Category:A]]\n[[Category:B]]\n"

        parse(before).removeCategory("A").serialize() shouldBe "Text.\n[[Category:B]]\n"
    }

    @Test
    fun `removing a category that is not there changes nothing`() {
        val before = "Text.\n[[Category:A]]\n"

        parse(before).removeCategory("B").serialize() shouldBe before
    }

    @Test
    fun `changing a category keeps the sort key, which belongs to the page`() {
        val before = "[[Category:People|Smith, John]]"

        parse(before).changeCategory("People", "Writers").serialize() shouldBe
            "[[Category:Writers|Smith, John]]"
    }

    @Test
    fun `language links sort by code and nothing else moves`() {
        val before = "Text.\n\n[[fr:Volcan]]\n<!-- note -->\n[[de:Vulkan]]\n[[es:Volcán]]\n"
        val codes = setOf("de", "es", "fr")

        parse(before).sortLanguageLinks(codes).serialize() shouldBe
            "Text.\n\n[[de:Vulkan]]\n<!-- note -->\n[[es:Volcán]]\n[[fr:Volcan]]\n"
    }

    @Test
    fun `a page with one language link is left alone`() {
        val before = "Text.\n\n[[fr:Volcan]]\n"

        parse(before).sortLanguageLinks(setOf("fr")).serialize() shouldBe before
    }

    @Test
    fun `a project with its own order can supply one`() {
        val before = "[[de:A]]\n[[fr:B]]\n"
        val reversed = Comparator<String> { a, b -> b.compareTo(a) }

        parse(before).sortLanguageLinks(setOf("de", "fr"), reversed).serialize() shouldBe
            "[[fr:B]]\n[[de:A]]\n"
    }

    @Test
    fun `an ordinary link is not a language link`() {
        val before = "[[volcano]]\n[[fr:Volcan]]\n"

        parse(before).sortLanguageLinks(setOf("fr")).serialize() shouldBe before
    }
}
