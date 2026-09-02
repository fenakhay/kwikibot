package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.WikiLink
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MarkupTest {

    @Test
    fun `templates are found, including nested ones`() {
        val code = Wikitext.parse("{{col|en|{{l|en|vog}}|[[volcano]]}}")

        code.templates().map { it.title } shouldBe listOf("col", "l")
    }

    @Test
    fun `templates can be found by name`() {
        val code = Wikitext.parse("{{col|en}} {{l|en|x}} {{col|fr}}")

        code.templates("col").size shouldBe 2
    }

    @Test
    fun `template names match the way MediaWiki matches them`() {
        Wikitext.parse("{{Col|en}}").templates("col").size shouldBe 1
        Wikitext.parse("{{col|en}}").templates("Col").size shouldBe 1
        Wikitext.parse("{{coL|en}}").templates("col").size shouldBe 0

        Wikitext.parse("{{col_top|en}}").templates("col top").size shouldBe 1
    }

    @Test
    fun `parameters are readable by name and by position`() {
        val template = Wikitext.parse("{{col|en|hypervolcano|title=Terms}}").templates().single()

        template.value("1") shouldBe "en"
        template.value("2") shouldBe "hypervolcano"
        template.value("title") shouldBe "Terms"
        template.value("nope").shouldBeNull()
        ("title" in template) shouldBe true
    }

    @Test
    fun `a named parameter does not shift the numbering of the positional ones`() {
        val template = Wikitext.parse("{{col|en|title=Terms|vog}}").templates().single()

        template.value("1") shouldBe "en"
        template.value("2") shouldBe "vog"
    }

    @Test
    fun `the last of a repeated parameter wins, as MediaWiki does`() {
        val template = Wikitext.parse("{{col|title=First|title=Second}}").templates().single()

        template.value("title") shouldBe "Second"
    }

    @Test
    fun `removing a parameter leaves the others alone`() {
        val template = Wikitext.parse("{{col|en|title=Terms|vog}}").templates().single()

        template.withoutParameter("title").serialize() shouldBe "{{col|en|vog}}"
    }

    @Test
    fun `wikilinks report their target and display text`() {
        val links = Wikitext.parse("[[volcano]] and [[vog|volcanic smog]]").wikilinks()

        links.map { it.title } shouldBe listOf("volcano", "vog")
        links[1].text?.text shouldBe "volcanic smog"
    }

    @Test
    fun `headings report their level`() {
        val headings = Wikitext.parse("==English==\n===Noun===\n====Derived terms====").headings()

        headings.map { it.level } shouldBe listOf(2, 3, 4)
        headings.map { it.title.text } shouldBe listOf("English", "Noun", "Derived terms")
    }

    @Test
    fun `visible text drops the markup a reader never sees`() {
        val code = Wikitext.parse("A [[volcano|mountain]] {{qualifier|rare}}<!-- note --> erupts.")

        code.text shouldBe "A mountain  erupts."
    }

    @Test
    fun `a node can be replaced anywhere in the tree`() {
        val code = Wikitext.parse("see [[volcano]] and [[vog]]")
        val target = code.wikilinks().first { it.title == "vog" }

        val updated = code.replace(target, WikiLink(Markup.of("volcanic smog")))

        updated.serialize() shouldBe "see [[volcano]] and [[volcanic smog]]"
    }

    @Test
    fun `a template nested inside a link can be edited`() {
        val code = Wikitext.parse("[[{{l|en|volcano}}]]")

        val updated = code.mapTemplates("l") { it.withParameter("2", "vog") }

        updated.serialize() shouldBe "[[{{l|en|vog}}]]"
    }

    @Test
    fun `an unclosed construct stays literal text, as MediaWiki renders it`() {
        val code = Wikitext.parse("{{col|en and [[volcano")

        code.templates().isEmpty() shouldBe true
        code.serialize() shouldBe "{{col|en and [[volcano"
    }

    @Test
    fun `comments are readable and survive serialization`() {
        val code = Wikitext.parse("{{col|en<!-- keep sorted -->}}")

        code.comments().single().contents shouldBe " keep sorted "
        code.serialize() shouldBe "{{col|en<!-- keep sorted -->}}"
    }

    @Test
    fun `tags report their name and contents`() {
        val code = Wikitext.parse("""<ref name="a">see [[volcano]]</ref>""")

        val tag = code.tags("ref").single()
        tag.attributes.single().name.text shouldBe "name"
        tag.contents?.wikilinks()?.single()?.title shouldBe "volcano"
    }

    @Test
    fun `bold markup is a tag that writes itself back as markup`() {
        val code = Wikitext.parse("'''bold''' text")

        code.tags("b").single().contents?.text shouldBe "bold"
        code.serialize() shouldBe "'''bold''' text"
    }
}
