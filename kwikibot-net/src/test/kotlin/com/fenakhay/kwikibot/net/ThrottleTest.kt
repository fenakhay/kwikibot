package com.fenakhay.kwikibot.net

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

class ThrottleTest {

    @Test
    fun `the first request goes out immediately`() = runTest {
        val throttle = Throttle(read = 100.milliseconds, timeSource = testScheduler.timeSource)

        throttle.acquire(RequestKind.READ)

        currentTime shouldBe 0
    }

    @Test
    fun `consecutive reads are spaced by the read delay`() = runTest {
        val throttle = Throttle(read = 100.milliseconds, timeSource = testScheduler.timeSource)

        repeat(3) { throttle.acquire(RequestKind.READ) }

        currentTime shouldBe 200
    }

    @Test
    fun `writes are paced separately and far more slowly`() = runTest {
        val throttle =
            Throttle(
                read = 100.milliseconds,
                write = 10.seconds,
                timeSource = testScheduler.timeSource,
            )

        throttle.acquire(RequestKind.WRITE)
        throttle.acquire(RequestKind.WRITE)

        currentTime shouldBe 10_000
    }

    @Test
    fun `a write also paces the read that follows it`() = runTest {
        val throttle =
            Throttle(
                read = 100.milliseconds,
                write = 10.seconds,
                timeSource = testScheduler.timeSource,
            )

        throttle.acquire(RequestKind.WRITE)
        throttle.acquire(RequestKind.READ)

        currentTime shouldBe 100
    }

    @Test
    fun `a wait requested by the server holds back reads and writes alike`() = runTest {
        val throttle = Throttle(read = 100.milliseconds, timeSource = testScheduler.timeSource)

        throttle.penalize(5.seconds)
        throttle.acquire(RequestKind.READ)

        currentTime shouldBe 5_000
    }

    @Test
    fun `a longer penalty wins and a shorter one cannot cut it short`() = runTest {
        val throttle = Throttle(read = 100.milliseconds, timeSource = testScheduler.timeSource)

        throttle.penalize(30.seconds)
        throttle.penalize(1.seconds)
        throttle.acquire(RequestKind.READ)

        currentTime shouldBe 30_000
    }

    @Test
    fun `concurrent callers are serialized into one evenly spaced stream`() = runTest {
        val throttle = Throttle(read = 100.milliseconds, timeSource = testScheduler.timeSource)

        List(5) { async { throttle.acquire(RequestKind.READ) } }.awaitAll()

        currentTime shouldBe 400
    }

    @Test
    fun `a throttle with no delay never waits`() = runTest {
        repeat(10) { Throttle.unpaced().acquire(RequestKind.READ) }

        currentTime shouldBe 0
    }

    @Test
    fun `peek reports the wait without consuming a slot`() = runTest {
        val throttle = Throttle(read = 100.milliseconds, timeSource = testScheduler.timeSource)

        throttle.acquire(RequestKind.READ)

        throttle.peek(RequestKind.READ) shouldBe 100.milliseconds
        currentTime shouldBe 0
    }
}
