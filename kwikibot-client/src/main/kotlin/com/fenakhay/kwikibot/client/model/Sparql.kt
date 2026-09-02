package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One cell of a SPARQL result.
 *
 * The type is kept because it decides what the value means: a URI is an entity, a literal is text, and
 * reading one as the other is how a query result turns into the wrong edit.
 */
public data class SparqlValue(
    /** The value itself: a URI, a literal, or a blank-node label. */
    val value: String,
    /** `uri`, `literal` or `bnode`. */
    val type: String,
    /** The language of a literal, when it has one. */
    val language: String? = null,
    /** The datatype URI of a typed literal. */
    val dataType: String? = null,
) {
    /**
     * The entity id at the end of a Wikidata URI, or `null` if this is not one.
     *
     * `http://www.wikidata.org/entity/Q42` is how a query returns an item, and `Q42` is what every other part
     * of this library wants.
     */
    public val entityId: String?
        get() = value.takeIf { type == "uri" && ENTITY_URI.containsMatchIn(it) }?.substringAfterLast('/')

    private companion object {
        val ENTITY_URI = Regex("""/entity/[QPL]\d+$""")
    }
}

/** One row of a SPARQL result, by variable name. */
public typealias SparqlRow = Map<String, SparqlValue>

/**
 * How a query service is told who is asking.
 *
 * Wikidata's service is public, but Commons' is not: it answers anonymous queries with an error page rather
 * than a challenge, so a missing token looks exactly like a query that found nothing.
 */
public sealed interface SparqlAuth {

    /** No credentials, which is what a public endpoint wants. */
    public data object None : SparqlAuth

    /** A cookie, which is how the Wikimedia query services carry an OAuth token. */
    public data class Cookie(
        /** The cookie name the service reads. */
        val name: String,
        /** The token itself. */
        val value: String,
    ) : SparqlAuth

    /** The services whose cookie name is worth knowing here rather than at every call site. */
    public companion object {

        /**
         * The Commons Query Service, authenticated with an OAuth token.
         *
         * The cookie name is not something a caller should have to know, and getting it wrong fails the same
         * silent way an absent token does.
         */
        public fun wcqs(token: String): SparqlAuth = Cookie("wcqsOauth", token)
    }
}

/**
 * Runs SPARQL queries against a Wikibase query service.
 *
 * Separate from [Wiki] because a query service is a different server with different limits: it has its own
 * timeout, its own rate limiting, and no session. Wikidata's is public and needs no credentials, but does
 * require an identifying user agent.
 *
 * ```
 * val rows = SparqlClient(client, userAgent).select(
 *     "SELECT ?item WHERE { ?item wdt:P31 wd:Q7889 } LIMIT 10",
 * )
 * rows.mapNotNull { it["item"]?.entityId }
 * ```
 */
public class SparqlClient(
    private val client: HttpClient,
    private val userAgent: UserAgent,
    private val endpoint: String = WIKIDATA,
    private val auth: SparqlAuth = SparqlAuth.None,
    private val retry: RetryPolicy = RetryPolicy(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The client the endpoint actually needs.
     *
     * A cookie-authenticated service does not answer the first request. Commons' answers `307` and sets a
     * short-lived session cookie on the redirect, so a caller has to follow it *and* keep what it was given.
     * Ktor does neither by default: redirects are followed only for `GET` and `HEAD`, and cookies are not
     * stored at all, so a POSTed query comes back as an empty `307` body that reads exactly like a rejected
     * query.
     *
     * The long-lived token goes into the same jar rather than onto the request, because the cookie plugin
     * replaces the header with whatever the jar holds.
     */
    private val http =
        when (val credentials = auth) {
            is SparqlAuth.None -> client
            is SparqlAuth.Cookie ->
                client.config {
                    install(HttpRedirect) { checkHttpMethod = false }
                    install(HttpCookies) {
                        default {
                            addCookie(Url(endpoint), Cookie(credentials.name, credentials.value, path = "/"))
                        }
                    }
                }
        }

    /**
     * Runs a SELECT query and returns its rows.
     *
     * POSTed rather than sent as a query string: a real query is longer than many proxies allow in a URL, and
     * truncating one produces a query that is still valid and answers something else.
     *
     * @throws WikiError.Api if the service rejects the query, with what it said.
     */
    public suspend fun select(query: String): List<SparqlRow> {
        val body = fetch(query)

        val parsed = runCatching {
            json.parseToJsonElement(body).jsonObject
        }
            .getOrElse {
                // The service answers a bad query with an HTML error page, so a parse failure here
                // is the query being wrong rather than the library being wrong.
                throw WikiError.Api(
                    "sparqlfailed",
                    "the query service did not return results: ${body.take(ERROR_EXCERPT)}",
                    "sparql",
                )
            }

        val bindings =
            parsed["results"]?.jsonObject?.get("bindings")?.jsonArray
                ?: throw WikiError.Api("sparqlfailed", "no results block in the response", "sparql")

        return bindings.map { row ->
            row.jsonObject.mapValues { (_, cell) ->
                val fields = cell.jsonObject
                SparqlValue(
                    value = fields["value"]?.jsonPrimitive?.content.orEmpty(),
                    type = fields["type"]?.jsonPrimitive?.content.orEmpty(),
                    language = fields["xml:lang"]?.jsonPrimitive?.content,
                    dataType = fields["datatype"]?.jsonPrimitive?.content,
                )
            }
        }
    }

    /**
     * The entity ids one variable of a query yields.
     *
     * The common case by a wide margin: a query names a set of items, and what is wanted is `Q42`, not
     * `http://www.wikidata.org/entity/Q42`.
     */
    public suspend fun entityIds(query: String, variable: String = "item"): List<String> =
        select(query).mapNotNull { it[variable]?.entityId }

    /**
     * The body of a query response, retrying while the service says it is busy.
     *
     * A query service under load answers `429` with `Retry-After` rather than queueing, so a caller that does
     * not wait simply loses the query. Retrying here rather than in every caller also keeps the wait out of
     * the [select] contract: it either returns rows or raises.
     */
    private suspend fun fetch(query: String): String {
        var attempt = 0

        while (true) {
            val response = post(query)
            if (!response.isBusy()) return response.bodyAsText()

            if (attempt >= retry.maxRetries) {
                throw WikiError.Api(
                    "sparqlfailed",
                    "the query service answered ${response.status.value} " +
                        "after ${retry.maxRetries} retries",
                    "sparql",
                )
            }

            attempt++
            delay(retry.delayFor(attempt, response.retryAfter()))
        }
    }

    /**
     * POSTs the query.
     *
     * POSTed rather than sent as a query string: a real query is longer than many proxies allow in a URL, and
     * truncating one produces a query that is still valid and answers something else.
     */
    private suspend fun post(query: String): HttpResponse =
        http.submitForm(
            url = endpoint,
            formParameters =
                Parameters.build {
                    append("query", query)
                    append("format", "json")
                },
        ) {
            header(HttpHeaders.UserAgent, userAgent.headerValue)
            header(HttpHeaders.Accept, "application/sparql-results+json")
        }

    /** Whether the service is asking to be tried again rather than answering. */
    private fun HttpResponse.isBusy(): Boolean =
        status == HttpStatusCode.TooManyRequests || status.value >= HttpStatusCode.InternalServerError.value

    /** The wait the service asked for, when it named one in seconds. */
    private fun HttpResponse.retryAfter(): Duration? =
        headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.seconds

    /** The public query endpoints, and the pacing they expect. */
    public companion object {
        /** Wikidata's public query service. */
        public const val WIKIDATA: String = "https://query.wikidata.org/sparql"

        /** Wikimedia Commons' query service, which needs a token — see [SparqlAuth.wcqs]. */
        public const val COMMONS: String = "https://commons-query.wikimedia.org/sparql"

        /** Lingua Libre's own Wikibase, where the recordings are described. */
        public const val LINGUA_LIBRE: String = "https://lingualibre.org/bigdata/namespace/wdq/sparql"

        /** How much of an unparseable body to quote back. Enough to recognise, not to drown in. */
        private const val ERROR_EXCERPT = 200
    }
}
