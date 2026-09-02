package com.fenakhay.kwikibot.bot.fix

import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.wikitext.Wikitext
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class UnlinkerTest {

    private val unlinker = Unlinker()
    private val target = Title.Local(Namespace.MAIN, "volcano")

    @Test
    fun `a plain link becomes the word it was showing`() {
        unlinker.unlink("A [[volcano]] erupts.", target) shouldBe "A volcano erupts."
    }

    @Test
    fun `a piped link keeps the text a reader saw`() {
        unlinker.unlink("Two [[volcano|volcanoes]] erupt.", target) shouldBe "Two volcanoes erupt."
    }

    @Test
    fun `a link with a section anchor is still a link to the page`() {
        unlinker.unlink("See [[volcano#English]].", target) shouldBe "See volcano#English."
    }

    @Test
    fun `underscores and spaces name the same page`() {
        val two = Title.Local(Namespace.MAIN, "shield volcano")

        unlinker.unlink("A [[shield_volcano]].", two) shouldBe "A shield_volcano."
    }

    @Test
    fun `the first letter is case-insensitive where the wiki capitalises it`() {
        unlinker.unlink("A [[Volcano]].", target) shouldBe "A Volcano."
    }

    @Test
    fun `on a wiki that does not capitalise, case matters`() {
        val wiktionary = Unlinker(capitalLinks = false)

        wiktionary.unlink("A [[Volcano]].", target) shouldBe "A [[Volcano]]."
        wiktionary.unlink("A [[volcano]].", target) shouldBe "A volcano."
    }

    @Test
    fun `the rest of the title is case-sensitive everywhere`() {
        unlinker.unlink("A [[VOLCANO]].", target) shouldBe "A [[VOLCANO]]."
    }

    @Test
    fun `a link to another page is left alone`() {
        val before = "A [[mountain]] and a [[volcano]]."

        unlinker.unlink(before, target) shouldBe "A [[mountain]] and a volcano."
    }

    @Test
    fun `a link inside a file caption is unlinked without touching the caption`() {
        val before = "[[File:V.jpg|thumb|A [[volcano]] at dawn]]"

        unlinker.unlink(before, target) shouldBe "[[File:V.jpg|thumb|A volcano at dawn]]"
    }

    @Test
    fun `a page with no link to the target is reported as such, so no edit is made`() {
        val code = Wikitext.parse("A [[mountain]].")

        unlinker.linksTo(code, target) shouldBe false
        unlinker.linksTo(Wikitext.parse("A [[volcano]]."), target) shouldBe true
    }

    @Test
    fun `text outside links is untouched, byte for byte`() {
        val before = "== English ==\n\n{{col|en|[[volcano]]}}\n\nA [[volcano]].\n"

        unlinker.unlink(before, target) shouldBe "== English ==\n\n{{col|en|volcano}}\n\nA volcano.\n"
    }
}
