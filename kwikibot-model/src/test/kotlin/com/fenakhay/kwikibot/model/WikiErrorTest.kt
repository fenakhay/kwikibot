package com.fenakhay.kwikibot.model

import com.fenakhay.kwikibot.model.page.ContentModel
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class WikiErrorTest {
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
}
