package com.fenakhay.kwikibot.model.edit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Instant

class ProtectionTest {

    private val now = Instant.parse("2026-08-31T21:43:26Z")

    @Test
    fun `an infinite expiry is a case rather than a sentinel date`() {
        Expiry.parse("infinity") shouldBe Expiry.Never
        Expiry.parse("indefinite") shouldBe Expiry.Never
    }

    @Test
    fun `a dated expiry keeps its instant`() {
        val expiry = Expiry.parse("2027-01-01T00:00:00Z").shouldBeInstanceOf<Expiry.At>()
        expiry.instant shouldBe Instant.parse("2027-01-01T00:00:00Z")
    }

    @Test
    fun `indefinite protection is always active`() {
        val protection = Protection("edit", Protection.SYSOP)
        protection.isActiveAt(now) shouldBe true
    }

    @Test
    fun `a dated protection lapses at its expiry`() {
        val protection = Protection("edit", Protection.AUTOCONFIRMED, Expiry.At(now))
        protection.isActiveAt(now - Duration.parse("1s")) shouldBe true
        protection.isActiveAt(now) shouldBe false
        protection.isActiveAt(now + Duration.parse("1s")) shouldBe false
    }

    @Test
    fun `an expiry renders back into the form MediaWiki accepts`() {
        Expiry.Never.toString() shouldBe "infinity"
        Expiry.At(now).toString() shouldBe "2026-08-31T21:43:26Z"
    }
}
