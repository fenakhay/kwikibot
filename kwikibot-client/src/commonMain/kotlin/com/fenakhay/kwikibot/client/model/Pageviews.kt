package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.identifyAs
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import com.fenakhay.kwikibot.net.transport.statusFailure
import com.fenakhay.kwikibot.net.transport.transportFailure
import com.fenakhay.kwikibot.net.transport.withRequestPermit
import com.fenakhay.kwikibot.protocol.decode.array
import com.fenakhay.kwikibot.protocol.decode.int
import com.fenakhay.kwikibot.protocol.decode.long
import com.fenakhay.kwikibot.protocol.decode.objects
import com.fenakhay.kwikibot.protocol.decode.string
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** How readers reached the pages counted. */
public enum class PageviewAccess(internal val segment: String) {
    /** Every way at once: the desktop site, the mobile site and the apps. */
    ALL_ACCESS("all-access"),

    /** The desktop site. */
    DESKTOP("desktop"),

    /** The Wikipedia apps. */
    MOBILE_APP("mobile-app"),

    /** The mobile site. */
    MOBILE_WEB("mobile-web"),
}

/** Who did the reading. */
public enum class PageviewAgent(internal val segment: String) {
    /** Everyone and everything, people and programs alike. */
    ALL_AGENTS("all-agents"),

    /** People, or what Wikimedia could not tell from people. Usually the number wanted. */
    USER("user"),

    /** Crawlers that said they were crawlers. */
    SPIDER("spider"),

    /** Programs Wikimedia recognised as automated without their saying so. */
    AUTOMATED("automated"),
}

/** How long each count covers. */
public enum class PageviewGranularity(internal val segment: String) {
    /** An hour each. Only a whole project is counted by the hour. */
    HOURLY("hourly"),

    /** A day each, in UTC. */
    DAILY("daily"),

    /** A calendar month each, in UTC. */
    MONTHLY("monthly"),
}

/** The views of one page, or of a whole project, over one hour, day or month. */
public data class PageviewCount(
    /** When the hour, day or month counted starts, in UTC. */
    val timestamp: Instant,
    /** How many times the page was viewed in it. */
    val views: Long,
    /** The project counted, as the API names it: `en.wikipedia`. */
    val project: String,
    /** How the readers reached it. */
    val access: PageviewAccess,
    /** Who the readers were. */
    val agent: PageviewAgent,
    /** How long the count covers. */
    val granularity: PageviewGranularity,
    /** The page, as its full title with spaces, or `null` for a count of a whole project. */
    val title: String? = null,
)

/** One of a project's most viewed pages over a day or a month. */
public data class TopPage(
    /** The page, as its full title with spaces. */
    val title: String,
    /** How many times it was viewed. */
    val views: Long,
    /** Its place in the ranking, from 1. */
    val rank: Int,
)

/**
 * How often pages on Wikimedia's wikis are read, from the Wikimedia REST API's pageviews.
 *
 * Separate from [Wiki] because the counts come from another server, `wikimedia.org`, with no session and its
 * own limits. It covers Wikimedia's wikis only, and only from July 2015: an earlier day, or a wiki of anyone
 * else's, has no data, which comes back as an empty list rather than an error.
 *
 * ```
 * val views = Pageviews(http, userAgent).perArticle(
 *     project = "en.wikipedia",
 *     title = "Albert Einstein",
 *     start = Instant.parse("2024-01-01T00:00:00Z"),
 *     end = Instant.parse("2024-01-31T00:00:00Z"),
 * )
 * views.sumOf { it.views }
 * ```
 *
 * One request counts one page. Ranking a thousand pages is a thousand requests, so [Wiki.pageviews] sends a
 * few at a time, each waiting for room among the client's requests in flight as every request to a wiki does,
 * and a `429` or a `5xx` is waited out as [retry] says, honouring `Retry-After`. For a project's most read
 * pages, [top] is one request.
 *
 * @param client the client to send through. One made by [WikiHttpClient] limits how many requests are in
 *   flight at once, and these count among them.
 * @param userAgent who to say is asking, which Wikimedia's user-agent policy requires.
 * @param retry how often to ask again, and how long to wait, while the API is busy or failing.
 * @param baseUrl where the API is served, Wikimedia's unless given.
 */
public class Pageviews(
    private val client: HttpClient,
    private val userAgent: UserAgent,
    private val retry: RetryPolicy = RetryPolicy(),
    private val baseUrl: String = WIKIMEDIA,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The views of one page, a count for each day or month from [start] to [end], both included.
     *
     * @param project the wiki, as the API names it: `en.wikipedia`, `commons.wikimedia`, `wikidata`.
     * @param title the page's full title, with its namespace prefix, as the wiki writes it.
     * @param start the first day or month counted; anything finer than an hour is ignored.
     * @param end the last.
     * @param granularity [PageviewGranularity.DAILY] or [PageviewGranularity.MONTHLY]: a page is not counted
     *   by the hour.
     * @param access how the readers reached it, every way unless given.
     * @param agent who they were, people unless given.
     * @return the counts, oldest first, or none when the API has no data for that page and period.
     * @throws WikiError.Transport.Refused when the API refuses the request, saying why.
     */
    public suspend fun perArticle(
        project: String,
        title: String,
        start: Instant,
        end: Instant,
        granularity: PageviewGranularity = PageviewGranularity.DAILY,
        access: PageviewAccess = PageviewAccess.ALL_ACCESS,
        agent: PageviewAgent = PageviewAgent.USER,
    ): List<PageviewCount> {
        require(granularity != PageviewGranularity.HOURLY) { "a page is counted by the day or the month" }
        require(start <= end) { "the start, $start, is after the end, $end" }

        val path =
            "per-article/$project/${access.segment}/${agent.segment}/${articleSegment(title)}/" +
                "${granularity.segment}/${hourOf(start)}/${hourOf(end)}"

        return read(path).array(ITEMS).objects().mapNotNull { item ->
            countOf(item, project, access, agent, granularity, title = item.string("article") ?: title)
        }
    }

    /**
     * The views of a whole project, a count for each hour, day or month from [start] to [end], both included.
     *
     * @param project the wiki, as the API names it, or `all-projects` for every Wikimedia wiki at once.
     * @param start the first hour, day or month counted.
     * @param end the last.
     * @param granularity how long each count covers.
     * @param access how the readers reached it, every way unless given.
     * @param agent who they were, people unless given.
     * @return the counts, oldest first, or none when the API has no data for that project and period.
     * @throws WikiError.Transport.Refused when the API refuses the request, saying why.
     */
    public suspend fun aggregate(
        project: String,
        start: Instant,
        end: Instant,
        granularity: PageviewGranularity = PageviewGranularity.DAILY,
        access: PageviewAccess = PageviewAccess.ALL_ACCESS,
        agent: PageviewAgent = PageviewAgent.USER,
    ): List<PageviewCount> {
        require(start <= end) { "the start, $start, is after the end, $end" }

        val path =
            "aggregate/$project/${access.segment}/${agent.segment}/${granularity.segment}/" +
                "${hourOf(start)}/${hourOf(end)}"

        return read(path).array(ITEMS).objects().mapNotNull { item ->
            countOf(item, project, access, agent, granularity, title = null)
        }
    }

    /**
     * A project's most viewed pages on one day, or over the month it falls in, most viewed first.
     *
     * The API ranks people's views only, and lists a thousand pages at most.
     *
     * @param project the wiki, as the API names it.
     * @param day the day, in UTC, or any moment of the month when [granularity] is monthly.
     * @param access how the readers reached them, every way unless given.
     * @param granularity [PageviewGranularity.DAILY] for that day, [PageviewGranularity.MONTHLY] for its
     *   month.
     * @return the pages, or none when the API has no data for that project and period.
     * @throws WikiError.Transport.Refused when the API refuses the request, saying why.
     */
    public suspend fun top(
        project: String,
        day: Instant,
        access: PageviewAccess = PageviewAccess.ALL_ACCESS,
        granularity: PageviewGranularity = PageviewGranularity.DAILY,
    ): List<TopPage> {
        require(granularity != PageviewGranularity.HOURLY) {
            "the most viewed pages are ranked by day or month"
        }

        val date = day.toLocalDateTime(TimeZone.UTC)
        val dayOfMonth = if (granularity == PageviewGranularity.MONTHLY) WHOLE_MONTH else twoDigits(date.day)
        val path = "top/$project/${access.segment}/${date.year}/${twoDigits(date.month.number)}/$dayOfMonth"

        return read(path).array(ITEMS).objects().flatMap { item ->
            item.array("articles").objects().map { article ->
                TopPage(
                    title = article.string("article").orEmpty().replace('_', ' '),
                    views = article.long("views") ?: 0L,
                    rank = article.int("rank") ?: 0,
                )
            }
        }
    }

    private fun countOf(
        item: JsonObject,
        project: String,
        access: PageviewAccess,
        agent: PageviewAgent,
        granularity: PageviewGranularity,
        title: String?,
    ): PageviewCount? =
        PageviewCount(
            timestamp = item.string("timestamp")?.let(::instantOf) ?: return null,
            views = item.long("views") ?: 0L,
            project = item.string("project") ?: project,
            access = access,
            agent = agent,
            granularity = granularity,
            title = title?.replace('_', ' '),
        )

    /**
     * The answer at [path], or an empty one when the API has no data there, asking again while it is busy or
     * failing.
     */
    private suspend fun read(path: String): JsonObject {
        val url = "$baseUrl/$path"
        var attempt = 0

        while (true) {
            val answer = runCatching { fetch(url) }
            val failure =
                answer.exceptionOrNull()?.let {
                    it as? WikiError.Transport ?: transportFailure(url, it) ?: throw it
                } ?: return answer.getOrThrow()

            if (!failure.isTransient || attempt >= retry.maxRetries) throw failure

            attempt++
            delay(retry.delayFor(attempt, (failure as? WikiError.Transport.RateLimited)?.retryAfter))
        }
    }

    /** One request, sent once there is room for it among the client's requests in flight. */
    private suspend fun fetch(url: String): JsonObject = client.withRequestPermit {
        val response =
            client.get(url) {
                identifyAs(userAgent)
                header(HttpHeaders.Accept, "application/json")
            }
        val body = response.bodyAsText()
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

        when {
            response.status.isSuccess() ->
                parsed
                    ?: throw WikiError.Api(
                        "badresponse",
                        "the pageviews API answered something other than JSON: ${body.take(ERROR_EXCERPT)}",
                        "pageviews",
                    )

            response.status == HttpStatusCode.NotFound && parsed.saysNoData() -> NO_DATA

            else ->
                throw statusFailure(url, response, parsed?.string("detail") ?: body)
                    ?: WikiError.Api(
                        "badresponse",
                        "the pageviews API answered ${response.status.value}",
                        "pageviews",
                    )
        }
    }

    /** Whether a `404` is the API saying it has no data, rather than that there is no such route. */
    private fun JsonObject?.saysNoData(): Boolean = this?.string("detail")?.contains(NO_DATA_DETAIL) == true

    /** Where the API is, how it names a project, and what it says when it has nothing. */
    public companion object {
        /** Wikimedia's pageviews API, which needs no credentials. */
        public const val WIKIMEDIA: String = "https://wikimedia.org/api/rest_v1/metrics/pageviews"

        /** The first day the API has data for. */
        public val FIRST_DAY: Instant = Instant.parse("2015-07-01T00:00:00Z")

        /**
         * The project the API counts the wiki at [server] under: `en.wikipedia` for `en.wikipedia.org`.
         *
         * @param server a host such as `en.wikipedia.org`, with or without a scheme, `www.` or a mobile `m.`.
         * @throws IllegalArgumentException for a host that is not one of Wikimedia's wikis, which the API
         *   does not count. It would answer that it has no data, as it does for a page nobody read.
         */
        public fun projectOf(server: String): String {
            val host =
                server
                    .substringAfter("://")
                    .substringBefore('/')
                    .substringBefore(':')
                    .lowercase()
                    .removePrefix("www.")
            val labels = host.split('.').toMutableList()
            val mobile = labels.size - MOBILE_FROM_END
            if (mobile >= 0 && labels[mobile] == MOBILE) labels.removeAt(mobile)
            val canonical = labels.joinToString(".")

            require(WIKIMEDIA_DOMAINS.any { canonical == it || canonical.endsWith(".$it") }) {
                "the pageviews API counts Wikimedia's wikis only, and $server is not one"
            }
            return canonical.removeSuffix(".org")
        }

        /**
         * [title] as the API takes it in a path: spaces as underscores, and every byte of its UTF-8 that is
         * not a letter, a digit or one of `-._~` percent-encoded, a slash and a question mark included.
         */
        internal fun articleSegment(title: String): String = buildString {
            for (byte in title.trim().replace(' ', '_').encodeToByteArray()) {
                val code = byte.toInt() and BYTE
                if (code.toChar() in UNRESERVED) {
                    append(code.toChar())
                } else {
                    append('%')
                    append(HEX[code shr NIBBLE])
                    append(HEX[code and LOW_NIBBLE])
                }
            }
        }

        /** [instant] as the hour the API reads it as: `2024010100`. */
        internal fun hourOf(instant: Instant): String {
            val time = instant.toLocalDateTime(TimeZone.UTC)
            return time.year.toString().padStart(YEAR_DIGITS, '0') +
                twoDigits(time.month.number) +
                twoDigits(time.day) +
                twoDigits(time.hour)
        }

        /** The hour the API wrote, `2024010100`, as the moment it starts, or `null` if it is not one. */
        private fun instantOf(hour: String): Instant? {
            if (hour.length != HOUR_LENGTH || !hour.all { it.isDigit() }) return null
            return runCatching {
                LocalDateTime(
                        year = hour.substring(0, YEAR_DIGITS).toInt(),
                        month = hour.substring(YEAR_DIGITS, MONTH_END).toInt(),
                        day = hour.substring(MONTH_END, DAY_END).toInt(),
                        hour = hour.substring(DAY_END, HOUR_LENGTH).toInt(),
                        minute = 0,
                    )
                    .toInstant(TimeZone.UTC)
            }
                .getOrNull()
        }

        private fun twoDigits(number: Int): String = number.toString().padStart(2, '0')

        private const val ITEMS = "items"
        private const val WHOLE_MONTH = "all-days"
        private const val MOBILE = "m"

        /** Where a mobile host has its `m`: third from the end, as in `en.m.wikipedia.org`. */
        private const val MOBILE_FROM_END = 3

        /** How the API's answer for a page or period it has no data for begins its explanation. */
        private const val NO_DATA_DETAIL = "do not have data"

        private val NO_DATA = JsonObject(emptyMap())

        private val WIKIMEDIA_DOMAINS =
            listOf(
                "wikipedia.org",
                "wiktionary.org",
                "wikisource.org",
                "wikiquote.org",
                "wikibooks.org",
                "wikinews.org",
                "wikiversity.org",
                "wikivoyage.org",
                "wikimedia.org",
                "wikidata.org",
                "mediawiki.org",
                "wikifunctions.org",
            )

        private val UNRESERVED = ('A'..'Z').toSet() + ('a'..'z') + ('0'..'9') + setOf('-', '.', '_', '~')
        private const val HEX = "0123456789ABCDEF"
        private const val BYTE = 0xFF
        private const val NIBBLE = 4
        private const val LOW_NIBBLE = 0x0F
        private const val YEAR_DIGITS = 4
        private const val MONTH_END = 6
        private const val DAY_END = 8
        private const val HOUR_LENGTH = 10
        private const val ERROR_EXCERPT = 200
    }
}

/**
 * The project the pageviews API counts this wiki under, from its server: `en.wikipedia` for
 * `en.wikipedia.org`.
 *
 * @throws IllegalArgumentException for a wiki that is not one of Wikimedia's, which the API does not count.
 */
public val Wiki.pageviewsProject: String
    get() = Pageviews.projectOf(info.server)

/**
 * The views of each of [pages] on this wiki, a count for each day or month from [start] to [end].
 *
 * One request a page, four at most at once, and each waits for room among the requests [pageviews]'s client
 * has in flight, so a client made by [WikiHttpClient] keeps to its own limit, three unless told otherwise. A
 * page the API has no data for has no counts.
 *
 * @param pageviews the client to ask through.
 * @param pages the pages, each asked about once however often it is named.
 * @param start the first day or month counted.
 * @param end the last.
 * @param granularity [PageviewGranularity.DAILY] or [PageviewGranularity.MONTHLY].
 * @param access how the readers reached them, every way unless given.
 * @param agent who they were, people unless given.
 * @return each page's counts, oldest first, in the order the pages were named.
 * @throws IllegalArgumentException for a wiki that is not one of Wikimedia's, before anything is sent.
 */
public suspend fun Wiki.pageviews(
    pageviews: Pageviews,
    pages: Collection<PageRef>,
    start: Instant,
    end: Instant,
    granularity: PageviewGranularity = PageviewGranularity.DAILY,
    access: PageviewAccess = PageviewAccess.ALL_ACCESS,
    agent: PageviewAgent = PageviewAgent.USER,
): Map<PageRef, List<PageviewCount>> {
    val project = pageviewsProject
    val room = Semaphore(PAGES_AT_ONCE)

    return coroutineScope {
        pages
            .distinct()
            .map { ref ->
                async {
                    room.withPermit {
                        ref to
                            pageviews.perArticle(
                                project,
                                namespaces.format(ref.title),
                                start,
                                end,
                                granularity,
                                access,
                                agent,
                            )
                    }
                }
            }
            .awaitAll()
            .toMap()
    }
}

/**
 * How many pages [Wiki.pageviews] asks about at once: enough to keep a client's three requests in flight
 * busy, few enough that a long list never floods the API.
 */
private const val PAGES_AT_ONCE = 4
