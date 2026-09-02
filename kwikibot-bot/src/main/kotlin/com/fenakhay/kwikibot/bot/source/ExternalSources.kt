package com.fenakhay.kwikibot.bot.source

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.model.SparqlAuth
import com.fenakhay.kwikibot.client.model.SparqlClient
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.net.UserAgent
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Page lists built somewhere other than the wiki.
 *
 * PetScan and PagePile are Toolforge tools that Wikimedia editors use to build a working list — "every
 * article in this category tree that has no image" — and then hand to a bot. Supporting them is how a bot
 * takes a list a person assembled rather than making the person describe it again in bot arguments. A query
 * service answers the same kind of question from structured data instead.
 *
 * These are separate from [PageSourceSpec] because they need an HTTP client and a user agent, and a source
 * spec is parsed before there is either.
 */
public object ExternalSources {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The pages a PetScan query returns.
     *
     * @param id the PSID of a saved query. A saved query rather than the parameters themselves, because a
     *   PetScan query has forty of them and a bot repeating one should repeat exactly the query a person
     *   checked.
     * @param client the HTTP client to fetch with. PetScan is not a wiki, so the wiki's own transport does
     *   not apply.
     * @param userAgent who to say is asking. Toolforge asks for this as the wikis do, and the wiki's own
     *   transport is not carrying this request.
     */
    public fun petScan(id: String, client: HttpClient, userAgent: UserAgent): PageSource =
        PageSource { wiki ->
            flow {
                val body =
                    client
                        .get(PETSCAN) {
                            header(HttpHeaders.UserAgent, userAgent.headerValue)
                            parameter("psid", id)
                            parameter("format", "json")
                            parameter("doit", "1")
                        }
                        .bodyAsText()

                titlesFromPetScan(body).forEach { title -> emit(wiki.refOrSkip(title) ?: return@forEach) }
            }
        }

    /**
     * The pages a PetScan query returns, given the query itself rather than a saved one.
     *
     * A saved query is the better habit, but it cannot be built at run time — a bot that sweeps thirty
     * languages would need thirty saved queries kept in step with its configuration. The parameters are
     * PetScan's own; `format` and `doit` are supplied.
     *
     * POSTed rather than GET, because a real query carries category lists too long to put in a URL.
     */
    public fun petScan(
        parameters: Map<String, String>,
        client: HttpClient,
        userAgent: UserAgent,
    ): PageSource = PageSource { wiki ->
        flow {
            petScanTitles(parameters, client, userAgent).forEach { title ->
                emit(wiki.refOrSkip(title) ?: return@forEach)
            }
        }
    }

    /**
     * The titles a PetScan query returns, as written.
     *
     * Separate from [petScan] because not every list of titles is a list of pages to edit. A bot that caches
     * "every entry this wiki has for German" wants the strings: resolving a million of them against the wiki
     * costs a title parse apiece, and silently drops the ones that fail to parse — which is the opposite of
     * what a cache of what exists should do.
     *
     * @param parameters PetScan's own parameters; `format` and `doit` are supplied.
     * @param client the HTTP client to fetch with.
     * @param userAgent who to say is asking.
     */
    public suspend fun petScanTitles(
        parameters: Map<String, String>,
        client: HttpClient,
        userAgent: UserAgent,
    ): List<String> =
        titlesFromPetScan(
            client
                .submitForm(
                    url = PETSCAN,
                    formParameters =
                        Parameters.build {
                            parameters.forEach { (name, value) -> append(name, value) }
                            append("format", "json")
                            append("doit", "1")
                        },
                ) {
                    header(HttpHeaders.UserAgent, userAgent.headerValue)
                }
                .bodyAsText()
        )

    /**
     * The pages a SPARQL query names.
     *
     * The query must select page titles, not entity URIs: a query service answers about items, and what a bot
     * needs is the article. `?title` by default, since that is what a `schema:name` or `wikibase:title`
     * binding is usually called.
     *
     * @param query the SELECT to run.
     * @param client the HTTP client to ask with. A query service is not a wiki, so the wiki's own transport
     *   does not apply.
     * @param userAgent who to say is asking.
     * @param endpoint which query service to ask; see [SparqlClient.WIKIDATA] and its neighbours.
     * @param variable the result variable holding the title.
     * @param auth credentials, for a service that needs them — see [SparqlAuth.wcqs].
     */
    public fun sparql(
        query: String,
        client: HttpClient,
        userAgent: UserAgent,
        endpoint: String = SparqlClient.WIKIDATA,
        variable: String = "title",
        auth: SparqlAuth = SparqlAuth.None,
    ): PageSource = PageSource { wiki ->
        flow {
            val rows = SparqlClient(client, userAgent, endpoint, auth).select(query)

            rows.forEach { row ->
                val title = row[variable]?.value ?: return@forEach
                emit(wiki.refOrSkip(title) ?: return@forEach)
            }
        }
    }

    /**
     * The pages of a PagePile.
     *
     * A pile is a saved, fixed list: it returns the same pages next week, which a category does not.
     */
    public fun pagePile(id: String, client: HttpClient, userAgent: UserAgent): PageSource =
        PageSource { wiki ->
            flow {
                val body =
                    client
                        .get(PAGE_PILE) {
                            header(HttpHeaders.UserAgent, userAgent.headerValue)
                            parameter("id", id)
                            parameter("action", "get_data")
                            parameter("format", "json")
                            parameter("doit", "1")
                        }
                        .bodyAsText()

                titlesFromPagePile(body).forEach { title -> emit(wiki.refOrSkip(title) ?: return@forEach) }
            }
        }

    /** Titles out of a PetScan result. */
    internal fun titlesFromPetScan(body: String): List<String> =
        json
            .parseToJsonElement(body)
            .jsonObject["*"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("a")
            ?.jsonObject
            ?.get("*")
            ?.jsonArray
            ?.mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.content }
            .orEmpty()

    /** Titles out of a PagePile result. */
    internal fun titlesFromPagePile(body: String): List<String> =
        json
            .parseToJsonElement(body)
            .jsonObject["pages"]
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()

    /**
     * A title the wiki accepts, or `null` if it does not.
     *
     * An external list is unvalidated input and will contain titles this wiki rejects or that name another
     * project. Skipping them keeps one bad row from ending a long run.
     */
    private fun Wiki.refOrSkip(title: String): PageRef? = runCatching { ref(title) }.getOrNull()

    private const val PETSCAN = "https://petscan.wmcloud.org/"
    private const val PAGE_PILE = "https://pagepile.toolforge.org/api.php"
}

/**
 * Repeats a source, emitting only what it has not emitted before.
 *
 * The shape of a bot that watches a wiki rather than sweeping it: poll, act on what is new, wait, poll again.
 * The seen set is bounded so that a long-running process does not accumulate every title it has encountered.
 *
 * The flow never ends on its own. Cancel the collecting coroutine to stop it.
 *
 * @param every how long to wait between rounds. Shorter than the wiki's own pace is pointless: recent changes
 *   does not update faster than edits arrive.
 * @param remember how many titles to keep in the seen set, which bounds what a long-running process
 *   accumulates.
 */
public fun PageSource.repeating(
    every: Duration = DEFAULT_INTERVAL,
    remember: Int = DEFAULT_MEMORY,
): PageSource = PageSource { wiki ->
    flow {
        val seen = ArrayDeque<PageRef>()
        val seenSet = mutableSetOf<PageRef>()

        while (true) {
            pages(wiki).collect { ref ->
                if (seenSet.add(ref)) {
                    seen.addLast(ref)
                    // Bounded on purpose: a bot watching a wiki for a month would otherwise hold
                    // a month of titles, and the oldest of them can no longer come round again.
                    if (seen.size > remember) seenSet.remove(seen.removeFirst())
                    emit(ref)
                }
            }
            delay(every)
        }
    }
}

/** How long a repeating source waits between rounds. */
private val DEFAULT_INTERVAL = 1.minutes

/** How many pages a repeating source remembers having emitted. */
private const val DEFAULT_MEMORY = 5000
