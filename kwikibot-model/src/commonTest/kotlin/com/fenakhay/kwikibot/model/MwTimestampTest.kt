package com.fenakhay.kwikibot.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class MwTimestampTest {

    private val moment = Instant.parse("2026-08-31T21:43:26Z")

    @Test
    fun `reads the ISO form the API returns`() {
        MwTimestamp.parse("2026-08-31T21:43:26Z") shouldBe moment
    }

    @Test
    fun `reads the compact form used by database fields and dumps`() {
        MwTimestamp.parse("20260831214326") shouldBe moment
    }

    @Test
    fun `writes the ISO form without fractional seconds`() {
        MwTimestamp.format(Instant.parse("2026-08-31T21:43:26.512Z")) shouldBe "2026-08-31T21:43:26Z"
        MwTimestamp.format(moment) shouldBe "2026-08-31T21:43:26Z"
    }

    @Test
    fun `writes the compact form`() {
        MwTimestamp.formatCompact(moment) shouldBe "20260831214326"
    }

    @Test
    fun `round-trips through both forms`() {
        MwTimestamp.parse(MwTimestamp.format(moment)) shouldBe moment
        MwTimestamp.parse(MwTimestamp.formatCompact(moment)) shouldBe moment
    }

    @Test
    fun `recognises every spelling of never`() {
        for (raw in listOf("infinity", "infinite", "indefinite", "never", "INFINITY", " infinity ")) {
            MwTimestamp.isNever(raw) shouldBe true
        }
        MwTimestamp.isNever("2026-08-31T21:43:26Z") shouldBe false
    }

    @Test
    fun `rejects what is not a timestamp`() {
        MwTimestamp.parseOrNull("").shouldBeNull()
        MwTimestamp.parseOrNull("infinity").shouldBeNull()
        MwTimestamp.parseOrNull("2026-13-45").shouldBeNull()
        MwTimestamp.parseOrNull("99999999999999").shouldBeNull()
        assertFailsWith<IllegalArgumentException> { MwTimestamp.parse("nonsense") }
    }
}
