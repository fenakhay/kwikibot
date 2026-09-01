package com.fenakhay.kwikibot.wikitext

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class IsbnTest {

    @Test
    fun `a valid ten-digit isbn parses, hyphens and all`() {
        Isbn.parse("0-19-853737-9")?.normalised shouldBe "0198537379"
        Isbn.parse("0 19 853737 9")?.normalised shouldBe "0198537379"
    }

    @Test
    fun `X is ten in the check position and nowhere else`() {
        Isbn.parse("043942089X")?.normalised shouldBe "043942089X"
        Isbn.parse("04394X0895").shouldBeNull()
    }

    @Test
    fun `a wrong check digit is not an isbn`() {
        Isbn.parse("0-19-853737-8").shouldBeNull()
        Isbn.isValid("978-0-306-40615-7") shouldBe true
        Isbn.isValid("978-0-306-40615-6") shouldBe false
    }

    @Test
    fun `the wrong number of digits is not an isbn`() {
        Isbn.parse("019853737").shouldBeNull()
        Isbn.parse("97803064061").shouldBeNull()
    }

    @Test
    fun `ten digits convert to thirteen with a recomputed check digit`() {
        Isbn.parse("0-19-853737-9")?.toIsbn13()?.normalised shouldBe "9780198537373"
    }

    @Test
    fun `a thirteen-digit isbn converts to itself`() {
        val isbn = checkNotNull(Isbn.parse("978-0-306-40615-7"))

        isbn.toIsbn13() shouldBe isbn
        isbn.isIsbn13 shouldBe true
    }

    @Test
    fun `the pass converts valid isbns and leaves broken ones alone`() {
        val before = "See ISBN 0-19-853737-9 and ISBN 0-19-853737-8."

        ReformatIsbns(toIsbn13 = true).apply(Wikitext.parse(before)).serialize() shouldBe
            "See ISBN 9780198537373 and ISBN 0-19-853737-8."
    }

    @Test
    fun `an isbn inside a template belongs to the template`() {
        val before = "{{cite book|isbn=0-19-853737-9}} and ISBN 0-19-853737-9"

        ReformatIsbns(toIsbn13 = true).apply(Wikitext.parse(before)).serialize() shouldBe
            "{{cite book|isbn=0-19-853737-9}} and ISBN 9780198537373"
    }

    @Test
    fun `doing nothing is the default`() {
        val before = "ISBN 0-19-853737-9"

        ReformatIsbns().apply(Wikitext.parse(before)).serialize() shouldBe before
    }
}

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
