package com.fenakhay.kwikibot.bot.fix

import com.fenakhay.kwikibot.bot.run.apply
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FixRegistryTest {
    private val pages =
        FakePageService(
            "volcano" to "==English==",
            "vulcan" to "#REDIRECT [[volcano]]",
            "geyser" to "==English==",
        )

    private fun content(title: String, text: String, redirectTo: String? = null) =
        PageContent(
            pages.ref(title),
            RevisionId(1),
            text,
            redirectTarget = redirectTo?.let { pages.ref(it).title },
        )

    @Test
    fun `every shipped fix is listed by name`() {
        val all = Fixes.all

        all.isNotEmpty() shouldBe true
        all.keys.contains("ellipsis") shouldBe true
        all.getValue("ellipsis").apply("one...two") shouldBe "one…two"
    }
}
