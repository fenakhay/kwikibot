package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.client.Wiki
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One stop page for every bot in a fleet.
 *
 * `StopPolicy.page` reads the page before every save, which is right for one bot and wasted budget across
 * many. This reads it at most once per [every], and every bot asks what it found. The page has to say `false`
 * for bots to run, so anything else stops them.
 *
 * A failed read keeps the last good answer: a brief blip should not halt a run. An outage long enough to make
 * the answer untrustworthy does, which is what [staleAfter] decides. With no good answer yet, it fails
 * closed.
 *
 * @param read the page's text, or `null` when there is no such page.
 * @param every how often to look.
 * @param staleAfter how long an answer stays good once it can no longer be refreshed.
 * @param clock what the time is, so a test can say rather than wait.
 */
public class SharedStop(
    private val read: suspend () -> String?,
    private val every: Duration = 1.minutes,
    private val staleAfter: Duration = 5.minutes,
    private val clock: Clock = Clock.System,
) : StopPolicy {

    private val lock = Mutex()
    private var allowed = false
    private var readAt: Instant? = null
    private var triedAt: Instant? = null

    override suspend fun mayContinue(): Boolean {
        currentCoroutineContext().ensureActive()
        return lock.withLock {
            val now = clock.now()
            val tried = triedAt
            if (tried == null || now - tried >= every) {
                triedAt = now
                refresh(now)
            }
            val at = readAt
            at != null && now - at <= staleAfter && allowed
        }
    }

    private suspend fun refresh(now: Instant) {
        val text = runCatching {
            read()
        }
            .getOrElse {
                if (it is CancellationException) throw it
                return
            }
        allowed = text?.trim().equals("false", ignoreCase = true)
        readAt = now
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
