package com.fenakhay.kwikibot.bot.run

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SkipPageJvmTest {

    @Test
    fun `a skip carries its reason and no stack trace`() {
        val skip = skipPage("no English section")

        skip.reason shouldBe "no English section"
        skip.message shouldBe "no English section"
        skip.stackTrace.size shouldBe 0
    }
}
