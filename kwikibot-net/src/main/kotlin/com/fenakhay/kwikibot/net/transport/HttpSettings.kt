package com.fenakhay.kwikibot.net.transport

import kotlin.time.Duration

/**
 * How a client's HTTP connections to a wiki are managed.
 *
 * Every request a bot makes goes to one host, so the limit per host is the limit on the whole run. Set below
 * the run's read and write concurrency, it is where the run waits. The HTTP library's own default is five.
 */
public data class HttpSettings(
    /** How many requests may be in flight to one host at once. */
    val maxRequestsPerHost: Int = DEFAULT_CONNECTIONS,
    /** How many idle connections are kept open for reuse. */
    val maxIdleConnections: Int = DEFAULT_CONNECTIONS,
    /** How long connecting, a request and a silent socket may each take before giving up. */
    val timeout: Duration = WikiHttpClient.DEFAULT_TIMEOUT,
) {
    init {
        require(maxRequestsPerHost >= 1) { "at least one request must be allowed in flight" }
        require(maxIdleConnections >= 0) { "the idle connection count cannot be negative" }
        require(timeout.isPositive()) { "the timeout must be positive" }
    }

    /** The defaults a client starts from. */
    public companion object {
        /** Enough for any concurrency a polite bot asks for. */
        public const val DEFAULT_CONNECTIONS: Int = 32
    }
}
