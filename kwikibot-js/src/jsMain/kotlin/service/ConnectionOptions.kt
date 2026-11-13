import com.fenakhay.kwikibot.bindings.common.Setting
import com.fenakhay.kwikibot.bindings.connect.configOf as wikiConfigOf
import com.fenakhay.kwikibot.bindings.connect.httpOf
import com.fenakhay.kwikibot.bindings.connect.retryOf as retryPolicyOf
import com.fenakhay.kwikibot.bindings.connect.throttleOf
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.auth.Identity as ClientIdentity
import com.fenakhay.kwikibot.net.cache.DiskCache as ClientDiskCache
import com.fenakhay.kwikibot.net.cache.ResponseCache as ClientResponseCache
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.TransportListener
import com.fenakhay.kwikibot.protocol.ApiWarning as ClientApiWarning
import com.fenakhay.kwikibot.protocol.ApiWarningListener
import kotlin.js.Promise
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject

/** How fast requests may go out, in milliseconds between the starts of two. */
@JsExport
public external interface ThrottleOptions {
    /** The least time between two reads; 100 unless given. */
    public val read: Double?

    /** The least time between two writes; 10000 unless given. */
    public val write: Double?
}

/** How failed requests are retried. */
@JsExport
public external interface RetryOptions {
    /** How many times a request may be retried before the failure is raised; 5 unless given. */
    public val maxRetries: Int?

    /** The wait before the first retry, in milliseconds; 1000 unless given. */
    public val initialDelay: Double?

    /** The ceiling each doubling of the wait is capped at, in milliseconds; 120000 unless given. */
    public val maxDelay: Double?
}

/** How a client's connections are managed. */
@JsExport
public external interface HttpOptions {
    /** How many requests may be in flight at once, to every wiki the client opens; 3 unless given. */
    public val maxConcurrentRequests: Int?

    /** How many idle connections are kept open for reuse; 32 unless given. fetch manages its own. */
    public val maxIdleConnections: Int?

    /** How long a request may take before giving up, in milliseconds; 60000 unless given. */
    public val timeout: Double?
}

/**
 * Hears what a client does on a bot's behalf. Every function is optional. One that throws fails the request.
 * What it is given never holds a password, a token or an auth header: the value of any parameter or field
 * named so is `"[redacted]"` instead, whatever was sent.
 */
@JsExport
public external interface ListenerOptions {
    /** Hears each answer the wiki sent, with the request's parameters. Not one served from a cache. */
    public val onResponse: ((params: Record<String, String>, response: Record<String, JsonValue>) -> Unit)?

    /** Hears each retry: its parameters, which retry it is, the wait in milliseconds, and why. */
    public val onRetry:
        ((params: Record<String, String>, attempt: Int, wait: Double, reason: String) -> Unit)?

    /** Hears a wiki asking the whole client to hold back, for this many milliseconds. */
    public val onPenalty: ((wait: Double) -> Unit)?

    /** Hears a dropped session being restored by logging in again. */
    public val onRelogin: ((identity: Identity) -> Unit)?
}

/**
 * Remembers responses so a repeated read does not repeat the request. `DiskCache` is one; any object with
 * these two functions is another. Each may return a promise.
 */
@JsExport
public external interface ResponseCache {
    /** The stored response for the request with [params], or `null` when there is none. */
    public fun get(params: Record<String, String>): Awaitable<Record<String, JsonValue>?>

    /** Stores [response] for the request with [params]. */
    public fun put(params: Record<String, String>, response: Record<String, JsonValue>): Awaitable<Ignored>
}

/**
 * A response cache on disk, one file per request, under the directory [root], for developing a bot rather
 * than running one. An entry older than [ttl] milliseconds, 12 hours unless given, is ignored and deleted.
 * Writes and anything carrying a credential are never stored.
 *
 * Works in Node only: in a browser, which has no filesystem, making one throws.
 */
@JsExport
public class DiskCache(
    /** The directory the entries go under. */
    public val root: String,
    ttl: Double? = null,
) {
    init {
        requireFileSystem("a disk cache")
    }

    internal val cache: ClientDiskCache =
        ClientDiskCache(Path(root), ttl?.milliseconds ?: ClientDiskCache.DEFAULT_TTL)

    /** The stored response for the request with [params], or `null` when there is none or it has expired. */
    public fun get(params: Record<String, String>): Promise<Record<String, JsonValue>?> = cacheScope.promise {
        cache.get(ApiRequest(params.toMap()))?.let(::plainOf)
    }

    /** Stores [response] for the request with [params], unless it is one that is never stored. */
    public fun put(params: Record<String, String>, response: Record<String, JsonValue>): Promise<Unit> =
        cacheScope.promiseVoid {
            cache.put(ApiRequest(params.toMap()), jsonObjectOf(response))
        }

    /** Removes every entry. */
    public fun clear() {
        cache.clear()
    }
}

private val cacheScope = CoroutineScope(SupervisorJob())

/** Everything a wiki can be opened with beyond its server. */
@JsExport
public external interface ConnectOptions {
    /** Who to log in as, a `BotPassword` or an `OAuth2`. Anonymous unless given. */
    public val login: BotPassword?

    /** Where `api.php` is under the server, `"/w"` unless given. */
    public val scriptPath: String?

    /** Whether to speak HTTPS, `true` unless given. Off only for a local test wiki. */
    public val secure: Boolean?

    /** Identifies the bot to operators; the library's name with the contact unless given. */
    public val userAgent: UserAgent?

    /** How fast requests may go out. */
    public val throttle: ThrottleOptions?

    /** How failed requests are retried. */
    public val retry: RetryOptions?

    /**
     * The replication lag in seconds above which a wiki should defer requests; 5 unless given, as Wikimedia
     * asks. `null` leaves the parameter out, which only makes sense for a self-hosted wiki.
     */
    public val maxlag: Int?

    /** Where read responses are remembered, such as a `DiskCache`. Nowhere unless given. */
    public val cache: ResponseCache?

    /** Hears each warning a wiki attaches to an answer. Unless given, each distinct one is logged once. */
    public val onWarning: ((warning: ApiWarning) -> Unit)?

    /** How many times in ten minutes a dropped session is restored by logging in again; 3 unless given. */
    public val relogins: Int?

    /** How the client's connections are managed. */
    public val http: HttpOptions?

    /** Hears what the client does on the bot's behalf. */
    public val listener: ListenerOptions?
}

/** A warning a wiki attached to an answer. */
@JsExport
public class ApiWarning internal constructor(internal val warning: ClientApiWarning) {

    /** The module that raised it. */
    public val module: String
        get() = warning.module

    /** What it says. */
    public val text: String
        get() = warning.text

    /** The wiki's code for it, or `null` when it gave none. */
    public val code: String?
        get() = warning.code

    /** Whether it says something the request used is deprecated. */
    public val isDeprecation: Boolean
        get() = warning.isDeprecation

    /** The warning as a line of text. */
    override fun toString(): String = warning.toString()
}

/**
 * An `onWarning` for tests against a real wiki, which fails any request a wiki answers with a deprecation
 * warning, so a test catches what needs replacing before the release that removes it. Every other warning
 * goes to [others], or is logged once when none is given.
 */
@JsExport
public fun failOnDeprecation(others: ((warning: ApiWarning) -> Unit)? = null): (warning: ApiWarning) -> Unit =
    { warning ->
        when {
            warning.isDeprecation ->
                throw AssertionError(
                    "a request drew a deprecation warning from ${warning.module}: ${warning.text}"
                )
            others != null -> others(warning)
            else ->
                ApiWarningListener.LOG.onWarning(
                    ApiRequest(mapOf("action" to warning.module)),
                    warning.warning,
                )
        }
    }

/** [options] as the library's own configuration, whose defaults hold for what they leave out. */
internal fun configOf(contact: String, options: ConnectOptions?): WikiConfig =
    wikiConfigOf(
        contact,
        options?.userAgent?.agent,
        options?.throttle?.let { throttleOf(it.read?.milliseconds, it.write?.milliseconds) },
        options?.retry?.let(::retryOf),
        maxlagOf(options),
        options?.cache?.let(::cacheOf),
        options?.onWarning?.let(::warningListenerOf),
        options?.relogins,
        options?.http?.let {
            httpOf(it.maxConcurrentRequests, it.maxIdleConnections, it.timeout?.milliseconds)
        },
        options?.listener?.let(::listenerOf),
    )

/** What [options] says about maxlag, where leaving it out and `null` say different things. */
private fun maxlagOf(options: ConnectOptions?): Setting<Int> {
    if (options == null) return Setting.Default
    val maxlag = options.asDynamic().maxlag
    return if (maxlag === undefined) Setting.Default else Setting.ofNullable(maxlag.unsafeCast<Int?>())
}

/** [options] as a retry policy, whose defaults hold for what they leave out. */
internal fun retryOf(options: RetryOptions?): RetryPolicy =
    retryPolicyOf(options?.maxRetries, options?.initialDelay?.milliseconds, options?.maxDelay?.milliseconds)

private fun cacheOf(cache: ResponseCache): ClientResponseCache =
    (cache as Any as? DiskCache)?.cache ?: JsResponseCache(cache)

/** A cache written in JavaScript. It sees only what may be stored, as a cache of this library's does. */
private class JsResponseCache(private val cache: ResponseCache) : ClientResponseCache {

    override suspend fun get(request: ApiRequest): JsonObject? {
        if (!ClientResponseCache.isCacheable(request)) return null
        val found = settledCall { cache.get(request.params.toRecord { it }) }
        return found?.let(::jsonObjectOf)
    }

    override suspend fun put(request: ApiRequest, response: JsonObject) {
        if (!ClientResponseCache.isCacheable(request)) return
        val _ = settledCall { cache.put(request.params.toRecord { it }, plainOf(response)) }
    }
}

private fun warningListenerOf(listener: (ApiWarning) -> Unit): ApiWarningListener =
    ApiWarningListener { _, warning ->
        fromJs { listener(ApiWarning(warning)) }
    }

private fun listenerOf(options: ListenerOptions): TransportListener =
    object : TransportListener {
        override fun onResponse(request: ApiRequest, response: JsonObject) {
            fromJs {
                options.onResponse?.invoke(paramsWithoutSecrets(request), withoutSecrets(plainOf(response)))
            }
        }

        override fun onRetry(request: ApiRequest, attempt: Int, wait: Duration, reason: String) {
            fromJs {
                options.onRetry?.invoke(
                    paramsWithoutSecrets(request),
                    attempt,
                    wait.inWholeMilliseconds.toDouble(),
                    reason,
                )
            }
        }

        override fun onPenalty(wait: Duration) {
            fromJs { options.onPenalty?.invoke(wait.inWholeMilliseconds.toDouble()) }
        }

        override fun onRelogin(identity: ClientIdentity) {
            fromJs { options.onRelogin?.invoke(Identity(identity)) }
        }
    }
