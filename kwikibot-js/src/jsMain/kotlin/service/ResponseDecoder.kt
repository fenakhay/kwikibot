import com.fenakhay.kwikibot.bindings.client.titleOf
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.protocol.ApiFailure
import com.fenakhay.kwikibot.protocol.decode.ActivityDecoder
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.protocol.decode.PageResult
import com.fenakhay.kwikibot.protocol.throwOnError

/**
 * Reads what `Wiki.call` and `Wiki.callPaged` answer into this package's own values, for a query this library
 * does not model but whose entries it can read. Each entry is one element of what the wiki listed, as plain
 * JSON. Titles are read with the wiki's namespaces.
 */
@JsExport
public class ResponseDecoder internal constructor(private val wiki: ClientWiki) {
    private val pages = PageDecoder(wiki.id, wiki.namespaces)
    private val activity = ActivityDecoder(pages)

    /** One entry of a `query` result's `pages`. */
    public fun decodePage(page: Record<String, JsonValue>): DecodedPage =
        DecodedPage(pages.decode(jsonObjectOf(page)), wiki)

    /**
     * Every revision a `query` result's page entry carries with its content, newest as the wiki listed it.
     *
     * @param page the entry.
     * @param isRedirect whether a text is a redirect; none is unless given.
     */
    public fun decodeContents(
        page: Record<String, JsonValue>,
        isRedirect: ((text: String) -> Boolean)? = null,
    ): Array<Page> =
        pages
            .decodeContents(jsonObjectOf(page), isRedirect ?: { false })
            .map { Page(it, wiki.namespaces) }
            .toTypedArray()

    /** One entry of a page's `revisions`. */
    public fun decodeRevision(revision: Record<String, JsonValue>): Revision =
        Revision(pages.decodeRevision(jsonObjectOf(revision)))

    /** The title a page entry names, as this wiki writes it, or `null` when it names none. */
    public fun titleOf(page: Record<String, JsonValue>): String? =
        pages.refOf(jsonObjectOf(page))?.let(wiki::titleOf)

    /** One entry of `list=logevents`. */
    public fun decodeLogEvent(entry: Record<String, JsonValue>): LogEvent =
        LogEvent(activity.decodeLogEvent(jsonObjectOf(entry)), wiki.namespaces)

    /** One entry of `list=recentchanges`. */
    public fun decodeRecentChange(entry: Record<String, JsonValue>): RecentChange =
        RecentChange(activity.decodeRecentChange(jsonObjectOf(entry)), wiki.namespaces)

    /** One entry of `list=usercontribs`, or `null` when what it says of the page is hidden. */
    public fun decodeContribution(entry: Record<String, JsonValue>): Contribution? =
        activity.decodeContribution(jsonObjectOf(entry))?.let { Contribution(it, wiki.namespaces) }

    /** One entry of `list=blocks`. */
    public fun decodeBlockRecord(entry: Record<String, JsonValue>): BlockRecord =
        BlockRecord(activity.decodeBlockRecord(jsonObjectOf(entry)))

    /** One entry of `list=users`. */
    public fun decodeUser(entry: Record<String, JsonValue>): UserInfo =
        UserInfo(activity.decodeUser(jsonObjectOf(entry)))

    /** The `userinfo` of `meta=userinfo`. */
    public fun decodeCurrentUser(entry: Record<String, JsonValue>): UserInfo =
        UserInfo(activity.decodeCurrentUser(jsonObjectOf(entry)))
}

/** One entry of a `query` result's `pages`. */
@JsExport
public class DecodedPage internal constructor(private val result: PageResult, private val wiki: ClientWiki) {

    /** `"existing"`, `"missing"`, or `"invalid"` for a title the wiki refused. */
    public val kind: DecodedPageKind
        get() =
            when (result) {
                is PageResult.Existing -> "existing"
                is PageResult.Missing -> "missing"
                is PageResult.Invalid -> "invalid"
            }.unsafeCast<DecodedPageKind>()

    /** The page's title, or on an `"invalid"`, the title as it was sent. */
    public val title: String
        get() =
            when (result) {
                is PageResult.Existing -> wiki.titleOf(result.ref)
                is PageResult.Missing -> wiki.titleOf(result.ref)
                is PageResult.Invalid -> result.raw
            }

    /** On an `"invalid"`, why the wiki refused it. */
    public val reason: String?
        get() = (result as? PageResult.Invalid)?.reason

    /** On an `"existing"`, what kind of content it holds. */
    public val contentModel: String?
        get() = (result as? PageResult.Existing)?.contentModel?.id

    /** On an `"existing"`, whether it redirects elsewhere. */
    public val isRedirect: Boolean?
        get() = (result as? PageResult.Existing)?.isRedirect

    /** On an `"existing"`, its newest revision, when the query asked for revisions. */
    public val latestRevision: Revision?
        get() = (result as? PageResult.Existing)?.latestRevision?.let(::Revision)

    /** On an `"existing"`, its text, when the query asked for content. */
    public val content: Page?
        get() = (result as? PageResult.Existing)?.content?.let { Page(it, wiki.namespaces) }
}

/**
 * The error in [response], an answer `Wiki.call` gave, as `describeError` reads one, or `null` when it holds
 * none.
 */
@JsExport
public fun responseError(response: Record<String, JsonValue>): ErrorInfo? =
    ApiFailure.from(jsonObjectOf(response))?.let { ErrorInfo(it.toWikiError()) }

/** [response], an answer `Wiki.call` gave, or a throw of the error in it, for a caller that wants one. */
@JsExport
public fun throwOnResponseError(response: Record<String, JsonValue>): Record<String, JsonValue> {
    val _ = jsonObjectOf(response).throwOnError()
    return response
}
