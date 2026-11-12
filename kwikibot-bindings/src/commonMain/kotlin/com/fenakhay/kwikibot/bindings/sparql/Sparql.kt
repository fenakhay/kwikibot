package com.fenakhay.kwikibot.bindings.sparql

import com.fenakhay.kwikibot.bindings.connect.userAgent
import com.fenakhay.kwikibot.bot.source.ExternalSources
import com.fenakhay.kwikibot.bot.source.PageSource
import com.fenakhay.kwikibot.client.model.SparqlAuth
import com.fenakhay.kwikibot.client.model.SparqlClient
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import io.ktor.client.HttpClient

private const val TITLE_VARIABLE = "title"

/**
 * A SPARQL client over [http]: asking Wikidata's query service, signed in to nothing, as this library for
 * [contact], retried as the library retries, unless told.
 */
@Suppress("LongParameterList")
public fun sparqlClientWithDefaults(
    http: HttpClient,
    contact: String,
    userAgent: UserAgent? = null,
    endpoint: String? = null,
    auth: SparqlAuth? = null,
    retry: RetryPolicy? = null,
): SparqlClient =
    SparqlClient(
        http,
        userAgent(contact, userAgent),
        endpoint ?: SparqlClient.WIKIDATA,
        auth ?: SparqlAuth.None,
        retry ?: RetryPolicy(),
    )

/**
 * The pages a SPARQL [query] names, over [http]: asking Wikidata's query service for the `title` variable,
 * signed in to nothing, as this library for [contact], unless told.
 */
@Suppress("LongParameterList")
public fun sparqlSourceWithDefaults(
    query: String,
    http: HttpClient,
    contact: String,
    userAgent: UserAgent? = null,
    endpoint: String? = null,
    variable: String? = null,
    auth: SparqlAuth? = null,
): PageSource =
    ExternalSources.sparql(
        query,
        http,
        userAgent(contact, userAgent),
        endpoint ?: SparqlClient.WIKIDATA,
        variable ?: TITLE_VARIABLE,
        auth ?: SparqlAuth.None,
    )
