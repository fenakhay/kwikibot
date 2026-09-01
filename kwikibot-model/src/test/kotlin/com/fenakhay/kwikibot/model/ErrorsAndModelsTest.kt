package com.fenakhay.kwikibot.model

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ErrorsAndModelsTest {

    private val title = Title.Local(Namespace.MAIN, "volcano")
    private val ref = PageRef(WikiId("enwiktionary"), title)

    @Test
    fun `waiting on a replica is transient, so a run should come back to it`() {
        WikiError.Transport.Maxlag(5.seconds, "waiting for a replica").isTransient shouldBe true
    }

    @Test
    fun `a page the wiki will refuse the same way forever is not transient`() {
        WikiError.Page.UnsupportedContentModel(title, ContentModel.CSS).isTransient shouldBe false
        WikiError.Page.UnresolvableRedirect(title, "loops").isTransient shouldBe false
        WikiError.Auth.NotLoggedIn("editing").isTransient shouldBe false
        WikiError.Configuration.MissingExtension("ProofreadPage").isTransient shouldBe false
    }

    @Test
    fun `a rate limit says how long to wait, so a run need not guess`() {
        val limited = EditOutcome.RateLimited(ref, "slow down", retryAfter = 30.seconds)

        limited.ref shouldBe ref
        limited.retryAfter shouldBe 30.seconds
    }

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

    @Test
    fun `a language carries both its own name and the wiki's name for it`() {
        val french = LanguageInfo(
            code = LangCode("fr"),
            name = "French",
            autonym = "français",
            direction = TextDirection.LEFT_TO_RIGHT,
            fallbacks = listOf(LangCode("en")),
        )

        french.autonym shouldBe "français"
        french.direction shouldBe TextDirection.LEFT_TO_RIGHT
        french.fallbacks.single() shouldBe LangCode("en")
    }

    @Test
    fun `a right-to-left language says so, which decides how text around it is wrapped`() {
        LanguageInfo(
            code = LangCode("ar"),
            name = "Arabic",
            autonym = "العربية",
            direction = TextDirection.RIGHT_TO_LEFT,
        ).direction shouldBe TextDirection.RIGHT_TO_LEFT
    }

    @Test
    fun `a shared repository is not local, which is what decides where an edit goes`() {
        val commons = FileRepository(
            name = "shared",
            displayName = "Wikimedia Commons",
            isLocal = false,
            url = "https://commons.wikimedia.org/wiki/File:$1",
            rootUrl = "https://upload.wikimedia.org",
        )

        commons.isLocal shouldBe false
        commons.displayName shouldBe "Wikimedia Commons"

        FileRepository(name = "local", displayName = "Test Wiki", isLocal = true).isLocal shouldBe true
    }
}
