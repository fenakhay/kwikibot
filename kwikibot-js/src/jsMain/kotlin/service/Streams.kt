import com.fenakhay.kwikibot.bindings.connect.userAgent
import com.fenakhay.kwikibot.bindings.events.eventStreamsWithDefaults
import com.fenakhay.kwikibot.bindings.sparql.sparqlClientWithDefaults
import com.fenakhay.kwikibot.client.model.EventStreams as ClientEventStreams
import com.fenakhay.kwikibot.client.model.SparqlAuth as ClientSparqlAuth
import com.fenakhay.kwikibot.client.model.SparqlClient as ClientSparqlClient
import com.fenakhay.kwikibot.client.model.WikiEvent as ClientWikiEvent
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.promise

/** Wikimedia Commons' query service, which needs a token: see `sparqlAuthWcqs`. */
@JsExport public const val COMMONS_QUERY_SERVICE: String = ClientSparqlClient.COMMONS

/** Lingua Libre's own query service. */
@JsExport public const val LINGUA_LIBRE_QUERY_SERVICE: String = ClientSparqlClient.LINGUA_LIBRE

/** Wikimedia's public event stream, which needs no credentials. */
@JsExport public const val WIKIMEDIA_EVENT_STREAMS: String = ClientEventStreams.WIKIMEDIA

/** How a query service is told who is asking. */
@JsExport
public class SparqlAuth internal constructor(internal val auth: ClientSparqlAuth) {

    /** `"none"`, what a public endpoint wants, or `"cookie"`. */
    public val kind: SparqlAuthKind
        get() =
            when (auth) {
                is ClientSparqlAuth.None -> "none"
                is ClientSparqlAuth.Cookie -> "cookie"
            }.unsafeCast<SparqlAuthKind>()

    /** The cookie's name, or `null` unless a `"cookie"`. */
    public val name: String?
        get() = (auth as? ClientSparqlAuth.Cookie)?.name

    /** The token the cookie holds, or `null` unless a `"cookie"`. */
    public val value: String?
        get() = (auth as? ClientSparqlAuth.Cookie)?.value
}

/** No credentials, which is what a public endpoint wants. */
@JsExport public fun sparqlAuthNone(): SparqlAuth = SparqlAuth(ClientSparqlAuth.None)

/** A cookie called [name] holding [value], which is how Wikimedia's query services carry an OAuth token. */
@JsExport
public fun sparqlAuthCookie(name: String, value: String): SparqlAuth =
    SparqlAuth(ClientSparqlAuth.Cookie(name, value))

/** The Commons Query Service's cookie, holding the OAuth [token]. */
@JsExport public fun sparqlAuthWcqs(token: String): SparqlAuth = SparqlAuth(ClientSparqlAuth.wcqs(token))

/** What a query service client is made with. */
@JsExport
public external interface SparqlClientOptions {
    /** The query service, Wikidata's unless given. */
    public val endpoint: String?

    /** How the service is told who is asking; nothing unless given. */
    public val auth: SparqlAuth?

    /** How often to ask again, and how long to wait, while the service says it is busy. */
    public val retry: RetryOptions?

    /** Who to say is asking; the library's name with the contact unless given. */
    public val userAgent: UserAgent?
}

/**
 * Makes a client for a query service, asking as the tool whose operator [contact] reaches. A query service is
 * a server of its own, with its own limits and no session, so it is separate from any wiki. Close it when
 * done.
 */
@JsExport
public fun sparqlClient(contact: String, options: SparqlClientOptions? = null): SparqlClient =
    sparqlClient(contact, options, engine = null)

/** [sparqlClient], through [engine], or the platform's own when it is `null`. */
internal fun sparqlClient(
    contact: String,
    options: SparqlClientOptions?,
    engine: HttpClientEngine?,
): SparqlClient {
    withoutLoggingBanner()
    val http = WikiHttpClient.create(engine = engine)
    val client =
        sparqlClientWithDefaults(
            http,
            contact,
            options?.userAgent?.agent,
            options?.endpoint,
            options?.auth?.auth,
            options?.retry?.let(::retryOf),
        )
    return SparqlClient(client, http)
}

/** Runs SPARQL queries against a query service. */
@JsExport
public class SparqlClient
internal constructor(private val client: ClientSparqlClient, private val http: HttpClient) {

    private val scope = CoroutineScope(SupervisorJob())

    /** The rows a `SELECT` [query] gives. Rejects with what the service said when it refuses the query. */
    public fun select(query: String): Promise<Array<SparqlRow>> = scope.promise {
        client.select(query).map(::SparqlRow).toTypedArray()
    }

    /** The entity ids, such as `"Q42"`, the [variable] of a `SELECT` [query] is bound to. */
    public fun entityIds(query: String, variable: String = "item"): Promise<Array<String>> = scope.promise {
        client.entityIds(query, variable).toTypedArray()
    }

    /**
     * The rows a `SELECT` [query] gives, one at a time, read from a table of tab-separated values kept until
     * the walk ends rather than parsed whole. In a browser and in Node it is kept in memory.
     */
    public fun selectStreamed(query: String): AsyncIterable<SparqlRow> = flow {
        client.selectStreamed(query) { rows -> for (row in rows) emit(row) }
    }
        .toAsyncIterable(scope, ::SparqlRow)

    /** Ends what is still being fetched and releases the connections. */
    public fun close() {
        scope.cancel()
        http.close()
    }
}

/** What an event stream client is made with. */
@JsExport
public external interface EventStreamsOptions {
    /** Where the streams are served, Wikimedia's unless given. */
    public val baseUrl: String?

    /** How often to try again, and how long to wait, when a connection fails. */
    public val retry: RetryOptions?

    /** Who to say is asking; the library's name with the contact unless given. */
    public val userAgent: UserAgent?
}

/**
 * Makes a client for the live feed of changes across Wikimedia's wikis, asking as the tool whose operator
 * [contact] reaches. Close it when done.
 */
@JsExport
public fun eventStreams(contact: String, options: EventStreamsOptions? = null): EventStreams =
    eventStreams(contact, options, engine = null)

/** [eventStreams], through [engine], or the platform's own when it is `null`. */
internal fun eventStreams(
    contact: String,
    options: EventStreamsOptions?,
    engine: HttpClientEngine?,
): EventStreams {
    withoutLoggingBanner()
    val http = WikiHttpClient.create(engine = engine)
    val streams =
        eventStreamsWithDefaults(
            http,
            contact,
            options?.userAgent?.agent,
            options?.retry?.let(::retryOf),
            options?.baseUrl,
        )
    return EventStreams(streams, http)
}

/**
 * The live feed of changes across Wikimedia's wikis, over server-sent events.
 *
 * A walk of one of these does not end on its own: leave it with `break` or `return` to stop it. The server
 * closes every connection after about fifteen minutes, so the walk reconnects from the last event it read,
 * and an event around a reconnect can arrive twice. A connection that keeps failing rejects the walk once the
 * retries are spent, and a refusal does at once.
 *
 * Node reads the stream as it arrives. In a browser this goes through `fetch`, and what the stream's server
 * allows a page's origin decides whether it can be read.
 */
@JsExport
public class EventStreams
internal constructor(private val streams: ClientEventStreams, private val http: HttpClient) {

    private val scope = CoroutineScope(SupervisorJob())

    /**
     * Recent changes, as they happen. [since] is where to resume from: a previous event's `offset`, or an ISO
     * 8601 timestamp. Without it, from now.
     */
    public fun recentChanges(since: String? = null): AsyncIterable<WikiEvent> =
        walk(streams.recentChanges(since))

    /** Page creations, as they happen, from [since] as `recentChanges` reads it. */
    public fun pageCreations(since: String? = null): AsyncIterable<WikiEvent> =
        walk(streams.pageCreations(since))

    /** The events of the stream called [stream], from [since] as `recentChanges` reads it. */
    public fun events(stream: String, since: String? = null): AsyncIterable<WikiEvent> =
        walk(streams.events(stream, since))

    /** Ends every walk and releases the connections. */
    public fun close() {
        scope.cancel()
        http.close()
    }

    private fun walk(events: Flow<ClientWikiEvent>): AsyncIterable<WikiEvent> =
        events.toAsyncIterable(scope, ::WikiEvent)
}

/** One change, as an event stream reports it. Every wiki is on a stream at once, so filter on [wiki]. */
@JsExport
public class WikiEvent internal constructor(private val event: ClientWikiEvent) {

    /** The database name of the wiki it happened on, such as `"enwiktionary"`. */
    public val wiki: String
        get() = event.wiki

    /**
     * `"edit"`, `"new"`, `"log"` or `"categorize"` on recent changes; what its stream calls it, or `""`, on
     * another.
     */
    public val type: EventType
        get() = event.type.unsafeCast<EventType>()

    /** The full title, with its namespace prefix. */
    public val title: String
        get() = event.title

    /** The namespace number. */
    public val namespace: Int
        get() = event.namespace

    /** Who made the change, or `null` where the stream withheld it. */
    public val user: String?
        get() = event.user

    /** The edit summary or log reason. */
    public val comment: String?
        get() = event.comment

    /** When it happened, as the stream reported it. */
    public val timestamp: Date?
        get() = event.timestamp?.let(::dateOf)

    /** The revision the change produced, or `null` on a log event. */
    public val revisionId: Double?
        get() = event.revisionId?.toDouble()

    /** The revision it was made from, or `null` on a page creation. */
    public val previousRevisionId: Double?
        get() = event.previousRevisionId?.toDouble()

    /** Whether it was flagged a bot's. */
    public val isBot: Boolean
        get() = event.isBot

    /** Whether the editor marked it minor. */
    public val isMinor: Boolean
        get() = event.isMinor

    /** Where in the stream this event was, to resume from. */
    public val offset: String?
        get() = event.offset

    /** Whether the event is from [wiki] and in one of [namespaces], any namespace when none are given. */
    public fun matches(wiki: String, namespaces: Array<Int>? = null): Boolean =
        event.matches(wiki, namespaces?.toSet().orEmpty())
}
