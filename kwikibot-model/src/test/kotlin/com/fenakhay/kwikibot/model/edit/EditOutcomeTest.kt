package com.fenakhay.kwikibot.model.edit

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
}
