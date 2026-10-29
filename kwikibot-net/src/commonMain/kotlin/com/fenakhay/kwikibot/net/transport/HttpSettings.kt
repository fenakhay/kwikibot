package com.fenakhay.kwikibot.net.transport

import kotlin.time.Duration

/**
 * How a client's HTTP connections to wikis are managed.
 *
 * A client [WikiHttpClient] makes has at most [maxConcurrentRequests] requests in flight at once, to every
 * wiki it talks to together, on every platform: API requests, uploads and file downloads alike. A request
 * over the limit waits for one in flight to be answered, so a run whose read and write concurrency add up to
 * more than the limit waits here rather than sending more.
 *
 * Each platform's engine honours the rest as far as it lets them be set. On the JVM, OkHttp takes
 * [maxConcurrentRequests] as its own limit and [maxIdleConnections]. On macOS, NSURLSession takes
 * [maxConcurrentRequests] as its limit per host and keeps its own idle connections. On Linux, Windows and
 * JavaScript, libcurl, WinHTTP and fetch manage their connections themselves.
 */
public data class HttpSettings(
    /**
     * How many requests the client may have in flight at once, across every wiki it opens.
     *
     * Wikimedia asks every client to keep to [DEFAULT_CONCURRENT_REQUESTS] or fewer, which is the default.
     * Raise it only for a wiki whose operators allow more.
     */
    val maxConcurrentRequests: Int = DEFAULT_CONCURRENT_REQUESTS,
    /** How many idle connections are kept open for reuse. */
    val maxIdleConnections: Int = DEFAULT_CONNECTIONS,
    /**
     * How long connecting, a request and a silent socket may each take before giving up. On JavaScript, whose
     * fetch has no connect or socket timeout, it bounds the whole request only.
     */
    val timeout: Duration = WikiHttpClient.DEFAULT_TIMEOUT,
) {
    init {
        require(maxConcurrentRequests >= 1) { "at least one request must be allowed in flight" }
        require(maxIdleConnections >= 0) { "the idle connection count cannot be negative" }
        require(timeout.isPositive()) { "the timeout must be positive" }
    }

    /** The defaults a client starts from. */
    public companion object {
        /**
         * How many requests a client has in flight at once unless told otherwise: the most
         * [Wikimedia's rate limits](https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits) ask for.
         */
        public const val DEFAULT_CONCURRENT_REQUESTS: Int = 3

        /** How many idle connections a client keeps for reuse unless told otherwise. */
        public const val DEFAULT_CONNECTIONS: Int = 32
    }
}
