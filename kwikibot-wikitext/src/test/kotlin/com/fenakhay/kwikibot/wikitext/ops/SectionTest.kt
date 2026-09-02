package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.node.Heading
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SectionTest {

    private val entry =
        """
        {{also|Vog}}
        ==English==

        ===Etymology===
        {{blend|en|volcano|fog}}

        ===Noun===
        {{en-noun}}

        # Volcanic smog.

        ====Derived terms====
        {{col|en|vog-free}}

        ==Swedish==

        ===Noun===
        {{sv-noun}}
        """
            .trimIndent()

    @Test
    fun `the lead is everything before the first heading`() {
        val outline = Wikitext.parse(entry).outline()

        outline.title.shouldBeNull()
        outline.level shouldBe 0
        outline.content.serialize().trim() shouldBe "{{also|Vog}}"
    }

    @Test
    fun `top-level sections sit under the lead`() {
        val outline = Wikitext.parse(entry).outline()

        outline.subsections.map { it.title } shouldBe listOf("English", "Swedish")
        outline.subsections.map { it.level } shouldBe listOf(2, 2)
    }

    @Test
    fun `deeper headings nest, and shallower ones close`() {
        val english = checkNotNull(Wikitext.parse(entry).outline().find("English"))

        english.subsections.map { it.title } shouldBe listOf("Etymology", "Noun")

        val noun = checkNotNull(english.find("Noun"))
        noun.subsections.map { it.title } shouldBe listOf("Derived terms")
    }

    @Test
    fun `a section carries its subsections when it is serialized`() {
        val english = checkNotNull(Wikitext.parse(entry).outline().find("English"))

        val text = english.serialize()
        text shouldContain "===Noun==="
        text shouldContain "====Derived terms===="
        text.contains("sv-noun") shouldBe false
    }

    @Test
    fun `a heading name repeated under two languages is told apart by its parent`() {
        val outline = Wikitext.parse(entry).outline()

        val englishNoun = checkNotNull(outline.find("English")).find("Noun")
        val swedishNoun = checkNotNull(outline.find("Swedish")).find("Noun")

        englishNoun?.content?.serialize()?.contains("en-noun") shouldBe true
        swedishNoun?.content?.serialize()?.contains("sv-noun") shouldBe true
    }

    @Test
    fun `a level can be required, so a language section is not confused with a heading`() {
        val page = Wikitext.parse("==Noun==\ntop\n\n===Noun===\nnested")
        val outline = page.outline()

        outline.find("Noun", level = 2)?.content?.serialize()?.trim() shouldBe "top"
        outline.find("Noun", level = 3)?.content?.serialize()?.trim() shouldBe "nested"
    }

    @Test
    fun `headings are matched with their whitespace trimmed`() {
        val outline = Wikitext.parse("== English ==\ntext").outline()

        outline.find("English")?.title shouldBe "English"
    }

    @Test
    fun `the outline reassembles into the original page`() {
        Wikitext.parse(entry).outline().serialize() shouldBe entry
    }

    @Test
    fun `replacing a section leaves the rest of the page untouched`() {
        val code = Wikitext.parse(entry)
        val derived = checkNotNull(code.outline().find("Derived terms"))

        val updated =
            code.replaceSection(
                derived,
                derived.withContent(Wikitext.parse("\n{{col|en|vog-free|vogging}}\n\n")),
            )

        updated.serialize() shouldBe entry.replace("{{col|en|vog-free}}", "{{col|en|vog-free|vogging}}")
    }

    @Test
    fun `a subsection can be added to a section`() {
        val code = Wikitext.parse("==English==\n\n===Noun===\ntext\n")
        val noun = checkNotNull(code.outline().find("Noun"))

        val added =
            noun.withSubsection(
                Section(
                    heading = Heading(Markup.of("Derived terms"), level = 4),
                    nodes = Wikitext.parse("\n{{col|en|vog}}\n").nodes,
                )
            )

        code.replaceSection(noun, added).serialize() shouldBe
            "==English==\n\n===Noun===\ntext\n====Derived terms====\n{{col|en|vog}}\n"
    }

    @Test
    fun `a subsection can be added at a chosen position`() {
        val code = Wikitext.parse("==English==\n\n===Etymology===\nfrom vog\n\n===Noun===\ntext\n")
        val english = checkNotNull(code.outline().find("English"))

        val added = english.withSubsectionAt(1, pronunciation())

        code.replaceSection(english, added).serialize() shouldBe
            "==English==\n\n===Etymology===\nfrom vog\n\n===Pronunciation===\n" +
                "* {{audio|en|vog.wav}}\n\n===Noun===\ntext\n"
    }

    @Test
    fun `adding at the end of the children is the same as appending`() {
        val code = Wikitext.parse("==English==\n\n===Noun===\ntext\n")
        val english = checkNotNull(code.outline().find("English"))

        val atEnd = english.withSubsectionAt(english.subsections.size, pronunciation())

        atEnd.serialize() shouldBe english.withSubsection(pronunciation()).serialize()
    }

    @Test
    fun `adding first puts the section above the ones already there`() {
        val code = Wikitext.parse("==English==\n\n===Noun===\ntext\n")
        val english = checkNotNull(code.outline().find("English"))

        val added = english.withSubsectionAt(0, pronunciation())

        added.subsections.map { it.title } shouldBe listOf("Pronunciation", "Noun")
    }

    @Test
    fun `a position outside the children is refused rather than silently appended`() {
        val code = Wikitext.parse("==English==\n\n===Noun===\ntext\n")
        val english = checkNotNull(code.outline().find("English"))

        assertFailsWith<IndexOutOfBoundsException> { english.withSubsectionAt(2, pronunciation()) }
        assertFailsWith<IndexOutOfBoundsException> { english.withSubsectionAt(-1, pronunciation()) }
    }

    @Test
    fun `bold that closes in a later section does not swallow the headings between`() {
        val page = "==English==\nA '''opens here\n\n==German==\nand closes''' here\n"

        Wikitext.parse(page).outline().subsections.map { it.title } shouldBe listOf("English", "German")
    }

    @Test
    fun `unbalanced bold does not swallow the headings after it`() {
        val page = "==English==\nhelp me '''1'''! and '''\n\n==German==\ntext\n"

        Wikitext.parse(page).outline().subsections.map { it.title } shouldBe listOf("English", "German")
    }

    @Test
    fun `an unclosed tag does not swallow the headings after it`() {
        val page = "==English==\n{{syn|en|2<id:Q109>}}\n\n==German==\ntext\n"

        Wikitext.parse(page).outline().subsections.map { it.title } shouldBe listOf("English", "German")
    }

    @Test
    fun `a stray closing tag does not swallow the headings after it`() {
        val page = "==English==\nA </br > B\n\n==German==\ntext\n"

        Wikitext.parse(page).outline().subsections.map { it.title } shouldBe listOf("English", "German")
    }

    @Test
    fun `a page with no headings is all lead`() {
        val outline = Wikitext.parse("just some text").outline()

        outline.subsections.isEmpty() shouldBe true
        outline.content.serialize() shouldBe "just some text"
    }

    @Test
    fun `a heading deeper than its parent by more than one level still nests`() {
        val outline = Wikitext.parse("==English==\n====Derived terms====\ntext").outline()

        val english = checkNotNull(outline.find("English"))
        english.subsections.map { it.title } shouldBe listOf("Derived terms")
    }

    private fun pronunciation() =
        Section(
            heading = Heading(Markup.of("Pronunciation"), level = 3),
            nodes = Wikitext.parse("\n* {{audio|en|vog.wav}}\n\n").nodes,
        )
}
