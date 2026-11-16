package com.fenakhay.kwikibot.bindings.services

import com.fenakhay.kwikibot.client.model.PageviewAccess
import com.fenakhay.kwikibot.client.model.PageviewAgent
import com.fenakhay.kwikibot.client.model.PageviewGranularity
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** What the Wikimedia services a binding reaches ask for unless told. */
class ServiceDefaultsTest {

    @Test
    fun `pageviews are daily from every kind of site by people unless told`() {
        granularityOf(null) shouldBe PageviewGranularity.DAILY
        accessOf(null) shouldBe PageviewAccess.ALL_ACCESS
        agentOf(null) shouldBe PageviewAgent.USER
        granularityOf(PageviewGranularity.MONTHLY) shouldBe PageviewGranularity.MONTHLY
        accessOf(PageviewAccess.entries.last()) shouldBe PageviewAccess.entries.last()
        agentOf(PageviewAgent.entries.last()) shouldBe PageviewAgent.entries.last()
    }
}
