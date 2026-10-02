package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.client.internal.ApiWiki
import com.fenakhay.kwikibot.client.internal.SessionTransport
import com.fenakhay.kwikibot.client.internal.wire.MediaWikiBehaviour
import com.fenakhay.kwikibot.client.internal.wire.Registry
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
import com.fenakhay.kwikibot.net.transport.HttpSettings
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.net.transport.TransportListener
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import com.fenakhay.kwikibot.protocol.ApiWarningListener
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.throwOnError
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

private val log = KotlinLogging.logger {}

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
    /**
     * What hears the warnings wikis attach to their answers.
     *
     * By default each distinct warning is logged once, deprecations at `WARN`. A test can pass one that fails
     * on a deprecation, such as `FailOnDeprecation` in `kwikibot-testkit`.
     */
    val onWarning: ApiWarningListener = ApiWarningListener.LOG,
    /**
     * How many times in ten minutes a dropped session is restored by logging in again.
     *
     * A wiki ends a session that has gone unused for long enough or that its session store loses, and a
     * bot-password session when the bot password is reset, so a long run needs this. A logged-in session
     * asserts its account on every request, so a lost session is reported instead of read anonymously, and
     * the request is repeated once after logging in. Past this many logins the error stops the run, since
     * credentials that keep failing will not start working. Zero turns it off.
     */
    val relogins: Int = DEFAULT_RELOGINS,
    /**
     * How the client's connections are managed: how many requests may be in flight to one wiki, and the
     * timeout. Set the connection limit above a run's read and write concurrency, or the run waits on
     * connections instead of the wiki.
     */
    val http: HttpSettings = HttpSettings(),
    /**
     * What hears every response, and each time the client retries a request, holds back because a wiki asked,
     * or logs a dropped session back in. Retries are also logged at `INFO`.
     */
    val listener: TransportListener = TransportListener.NONE,
) {
    init {
        require(relogins >= 0) { "relogins cannot be negative" }
    }

    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        userAgent: UserAgent,
        throttle: Throttle = Throttle(),
        retry: RetryPolicy = RetryPolicy(),
        maxlag: Int? = KtorTransport.DEFAULT_MAXLAG,
        cache: ResponseCache = ResponseCache.NONE,
    ) : this(userAgent, throttle, retry, maxlag, cache, ApiWarningListener.LOG)

    /** The defaults a configuration starts from. */
    public companion object {
        /** How many logins in ten minutes [relogins] allows unless told otherwise. */
        public const val DEFAULT_RELOGINS: Int = 3
    }
}

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
        httpClient ?: WikiHttpClient.create(config.http, credentials = credentials, engine = engine)

    /** Opens the wiki of a family, such as `en` + [Family.WIKTIONARY]. */
    public suspend fun wiki(code: LangCode, family: Family): Wiki = wiki(family.endpoint(code))

    /** Opens the wiki served at [endpoint]. */
    public suspend fun wiki(endpoint: ApiEndpoint): Wiki {
        val listeners = TransportListener.of(config.onWarning, config.listener)
        val transport =
            KtorTransport(
                client = http,
                endpoint = endpoint,
                userAgent = config.userAgent,
                throttle = config.throttle,
                retry = config.retry,
                maxlag = config.maxlag,
                cache = config.cache,
                listener = listeners,
            )

        val tokens = TokenStore(transport)
        val login = LoginManager(transport, credentials, tokens)
        val identity = login.login()
        val info = fetchSiteInfo(transport)

        // Everything after logging in goes through the session, so a dropped one is restored instead of
        // carried on anonymously. Logging in and fetching tokens must not.
        val session =
            SessionTransport(
                transport,
                login,
                identity,
                relogins = config.relogins,
                onRelogin = { restored ->
                    log.info {
                        "logged back in to ${endpoint.server} as ${restored.name}: the session was lost"
                    }
                    listeners.onRelogin(restored)
                },
            )

        val oldest = MediaWikiBehaviour.OLDEST_SUPPORTED
        if (!info.hasVersion(oldest)) {
            log.warn {
                "${info.server} runs MediaWiki ${info.version}, older than the $oldest this library is " +
                    "tested against. Most of the API will work; report what does not."
            }
        }

        // The HTTP client is handed through for uploads alone: they need a multipart body
        // carrying bytes, which the transport deliberately cannot express.
        return ApiWiki(info, identity, session, tokens, http, endpoint, config.userAgent, listeners)
    }

    private suspend fun fetchSiteInfo(transport: MediaWikiTransport): SiteInfo =
        SiteInfo.decode(
            transport
                .call(ApiRequest.of("query", "meta" to "siteinfo", "siprop" to Registry.SITE_INFO.joined))
                .throwOnError()
        )

    override fun close() {
        if (ownsClient) http.close()
    }
}
