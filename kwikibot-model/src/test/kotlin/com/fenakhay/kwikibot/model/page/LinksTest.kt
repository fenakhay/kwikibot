package com.fenakhay.kwikibot.model.page

import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LinksTest {

    private val namespaces = NamespaceMap.CANONICAL

    @Test
    fun `a local link renders with its namespace prefix`() {
        Title.Local(Namespace.MAIN, "volcano").render(namespaces) shouldBe "[[volcano]]"
        Title.Local(Namespace.TEMPLATE, "col").render(namespaces) shouldBe "[[Template:col]]"
    }

    @Test
    fun `a category needs a colon to be linked rather than used`() {
        val category = Title.Local(Namespace.CATEGORY, "English lemmas")

        category.render(namespaces) shouldBe "[[Category:English lemmas]]"
        category.render(namespaces, forced = true) shouldBe "[[:Category:English lemmas]]"
    }

    @Test
    fun `display text is added when there is any`() {
        Title.Local(Namespace.MAIN, "volcano").render(namespaces, text = "volcanoes") shouldBe
            "[[volcano|volcanoes]]"
    }

    @Test
    fun `the pipe trick is written out rather than left for the wiki`() {
        Title.Local(Namespace.MAIN, "Volcano (film)").renderPiped(namespaces) shouldBe
            "[[Volcano (film)|Volcano]]"
        Title.Local(Namespace.MAIN, "Smith, John").renderPiped(namespaces) shouldBe "[[Smith, John|Smith]]"
    }

    @Test
    fun `a title with nothing to trim renders unpiped`() {
        Title.Local(Namespace.MAIN, "volcano").renderPiped(namespaces) shouldBe "[[volcano]]"
    }

    @Test
    fun `an interwiki link renders with its prefix`() {
        SiteLink("fr", "Volcan").render() shouldBe "[[fr:Volcan]]"
        SiteLink("w", "en:Volcano", text = "Volcano").render() shouldBe "[[w:en:Volcano|Volcano]]"
    }

    @Test
    fun `the colon decides whether a language link is a sidebar entry or a link`() {
        SiteLink("fr", "Volcan").render() shouldBe "[[fr:Volcan]]"
        SiteLink("fr", "Volcan").render(forced = true) shouldBe "[[:fr:Volcan]]"
    }

    @Test
    fun `an interwiki link needs both halves`() {
        assertFailsWith<IllegalArgumentException> { SiteLink("", "Volcan") }
        assertFailsWith<IllegalArgumentException> { SiteLink("fr", "  ") }
    }
}
