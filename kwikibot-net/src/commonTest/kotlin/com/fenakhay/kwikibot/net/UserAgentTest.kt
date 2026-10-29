package com.fenakhay.kwikibot.net

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class UserAgentTest {

    @Test
    fun `a user agent prints as the header it sends`() {
        val agent = UserAgent("FenaBot", "2.0", "https://en.wiktionary.org/wiki/User:FenaBot")

        agent.toString() shouldBe agent.headerValue
        agent.headerValue shouldBe
            "FenaBot/2.0 (https://en.wiktionary.org/wiki/User:FenaBot) kwikibot/${UserAgent.LIBRARY_VERSION}"
    }

    @Test
    fun `a user agent without a name or a way to reach its operator is refused`() {
        assertFailsWith<IllegalArgumentException> { UserAgent(" ", "1.0", "https://example.org") }
        assertFailsWith<IllegalArgumentException> { UserAgent("FenaBot", "1.0", "") }
    }
}
