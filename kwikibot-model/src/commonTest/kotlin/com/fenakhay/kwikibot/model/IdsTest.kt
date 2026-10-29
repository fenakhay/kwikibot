package com.fenakhay.kwikibot.model

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class IdsTest {

    @Test
    fun `an id prints as the bare value which is what logs and messages want`() {
        LangCode("zh-min-nan").toString() shouldBe "zh-min-nan"
        PageId(736).toString() shouldBe "736"
        RevisionId(81_234_567).toString() shouldBe "81234567"
    }

    @Test
    fun `a language code cannot be blank`() {
        assertFailsWith<IllegalArgumentException> { LangCode("") }
    }
}
