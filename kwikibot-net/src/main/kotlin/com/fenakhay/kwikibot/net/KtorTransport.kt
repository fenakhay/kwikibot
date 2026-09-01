package com.fenakhay.kwikibot.net

import com.fenakhay.kwikibot.model.WikiError
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Ktor-backed [MediaWikiTransport].
 *
 * Retries are handled here rather than by Ktor's retry plugin because two of the three
 * conditions worth retrying are invisible to it: MediaWiki reports replication lag and rate
 * limiting inside a `200 OK` body, not as a status code. Keeping all three in one loop also
 * keeps the behaviour testable on virtual time.
 *
 * @param client the Ktor client to send with. The caller owns it and closes it.
 * @param endpoint which wiki's `api.php` to talk to.
 * @param userAgent identifies the bot, which Wikimedia policy requires.
 * @param throttle paces the requests. Share one per wiki.
 * @param retry how often and how long to wait before giving up.
 * @param maxlag the replication lag, in seconds, above which the wiki should defer our
 *   request.
 *   Wikimedia asks bots for 5; passing `null` omits the parameter, which only makes sense for a
 *   self-hosted wiki.
 * @param cache reuses responses the wiki said were reusable. Off by default.
 */
public class KtorTransport(
    private val client: HttpClient,
    override val endpoint: ApiEndpoint,
    private val userAgent: UserAgent,
    private val throttle: Throttle = Throttle(),
    private val retry: RetryPolicy = RetryPolicy(),
    private val maxlag: Int? = DEFAULT_MAXLAG,
    private val cache: ResponseCache = ResponseCache.NONE,
) : MediaWikiTransport {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun call(request: ApiRequest): JsonObject {
        // Before the throttle, not after: a cached answer costs the wiki nothing, so making the
        // caller wait for a rate limit it is not going to use would be pure delay.
        cache.get(request)?.let { return it }

        val params = withDefaults(request)
        var attempt = 0

        while (true) {
            throttle.acquire(request.kind)

            val outcome = attemptOnce(request, params)
            if (outcome is Attempt.Done) {
                cache.put(request, outcome.body)
                return outcome.body
            }

            val deferral = outcome as Attempt.Deferred
            if (attempt >= retry.maxRetries) throw deferral.toError()

            attempt++
            val wait = retry.delayFor(attempt, deferral.retryAfter)
            // A server that asked us to wait is telling the whole client to slow down, not just
            // this call, so the pause goes through the throttle as well.
            deferral.retryAfter?.let { throttle.penalize(it) }
            delay(wait)
        }
    }

    private suspend fun attemptOnce(request: ApiRequest, params: List<Pair<String, String>>): Attempt {
        val response = try {
            send(request, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Attempt.Deferred.Network(endpoint.apiUrl, e)
        }
        return classify(response)
    }

    private suspend fun classify(response: HttpResponse): Attempt {
        statusDeferral(response)?.let { return it }

        val parsed = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrElse {
                // A non-JSON body behind a 2xx means something between us and MediaWiki answered
                // instead of it — a proxy error page, a captive portal, a WAF block.
                return Attempt.Deferred.Server(
                    response.status.value,
                    endpoint.apiUrl,
                    response.retryAfter(),
                )
            }

        return when (parsed.errorCode()) {
            MAXLAG_CODE ->
                Attempt.Deferred.Lag(parsed.lagSeconds(), parsed.lagHost(), response.retryAfter())

            RATELIMIT_CODE -> Attempt.Deferred.RateLimited(response.retryAfter())
            else -> Attempt.Done(parsed)
        }
    }

    /** Failures the HTTP status alone is enough to classify. */
    private fun statusDeferral(response: HttpResponse): Attempt.Deferred? = when {
        response.status == HttpStatusCode.TooManyRequests ->
            Attempt.Deferred.RateLimited(response.retryAfter())

        response.status.value >= HttpStatusCode.InternalServerError.value ->
            Attempt.Deferred.Server(response.status.value, endpoint.apiUrl, response.retryAfter())

        else -> null
    }

    private suspend fun send(request: ApiRequest, params: List<Pair<String, String>>): HttpResponse {
        val useGet = !request.requiresPost && queryLength(params) <= MAX_GET_LENGTH
        return if (useGet) {
            client.get(endpoint.apiUrl + "?" + params.encode()) {
                header(HttpHeaders.UserAgent, userAgent.headerValue)
            }
        } else {
            client.submitForm(
                url = endpoint.apiUrl,
                formParameters = Parameters.build { params.forEach { (k, v) -> append(k, v) } },
            ) {
                header(HttpHeaders.UserAgent, userAgent.headerValue)
            }
        }
    }

    /** Adds the parameters every call needs, without letting a caller override the format. */
    private fun withDefaults(request: ApiRequest): List<Pair<String, String>> {
        val defaults = buildMap {
            putAll(request.params)
            put("format", "json")
            put("formatversion", "2")
            put("errorformat", "plaintext")
            if (maxlag != null) put("maxlag", maxlag.toString())
        }
        return ApiRequest(defaults, request.kind).ordered()
    }

    private fun List<Pair<String, String>>.encode(): String = joinToString("&") { (key, value) ->
        "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
    }

    private fun queryLength(params: List<Pair<String, String>>): Int =
        endpoint.apiUrl.length + params.encode().length

    private fun HttpResponse.retryAfter(): Duration? =
        headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.seconds

    /**
     * The first error the response reports, in either shape MediaWiki uses.
     *
     * `errorformat=plaintext` produces an `errors` array; the legacy format produces a single
     * `error` object with `info` instead of `text`. Both are read, because a wiki can be
     * configured or proxied in ways that change which one comes back.
     */
    private fun JsonObject.firstError(): JsonObject? =
        this["errors"]?.jsonArray?.firstOrNull()?.jsonObject ?: this["error"]?.jsonObject

    private fun JsonObject.errorCode(): String? =
        firstError()?.get("code")?.jsonPrimitive?.content

    private fun JsonObject.errorText(): String {
        val error = firstError() ?: return ""
        val text = error["text"] ?: error["info"]
        return text?.jsonPrimitive?.content.orEmpty()
    }

    private fun JsonObject.lagSeconds(): Duration =
        (LAG_SECONDS.find(errorText())?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0).seconds

    private fun JsonObject.lagHost(): String? =
        LAG_HOST.find(errorText())?.groupValues?.get(1)

    /** The result of one attempt: either a response, or a reason to try again. */
    private sealed interface Attempt {

        data class Done(val body: JsonObject) : Attempt

        sealed interface Deferred : Attempt {
            val retryAfter: Duration?

            /** The failure to raise once the retry budget is spent. */
            fun toError(): WikiError.Transport

            data class Network(val url: String, val cause: Throwable) : Deferred {
                override val retryAfter: Duration? get() = null
                override fun toError(): WikiError.Transport = WikiError.Transport.Unreachable(url, cause)
            }

            data class Server(
                val status: Int,
                val url: String,
                override val retryAfter: Duration?,
            ) : Deferred {
                override fun toError(): WikiError.Transport = WikiError.Transport.ServerError(status, url)
            }

            data class RateLimited(override val retryAfter: Duration?) : Deferred {
                override fun toError(): WikiError.Transport = WikiError.Transport.RateLimited(retryAfter)
            }

            data class Lag(
                val lag: Duration,
                val host: String?,
                override val retryAfter: Duration?,
            ) : Deferred {
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

        private const val MAXLAG_CODE = "maxlag"
        private const val RATELIMIT_CODE = "ratelimited"

        private val LAG_SECONDS = Regex("""([\d.]+) seconds? lagged""")
        private val LAG_HOST = Regex("""Waiting for ([^:]+):""")
    }
}
