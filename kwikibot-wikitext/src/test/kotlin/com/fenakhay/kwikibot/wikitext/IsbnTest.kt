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
    fun `a letter other than a check X is not an isbn`() {
        Isbn.parse("043942089Y").shouldBeNull()
        Isbn.parse("978030640615X").shouldBeNull()
    }

    @Test
    fun `isbns are equal by their digits, however they were written`() {
        val isbn = checkNotNull(Isbn.parse("0-19-853737-9"))

        isbn shouldBe Isbn.parse("0198537379")
        isbn.hashCode() shouldBe Isbn.parse("0 19 853737 9").hashCode()
        isbn.toString() shouldBe "0198537379"
        isbn.equals("0198537379") shouldBe false
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
            "See ISBN 978-0-19-853737-3 and ISBN 0-19-853737-8."
    }

    @Test
    fun `a converted isbn keeps the separators it was written with`() {
        val toIsbn13 = ReformatIsbns(toIsbn13 = true)

        toIsbn13.apply(Wikitext.parse("ISBN 0198537379")).serialize() shouldBe "ISBN 9780198537373"
        toIsbn13.apply(Wikitext.parse("ISBN 0 19 853737 9")).serialize() shouldBe "ISBN 978 0 19 853737 3"
        toIsbn13.apply(Wikitext.parse("ISBN  043942089X")).serialize() shouldBe "ISBN  9780439420891"
    }

    @Test
    fun `a thirteen-digit isbn is left as it was written`() {
        val before = "ISBN 978-0-306-40615-7 and ISBN  9780306406157"

        ReformatIsbns(toIsbn13 = true).apply(Wikitext.parse(before)).serialize() shouldBe before
    }

    @Test
    fun `an isbn inside a template belongs to the template`() {
        val before = "{{cite book|isbn=0-19-853737-9}} and ISBN 0-19-853737-9"

        ReformatIsbns(toIsbn13 = true).apply(Wikitext.parse(before)).serialize() shouldBe
            "{{cite book|isbn=0-19-853737-9}} and ISBN 978-0-19-853737-3"
    }

    @Test
    fun `doing nothing is the default`() {
        val before = "ISBN 0-19-853737-9"

        ReformatIsbns().apply(Wikitext.parse(before)).serialize() shouldBe before
    }
}
