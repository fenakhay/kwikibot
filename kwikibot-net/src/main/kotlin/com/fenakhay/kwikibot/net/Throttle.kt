package com.fenakhay.kwikibot.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
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
 * Each request takes the next free slot of its kind and waits for it on its own. A pool of coroutines sharing
 * a throttle produces one evenly spaced stream of each kind rather than bursts, and a write waiting for its
 * slot holds up no read. Sharing one instance per wiki is the intended use.
 *
 * @param read the least time between the starts of two reads.
 * @param write the least time between the starts of two writes, which Wikimedia asks to be far longer.
 * @param timeSource must be the same clock the coroutines are scheduled on. In production that is the
 *   default; under `runTest` pass `testScheduler.timeSource`, or a throttle reading the real clock while
 *   `delay` advances virtual time will double-count every pause.
 */
public class Throttle(
    /** The least time between the starts of two reads. */
    public val read: Duration = DEFAULT_READ,
    /** The least time between the starts of two writes. */
    public val write: Duration = DEFAULT_WRITE,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutex = Mutex()

    /** What every time below is measured from, since marks from an arbitrary clock cannot be compared. */
    private val origin: TimeMark = timeSource.markNow()

    // The earliest moment the next request of each kind may start, and the end of any penalty.
    private var nextRead = Duration.ZERO
    private var nextWrite = Duration.ZERO
    private var penaltyUntil = Duration.ZERO

    // The start of each pending or recent write: no read may start within [read] after one.
    // Writes are few, so this stays short.
    private val writes = ArrayDeque<Duration>()

    /**
     * Suspends until the next request of this [kind] may go out, and records it as sent.
     *
     * The slot is taken under the lock and waited for outside it, so callers of the other kind are not held
     * up behind this wait. Cancellable: a caller cancelled while it waits gives its slot back, unless a later
     * caller already holds the next one.
     */
    public suspend fun acquire(kind: RequestKind) {
        val slot = mutex.withLock { reserve(kind) }

        try {
            if (slot.wait > Duration.ZERO) delay(slot.wait)
            // A penalty asked for while this caller slept still applies to it.
            val penalty = penaltyRemaining()
            if (penalty > Duration.ZERO) delay(penalty)
        } catch (e: CancellationException) {
            mutex.withLock { release(slot) }
            throw e
        }
    }

    /** Takes the next slot of [kind] and says how long until it. */
    private fun reserve(kind: RequestKind): Slot {
        val now = origin.elapsedNow()
        val next =
            when (kind) {
                RequestKind.READ -> nextRead
                RequestKind.WRITE -> nextWrite
            }
        var at = maxOf(now, next, penaltyUntil)

        // A write is also traffic, so a read should not follow it instantly. Only a read that would land
        // just after a write is moved; a read due before a pending write still goes first.
        while (writes.isNotEmpty() && writes.first() + read <= now) writes.removeFirst()
        if (kind == RequestKind.READ) {
            for (start in writes) if (at >= start && at < start + read) at = start + read
        }

        val slot = Slot(kind, at, at - now, nextRead, nextWrite)
        when (kind) {
            RequestKind.READ -> nextRead = at + read
            RequestKind.WRITE -> {
                nextWrite = at + write
                writes.addLast(at)
            }
        }
        return slot
    }

    /** Hands back a slot nobody has queued behind, so a cancelled caller costs the others nothing. */
    private fun release(slot: Slot) {
        when (slot.kind) {
            RequestKind.READ -> if (nextRead == slot.at + read) nextRead = slot.previousRead
            RequestKind.WRITE ->
                if (nextWrite == slot.at + write) {
                    nextWrite = slot.previousWrite
                    writes.remove(slot.at)
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
        mutex.withLock { penaltyUntil = maxOf(penaltyUntil, origin.elapsedNow() + pause) }
    }

    /** How long a request of this [kind] would have to wait right now. For diagnostics. */
    public fun peek(kind: RequestKind): Duration {
        val next =
            when (kind) {
                RequestKind.READ -> nextRead
                RequestKind.WRITE -> nextWrite
            }
        return maxOf(next - origin.elapsedNow(), penaltyRemaining(), Duration.ZERO)
    }

    private fun penaltyRemaining(): Duration =
        (penaltyUntil - origin.elapsedNow()).coerceAtLeast(Duration.ZERO)

    /** One caller's place in line: when it may go, and what the line was before it joined. */
    private class Slot(
        val kind: RequestKind,
        val at: Duration,
        val wait: Duration,
        val previousRead: Duration,
        val previousWrite: Duration,
    )

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
