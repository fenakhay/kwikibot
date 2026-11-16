import com.fenakhay.kwikibot.bindings.client.titleOf
import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.connect.userAgent
import com.fenakhay.kwikibot.bindings.services.accessOf
import com.fenakhay.kwikibot.bindings.services.agentOf
import com.fenakhay.kwikibot.bindings.services.granularityOf
import com.fenakhay.kwikibot.bindings.services.pageviewsWithDefaults
import com.fenakhay.kwikibot.client.model.PageviewAccess as ClientPageviewAccess
import com.fenakhay.kwikibot.client.model.PageviewAgent as ClientPageviewAgent
import com.fenakhay.kwikibot.client.model.PageviewCount as ClientPageviewCount
import com.fenakhay.kwikibot.client.model.PageviewGranularity as ClientPageviewGranularity
import com.fenakhay.kwikibot.client.model.Pageviews as ClientPageviews
import com.fenakhay.kwikibot.client.model.TopPage as ClientTopPage
import com.fenakhay.kwikibot.client.model.pageviews
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise

/** Wikimedia's pageviews API, which needs no credentials. */
@JsExport public const val WIKIMEDIA_PAGEVIEWS: String = ClientPageviews.WIKIMEDIA

/** What a pageviews client is made with. */
@JsExport
public external interface PageviewsOptions {
    /** Where the API is served, Wikimedia's unless given. */
    public val baseUrl: String?

    /** How often to ask again, and how long to wait, while the API is busy or failing. */
    public val retry: RetryOptions?

    /** Who to say is asking; the library's name with the contact unless given. */
    public val userAgent: UserAgent?
}

/** What is counted: by how long, how readers came, and who they were. */
@JsExport
public external interface PageviewsQuery {
    /** `"daily"` unless given; `"hourly"` counts a whole project only. */
    public val granularity: PageviewGranularity?

    /** `"all-access"` unless given. */
    public val access: PageviewAccess?

    /** `"user"`, people, unless given. */
    public val agent: PageviewAgent?
}

/** Which ranking of the most viewed pages. */
@JsExport
public external interface TopPagesOptions {
    /** `"all-access"` unless given. */
    public val access: PageviewAccess?

    /** `"daily"` for the day, unless given; `"monthly"` for the month it falls in. */
    public val granularity: PageviewGranularity?
}

/**
 * Makes a client for Wikimedia's pageviews, asking as the tool whose operator [contact] reaches. Close it
 * when done.
 */
@JsExport
public fun pageviews(contact: String, options: PageviewsOptions? = null): Pageviews =
    pageviews(contact, options, engine = null)

/** [pageviews], through [engine], or the platform's own when it is `null`. */
internal fun pageviews(contact: String, options: PageviewsOptions?, engine: HttpClientEngine?): Pageviews {
    withoutLoggingBanner()
    val http = WikiHttpClient.create(engine = engine)
    val client =
        pageviewsWithDefaults(
            http,
            contact,
            options?.userAgent?.agent,
            options?.retry?.let(::retryOf),
            options?.baseUrl,
        )
    return Pageviews(client, http)
}

/**
 * How often pages on Wikimedia's wikis are read, from the pageviews API. It covers Wikimedia's wikis only,
 * from July 2015, and a page or a period it has no data for answers with no counts rather than rejecting. One
 * request counts one page, and a busy API is waited out as the retry options say.
 */
@JsExport
public class Pageviews
internal constructor(private val client: ClientPageviews, private val http: HttpClient) {

    private val scope = CoroutineScope(SupervisorJob())

    /** The first day the API has data for. */
    public val firstDay: Date
        get() = dateOf(ClientPageviews.FIRST_DAY)

    /**
     * The views of the page [title] on [project], such as `"en.wikipedia"`, a count for each day or month
     * from [start] to [end].
     */
    public fun perArticle(
        project: String,
        title: String,
        start: Date,
        end: Date,
        query: PageviewsQuery? = null,
    ): Promise<Array<PageviewCount>> = scope.promise {
        client
            .perArticle(
                project,
                title,
                instantOf(start),
                instantOf(end),
                granularity(query?.granularity),
                access(query?.access),
                agent(query?.agent),
            )
            .map(::PageviewCount)
            .toTypedArray()
    }

    /** The views of the whole of [project], a count for each hour, day or month from [start] to [end]. */
    public fun aggregate(
        project: String,
        start: Date,
        end: Date,
        query: PageviewsQuery? = null,
    ): Promise<Array<PageviewCount>> = scope.promise {
        client
            .aggregate(
                project,
                instantOf(start),
                instantOf(end),
                granularity(query?.granularity),
                access(query?.access),
                agent(query?.agent),
            )
            .map(::PageviewCount)
            .toTypedArray()
    }

    /** The most viewed pages of [project] on [day], in UTC, or over its month, most viewed first. */
    public fun top(project: String, day: Date, options: TopPagesOptions? = null): Promise<Array<TopPage>> =
        scope.promise {
            client
                .top(project, instantOf(day), access(options?.access), granularity(options?.granularity))
                .map(::TopPage)
                .toTypedArray()
        }

    /**
     * The views of each of [titles] on [wiki], one of Wikimedia's, a count for each day or month from [start]
     * to [end], in the order the titles were given. A few pages are asked about at a time. Rejects, sending
     * nothing, for a wiki that is not Wikimedia's.
     */
    public fun ofPages(
        wiki: Wiki,
        titles: Array<String>,
        start: Date,
        end: Date,
        query: PageviewsQuery? = null,
    ): Promise<Array<PagePageviews>> = scope.promise {
        val site = wiki.wiki
        site
            .pageviews(
                client,
                titles.map { site.ref(it) },
                instantOf(start),
                instantOf(end),
                granularity(query?.granularity),
                access(query?.access),
                agent(query?.agent),
            )
            .map { (ref, counts) -> PagePageviews(site.titleOf(ref), counts) }
            .toTypedArray()
    }

    /**
     * The project the API counts the wiki at [server] under, `"en.wikipedia"` for `"en.wikipedia.org"`.
     * Throws for a host that is not one of Wikimedia's wikis.
     */
    public fun projectOf(server: String): String = ClientPageviews.projectOf(server)

    /** Ends what is still being asked and releases the connections. */
    public fun close() {
        scope.cancel()
        http.close()
    }

    private fun granularity(value: PageviewGranularity?): ClientPageviewGranularity =
        granularityOf(enumOf("granularity", value))

    private fun access(value: PageviewAccess?): ClientPageviewAccess = accessOf(enumOf("access", value))

    private fun agent(value: PageviewAgent?): ClientPageviewAgent = agentOf(enumOf("agent", value))
}

/** The views of one page, or of a whole project, over one hour, day or month. */
@JsExport
public class PageviewCount internal constructor(private val count: ClientPageviewCount) {

    /** When the hour, day or month counted starts. */
    public val timestamp: Date
        get() = dateOf(count.timestamp)

    /** How many times it was viewed. */
    public val views: Double
        get() = count.views.toDouble()

    /** The project, as the API names it, such as `"en.wikipedia"`. */
    public val project: String
        get() = count.project

    /** How the readers reached it. */
    public val access: PageviewAccess
        get() = count.access.argumentName().unsafeCast<PageviewAccess>()

    /** Who the readers were. */
    public val agent: PageviewAgent
        get() = count.agent.argumentName().unsafeCast<PageviewAgent>()

    /** How long the count covers. */
    public val granularity: PageviewGranularity
        get() = count.granularity.argumentName().unsafeCast<PageviewGranularity>()

    /** The page's full title, or `null` for a count of a whole project. */
    public val title: String?
        get() = count.title
}

/** One of a project's most viewed pages. */
@JsExport
public class TopPage internal constructor(private val page: ClientTopPage) {

    /** The page's full title. */
    public val title: String
        get() = page.title

    /** How many times it was viewed. */
    public val views: Double
        get() = page.views.toDouble()

    /** Its place in the ranking, from 1. */
    public val rank: Int
        get() = page.rank
}

/** The views of one page of a wiki. */
@JsExport
public class PagePageviews
internal constructor(
    /** The page, prefix included. */
    public val title: String,
    private val found: List<ClientPageviewCount>,
) {
    /** Its counts, oldest first; none when the API has no data for it. */
    public val counts: Array<PageviewCount>
        get() = found.map(::PageviewCount).toTypedArray()
}
