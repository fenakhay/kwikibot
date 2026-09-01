package com.fenakhay.kwikibot.client

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ProofreadTextTest {

    private val page = """
        <noinclude><pagequality level="3" user="Someone" />{{RunningHeader|Chapter I}}</noinclude>
        It was the best of times.
        <noinclude>{{smallrefs}}</noinclude>
    """.trimIndent()

    @Test
    fun `the three parts are separated`() {
        val parsed = ProofreadText.parse(page)

        parsed.header shouldBe "{{RunningHeader|Chapter I}}"
        parsed.body.trim() shouldBe "It was the best of times."
        parsed.footer shouldBe "{{smallrefs}}"
    }

    @Test
    fun `the quality and who set it are read, not guessed`() {
        val parsed = ProofreadText.parse(page)

        parsed.quality shouldBe ProofreadQuality.PROOFREAD
        parsed.proofreader shouldBe "Someone"
    }

    @Test
    fun `qualities compare in the order of progress`() {
        (ProofreadQuality.NOT_PROOFREAD < ProofreadQuality.PROOFREAD) shouldBe true
        (ProofreadQuality.VALIDATED > ProofreadQuality.PROOFREAD) shouldBe true
        ProofreadQuality.of(4) shouldBe ProofreadQuality.VALIDATED
        ProofreadQuality.of(9).shouldBeNull()
    }

    @Test
    fun `a page round-trips when nothing was changed`() {
        ProofreadText.parse(page).serialize() shouldBe page
    }

    @Test
    fun `changing the body leaves the marker and the running heads alone`() {
        val fixed = ProofreadText.parse(page).withBody("\nIt was the worst of times.\n")

        fixed.serialize() shouldBe page.replace(
            "It was the best of times.",
            "It was the worst of times.",
        )
    }

    @Test
    fun `a page nobody has rated gets no marker invented for it`() {
        val unrated = "<noinclude>{{header}}</noinclude>text<noinclude></noinclude>"

        val parsed = ProofreadText.parse(unrated)

        parsed.quality.shouldBeNull()
        parsed.serialize() shouldBe unrated
    }

    @Test
    fun `a page not in this shape is all body, so nothing is dropped`() {
        val plain = "Somebody wrote this by hand."

        val parsed = ProofreadText.parse(plain)

        parsed.body shouldBe plain
        parsed.header shouldBe ""
        parsed.footer shouldBe ""
    }

    @Test
    fun `a page with an opening noinclude and no closing one is all body`() {
        val half = "<noinclude>{{header}}</noinclude>the text with no footer"

        ProofreadText.parse(half).body shouldBe half
    }
}
