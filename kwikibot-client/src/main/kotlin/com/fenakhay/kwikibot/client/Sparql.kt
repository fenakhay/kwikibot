package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.UserAgent
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One cell of a SPARQL result.
 *
 * The type is kept because it decides what the value means: a URI is an entity, a literal is
 * text, and reading one as the other is how a query result turns into the wrong edit.
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
     * `http://www.wikidata.org/entity/Q42` is how a query returns an item, and `Q42` is what
     * every other part of this library wants.
     */
    public val entityId: String?
        get() = value.takeIf { type == "uri" && ENTITY_URI.containsMatchIn(it) }
            ?.substringAfterLast('/')

    private companion object {
        val ENTITY_URI = Regex("""/entity/[QPL]\d+$""")
    }
}

/** One row of a SPARQL result, by variable name. */
public typealias SparqlRow = Map<String, SparqlValue>

/**
 * Runs SPARQL queries against a Wikibase query service.
 *
 * Separate from [Wiki] because a query service is a different server with different limits: it
 * has its own timeout, its own rate limiting, and no session. Wikidata's is public and needs no
 * credentials, but does require an identifying user agent.
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
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Runs a SELECT query and returns its rows.
     *
     * POSTed rather than sent as a query string: a real query is longer than many proxies allow
     * in a URL, and truncating one produces a query that is still valid and answers something
     * else.
     *
     * @throws WikiError.Api if the service rejects the query, with what it said.
     */
    public suspend fun select(query: String): List<SparqlRow> {
        val body = client.submitForm(
            url = endpoint,
            formParameters = Parameters.build {
                append("query", query)
                append("format", "json")
            },
        ) {
            header(HttpHeaders.UserAgent, userAgent.headerValue)
            header(HttpHeaders.Accept, "application/sparql-results+json")
        }.bodyAsText()

        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            // The service answers a bad query with an HTML error page, so a parse failure here
            // is the query being wrong rather than the library being wrong.
            throw WikiError.Api(
                "sparqlfailed",
                "the query service did not return results: ${body.take(ERROR_EXCERPT)}",
                "sparql",
            )
        }

        val bindings = parsed["results"]?.jsonObject?.get("bindings")?.jsonArray
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
     * The common case by a wide margin: a query names a set of items, and what is wanted is
     * `Q42`, not `http://www.wikidata.org/entity/Q42`.
     */
    public suspend fun entityIds(query: String, variable: String = "item"): List<String> =
        select(query).mapNotNull { it[variable]?.entityId }

    /** The public query endpoints, and the pacing they expect. */
    public companion object {
        /** Wikidata's public query service. */
        public const val WIKIDATA: String = "https://query.wikidata.org/sparql"

        /** How much of an unparseable body to quote back. Enough to recognise, not to drown in. */
        private const val ERROR_EXCERPT = 200
    }
}
