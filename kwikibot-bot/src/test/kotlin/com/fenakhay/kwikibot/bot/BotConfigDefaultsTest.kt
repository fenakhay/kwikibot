package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class BotConfigDefaultsTest {
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
    fun `a configuration reports the language and family it names`() {
        val config =
            BotConfig.parse(
                """
                [bot]
                name = "FenaBot"
                contact = "https://en.wiktionary.org/wiki/User:FenaBot"

                [wiki]
                lang = "fr"
                family = "wiktionary"
                """
                    .trimIndent()
            )

        config.language() shouldBe LangCode("fr")
    }

    @Test
    fun `a cache duration is read as a duration, not left as text`() {
        val config =
            BotConfig.parse(
                """
                [bot]
                name = "FenaBot"
                contact = "https://en.wiktionary.org/wiki/User:FenaBot"

                [cache]
                path = "apicache"
                ttl = "6h"
                """
                    .trimIndent()
            )

        config.cache?.ttl shouldBe "6h"
        config.toWikiConfig().cache.toString().isNotEmpty() shouldBe true
    }
}
