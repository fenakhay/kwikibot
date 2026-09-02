package com.fenakhay.kwikibot.model

import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LanguageInfoTest {
    private val title = Title.Local(Namespace.MAIN, "volcano")
    private val ref = PageRef(WikiId("enwiktionary"), title)

    @Test
    fun `a language carries both its own name and the wiki's name for it`() {
        val french =
            LanguageInfo(
                code = LangCode("fr"),
                name = "French",
                autonym = "français",
                direction = TextDirection.LEFT_TO_RIGHT,
                fallbacks = listOf(LangCode("en")),
            )

        french.autonym shouldBe "français"
        french.direction shouldBe TextDirection.LEFT_TO_RIGHT
        french.fallbacks.single() shouldBe LangCode("en")
    }

    @Test
    fun `a right-to-left language says so, which decides how text around it is wrapped`() {
        LanguageInfo(
                code = LangCode("ar"),
                name = "Arabic",
                autonym = "العربية",
                direction = TextDirection.RIGHT_TO_LEFT,
            )
            .direction shouldBe TextDirection.RIGHT_TO_LEFT
    }
}
