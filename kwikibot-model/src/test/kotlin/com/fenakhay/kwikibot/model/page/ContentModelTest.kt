package com.fenakhay.kwikibot.model.page

import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ContentModelTest {
    private val title = Title.Local(Namespace.MAIN, "volcano")
    private val ref = PageRef(WikiId("enwiktionary"), title)

    @Test
    fun `only wikitext is wikitext`() {
        ContentModel.WIKITEXT.isWikitext shouldBe true
        ContentModel.JSON.isWikitext shouldBe false
        ContentModel.SCRIBUNTO.isWikitext shouldBe false
        ContentModel.SANITIZED_CSS.isWikitext shouldBe false

        ContentModel("Wikitext").isWikitext shouldBe false
    }

    @Test
    fun `a content model prints as the name the API uses`() {
        ContentModel.SCRIBUNTO.toString() shouldBe "Scribunto"
        ContentModel.WIKITEXT.toString() shouldBe "wikitext"
    }
}
