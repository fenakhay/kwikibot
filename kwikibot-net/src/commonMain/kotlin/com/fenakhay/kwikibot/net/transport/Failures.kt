package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.model.WikiError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What [failure], thrown while a request to [url] was sent or its answer read, means, or `null` if it says
 * nothing about the connection: a cancellation, or a bug. Those are for the caller to pass on as they are.
 */
public fun transportFailure(url: String, failure: Throwable): WikiError.Transport? =
    when {
        failure is CancellationException || !isConnectionFailure(failure) -> null
        failure.isTimeout() -> WikiError.Transport.Timeout(url, failure)
        else -> WikiError.Transport.Unreachable(url, failure)
    }

/**
 * What a response means when its status says it failed, or `null` when its status does not.
 *
 * @param url where the request went.
 * @param response what came back.
 * @param body what the server answered, the start of which names the reason for a refusal.
 */
public fun statusFailure(url: String, response: HttpResponse, body: String = ""): WikiError.Transport? =
    when {
        response.status == HttpStatusCode.TooManyRequests ->
            WikiError.Transport.RateLimited(response.retryAfter())
        response.status.value >= HttpStatusCode.InternalServerError.value ->
            WikiError.Transport.ServerError(response.status.value, url)
        response.status.value >= HttpStatusCode.BadRequest.value ->
            WikiError.Transport.Refused(response.status.value, url, body.gist())
        else -> null
    }

/**
 * Whether this is one of the three timeouts the client applies: on connecting, on a silent socket, and on the
 * whole request. A channel hands on what closed it wrapped in a failure of its own, so a cause counts too.
 */
internal fun Throwable.isTimeout(): Boolean =
    generateSequence(this) { it.cause }
        .take(MAX_CAUSES)
        .any {
            it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is SocketTimeoutException
        }

/** How long the server asked us to wait, when it said so in seconds rather than as a date. */
internal fun HttpResponse.retryAfter(): Duration? =
    headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.seconds

/** The start of an error page as one line of text, without its markup. */
internal fun String.gist(): String = replace(MARKUP, " ").replace(WHITESPACE, " ").trim().take(MAX_DETAIL)

/** How far down its causes a failure is searched, since a chain can loop. */
private const val MAX_CAUSES = 8

/** How much of an error page goes into [WikiError.Transport.Refused.detail]. */
private const val MAX_DETAIL = 300

private val MARKUP = Regex("""<[^>]*>""")

private val WHITESPACE = Regex("""\s+""")
