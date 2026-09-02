package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class StopPolicyTest {
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
    fun `a stop page halts the run unless it says false`() = runTest {
        val stopPage = FakePageService("Stop" to "false")

        val running = StopPolicy.page(stopPage, stopPage.ref("Stop"))
        running.mayContinue() shouldBe true

        val halted = FakePageService("Stop" to "")
        StopPolicy.page(halted, halted.ref("Stop")).mayContinue() shouldBe false
    }

    @Test
    fun `a stop page that does not exist halts the run`() = runTest {
        val empty = FakePageService()

        StopPolicy.page(empty, empty.ref("Stop")).mayContinue() shouldBe false
    }
}
