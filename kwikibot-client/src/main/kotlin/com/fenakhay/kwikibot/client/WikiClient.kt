package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.Credentials
import com.fenakhay.kwikibot.net.Identity
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.LoginManager
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.net.ResponseCache
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.WikiHttpClient
import com.fenakhay.kwikibot.protocol.ActivityDecoder
import com.fenakhay.kwikibot.protocol.PageDecoder
import com.fenakhay.kwikibot.protocol.ParamInfo
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.throwOnError
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/**
 * Everything that is true of a client rather than of one wiki.
 *
 * Immutable, and passed in rather than read from a global, so a test constructs one directly and
 * two clients in the same process cannot interfere.
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
     * Wikimedia asks bots for 5 seconds. `null` omits the parameter, which only makes sense for
     * a self-hosted wiki.
     */
    val maxlag: Int? = KtorTransport.DEFAULT_MAXLAG,
    /**
     * Where read responses are remembered, if anywhere.
     *
     * Off by default. A cache is for developing a bot rather than running one: it saves the wiki
     * from serving the same three thousand pages again because a summary had a typo in it.
     */
    val cache: ResponseCache = ResponseCache.NONE,
)

/**
 * Opens connections to wikis.
 *
 * One client owns the HTTP stack; each [wiki] call establishes a session, so opening a wiki is a
 * suspending operation rather than a constructor — it logs in and fetches the site's namespaces
 * before handing back something usable.
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

    private val http: HttpClient = httpClient
        ?: WikiHttpClient.create(credentials = credentials, engine = engine)

    /** Opens the wiki of a family, such as `en` + [Family.WIKTIONARY]. */
    public suspend fun wiki(code: LangCode, family: Family): Wiki =
        wiki(family.endpoint(code))

    /** Opens the wiki served at [endpoint]. */
    public suspend fun wiki(endpoint: ApiEndpoint): Wiki {
        val transport = KtorTransport(
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
            transport.call(
                ApiRequest.of("query", "meta" to "siteinfo", "siprop" to SiteInfo.PROPERTIES),
            ).throwOnError(),
        )

    override fun close() {
        if (ownsClient) http.close()
    }
}

internal class ApiWiki(
    override val info: SiteInfo,
    override val identity: Identity,
    override val transport: MediaWikiTransport,
    override val tokens: TokenStore,
    private val http: HttpClient,
    private val endpoint: ApiEndpoint,
    private val userAgent: UserAgent,
) : Wiki {

    override val id: WikiId get() = info.id

    private val decoder = PageDecoder(info.id, info.namespaces)

    override val pages: PageService = ApiPageService(
        transport = transport,
        tokens = tokens,
        decoder = decoder,
        namespaces = info.namespaces,
    )

    override val lists: ListService = ApiListService(
        transport = transport,
        decoder = decoder,
        namespaces = info.namespaces,
    )

    override val revisions: RevisionService = ApiRevisionService(
        transport = transport,
        tokens = tokens,
        decoder = decoder,
        namespaces = info.namespaces,
    )

    override val users: UserService = ApiUserService(
        transport = transport,
        tokens = tokens,
        activity = ActivityDecoder(decoder),
    )

    override val logs: LogService = ApiLogService(
        transport = transport,
        activity = ActivityDecoder(decoder),
        namespaces = info.namespaces,
    )

    override val paramInfo: ParamInfo = ParamInfo(transport)

    override val renderer: RenderService = ApiRenderService(
        transport = transport,
        decoder = decoder,
        namespaces = info.namespaces,
    )

    override val meta: MetaService = ApiMetaService(transport, tokens)

    override val proofread: ProofreadService = ApiProofreadService(
        transport = transport,
        decoder = decoder,
        namespaces = info.namespaces,
        info = info,
    )

    override val extensions: ExtensionService = ApiExtensionService(
        transport = transport,
        tokens = tokens,
        decoder = decoder,
        namespaces = info.namespaces,
        info = info,
    )

    override val files: FileService = ApiFileService(
        transport = transport,
        tokens = tokens,
        decoder = decoder,
        namespaces = info.namespaces,
        http = http,
        endpoint = endpoint,
        userAgent = userAgent,
    )

    override fun toString(): String = "Wiki(${info.id} as ${identity.name})"
}
