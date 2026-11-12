package com.fenakhay.kwikibot.bindings.common

import com.fenakhay.kwikibot.bindings.bot.commonOptionsOf
import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.client.service.HistoryOrder
import com.fenakhay.kwikibot.wikibase.value.EntityId
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.io.files.Path

/** How a binding names the core's enums, and what it does with a setting left alone or turned off. */
class NamesTest {

    @Test
    fun `an entry is named in kebab case apart from the few spelled as one word`() {
        HistoryOrder.NEWEST_FIRST.argumentName() shouldBe "newest-first"
        EntityId.Kind.MEDIA_INFO.argumentName() shouldBe "mediainfo"
        EntityId.Kind.ITEM.argumentName() shouldBe "item"
    }

    @Test
    fun `an argument names its entry or says which it could have been`() {
        enumArgument<HistoryOrder>("order", "oldest-first") shouldBe HistoryOrder.OLDEST_FIRST
        enumArgument<EntityId.Kind>("kind", "mediainfo") shouldBe EntityId.Kind.MEDIA_INFO
        enumArgumentOrNull<HistoryOrder>("order", null) shouldBe null
        enumArgumentOrNull<HistoryOrder>("order", "newest-first") shouldBe HistoryOrder.NEWEST_FIRST
        assertFailsWith<IllegalArgumentException> { enumArgument<HistoryOrder>("order", "sideways") }
            .message shouldBe "order cannot be 'sideways': it is one of 'newest-first', 'oldest-first'"
    }

    @Test
    fun `a setting left alone is the default and one turned off is nothing`() {
        Setting.Default.resolve(5) shouldBe 5
        Setting.Off.resolve(5) shouldBe null
        Setting.Value(1).resolve(5) shouldBe 1
        Setting.ofNullable(null) shouldBe Setting.Off
        Setting.ofNullable(2) shouldBe Setting.Value(2)
    }

    @Test
    fun `the common flags are a dry run unless told and take everything given`() {
        commonOptionsOf() shouldBe CommonOptions()
        commonOptionsOf(
            save = true,
            limit = 3,
            diffLog = Path("diff.log"),
            skipLog = Path("skip.log"),
            stopPage = "User:Bot/Stop",
            readDelay = 5.milliseconds,
            writeDelay = 6.milliseconds,
            httpConnections = 2,
            contact = "bot@example.org",
            state = Path("state"),
            resume = true,
            trial = true,
        ) shouldBe
            CommonOptions(
                true,
                3,
                Path("diff.log"),
                Path("skip.log"),
                "User:Bot/Stop",
                5.milliseconds,
                6.milliseconds,
                2,
                "bot@example.org",
                Path("state"),
                true,
                true,
            )
    }
}
