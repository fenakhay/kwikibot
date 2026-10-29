package com.fenakhay.kwikibot.model.page

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ContentModelTest {
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
        ContentModel.JAVASCRIPT.toString() shouldBe "javascript"
        ContentModel.TEXT.toString() shouldBe "text"
    }
}
