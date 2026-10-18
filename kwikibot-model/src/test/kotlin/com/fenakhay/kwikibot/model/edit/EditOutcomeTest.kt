package com.fenakhay.kwikibot.model.edit

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class EditOutcomeTest {
    private val title = Title.Local(Namespace.MAIN, "volcano")
    private val ref = PageRef(WikiId("enwiktionary"), title)

    @Test
    fun `a rate limit says how long to wait, so a run need not guess`() {
        val limited = EditOutcome.RateLimited(ref, "slow down", retryAfter = 30.seconds)

        limited.ref shouldBe ref
        limited.retryAfter shouldBe 30.seconds
    }

    @Test
    fun `a refusal that passes with time or a fresh read is retryable, and the rest are not`() {
        EditOutcome.Conflict(ref, "edit conflict").isRetryable shouldBe true
        EditOutcome.RateLimited(ref, "slow down").isRetryable shouldBe true
        EditOutcome.PageStateChanged(ref, "deleted", wasDeleted = true).isRetryable shouldBe true

        EditOutcome.Protected(ref, "protected").isRetryable shouldBe false
        EditOutcome.Filtered(ref, "abuse filter").isRetryable shouldBe false
        EditOutcome.PermissionDenied(ref, "blocked").isRetryable shouldBe false
        EditOutcome.Rejected(ref, "too big", "contenttoobig").isRetryable shouldBe false
    }

    @Test
    fun `a captcha cannot be answered by a bot, so trying again does not help`() {
        val captcha = EditOutcome.CaptchaRequired(ref, "solve this", captchaId = "123")

        captcha.isRetryable shouldBe false
        captcha.captchaId shouldBe "123"
        EditOutcome.CaptchaRequired(ref, "solve this").captchaId shouldBe null
    }

    @Test
    fun `only a saved edit changed the page`() {
        EditOutcome.Saved(ref, RevisionId(2), RevisionId(1)).didChange shouldBe true
        EditOutcome.NoChange(ref, RevisionId(1)).didChange shouldBe false
        EditOutcome.Conflict(ref, "edit conflict").didChange shouldBe false
    }
}
