package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.identifyAs
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.statusFailure
import com.fenakhay.kwikibot.net.transport.transportFailure
import com.fenakhay.kwikibot.net.transport.withRequestPermit
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds where a wiki keeps its `api.php`, given any page of it.
 *
 * Third-party wikis put MediaWiki wherever they like — `/w/api.php`, `/api.php`, `/mediawiki/api.php`, or
 * behind a rewrite that hides it entirely. Rather than guess, this reads the answer out of the page: every
 * MediaWiki install advertises its API in an `EditURI` link in the document head.
 */
public object ApiDetector {

    /**
     * The endpoint the wiki at [url] advertises, or `null` if it advertises none.
     *
     * `null` means the page is not MediaWiki, or its head was stripped by a caching layer, or it does not
     * exist. Not that the wiki has no API: a caller that knows better can still name the endpoint itself. A
     * page that could not be fetched is not an answer, and says why.
     *
     * The link is looked for first, whatever the status, since the page a wiki serves for a missing title is
     * a 404 that advertises its API like any other.
     *
     * @throws WikiError.Transport if the host could not be reached, did not answer in time, or refused.
     */
    public suspend fun discover(
        url: String,
        client: HttpClient,
        userAgent: UserAgent,
    ): ApiEndpoint? {
        val [response, html] =
            runCatching {
                client.withRequestPermit {
                    val response = client.get(url) { identifyAs(userAgent) }
                    response to response.bodyAsText()
                }
            }
                .getOrElse { throw transportFailure(url, it) ?: it }

        endpointFrom(html, url)?.let {
            return it
        }
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Gone) return null
        statusFailure(url, response, html)?.let { throw it }
        return null
    }

    /**
     * The endpoint the wiki at [url] advertises, or `null` if it advertises none or could not be asked.
     *
     * `null` means the page is not MediaWiki, or its head was stripped by a caching layer — not that the wiki
     * has no API. A caller that knows better can still name the endpoint itself.
     */
    @Deprecated(
        "Answers null when the page could not be fetched, as if it had no API. Use discover, which says " +
            "why. Deprecated since kwikibot 1.5.0.",
        ReplaceWith("discover(url, client, userAgent)"),
    )
    public suspend fun detect(
        url: String,
        client: HttpClient,
        userAgent: UserAgent,
    ): ApiEndpoint? = runCatching {
        discover(url, client, userAgent)
    }
        .getOrElse {
            if (it is CancellationException) throw it
            null
        }

    /**
     * Reads the endpoint out of a page's HTML.
     *
     * Separated from fetching so it can be tested against recorded pages, and so a caller that already has
     * the HTML does not fetch it twice.
     */
    public fun endpointFrom(html: String, pageUrl: String): ApiEndpoint? {
        val advertised = advertisedHref(html)?.let(::unescape) ?: return null

        // The link points at api.php with a query attached: "//example.org/w/api.php?action=rsd".
        val apiUrl = absolute(advertised.substringBefore('?'), pageUrl)
        if (!apiUrl.endsWith("/api.php") || "://" !in apiUrl) return null

        val withoutScheme = apiUrl.substringAfter("://")
        val server = withoutScheme.substringBefore('/')
        // "/w/api.php" is a script path of "/w"; "/api.php" is a script path of "".
        val scriptPath = withoutScheme.substringAfter('/', "").removeSuffix("api.php").trim('/')

        return ApiEndpoint(server = server, scriptPath = if (scriptPath.isEmpty()) "" else "/$scriptPath")
    }

    /** Resolves a protocol-relative or root-relative URL against the page it was found on. */
    private fun absolute(href: String, pageUrl: String): String {
        val scheme = pageUrl.substringBefore("://", "https")
        val host = pageUrl.substringAfter("://", pageUrl).substringBefore('/')

        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "$scheme:$href"
            href.startsWith("/") -> "$scheme://$host$href"
            else -> href
        }
    }

    /** HTML entities MediaWiki writes into an attribute value. */
    private fun unescape(value: String): String =
        value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    /**
     * The href of the `EditURI` link, whatever order its attributes are in.
     *
     * Matching the whole tag first and reading its attributes second, rather than one regex over both: `href`
     * before `rel` is legal HTML and appears in the wild, and a pattern that assumes an order silently finds
     * nothing on those wikis.
     */
    private fun advertisedHref(html: String): String? =
        LINK_TAG.findAll(html)
            .firstOrNull { REL_EDIT_URI.containsMatchIn(it.value) }
            ?.let { HREF.find(it.value)?.groupValues?.get(1) }

    private val LINK_TAG = Regex("""<link\s[^>]*>""", RegexOption.IGNORE_CASE)
    private val REL_EDIT_URI = Regex("""rel\s*=\s*["']EditURI["']""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
}
