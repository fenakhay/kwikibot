package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.auth.LoginManager
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Builds the HTTP client a transport should be given.
 *
 * Two settings here are not optional in practice. Cookies must be stored, or a bot-password session is lost
 * after the login response; an OAuth 2.0 token keeps them too, since Wikimedia needs the session cookies
 * alongside the token to apply the account's rate limit. And an OAuth 2.0 bearer header has to be attached to
 * every request, since no login establishes the account.
 *
 * A client made here lets at most [HttpSettings.maxConcurrentRequests] requests be in flight at once, three
 * unless told otherwise, which is what Wikimedia asks; [withRequestPermit] is where a request waits for room.
 *
 * The caller owns the returned client and should close it when done.
 */
public object WikiHttpClient {

    /** Long enough for a slow API query, short enough that a hung request does not stall a run. */
    public val DEFAULT_TIMEOUT: Duration = 60.seconds

    /**
     * Builds a client configured the way a wiki expects.
     *
     * @param credentials decide whether a bearer header is set; a bot password logs in later, through
     *   [LoginManager], and needs the cookie jar this installs.
     * @param engine the Ktor engine to use, or `null` for this platform's own.
     * @param timeout applies to the connection, the request and the socket alike; on JavaScript to the
     *   request only, as [HttpSettings.timeout] says.
     */
    public fun create(
        credentials: Credentials = Credentials.Anonymous,
        engine: HttpClientEngine? = null,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): HttpClient = create(HttpSettings(timeout = timeout), credentials, engine)

    /**
     * Builds a client configured the way a wiki expects, with [settings] for its connections.
     *
     * @param settings how many requests may be in flight at once, how many idle connections are kept, and the
     *   timeout. The limit on requests in flight holds whatever the engine. The connection settings apply to
     *   this platform's own engine as far as it lets them be set, which [HttpSettings] lists; one passed as
     *   [engine] keeps its own.
     * @param credentials decide whether a bearer header is set; a bot password logs in later, through
     *   [LoginManager], and needs the cookie jar this installs.
     * @param engine the Ktor engine to use, or `null` for this platform's own.
     */
    public fun create(
        settings: HttpSettings,
        credentials: Credentials = Credentials.Anonymous,
        engine: HttpClientEngine? = null,
    ): HttpClient {
        val timeout = settings.timeout
        val configure: HttpClientConfig<*>.() -> Unit = {
            install(HttpCookies)

            install(HttpTimeout) {
                requestTimeoutMillis = timeout.inWholeMilliseconds
                connectTimeoutMillis = timeout.inWholeMilliseconds
                socketTimeoutMillis = timeout.inWholeMilliseconds
            }

            if (credentials is Credentials.OAuth2) {
                defaultRequest {
                    header(HttpHeaders.Authorization, "Bearer ${credentials.accessToken}")
                }
            }

            // Redirects are followed by default; MediaWiki uses them for canonical hosts.
            expectSuccess = false
        }

        val client = if (engine != null) HttpClient(engine, configure) else defaultClient(settings, configure)
        client.attributes.put(REQUEST_PERMITS, Semaphore(settings.maxConcurrentRequests))
        return client
    }
}

/** Where a client [WikiHttpClient] made keeps its places for requests in flight. */
private val REQUEST_PERMITS = AttributeKey<Semaphore>("kwikibot.REQUEST_PERMITS")

/**
 * Runs [block], which sends one request and reads its answer, once this client has fewer requests in flight
 * than its [HttpSettings.maxConcurrentRequests]. A client [WikiHttpClient] did not make has no such limit,
 * and [block] runs at once.
 *
 * Every request the library sends to a wiki through a client goes through here. A request sent through the
 * same client some other way is not counted, nor does it wait.
 */
public suspend fun <T> HttpClient.withRequestPermit(block: suspend () -> T): T {
    val permits = attributes.getOrNull(REQUEST_PERMITS) ?: return block()
    return permits.withPermit { block() }
}
