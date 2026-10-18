package com.fenakhay.kwikibot.net

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

    @Test
    fun `each wait doubles the one before, up to the ceiling`() {
        val policy = RetryPolicy(initialDelay = 1.seconds, maxDelay = 5.seconds)

        (1..5).map { policy.delayFor(it) } shouldBe
            listOf(1.seconds, 2.seconds, 4.seconds, 5.seconds, 5.seconds)
        RetryPolicy(initialDelay = 10.seconds, maxDelay = 5.seconds).delayFor(1) shouldBe 5.seconds
    }

    @Test
    fun `a wait the server asked for wins, but never shortens the curve`() {
        val policy = RetryPolicy(initialDelay = 1.seconds)

        policy.delayFor(1, retryAfter = 30.seconds) shouldBe 30.seconds
        policy.delayFor(3, retryAfter = 1.seconds) shouldBe 4.seconds
        policy.delayFor(2, retryAfter = null) shouldBe 2.seconds
    }

    @Test
    fun `a policy that could not work is refused, and so is a retry numbered from zero`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxRetries = -1) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(initialDelay = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy().delayFor(0) }
        RetryPolicy.NONE.maxRetries shouldBe 0
        RetryPolicy(initialDelay = Duration.ZERO).delayFor(3) shouldBe Duration.ZERO
    }
}
