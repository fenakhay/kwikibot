package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class EditPermissionTest {
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
    fun `an allowed permission says so, and a denial carries its reason`() {
        EditPermission.Allowed.isAllowed shouldBe true

        val denied = EditPermission.Denied("nobots")
        denied.isAllowed shouldBe false
        denied.reason shouldBe "nobots"
    }
}
