package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.auth.LoginManager
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Builds the HTTP client a transport should be given.
 *
 * Two settings here are not optional in practice. Cookies must be stored, or a bot-password session is lost
 * after the login response. And an OAuth 2.0 bearer header has to be attached to every request, since there
 * is no session to carry it.
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
     * @param engine the Ktor engine to use, or `null` for the one on the classpath.
     * @param timeout applies to the connection, the request and the socket alike.
     */
    public fun create(
        credentials: Credentials = Credentials.Anonymous,
        engine: HttpClientEngine? = null,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): HttpClient {
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

        return if (engine == null) HttpClient(OkHttp, configure) else HttpClient(engine, configure)
    }
}
