package com.fenakhay.kwikibot.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether a request only reads, or changes the wiki. Writes are paced far more slowly. */
public enum class RequestKind {
    /** Reads nothing but the wiki's own state, and is paced in tens of milliseconds. */
    READ,

    /** Changes the wiki, and is paced in seconds. */
    WRITE,
}

/**
 * Paces requests to one wiki.
 *
 * Reads and writes are paced separately. On top of that sits a penalty: when a server answers `429` or
 * reports replication lag it can ask for a pause, and that pause holds back reads and writes alike until it
 * elapses.
 *
 * Callers are serialized, so this also bounds concurrency to one in-flight acquisition at a time — a pool of
 * coroutines sharing a throttle produces one evenly spaced request stream rather than bursts. Sharing one
 * instance per wiki is the intended use.
 *
 * @param read the least time between two reads.
 * @param write the least time between two writes, which Wikimedia asks to be far longer.
 * @param timeSource must be the same clock the coroutines are scheduled on. In production that is the
 *   default; under `runTest` pass `testScheduler.timeSource`, or a throttle reading the real clock while
 *   `delay` advances virtual time will double-count every pause.
 */
public class Throttle(
    /** The least time between two reads. */
    public val read: Duration = DEFAULT_READ,
    /** The least time between two writes. */
    public val write: Duration = DEFAULT_WRITE,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutex = Mutex()

    private var lastRead: TimeMark? = null
    private var lastWrite: TimeMark? = null
    private var penaltyUntil: TimeMark? = null

    /**
     * Suspends until the next request of this [kind] may go out, then records it as sent.
     *
     * Cancellable: a cancelled caller does not consume its slot.
     */
    public suspend fun acquire(kind: RequestKind) {
        mutex.withLock {
            val wait = maxOf(waitFor(kind), penaltyRemaining())
            if (wait > Duration.ZERO) delay(wait)

            val now = timeSource.markNow()
            when (kind) {
                RequestKind.READ -> lastRead = now
                RequestKind.WRITE -> {
                    lastWrite = now
                    // A write is also traffic: it should not be followed instantly by a read.
                    lastRead = now
                }
            }
        }
    }

    /**
     * Holds back every subsequent request for [pause], because the server asked.
     *
     * Extends an existing penalty rather than shortening it, so overlapping `Retry-After` responses cannot
     * talk the client into going faster.
     */
    public suspend fun penalize(pause: Duration) {
        if (pause <= Duration.ZERO) return
        mutex.withLock {
            val proposed = timeSource.markNow() + pause
            if (penaltyRemaining() < pause) penaltyUntil = proposed
        }
    }

    /** How long a request of this [kind] would have to wait right now. For diagnostics. */
    public fun peek(kind: RequestKind): Duration = maxOf(waitFor(kind), penaltyRemaining())

    private fun waitFor(kind: RequestKind): Duration {
        val (last, spacing) =
            when (kind) {
                RequestKind.READ -> lastRead to read
                RequestKind.WRITE -> lastWrite to write
            }
        val since = last?.elapsedNow() ?: return Duration.ZERO
        return (spacing - since).coerceAtLeast(Duration.ZERO)
    }

    private fun penaltyRemaining(): Duration =
        penaltyUntil?.let { (-it.elapsedNow()).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO

    /** The pacing Wikimedia asks of bots, which is what this defaults to. */
    public companion object {
        /** Wikimedia asks bots to stay well under one read per second when running in parallel. */
        public val DEFAULT_READ: Duration = 100.milliseconds

        /** A conservative default write pace: six edits a minute. */
        public val DEFAULT_WRITE: Duration = 10.seconds

        /**
         * A fresh throttle with no pacing, for tests and self-hosted wikis.
         *
         * A function rather than a shared constant on purpose: a throttle carries the penalty a server asked
         * for, so a single shared instance would let one caller slow down another.
         */
        public fun unpaced(): Throttle = Throttle(Duration.ZERO, Duration.ZERO)
    }
}
