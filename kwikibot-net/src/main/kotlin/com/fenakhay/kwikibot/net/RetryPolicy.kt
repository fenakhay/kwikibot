package com.fenakhay.kwikibot.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long to wait before trying a failed request again.
 *
 * Deliberately deterministic — no jitter — so that a run is reproducible and the timing tests
 * can assert exact virtual-clock values. A single bot is not a thundering herd; the pacing that
 * protects the wiki is [Throttle], not randomised backoff.
 */
public data class RetryPolicy(
    /** How many times a request may be retried before the failure is raised. */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** The wait before the first retry. */
    val initialDelay: Duration = 1.seconds,
    /** The ceiling each doubling is capped at. */
    val maxDelay: Duration = 2.seconds * MAX_DELAY_MULTIPLIER,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(initialDelay >= Duration.ZERO) { "initialDelay must not be negative" }
    }

    /**
     * The wait before retry number [attempt], counting the first retry as 1.
     *
     * Doubles each time, capped at [maxDelay].
     */
    public fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt is 1-based" }
        var delay = initialDelay
        repeat(attempt - 1) {
            delay *= 2
            if (delay >= maxDelay) return maxDelay
        }
        return minOf(delay, maxDelay)
    }

    /**
     * The wait to use when the server named one.
     *
     * A server that says `Retry-After` is telling us something the backoff curve does not know,
     * so it wins — but never shortens the wait we would have taken anyway.
     */
    public fun delayFor(attempt: Int, retryAfter: Duration?): Duration =
        maxOf(delayFor(attempt), retryAfter ?: Duration.ZERO)

    /** The policy for a caller that would rather handle failure itself. */
    public companion object {
        private const val DEFAULT_MAX_RETRIES = 5
        private const val MAX_DELAY_MULTIPLIER = 60

        /** Never retry. Useful in tests, and for callers that do their own recovery. */
        public val NONE: RetryPolicy = RetryPolicy(maxRetries = 0)
    }
}
