package com.fenakhay.kwikibot.wikitext

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CosmeticChangesTest {

    private fun tidy(pass: CosmeticPass, text: String): String = CosmeticChanges.of(pass).apply(text)

    @Test
    fun `trailing whitespace goes and nothing else moves`() {
        val before = "==English==   \nA word.  \n\n{{col|en|a|b}}\t\n"

        tidy(CosmeticChanges.TRAILING_WHITESPACE, before) shouldBe "==English==\nA word.\n\n{{col|en|a|b}}\n"
    }

    @Test
    fun `whitespace inside nowiki is content and stays`() {
        val before = "<nowiki>two  spaces   \nand a line</nowiki>  \n"

        tidy(CosmeticChanges.TRAILING_WHITESPACE, before) shouldBe
            "<nowiki>two  spaces   \nand a line</nowiki>\n"
    }

    @Test
    fun `three blank lines become two`() {
        tidy(CosmeticChanges.EXTRA_BLANK_LINES, "a\n\n\n\n\nb") shouldBe "a\n\n\nb"
        tidy(CosmeticChanges.EXTRA_BLANK_LINES, "a\n\n\nb") shouldBe "a\n\n\nb"
    }

    @Test
    fun `a link whose text repeats its target loses the text`() {
        tidy(CosmeticChanges.REDUNDANT_LINK_TEXT, "[[volcano|volcano]] erupts") shouldBe "[[volcano]] erupts"
    }

    @Test
    fun `a link whose text differs in case is left alone`() {
        val before = "[[Volcano|volcano]] and [[volcano|volcanoes]]"

        tidy(CosmeticChanges.REDUNDANT_LINK_TEXT, before) shouldBe before
    }

    @Test
    fun `bold and italic tags become the markup that means the same thing`() {
        tidy(CosmeticChanges.HTML_EMPHASIS, "<b>loud</b> and <i>soft</i>") shouldBe "'''loud''' and ''soft''"
    }

    @Test
    fun `strong and em are left alone, because they say something bold and italic do not`() {
        val before = "<strong>warning</strong> <em>stress</em>"

        tidy(CosmeticChanges.HTML_EMPHASIS, before) shouldBe before
    }

    @Test
    fun `markup that is already markup is not touched`() {
        val before = "'''loud''' and ''soft''"

        tidy(CosmeticChanges.HTML_EMPHASIS, before) shouldBe before
    }

    @Test
    fun `a bare br becomes self-closing`() {
        tidy(CosmeticChanges.SELF_CLOSING_BR, "one<br>two") shouldBe "one<br />two"
        tidy(CosmeticChanges.SELF_CLOSING_BR, "one<br />two") shouldBe "one<br />two"
    }

    @Test
    fun `entities with a plain character behind them are decoded`() {
        tidy(CosmeticChanges.HTML_ENTITIES, "1990&ndash;1995 caf&eacute;") shouldBe "1990–1995 café"
    }

    @Test
    fun `numeric entities are decoded, in decimal and in hex`() {
        tidy(CosmeticChanges.HTML_ENTITIES, "&#8211; and &#x2014;") shouldBe "– and —"
    }

    @Test
    fun `the entities that are markup are never decoded`() {
        val before = "&lt;ref&gt; &amp; a&nbsp;b &quot;q&quot;"

        tidy(CosmeticChanges.HTML_ENTITIES, before) shouldBe before
    }

    @Test
    fun `an entity inside nowiki is what the page displays and stays`() {
        val before = "<nowiki>&ndash;</nowiki> but &ndash; here"

        tidy(CosmeticChanges.HTML_ENTITIES, before) shouldBe "<nowiki>&ndash;</nowiki> but – here"
    }

    @Test
    fun `a heading with nothing under it goes`() {
        val before = "==English==\nA word.\n\n==Etymology==\n\n==French==\nUn mot.\n"

        tidy(CosmeticChanges.EMPTY_SECTIONS, before) shouldBe "==English==\nA word.\n\n==French==\nUn mot.\n"
    }

    @Test
    fun `a section holding only a comment is not empty`() {
        val before = "==English==\n<!-- to be written -->\n"

        tidy(CosmeticChanges.EMPTY_SECTIONS, before) shouldBe before
    }

    @Test
    fun `a section holding only a template is not empty`() {
        val before = "==English==\n{{rfdef|en}}\n"

        tidy(CosmeticChanges.EMPTY_SECTIONS, before) shouldBe before
    }

    @Test
    fun `the safe set changes only what nobody disputes`() {
        val before = "==English==  \n[[volcano|volcano]]\n\n\n\n\nA word.\n"

        CosmeticChanges.SAFE.apply(before) shouldBe "==English==\n[[volcano]]\n\n\nA word.\n"
    }

    @Test
    fun `a page with nothing to tidy comes back byte for byte`() {
        val before = "==English==\n\n===Noun===\n{{en-noun}}\n\n# A [[volcano]].\n"

        CosmeticChanges.SAFE.apply(before) shouldBe before
        CosmeticChanges.SAFE.wouldChange(before) shouldBe false
    }

    @Test
    fun `passes compose in the order they were given`() {
        val tidier =
            CosmeticChanges.of(
                CosmeticChanges.HTML_EMPHASIS,
                CosmeticChanges.TRAILING_WHITESPACE,
            )

        tidier.apply("<b>a</b>   \nb") shouldBe "'''a'''\nb"
    }
}
