package com.fenakhay.kwikibot.model.user

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

class BlockTest {

    @Test
    fun `a block with no expiry never ends`() {
        BlockInfo(reason = "vandalism").isInfinite shouldBe true
        BlockInfo(expiry = Instant.parse("2026-09-07T00:00:00Z")).isInfinite shouldBe false
    }

    @Test
    fun `a target with a slash is an address range`() {
        BlockRecord(BlockInfo(), target = "203.0.113.0/24").isRange shouldBe true
        BlockRecord(BlockInfo(), target = "203.0.113.5").isRange shouldBe false
        BlockRecord(BlockInfo(), target = null).isRange shouldBe false
    }
}
