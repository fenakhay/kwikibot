package com.fenakhay.kwikibot.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

class SignaturesTest {

    private val thread = """
        == A question ==

        Does anyone know? ~~~~ [[User:Someone|Someone]] ([[User talk:Someone|talk]])
        21:43, 31 August 2026 (UTC)

        :Yes. [[User:Other|Other]] ([[User talk:Other|talk]]) 09:12, 1 September 2026 (UTC)
    """.trimIndent()

    @Test
    fun `every signature in a thread is found, in the order they appear`() {
        val found = Signatures.ENGLISH.findAll(thread)

        found.size shouldBe 2
        found.map { it.text } shouldBe listOf(
            "21:43, 31 August 2026 (UTC)",
            "09:12, 1 September 2026 (UTC)",
        )
    }

    @Test
    fun `the latest signature is when the discussion was last touched`() {
        val latest = Signatures.ENGLISH.latest(thread)

        latest?.instant shouldBe Instant.parse("2026-09-01T09:12:00Z")
    }

    @Test
    fun `the earliest is when it started`() {
        Signatures.ENGLISH.earliest(thread)?.instant shouldBe
            Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `a signature carries where it sits, so a thread can be cut in the right place`() {
        val first = Signatures.ENGLISH.findAll(thread).first()

        thread.substring(first.range) shouldBe first.text
    }

    @Test
    fun `the american order older signatures use is read too`() {
        val old = "Agreed. ~~~~ 14:05, August 3, 2019 (UTC)"

        Signatures.ENGLISH.latest(old)?.instant shouldBe Instant.parse("2019-08-03T14:05:00Z")
    }

    @Test
    fun `french signatures put the time last`() {
        val fr = "D'accord. [[Utilisateur:X|X]] 31 août 2026 à 21:43 (CEST)"

        Signatures.FRENCH.latest(fr)?.instant shouldBe Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `german signatures abbreviate the month, or do not`() {
        val abbreviated = "-- X 21:43, 31. Aug. 2026 (CEST)"
        val spelled = "-- X 21:43, 31. August 2026 (CEST)"

        Signatures.GERMAN.latest(abbreviated)?.instant shouldBe
            Instant.parse("2026-08-31T21:43:00Z")
        Signatures.GERMAN.latest(spelled)?.instant shouldBe Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `spanish signatures put the time first without a comma`() {
        val es = "Sí. X 21:43 31 ago 2026 (UTC)"

        Signatures.SPANISH.latest(es)?.instant shouldBe Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `a page with no signature has no timestamps rather than a wrong one`() {
        Signatures.ENGLISH.latest("== A heading ==\n\nSome text with 21:43 in it.").shouldBeNull()
    }

    @Test
    fun `a month name this format does not know is skipped, not guessed at`() {
        val wrong = "-- X 21:43, 31 Augustus 2026 (UTC)"

        Signatures.ENGLISH.latest(wrong).shouldBeNull()
    }

    @Test
    fun `an impossible date is skipped rather than rolled over into the next month`() {
        Signatures.ENGLISH.latest("-- X 21:43, 31 February 2026 (UTC)").shouldBeNull()
    }

    @Test
    fun `a timestamp written in non-ASCII digits is read`() {
        val devanagari = "-- X २१:४३, ३१ August २०२६ (UTC)"

        Signatures.ENGLISH.latest(devanagari)?.instant shouldBe
            Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `a timestamp inside an HTML comment is not a reply`() {
        val page = """
            <!-- archived earlier:
            -- Old 09:00, 1 January 2020 (UTC)
            -->
            -- New 21:43, 31 August 2026 (UTC)
        """.trimIndent()

        val found = Signatures.ENGLISH.findAll(page)

        found.size shouldBe 1
        found.single().instant shouldBe Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `masking a comment leaves the range pointing at the original text`() {
        val page = "<!-- x -->\n-- X 21:43, 31 August 2026 (UTC)"

        val found = Signatures.ENGLISH.findAll(page).single()

        page.substring(found.range) shouldBe found.text
        found.text shouldBe "21:43, 31 August 2026 (UTC)"
    }

    @Test
    fun `an unterminated comment swallows the rest of the page, as MediaWiki renders it`() {
        val page =
            "-- X 21:43, 31 August 2026 (UTC)\n<!-- unfinished\n-- Y 22:00, 31 August 2026 (UTC)"

        val found = Signatures.ENGLISH.findAll(page)

        found.single().instant shouldBe Instant.parse("2026-08-31T21:43:00Z")
    }

    @Test
    fun `formats compose, so a wiki that sees several languages can read them all`() {
        val mixed = """
            -- X 21:43, 31 August 2026 (UTC)
            -- Y 1 septembre 2026 à 09:12 (CEST)
        """.trimIndent()

        Signatures.ALL.findAll(mixed).size shouldBe 2
        Signatures.ALL.findAll(mixed).map { it.language } shouldBe listOf("en", "fr")
    }

    @Test
    fun `a language nothing is shipped for reads as english rather than failing`() {
        Signatures.forLanguage("xyz").findAll(thread).size shouldBe 2
    }
}
