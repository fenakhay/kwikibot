import com.fenakhay.kwikibot.bindings.connect.userAgent
import com.fenakhay.kwikibot.bindings.sparql.sparqlSourceWithDefaults
import com.fenakhay.kwikibot.bot.source.ExternalSources
import com.fenakhay.kwikibot.bot.source.PageSource as ClientPageSource
import com.fenakhay.kwikibot.net.UserAgent as ClientUserAgent
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The pages of a PagePile, a saved, fixed list.
 *
 * @param id the pile's id.
 * @param contact a URL or email address the tool's operators can reach you at.
 * @param agent who to say is asking, in place of the library's name with the contact.
 */
@JsExport
public fun pagePileSource(id: String, contact: String, agent: UserAgent? = null): PageSource =
    pagePileSource(id, contact, agent, engine = null)

/** [pagePileSource], through [engine], or the platform's own when it is `null`. */
internal fun pagePileSource(
    id: String,
    contact: String,
    agent: UserAgent?,
    engine: HttpClientEngine?,
): PageSource = fetched(engine) { http -> ExternalSources.pagePile(id, http, agent.orContact(contact)) }

/** Where a SPARQL source asks, and what it reads. */
@JsExport
public external interface SparqlSourceOptions {
    /** Which query service to ask; Wikidata's unless given. */
    public val endpoint: String?

    /** The result variable holding the title; `"title"` unless given. */
    public val variable: String?

    /** Credentials, for a service that needs them. */
    public val auth: SparqlAuth?

    /** Who to say is asking, in place of the library's name with the contact. */
    public val userAgent: UserAgent?
}

/**
 * The pages a SPARQL `SELECT` [query] names. It has to select page titles rather than entity URIs.
 *
 * @param query the `SELECT` to run.
 * @param contact a URL or email address the service's operators can reach you at.
 * @param options where to ask, and what to read.
 */
@JsExport
public fun sparqlSource(query: String, contact: String, options: SparqlSourceOptions? = null): PageSource =
    sparqlSource(query, contact, options, engine = null)

/** [sparqlSource], through [engine], or the platform's own when it is `null`. */
internal fun sparqlSource(
    query: String,
    contact: String,
    options: SparqlSourceOptions?,
    engine: HttpClientEngine?,
): PageSource =
    fetched(engine) { http ->
        sparqlSourceWithDefaults(
            query,
            http,
            contact,
            options?.userAgent?.agent,
            options?.endpoint,
            options?.variable,
            options?.auth?.auth,
        )
    }

/** This agent, or the library's name with [contact] when there is none. */
internal fun UserAgent?.orContact(contact: String): ClientUserAgent = userAgent(contact, this?.agent)

/** A source [source] makes from an HTTP client of its own, kept open for as long as it is walked. */
internal fun fetched(
    engine: HttpClientEngine?,
    source: (HttpClient) -> ClientPageSource,
): PageSource = sourceOf { wiki ->
    flow {
        withoutLoggingBanner()
        WikiHttpClient.create(engine = engine).use { http -> emitAll(source(http).pages(wiki)) }
    }
}
