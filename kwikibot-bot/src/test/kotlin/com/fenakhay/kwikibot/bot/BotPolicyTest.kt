package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class BotPolicyTest {

    private val policy = BotPolicy("FenaBot")

    @Test
    fun `a page with nothing on it may be edited`() {
        policy.check("==English==\n\nA word.").isAllowed shouldBe true
    }

    @Test
    fun `a bare nobots denies every bot`() {
        val denied = policy.check("{{nobots}}\n\nText.").shouldBeInstanceOf<EditPermission.Denied>()

        denied.reason shouldContain "nobots"
    }

    @Test
    fun `deny all denies every bot`() {
        policy.check("{{bots|deny=all}}").isAllowed shouldBe false
        policy.check("{{nobots|deny=all}}").isAllowed shouldBe false
    }

    @Test
    fun `deny names this bot, or does not`() {
        policy.check("{{bots|deny=FenaBot,OtherBot}}").isAllowed shouldBe false
        policy.check("{{bots|deny=OtherBot,ThirdBot}}").isAllowed shouldBe true
    }

    @Test
    fun `allow is a whitelist, so a bot not on it is denied`() {
        policy.check("{{bots|allow=FenaBot}}").isAllowed shouldBe true
        policy.check("{{bots|allow=OtherBot}}").isAllowed shouldBe false
    }

    @Test
    fun `allow all is the same as saying nothing`() {
        policy.check("{{bots|allow=all}}").isAllowed shouldBe true
    }

    @Test
    fun `an optout for everything covers a bot that named no task`() {
        policy.check("{{bots|optout=all}}").isAllowed shouldBe false
    }

    @Test
    fun `a task-specific optout covers the task it names`() {
        val summaries = BotPolicy("FenaBot", task = "nosummary")

        summaries.check("{{bots|optout=nosummary}}").isAllowed shouldBe false
        summaries.check("{{bots|optout=otherthing}}").isAllowed shouldBe true
    }

    @Test
    fun `a bot that named no task is not covered by a task-specific optout`() {
        policy.check("{{bots|optout=nosummary}}").isAllowed shouldBe true
    }

    @Test
    fun `bot names are matched however they are cased`() {
        policy.check("{{bots|deny=fenabot}}").isAllowed shouldBe false
    }

    @Test
    fun `spaces around a name in a list do not matter`() {
        policy.check("{{bots|deny= OtherBot , FenaBot }}").isAllowed shouldBe false
    }

    @Test
    fun `an exclusion anywhere on the page counts`() {
        val page = "==English==\n\nA word.\n\n{{nobots}}\n"

        policy.check(page).isAllowed shouldBe false
    }

    @Test
    fun `a template that merely mentions bots is not an exclusion`() {
        policy.check("{{botlist|deny=all}}").isAllowed shouldBe true
    }
}

class BotRunExclusionTest {

    @Test
    fun `a page that asked bots to stay away is skipped, and not transformed`() = runTest {
        val pages = FakePageService(
            "volcano" to "{{nobots}}\n==English==",
            "mountain" to "==English==",
        )
        var transformed = 0

        val report = botRun(pages) {
            source(flowOf(pages.ref("volcano"), pages.ref("mountain")))
            exclusionPolicy = BotPolicy("FenaBot")
            dryRun = false
            transform { content ->
                transformed++
                Edit(content.text + "\n<!-- seen -->", "test")
            }
        }

        transformed shouldBe 1
        pages.text("volcano") shouldBe "{{nobots}}\n==English=="

        val skipped = report.outcomes.filterIsInstance<PageOutcome.Skipped>().single()
        skipped.reason shouldContain "nobots"
    }

    @Test
    fun `without a policy nothing is excluded, which is why a bot sets one`() = runTest {
        val pages = FakePageService("volcano" to "{{nobots}}\n==English==")

        val report = botRun(pages) {
            source(flowOf(pages.ref("volcano")))
            dryRun = false
            transform { Edit(it.text + "!", "test") }
        }

        report.outcomes.filterIsInstance<PageOutcome.Saved>().size shouldBe 1
    }
}
