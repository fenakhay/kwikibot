package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.client.Wiki
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * One stop page for every bot in a fleet.
 *
 * `StopPolicy.page` reads the page before every save, which is right for one bot and wasted budget across
 * many. This reads it at most once per [every], and every bot asks what it found, waiting for it if a read is
 * under way. The page has to say `false` for bots to run, so anything else stops them.
 *
 * A failed read keeps the last good answer: a brief blip should not halt a run. An outage long enough to make
 * the answer untrustworthy does, which is what [staleAfter] decides, counted from when that answer arrived on
 * a clock that cannot be set back. With no good answer yet, it fails closed.
 *
 * @param read the page's text, or `null` when there is no such page.
 * @param every how often to look.
 * @param staleAfter how long an answer stays good once it can no longer be refreshed.
 * @param timeSource what measures those, so a test can say rather than wait.
 */
@OptIn(ExperimentalAtomicApi::class)
public class SharedStop(
    private val read: suspend () -> String?,
    private val every: Duration = 1.minutes,
    private val staleAfter: Duration = 5.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : StopPolicy {

    /** What is known. Only the one who set [reading] replaces this before it is done. */
    private class Known(
        val allowed: Boolean = false,
        val readAt: TimeMark? = null,
        val triedAt: TimeMark? = null,
        val reading: CompletableDeferred<Unit>? = null,
    ) {
        fun reading(by: CompletableDeferred<Unit>?): Known = Known(allowed, readAt, triedAt, by)
    }

    private val known = AtomicReference(Known())

    override suspend fun mayContinue(): Boolean {
        currentCoroutineContext().ensureActive()
        while (true) {
            val seen = known.load()
            val reading = seen.reading
            val tried = seen.triedAt
            if (reading != null) {
                reading.await()
            } else if (tried != null && tried.elapsedNow() < every) {
                return answer(seen)
            } else {
                val mine = CompletableDeferred<Unit>()
                if (known.compareAndSet(seen, seen.reading(mine))) {
                    refresh(seen, mine)
                    return answer(known.load())
                }
            }
        }
    }

    private fun answer(from: Known): Boolean {
        val at = from.readAt
        return at != null && at.elapsedNow() <= staleAfter && from.allowed
    }

    /**
     * Reads the page and writes down what came of it. Cancelled, this leaves things as they were, so whoever
     * was waiting reads the page instead. A cancellation with this coroutine still active is the read's own
     * timeout, which is a read that failed.
     */
    private suspend fun refresh(before: Known, mine: CompletableDeferred<Unit>) {
        val text = runCatching { read() }
        val failure = text.exceptionOrNull()
        val cancelled = failure is CancellationException && !currentCoroutineContext().isActive
        val now = timeSource.markNow()
        known.store(
            when {
                cancelled -> before.reading(null)
                failure != null -> Known(before.allowed, before.readAt, now)
                else -> Known(text.getOrNull()?.trim().equals("false", ignoreCase = true), now, now)
            }
        )
        mine.complete(Unit)
        if (cancelled) throw checkNotNull(failure)
    }

    /** Ways to make one. */
    public companion object {
        /**
         * The page called [title] on [wiki], read fresh rather than through a response cache.
         *
         * @param wiki where the page is.
         * @param title what it is called, such as `"User:MyBot/Stop"`.
         */
        public fun onPage(wiki: Wiki, title: String): SharedStop {
            val page = wiki.ref(title)
            return SharedStop({ wiki.pages.freshContents(listOf(page))[page]?.text })
        }
    }
}
