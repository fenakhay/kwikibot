package com.fenakhay.kwikibot.examples.compounds

import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.outline
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class EntryLayoutTest {

    private val entry = """
        ==English==

        ===Etymology===
        From somewhere.

        ===Noun===
        {{en-noun}}

        # A definition.

        ====Derived terms====
        {{col|en|vog}}

        ====References====
        * A reference.

        ==Swedish==

        ===Noun===
        {{sv-noun}}

        [[Category:English lemmas]]
    """.trimIndent()

    @Test
    fun `the language section is found and excludes the others`() {
        val english = checkNotNull(EntryLayout.language(Wikitext.parse(entry), "English"))

        english.serialize().contains("en-noun") shouldBe true
        english.serialize().contains("sv-noun") shouldBe false
    }

    @Test
    fun `a language the entry does not have is absent`() {
        EntryLayout.language(Wikitext.parse(entry), "French").shouldBeNull()
    }

    @Test
    fun `a language name appearing in body text is not a section`() {
        val page = Wikitext.parse("==English==\nThe word French appears here.\n")

        EntryLayout.language(page, "French").shouldBeNull()
    }

    @Test
    fun `a heading with padding still matches`() {
        val page = Wikitext.parse("== English ==\ntext\n")

        EntryLayout.language(page, "English")?.title shouldBe "English"
    }

    @Test
    fun `the whole page is recoverable from its sections`() {
        Wikitext.parse(entry).outline().serialize() shouldBe entry
    }

    @Test
    fun `parts of speech are found at level three`() {
        val english = checkNotNull(EntryLayout.language(Wikitext.parse(entry), "English"))

        EntryLayout.posLevel(english) shouldBe 3
        EntryLayout.posSections(english).map { it.title } shouldBe listOf("Noun")
    }

    @Test
    fun `two parts of speech are both reported`() {
        val page = Wikitext.parse("==English==\n\n===Noun===\nn\n\n===Verb===\nv\n")
        val english = checkNotNull(EntryLayout.language(page, "English"))

        EntryLayout.posSections(english).map { it.title } shouldBe listOf("Noun", "Verb")
    }

    @Test
    fun `headings that are not parts of speech are never treated as one`() {
        val page = Wikitext.parse(
            "==English==\n\n===Etymology===\ne\n\n===Pronunciation===\np\n\n===Anagrams===\na\n",
        )
        val english = checkNotNull(EntryLayout.language(page, "English"))

        EntryLayout.posSections(english) shouldBe emptyList()
    }

    @Test
    fun `part-of-speech matching ignores case`() {
        val page = Wikitext.parse("==English==\n\n===NOUN===\nn\n")
        val english = checkNotNull(EntryLayout.language(page, "English"))

        EntryLayout.posSections(english).map { it.title } shouldBe listOf("NOUN")
    }

    @Test
    fun `numbered etymologies push parts of speech down a level`() {
        val page = Wikitext.parse(
            """
            ==English==

            ===Etymology 1===

            ====Noun====
            n

            ===Etymology 2===

            ====Verb====
            v
            """.trimIndent(),
        )
        val english = checkNotNull(EntryLayout.language(page, "English"))

        EntryLayout.usesNumberedEtymologies(english) shouldBe true
        EntryLayout.posLevel(english) shouldBe 4
        EntryLayout.posSections(english).map { it.title } shouldBe listOf("Noun", "Verb")
    }

    @Test
    fun `a single numbered etymology still shifts the level`() {
        val page = Wikitext.parse("==English==\n\n===Etymology 1===\n\n====Noun====\nn\n")
        val english = checkNotNull(EntryLayout.language(page, "English"))

        EntryLayout.posLevel(english) shouldBe 4
    }

    @Test
    fun `an unnumbered etymology keeps parts of speech at level three`() {
        val page = Wikitext.parse("==English==\n\n===Etymology===\ne\n\n===Noun===\nn\n")
        val english = checkNotNull(EntryLayout.language(page, "English"))

        EntryLayout.usesNumberedEtymologies(english) shouldBe false
        EntryLayout.posLevel(english) shouldBe 3
    }

    @Test
    fun `the canonical sequence is ordered`() {
        val synonyms = EntryLayout.rank("Synonyms")
        val derived = EntryLayout.rank("Derived terms")
        val related = EntryLayout.rank("Related terms")
        val translations = EntryLayout.rank("Translations")

        (synonyms < derived) shouldBe true
        (derived < related) shouldBe true
        (related < translations) shouldBe true
    }

    @Test
    fun `headings WT-EL does not list as POS children are still ranked after derived terms`() {
        for (heading in listOf("Statistics", "See also", "References", "Further reading", "Anagrams")) {
            (EntryLayout.rank(heading) > EntryLayout.rank("Derived terms")) shouldBe true
        }
    }

    @Test
    fun `an unknown heading sorts before everything, so nothing is inserted above it`() {
        EntryLayout.rank("Something the bot has never seen") shouldBe -1
    }

    @Test
    fun `a new section goes before the first heading that should follow it`() {
        val page = Wikitext.parse(
            "==English==\n\n===Noun===\nn\n\n====Synonyms====\ns\n\n====Translations====\nt\n",
        )
        val noun = checkNotNull(EntryLayout.language(page, "English")?.find("Noun"))

        EntryLayout.insertionIndex(noun, "Derived terms") shouldBe 1
    }

    @Test
    fun `a new section goes last when nothing should follow it`() {
        val page = Wikitext.parse("==English==\n\n===Noun===\nn\n\n====Synonyms====\ns\n")
        val noun = checkNotNull(EntryLayout.language(page, "English")?.find("Noun"))

        EntryLayout.insertionIndex(noun, "Derived terms") shouldBe 1
    }

    @Test
    fun `a new section goes above the trailing headings entries actually use`() {
        val page = Wikitext.parse("==English==\n\n===Noun===\nn\n\n====Anagrams====\na\n")
        val noun = checkNotNull(EntryLayout.language(page, "English")?.find("Noun"))

        EntryLayout.insertionIndex(noun, "Derived terms") shouldBe 0
    }

    @Test
    fun `a new section goes below a heading the bot does not recognise`() {
        val page = Wikitext.parse("==English==\n\n===Noun===\nn\n\n====Mystery====\nm\n")
        val noun = checkNotNull(EntryLayout.language(page, "English")?.find("Noun"))

        EntryLayout.insertionIndex(noun, "Derived terms") shouldBe 1
    }

    @Test
    fun `categories at the end are furniture, not content`() {
        val nodes = Wikitext.parse("text\n\n[[Category:English lemmas]]\n").nodes

        val (content, furniture) = EntryLayout.splitTrailingFurniture(nodes)

        content.joinToString("") { it.serialize() } shouldBe "text\n"
        furniture.joinToString("") { it.serialize() } shouldBe "\n[[Category:English lemmas]]\n"
    }

    @Test
    fun `topic templates and a horizontal rule are furniture too`() {
        val nodes = Wikitext.parse("text\n\n{{cln|en|nouns}}\n\n----\n").nodes

        val (content, _) = EntryLayout.splitTrailingFurniture(nodes)

        content.joinToString("") { it.serialize() } shouldBe "text\n"
    }

    @Test
    fun `real content is left alone`() {
        val nodes = Wikitext.parse("text\n\n[[Category:X]]\n\nmore text\n").nodes

        val (content, furniture) = EntryLayout.splitTrailingFurniture(nodes)

        content.joinToString("") { it.serialize() } shouldBe "text\n\n[[Category:X]]\n\nmore text\n"
        furniture shouldBe emptyList()
    }

    @Test
    fun `a section that is nothing but furniture splits to empty content`() {
        val nodes = Wikitext.parse("[[Category:X]]\n").nodes

        val (content, furniture) = EntryLayout.splitTrailingFurniture(nodes)

        content shouldBe emptyList()
        furniture.isNotEmpty() shouldBe true
    }
}
