package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.array
import com.fenakhay.kwikibot.net.cache.ResponseCache
import com.fenakhay.kwikibot.net.identifyAs
import com.fenakhay.kwikibot.net.obj
import com.fenakhay.kwikibot.net.objects
import com.fenakhay.kwikibot.net.pageOrigin
import com.fenakhay.kwikibot.net.string
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val log = KotlinLogging.logger("com.fenakhay.kwikibot.net.transport.KtorTransport")

/**
 * The Ktor-backed [MediaWikiTransport].
 *
 * Retries are handled here rather than by Ktor's retry plugin because two of the three conditions worth
 * retrying are invisible to it: MediaWiki reports replication lag and rate limiting inside a `200 OK` body,
 * not as a status code. Keeping all three in one loop also keeps the behaviour testable on virtual time.
 *
 * @param client the Ktor client to send with. The caller owns it and closes it. A request takes its slot from
 *   [throttle] first, then waits for room among the client's requests in flight; see [withRequestPermit].
 * @param endpoint which wiki's `api.php` to talk to.
 * @param userAgent identifies the bot, which Wikimedia policy requires.
 * @param throttle paces the requests. Share one per wiki.
 * @param retry how often and how long to wait before giving up.
 * @param maxlag the replication lag, in seconds, above which the wiki should defer our request. Wikimedia
 *   asks bots for 5; passing `null` omits the parameter, which only makes sense for a self-hosted wiki.
 * @param cache reuses responses the wiki said were reusable. Off by default.
 * @param listener sees every response the wiki sent, which is where its warnings are read. A cached answer is
 *   not shown to it again.
 */
public class KtorTransport(
    private val client: HttpClient,
    override val endpoint: ApiEndpoint,
    private val userAgent: UserAgent,
    private val throttle: Throttle = Throttle(),
    private val retry: RetryPolicy = RetryPolicy(),
    private val maxlag: Int? = DEFAULT_MAXLAG,
    private val cache: ResponseCache = ResponseCache.NONE,
    private val listener: TransportListener = TransportListener.NONE,
) : MediaWikiTransport {

    private val json = Json { ignoreUnknownKeys = true }

    private val crossSiteOrigin: String? = corsOrigin(pageOrigin(), endpoint)

    override suspend fun call(request: ApiRequest): JsonObject {
        // Before the throttle, not after: a cached answer costs the wiki nothing, so making the
        // caller wait for a rate limit it is not going to use would be pure delay. A request that
        // needs the current answer skips the cache.
        if (request.cacheable) {
            cache.get(request)?.let {
                return it
            }
        }

        val params = withDefaults(request)
        var attempt = 0

        while (true) {
            throttle.acquire(request.kind)

            val outcome = attemptOnce(request, params)
            if (outcome is Attempt.Done) {
                listener.onResponse(request, outcome.body)
                if (request.cacheable) cache.put(request, outcome.body)
                return outcome.body
            }
            if (outcome is Attempt.Refused) throw outcome.error

            val deferral = outcome as Attempt.Deferred
            if (attempt >= retry.maxRetries) {
                when (deferral) {
                    // Once the retries are spent, an error the wiki reported in its body is returned as
                    // the answer, so the caller sees the read-only or database error itself.
                    is Attempt.Deferred.Transient -> {
                        listener.onResponse(request, deferral.body)
                        return deferral.body
                    }
                    is Attempt.Deferred.Unanswered -> throw deferral.toError()
                }
            }

            attempt++
            val wait = retry.delayFor(attempt, deferral.retryAfter)
            // At INFO, since a run that spends its time waiting otherwise looks like a slow bot.
            log.info {
                "action=${request.action}: ${deferral.reason}; retry $attempt of ${retry.maxRetries} in $wait"
            }
            listener.onRetry(request, attempt, wait, deferral.reason)
            // A server that asked us to wait is telling the whole client to slow down, not just
            // this call, so the pause goes through the throttle as well.
            deferral.retryAfter?.let {
                throttle.penalize(it)
                listener.onPenalty(it)
            }
            delay(wait)
        }
    }

    private suspend fun attemptOnce(request: ApiRequest, params: List<Pair<String, String>>): Attempt {
        val answered = runCatching {
            client.withRequestPermit {
                val response = send(request, params)
                response to response.bodyAsText()
            }
        }
        val (response, body) =
            answered.getOrElse { failure ->
                if (failure is CancellationException || !isConnectionFailure(failure)) throw failure
                return if (failure.isTimeout()) {
                    Attempt.Deferred.TimedOut(endpoint.apiUrl, failure)
                } else {
                    Attempt.Deferred.Network(endpoint.apiUrl, failure)
                }
            }
        return classify(response, body)
    }

    /**
     * Whether this is one of the three timeouts the client applies: on connecting, on a silent socket, and on
     * the whole request.
     */
    private fun Throwable.isTimeout(): Boolean =
        this is HttpRequestTimeoutException ||
            this is ConnectTimeoutException ||
            this is SocketTimeoutException

    private fun classify(response: HttpResponse, body: String): Attempt {
        statusDeferral(response)?.let {
            return it
        }

        val parsed = runCatching {
            json.parseToJsonElement(body).jsonObject
        }
            .getOrElse {
                // A body that is not JSON comes from something between us and MediaWiki. After a 4xx
                // it is a refusal, such as a block on the user agent or the address, or a URL with no
                // API behind it, and asking again gets the same answer. Otherwise it is a proxy error
                // page or a captive portal, which passes.
                if (response.status.isRefusal()) {
                    return Attempt.Refused(
                        WikiError.Transport.Refused(response.status.value, endpoint.apiUrl, body.gist())
                    )
                }
                return Attempt.Deferred.Server(
                    response.status.value,
                    endpoint.apiUrl,
                    response.retryAfter(),
                )
            }

        return answered(parsed, response)
    }

    /** What an answer in JSON calls for: waiting out the error it reports, or handing it back. */
    private fun answered(body: JsonObject, response: HttpResponse): Attempt {
        val error = body.firstError() ?: return Attempt.Done(body)
        val code = error.string("code")
        return when {
            code == MAXLAG_CODE ->
                Attempt.Deferred.Lag(error.lagSeconds(), error.lagHost(), response.retryAfter())
            code == RATELIMIT_CODE -> Attempt.Deferred.RateLimited(response.retryAfter())
            code in TRANSIENT_CODES -> Attempt.Deferred.Transient(body, response.retryAfter(), code)
            else -> Attempt.Done(body)
        }
    }

    /** Failures the HTTP status alone is enough to classify. */
    private fun statusDeferral(response: HttpResponse): Attempt.Deferred? =
        when {
            response.status == HttpStatusCode.TooManyRequests ->
                Attempt.Deferred.RateLimited(response.retryAfter())

            response.status.value >= HttpStatusCode.InternalServerError.value ->
                Attempt.Deferred.Server(response.status.value, endpoint.apiUrl, response.retryAfter())

            else -> null
        }

    private suspend fun send(request: ApiRequest, params: List<Pair<String, String>>): HttpResponse {
        val origin = crossSiteOrigin?.let { listOf("origin" to it) }.orEmpty()
        val useGet = !request.requiresPost && queryLength(params + origin) <= MAX_GET_LENGTH
        return if (useGet) {
            client.get(endpoint.apiUrl + "?" + (params + origin).encode()) {
                identifyAs(userAgent)
            }
        } else {
            client.submitForm(
                url = if (origin.isEmpty()) endpoint.apiUrl else endpoint.apiUrl + "?" + origin.encode(),
                formParameters = Parameters.build { params.forEach { (k, v) -> append(k, v) } },
            ) {
                identifyAs(userAgent)
            }
        }
    }

    /** Adds the parameters every call needs, without letting a caller override the format. */
    private fun withDefaults(request: ApiRequest): List<Pair<String, String>> {
        val defaults = buildMap {
            putAll(request.params)
            putAll(DEFAULT_PARAMS)
            if (maxlag != null) put("maxlag", maxlag.toString())
        }
        return ApiRequest(defaults, request.kind).ordered()
    }

    private fun List<Pair<String, String>>.encode(): String =
        joinToString("&") { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }

    private fun queryLength(params: List<Pair<String, String>>): Int =
        endpoint.apiUrl.length + params.encode().length

    /** How long the server asked us to wait, when it said so in seconds rather than as a date. */
    private fun HttpResponse.retryAfter(): Duration? =
        headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.seconds

    /**
     * A 4xx that means the request will not be served. 408 Request Timeout, 425 Too Early and 429 Too Many
     * Requests only mean "not now", so they are not refusals.
     */
    private fun HttpStatusCode.isRefusal(): Boolean =
        value in CLIENT_ERRORS && value !in RETRYABLE_CLIENT_ERRORS

    /** The start of an error page as one line of text, without its markup. */
    private fun String.gist(): String = replace(MARKUP, " ").replace(WHITESPACE, " ").trim().take(MAX_DETAIL)

    /**
     * The first error the response reports, in either shape MediaWiki uses.
     *
     * `errorformat=plaintext` produces an `errors` array; the legacy format produces a single `error` object
     * with `info` instead of `text`. Both are read, because a wiki can be configured or proxied in ways that
     * change which one comes back.
     */
    private fun JsonObject.firstError(): JsonObject? = array("errors").objects().firstOrNull() ?: obj("error")

    /** The message of an error, under `text` in an `errors` entry and under `info` in an `error` object. */
    private fun JsonObject.errorText(): String = string("text") ?: string("info").orEmpty()

    /** How far behind the replica was, from a `maxlag` error's message: `0s` when it does not say. */
    private fun JsonObject.lagSeconds(): Duration {
        val lag = LAG_SECONDS.find(errorText())?.let { it.groupValues[1].toDoubleOrNull() }
        return (lag ?: 0.0).seconds
    }

    private fun JsonObject.lagHost(): String? = LAG_HOST.find(errorText())?.let { it.groupValues[1] }

    /** The result of one attempt: either a response, or a reason to try again. */
    private sealed interface Attempt {

        data class Done(val body: JsonObject) : Attempt

        /** The server will not serve the request, so there is nothing to retry. */
        data class Refused(val error: WikiError.Transport.Refused) : Attempt

        sealed interface Deferred : Attempt {
            val retryAfter: Duration?

            /** Why the request is being retried, for the log and the listener. */
            val reason: String

            /**
             * A deferral with no answer from the wiki to hand back, so it is raised once retries are spent.
             */
            sealed interface Unanswered : Deferred {
                /** The failure to raise once the retry budget is spent. */
                fun toError(): WikiError.Transport
            }

            data class Network(val url: String, val cause: Throwable) : Unanswered {
                override val reason: String
                    get() = "could not reach $url (${cause.message ?: cause::class.simpleName})"

                override val retryAfter: Duration?
                    get() = null

                override fun toError(): WikiError.Transport = WikiError.Transport.Unreachable(url, cause)
            }

            data class TimedOut(val url: String, val cause: Throwable) : Unanswered {
                override val reason: String
                    get() = "$url did not answer in time"

                override val retryAfter: Duration?
                    get() = null

                override fun toError(): WikiError.Transport = WikiError.Transport.Timeout(url, cause)
            }

            data class Server(
                val status: Int,
                val url: String,
                override val retryAfter: Duration?,
            ) : Unanswered {
                override val reason: String
                    get() = "the server answered HTTP $status"

                override fun toError(): WikiError.Transport = WikiError.Transport.ServerError(status, url)
            }

            data class RateLimited(override val retryAfter: Duration?) : Unanswered {
                override val reason: String
                    get() = "rate limited"

                override fun toError(): WikiError.Transport = WikiError.Transport.RateLimited(retryAfter)
            }

            /**
             * An error the wiki reports in a 200 OK that goes away by itself: a read-only window, a database
             * connection that failed.
             */
            data class Transient(
                val body: JsonObject,
                override val retryAfter: Duration?,
                val code: String?,
            ) : Deferred {
                override val reason: String
                    get() = "the wiki reported $code"
            }

            data class Lag(
                val lag: Duration,
                val host: String?,
                override val retryAfter: Duration?,
            ) : Unanswered {
                override val reason: String
                    get() = "replication lag of $lag${host?.let { " on $it" }.orEmpty()}"

                override fun toError(): WikiError.Transport = WikiError.Transport.Maxlag(lag, host)
            }
        }
    }

    /** The defaults Wikimedia asks of bots. */
    public companion object {
        /** What Wikimedia asks well-behaved bots to send. */
        public const val DEFAULT_MAXLAG: Int = 5

        /**
         * Above this, a request is POSTed instead.
         *
         * MediaWiki accepts long GETs, but proxies in front of it often cap the URL length.
         */
        public const val MAX_GET_LENGTH: Int = 2000

        /**
         * The format every call asks for, whatever the action.
         *
         * Public so that a multipart upload, which cannot go through a transport, asks for the same.
         * `errorformat=plaintext` makes each warning carry a `code`, so a deprecation can be recognised
         * without reading the text.
         */
        public val DEFAULT_PARAMS: Map<String, String> =
            mapOf("format" to "json", "formatversion" to "2", "errorformat" to "plaintext")

        private const val MAXLAG_CODE = "maxlag"
        private const val RATELIMIT_CODE = "ratelimited"

        /**
         * Errors that belong to the moment rather than the request: a wiki in read-only mode during
         * maintenance, and the database failures MediaWiki reports as `internal_api_error_` plus the
         * exception class. MediaWiki rolls back the request that met one, so retrying even a write cannot
         * apply it twice. `DBQueryError` is left out, since a bad query causes it as often as a bad moment.
         */
        private val TRANSIENT_CODES =
            setOf(
                "readonly",
                "internal_api_error_DBConnectionError",
                "internal_api_error_DBQueryTimeoutError",
                "internal_api_error_DBReadOnlyError",
                "internal_api_error_DBTransactionStateError",
            )

        private val CLIENT_ERRORS = 400..499
        private val RETRYABLE_CLIENT_ERRORS = setOf(408, 425, 429)

        /** How much of an error page goes into [WikiError.Transport.Refused.detail]. */
        private const val MAX_DETAIL = 300
        private val MARKUP = Regex("""<[^>]*>""")
        private val WHITESPACE = Regex("""\s+""")

        private val LAG_SECONDS = Regex("""([\d.]+) seconds? lagged""")
        private val LAG_HOST = Regex("""Waiting for ([^:]+):""")
    }
}

/**
 * The `origin` parameter a request from a web page at [pageOrigin] needs to reach [endpoint], or `null` when
 * it needs none.
 *
 * Outside a browser, and from a page on the wiki's own site, none: the request is not cross-site, and a page
 * on the wiki sends the reader's own session. From a page on another site, `*`, which MediaWiki answers as an
 * anonymous user to any site. Acting as an account from another site needs that site on the wiki's own
 * allow-list, which only the wiki's administrators can grant.
 */
internal fun corsOrigin(pageOrigin: String?, endpoint: ApiEndpoint): String? =
    if (pageOrigin == null || pageOrigin == endpoint.origin) null else "*"
