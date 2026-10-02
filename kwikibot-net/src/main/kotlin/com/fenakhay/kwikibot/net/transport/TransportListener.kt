package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.auth.Identity
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject

/**
 * Hears what a client does on a bot's behalf: every response a wiki sent, every request it retried, every
 * pause a wiki asked for, and every time it logged a dropped session back in.
 *
 * A long run does each of these quietly, and an operator wants to know: a run that spends half its time
 * waiting out replication lag looks like a slow bot. Every method does nothing by default, so a listener
 * overrides only what it needs. A listener that throws fails the request, which is how a test makes a warning
 * fatal.
 *
 * `ApiWarningListener` in `kwikibot-protocol` is the one to implement for warnings: it reads them out of each
 * response, which this layer does not.
 */
public interface TransportListener {

    /**
     * Called with [response], the decoded answer to [request], before the caller reads it.
     *
     * Once per answered request, and not for an answer served from a cache.
     */
    public fun onResponse(request: ApiRequest, response: JsonObject) {}

    /** Called before [request] is sent again, the [attempt]th retry, after [wait], because of [reason]. */
    public fun onRetry(request: ApiRequest, attempt: Int, wait: Duration, reason: String) {}

    /** Called when a wiki asks the whole client to hold back for [wait], as `Retry-After` does. */
    public fun onPenalty(wait: Duration) {}

    /** Called after a dropped session was restored by logging in again, as [identity]. */
    public fun onRelogin(identity: Identity) {}

    /** Ready-made listeners. */
    public companion object {
        /** Hears nothing. */
        public val NONE: TransportListener = object : TransportListener {}

        /** One listener that tells each of [listeners] everything, in order. */
        public fun of(vararg listeners: TransportListener): TransportListener =
            object : TransportListener {
                override fun onResponse(request: ApiRequest, response: JsonObject) = listeners.forEach {
                    it.onResponse(request, response)
                }

                override fun onRetry(request: ApiRequest, attempt: Int, wait: Duration, reason: String) =
                    listeners.forEach {
                        it.onRetry(request, attempt, wait, reason)
                    }

                override fun onPenalty(wait: Duration) = listeners.forEach { it.onPenalty(wait) }

                override fun onRelogin(identity: Identity) = listeners.forEach { it.onRelogin(identity) }
            }
    }
}
