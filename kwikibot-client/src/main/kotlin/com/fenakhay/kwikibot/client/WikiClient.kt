package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.client.internal.ApiWiki
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.auth.LoginManager
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.cache.ResponseCache
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.throwOnError
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/**
 * Everything that is true of a client rather than of one wiki.
 *
 * Immutable, and passed in rather than read from a global, so a test constructs one directly and two clients
 * in the same process cannot interfere.
 */
public data class WikiConfig(
    /** Identifies the bot to wiki operators. Required by the Wikimedia user-agent policy. */
    val userAgent: UserAgent,
    /** How fast requests may go out. One throttle is shared per wiki. */
    val throttle: Throttle = Throttle(),
    /** How failed requests are retried. */
    val retry: RetryPolicy = RetryPolicy(),
    /**
     * The replication lag above which a wiki should defer our requests.
     *
     * Wikimedia asks bots for 5 seconds. `null` omits the parameter, which only makes sense for a self-hosted
     * wiki.
     */
    val maxlag: Int? = KtorTransport.DEFAULT_MAXLAG,
    /**
     * Where read responses are remembered, if anywhere.
     *
     * Off by default. A cache is for developing a bot rather than running one: it saves the wiki from serving
     * the same three thousand pages again because a summary had a typo in it.
     */
    val cache: ResponseCache = ResponseCache.NONE,
)

/**
 * Opens connections to wikis.
 *
 * One client owns the HTTP stack; each [wiki] call establishes a session, so opening a wiki is a suspending
 * operation rather than a constructor — it logs in and fetches the site's namespaces before handing back
 * something usable.
 *
 * The client owns its [HttpClient] unless one was supplied, and [close] shuts it down.
 */
public class WikiClient(
    private val config: WikiConfig,
    private val credentials: Credentials = Credentials.Anonymous,
    engine: HttpClientEngine? = null,
    httpClient: HttpClient? = null,
) : AutoCloseable {

    private val ownsClient = httpClient == null

    private val http: HttpClient =
        httpClient ?: WikiHttpClient.create(credentials = credentials, engine = engine)

    /** Opens the wiki of a family, such as `en` + [Family.WIKTIONARY]. */
    public suspend fun wiki(code: LangCode, family: Family): Wiki = wiki(family.endpoint(code))

    /** Opens the wiki served at [endpoint]. */
    public suspend fun wiki(endpoint: ApiEndpoint): Wiki {
        val transport =
            KtorTransport(
                client = http,
                endpoint = endpoint,
                userAgent = config.userAgent,
                throttle = config.throttle,
                retry = config.retry,
                maxlag = config.maxlag,
                cache = config.cache,
            )

        val tokens = TokenStore(transport)
        val identity = LoginManager(transport, credentials, tokens).login()
        val info = fetchSiteInfo(transport)

        // The HTTP client is handed through for uploads alone: they need a multipart body
        // carrying bytes, which the transport deliberately cannot express.
        return ApiWiki(info, identity, transport, tokens, http, endpoint, config.userAgent)
    }

    private suspend fun fetchSiteInfo(transport: MediaWikiTransport): SiteInfo =
        SiteInfo.decode(
            transport
                .call(ApiRequest.of("query", "meta" to "siteinfo", "siprop" to SiteInfo.PROPERTIES))
                .throwOnError()
        )

    override fun close() {
        if (ownsClient) http.close()
    }
}
