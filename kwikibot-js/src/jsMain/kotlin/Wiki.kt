import com.fenakhay.kwikibot.bindings.client.EditAnswer
import com.fenakhay.kwikibot.bindings.client.answerOf
import com.fenakhay.kwikibot.bindings.client.refusingRateLimits
import com.fenakhay.kwikibot.bindings.client.titleOf
import com.fenakhay.kwikibot.bindings.connect.userAgent
import com.fenakhay.kwikibot.bot.run.stopPage
import com.fenakhay.kwikibot.client.ApiDetector
import com.fenakhay.kwikibot.client.MediaWikiVersion
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.client.dayTitleFormat
import com.fenakhay.kwikibot.client.hasExtension
import com.fenakhay.kwikibot.client.hasRight
import com.fenakhay.kwikibot.client.hasVersion
import com.fenakhay.kwikibot.client.model.SiteMatrix as ClientSiteMatrix
import com.fenakhay.kwikibot.client.monthNames
import com.fenakhay.kwikibot.client.requireExtension
import com.fenakhay.kwikibot.client.requireGroup
import com.fenakhay.kwikibot.client.requireRight
import com.fenakhay.kwikibot.client.requireVersion
import com.fenakhay.kwikibot.client.signatures
import com.fenakhay.kwikibot.client.templateNames
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.Revision as ClientRevision
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.user.UserInfo
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.auth.Identity as ClientIdentity
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import com.fenakhay.kwikibot.protocol.decode.Continuation
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.client.engine.HttpClientEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise

/** A bot password from Special:BotPasswords, to log in with. */
@JsExport
public open class BotPassword(
    /** The account the bot password belongs to. */
    public val account: String,
    /** The bot password's name. */
    public val botName: String,
    private val password: String,
) {
    internal open val credentials: Credentials
        get() = Credentials.BotPassword(account, botName, password)
}

/**
 * Opens the wiki at [server], such as `"en.wiktionary.org"`, and reads its site information.
 *
 * [contact] is a URL or email address the wiki's operators can reach you at, which Wikimedia's user-agent
 * policy asks for. With a [login], the wiki is opened logged in. [scriptPath] is where `api.php` is under the
 * server, `"/w"` as on Wikimedia's wikis unless given.
 *
 * In a browser, an anonymous request to a wiki on another origin sends `origin=*`, which MediaWiki answers
 * for any page. A logged-in one needs the wiki to allow the page's origin.
 */
@JsExport
public fun connect(
    server: String,
    contact: String,
    login: BotPassword? = null,
    scriptPath: String = DEFAULT_SCRIPT_PATH,
): Promise<Wiki> =
    connect(server, login, scriptPath, WikiConfig(userAgent = userAgent(contact)), engine = null)

private const val DEFAULT_SCRIPT_PATH = "/w"

/** [connect] with [config], through [engine], or the platform's own when it is `null`. */
@OptIn(DelicateCoroutinesApi::class)
internal fun connect(
    server: String,
    login: BotPassword?,
    scriptPath: String,
    config: WikiConfig,
    engine: HttpClientEngine?,
): Promise<Wiki> = GlobalScope.promise {
    withoutLoggingBanner()
    val client = WikiClient(config, login?.credentials ?: Credentials.Anonymous, engine = engine)
    val opened = runCatching { client.wiki(ApiEndpoint(server, scriptPath)) }.onFailure { client.close() }
    Wiki(opened.getOrThrow(), CoroutineScope(SupervisorJob()), client::close)
}

/**
 * Finds where the API of the wiki at [url], any of its pages, is, from what the page advertises in its head.
 * `null` when it advertises none, or when the page cannot be fetched.
 */
@JsExport
public fun detect(url: String, contact: String): Promise<Endpoint?> = detect(url, contact, engine = null)

/** [detect], through [engine], or the platform's own when it is `null`. */
@OptIn(DelicateCoroutinesApi::class)
internal fun detect(url: String, contact: String, engine: HttpClientEngine?): Promise<Endpoint?> =
    GlobalScope.promise {
        withoutLoggingBanner()
        WikiHttpClient.create(engine = engine).use { client ->
            runCatching { ApiDetector.discover(url, client, userAgent(contact)) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    null
                }
                ?.let(::Endpoint)
        }
    }

/** Where a wiki's API is. */
@JsExport
public class Endpoint internal constructor(internal val endpoint: ApiEndpoint) {

    /** The URL of `api.php`. */
    public val apiUrl: String
        get() = endpoint.apiUrl

    /** The host the wiki is on, such as `"en.wiktionary.org"`. */
    public val server: String
        get() = endpoint.server

    /** Where `api.php` is under the server, such as `"/w"`, or `""` when it is at the root. */
    public val scriptPath: String
        get() = endpoint.scriptPath

    /** Whether it speaks HTTPS. */
    public val secure: Boolean
        get() = endpoint.secure

    /** The URL of `index.php`, for permalinks and links a person follows. */
    public val indexUrl: String
        get() = endpoint.indexUrl

    /** The URL of the page [title], as it appears in a browser. */
    public fun articleUrl(title: String): String = endpoint.articleUrl(title)
}

/** An open wiki. Close it when done, which ends what it is still fetching. */
@JsExport
public class Wiki
internal constructor(
    internal val wiki: ClientWiki,
    internal val scope: CoroutineScope,
    private val onClose: () -> Unit,
) {

    /** The wiki's database name, such as `"enwiktionary"`. */
    public val id: String
        get() = wiki.id.dbName

    /** The wiki's name, such as `"Wiktionary"`. */
    public val siteName: String
        get() = wiki.info.siteName

    /** The MediaWiki version the wiki runs, such as `"1.48.0-wmf.1"`. */
    public val mediaWikiVersion: String
        get() = wiki.info.version

    /** Everything the wiki can be asked to list. */
    public val lists: ListService = ListService(wiki, scope)

    /** The history of pages. */
    public val revisions: RevisionService = RevisionService(wiki, scope)

    /** What has happened on the wiki: its logs and its recent changes. */
    public val logs: LogService = LogService(wiki, scope)

    /** The people editing the wiki. */
    public val users: UserService = UserService(wiki, scope)

    /**
     * Reading and writing pages, everything an edit can say included. The name `pages` is the older way to
     * read many pages at once.
     */
    public val pagesService: PageService = PageService(wiki, scope)

    /** Files: what is known about them, where they are used, and how to add one. */
    public val files: FileService = FileService(wiki, scope)

    /**
     * What the wiki says about itself beyond its site information: its messages, magic words and counters.
     */
    public val meta: MetaService = MetaService(wiki, scope)

    /** The wiki's own view of a page: what it renders, and what it resolves to. */
    public val render: RenderService = RenderService(wiki, scope)

    /** What the wiki can do only because an extension is installed. */
    public val extensions: ExtensionService = ExtensionService(wiki, scope)

    /** Wikisource's scanned pages. Needs the ProofreadPage extension. */
    public val proofread: ProofreadService = ProofreadService(wiki, scope)

    /** What the wiki says its own API accepts. */
    public val paramInfo: ParamInfo = ParamInfo(wiki.paramInfo, scope)

    /** The tokens writes need, for actions this library does not model, sent through [call]. */
    public val tokens: TokenStore = TokenStore(wiki.tokens, scope)

    /** The Wikibase repository this wiki is: reading, searching and writing entities and statements. */
    public val entities: EntityService = EntityService(wiki, scope)

    /** What the wiki said about itself when it was opened. */
    public val info: SiteInfo
        get() = SiteInfo(wiki.info)

    /** Who the wiki saw when it was opened, with what [user] leaves out. */
    public val identity: Identity
        get() = Identity(wiki.identity)

    /** The wiki's namespaces. */
    public val namespaces: Namespaces
        get() = Namespaces(wiki.namespaces)

    /** The wiki's interwiki prefixes. */
    public val interwiki: Interwiki
        get() = Interwiki(wiki.interwiki)

    /** Where the wiki's API is. */
    public val endpoint: Endpoint
        get() = Endpoint(wiki.transport.endpoint)

    /**
     * The full title [raw] names on this wiki, normalised and with its prefix, as a page of the namespace
     * numbered [defaultNamespace] when it has no prefix of its own. Throws when it is not a title, or names a
     * page on another wiki.
     */
    public fun ref(raw: String, defaultNamespace: Int? = null): String =
        wiki.titleOf(wiki.ref(raw, defaultNamespace?.let(::Namespace) ?: Namespace.MAIN))

    /**
     * Reads [text] as a title with this wiki's namespaces, as a page of the namespace numbered
     * [defaultNamespace] when it has no prefix of its own.
     */
    public fun parseTitleIn(text: String, defaultNamespace: Int): Title =
        Title(wiki.parse(text, Namespace(defaultNamespace)), wiki.namespaces)

    /**
     * Sends [params] to `api.php` and answers with the response as plain JSON, for the actions this library
     * does not model. Paced, retried and sent as this wiki's session is.
     *
     * [params] has to name an `action`. A string goes as it is, a number as its digits, a date as a
     * timestamp, an array joined with `|`, and `true` as `1`; `false`, `null` and `undefined` leave the
     * parameter out, as MediaWiki reads a flag by its presence.
     *
     * An error from the wiki is an answer here: it comes back as the response's `error`, for the caller to
     * read, rather than as a rejection. A write has to carry a token from [tokens].
     */
    public fun call(
        params: Record<String, ApiValue>,
        options: CallOptions? = null,
    ): Promise<Record<String, JsonValue>> = scope.promise {
        plainOf(
            wiki.transport.call(
                ApiRequest(
                    apiParameters(params),
                    if (options?.write == true) RequestKind.WRITE else RequestKind.READ,
                    options?.cacheable ?: true,
                )
            )
        )
    }

    /**
     * Sends a read [params] as [call] does and answers with each batch of the response, following `continue`
     * as the walk asks. An error from the wiki rejects the walk: a query that fails halfway is not a short
     * result.
     *
     * @param params what to send, as [call] takes it.
     * @param maxBatches a ceiling on the round trips; none unless given.
     * @param list the `list=` module, to walk the entries it lists in place of whole batches.
     * @param pages whether to walk the page entries of a `prop=` query in place of whole batches. A page can
     *   arrive in several batches, each with different properties.
     */
    public fun callPaged(
        params: Record<String, ApiValue>,
        maxBatches: Int? = null,
        list: String? = null,
        pages: Boolean = false,
    ): AsyncIterable<Record<String, JsonValue>> {
        val request = ApiRequest(apiParameters(params))
        val paging = Continuation(wiki.transport)
        val walk =
            when {
                list != null -> paging.list(request, list, maxBatches)
                pages -> paging.pages(request, maxBatches)
                else -> paging.batches(request, maxBatches)
            }
        return walk.toAsyncIterable(scope, ::plainOf)
    }

    /** Reads what [call] and [callPaged] answer into this package's own values. */
    public val decoder: ResponseDecoder
        get() = ResponseDecoder(wiki)

    /**
     * How this wiki's wikitext parses: its extension tags, URL schemes and File namespace names. Its `parse`
     * reads a page as this wiki reads it.
     */
    public fun parseOptions(): Promise<ParseOptions> = scope.promise { ParseOptions(wiki.parseOptions()) }

    /**
     * How this wiki names pages: its namespace names, which namespaces keep case, its parser functions and
     * variables. Tells templates apart as this wiki does.
     */
    public fun titleRules(): Promise<TitleRules> = scope.promise { TitleRules(wiki.titleRules()) }

    /** The account the wiki saw when it was opened. */
    public val user: User
        get() = user(wiki.identity)

    /** The account the wiki sees now, asked afresh. */
    public fun whoami(): Promise<User> = scope.promise { user(wiki.users.current()) }

    /** Reads [text] as a title with this wiki's namespaces. */
    public fun parseTitle(text: String): Title = Title(wiki.parse(text), wiki.namespaces)

    /** The current text of [title], or `null` when there is no such page. */
    public fun page(title: String): Promise<Page?> = scope.promise {
        wiki.pages.content(wiki.ref(title))?.let { Page(it, wiki.namespaces) }
    }

    /** The current text of each of [titles] that exists, in as few requests as the wiki allows. */
    public fun pages(titles: Array<String>): Promise<Array<Page>> = scope.promise {
        val refs = titles.map { wiki.ref(it) }
        val found = wiki.pages.contents(refs)
        refs.mapNotNull { found[it] }.map { Page(it, wiki.namespaces) }.toTypedArray()
    }

    /**
     * Saves [text] to [title] with [summary], and says how the wiki answered: a refusal is a result, not an
     * error.
     *
     * [baseRevision] is the revision the text was computed from, a page's `revision`. When the page has
     * changed since, the wiki merges the two edits or, when it cannot, refuses this one as a conflict instead
     * of overwriting the other. A logged-in bot's edits are marked as a bot's.
     */
    public fun edit(
        title: String,
        text: String,
        summary: String,
        baseRevision: Double? = null,
        minor: Boolean = false,
    ): Promise<EditResult> = scope.promise {
        val ref = wiki.ref(title)
        val outcome =
            refusingRateLimits(ref) {
                wiki.pages.edit(ref) {
                    this.text = text
                    this.summary = summary
                    baseRevision?.let { this.baseRevision = RevisionId(it.toLong()) }
                    this.minor = minor
                }
            }
        editResult(outcome)
    }

    /** The titles in [category], which needs no `Category:` prefix. */
    public fun categoryMembers(category: String): AsyncIterable<String> = listing {
        wiki.lists.categoryMembers(wiki.ref(category, Namespace.CATEGORY))
    }
        .toAsyncIterable(scope, wiki::titleOf)

    /** The titles of the pages a search for [query] finds, best match first. */
    public fun search(query: String): AsyncIterable<String> = listing {
        wiki.lists.search(query)
    }
        .toAsyncIterable(scope, wiki::titleOf)

    /** The titles of the pages that transclude [template], which needs no `Template:` prefix. */
    public fun embeddedIn(template: String): AsyncIterable<String> = listing {
        wiki.lists.transclusions(wiki.ref(template, Namespace.TEMPLATE))
    }
        .toAsyncIterable(scope, wiki::titleOf)

    /** The revisions of [title], newest first. */
    public fun history(title: String): AsyncIterable<Revision> = listing {
        wiki.revisions.history(wiki.ref(title))
    }
        .toAsyncIterable(scope, ::Revision)

    /** The times of the signatures in [text], read in this wiki's time zone and with its month names. */
    public fun signatureTimes(text: String): Promise<Array<SignatureTime>> = scope.promise {
        wiki.signatures().findAll(text).map(::SignatureTime).toTypedArray()
    }

    /** The Wikibase entity [id], such as `"Q42"`, `"P31"` or `"L7"`, or `null` when there is none. */
    public fun entity(id: String): Promise<Entity?> = scope.promise { entityOf(wiki, id) }

    /** Every Wikimedia wiki, as the SiteMatrix extension on this wiki lists them. */
    public fun siteMatrix(): Promise<SiteMatrix> = scope.promise {
        SiteMatrix(ClientSiteMatrix.fetch(wiki.transport))
    }

    /** How this wiki's signatures are written, with its month names and in its time zone. */
    public fun signatures(): Promise<Signatures> = scope.promise { Signatures(wiki.signatures()) }

    /** The month names this wiki writes, lowercased, with their numbers. */
    public fun monthNames(): Promise<Record<String, Int>> = scope.promise {
        wiki.monthNames().toRecord { it }
    }

    /** How this wiki titles its pages about a calendar day, or `null` for a language with no format. */
    public fun dayTitleFormat(): Promise<DayFormat?> = scope.promise {
        wiki.dayTitleFormat()?.let(::DayFormat)
    }

    /**
     * The names [template] can be called by on this wiki: its own and every redirect's, to give to
     * `Wikitext.templatesNamed`.
     */
    public fun templateNames(template: String): Promise<Array<String>> = scope.promise {
        wiki.templateNames(template).toTypedArray()
    }

    /** Whether the account now holds [right], asked afresh. */
    public fun hasRight(right: String): Promise<Boolean> = scope.promise { wiki.hasRight(right) }

    /** Rejects unless the account now holds [right]. */
    public fun requireRight(right: String): Promise<Unit> = scope.promiseVoid { wiki.requireRight(right) }

    /** Throws unless the account was in [group] when the wiki was opened. */
    public fun requireGroup(group: String) {
        wiki.requireGroup(group)
    }

    /** Whether the wiki has [extension], named as MediaWiki reports it. */
    public fun hasExtension(extension: String): Boolean = wiki.hasExtension(extension)

    /** Throws unless the wiki has [extension], named as MediaWiki reports it. */
    public fun requireExtension(extension: String) {
        wiki.requireExtension(extension)
    }

    /**
     * Whether the wiki runs MediaWiki [version], such as `"1.44.0"`, or newer. A version it cannot read is
     * older.
     */
    public fun hasVersion(version: String): Boolean = wiki.hasVersion(MediaWikiVersion.parse(version))

    /** Throws unless the wiki runs MediaWiki [version], such as `"1.44.0"`, or newer. */
    public fun requireVersion(version: String) {
        wiki.requireVersion(MediaWikiVersion.parse(version))
    }

    /**
     * Runs a bot over [titles]: reads each page, gives its text and title to [transform], and saves the text
     * that comes back with [summary]. The transform returns `null` to leave a page alone, and may return a
     * promise of either.
     *
     * A dry run, which only computes each edit, unless [save] is `true`. [onChange] is called with each
     * change as the run computes it, or saves it when it saves. A page the wiki refuses, or cannot serve, is
     * a problem in the report; a transform that throws, or answers with anything else, stops the run, which
     * rejects with that error.
     *
     * [stopPage] names a page of the wiki that has to say `false` for the run to save, so that it can be
     * stopped from the wiki. [maxEdits] caps how many edits it saves.
     */
    public fun run(
        titles: Array<String>,
        summary: String,
        transform: (text: String, title: String) -> Awaitable<String?>,
        save: Boolean = false,
        onChange: ((change: Change) -> Unit)? = null,
        stopPage: String? = null,
        maxEdits: Int? = null,
    ): Promise<RunReport> = scope.promise {
        runBot(wiki, titles, summary, transform, save, onChange, stopPage, maxEdits = maxEdits)
    }

    /**
     * Runs a bot as [options] describes, with every setting a run has, and answers with the whole report:
     * reads each page of its `source`, gives it to its `transform`, and saves the edit that comes back. A dry
     * run unless told otherwise. Honours `{{nobots}}` and `{{bots}}` as this account unless told otherwise.
     *
     * A page the wiki refuses, or cannot serve, is a problem in the report. A transform that throws anything
     * but what `skipPage` throws stops the run, which rejects with that error.
     */
    public fun botRun(options: BotRunOptions): Promise<BotReport> = scope.promise {
        botRunWith(wiki, options)
    }

    /**
     * A stop policy on page [title] of this wiki, by convention `User:<account>/Stop`, which is the default.
     * The page has to exist and say `false` for the bot to run, so an administrator stops it by editing the
     * page, and a bot whose page was never made does not start.
     */
    public fun stopPage(title: String? = null): StopPolicy =
        StopPolicy(if (title == null) wiki.stopPage() else wiki.stopPage(title))

    /** Ends what this wiki is still fetching and releases its connections. */
    public fun close() {
        scope.cancel()
        onClose()
    }
}

/** A page's current text and the revision it is. */
@JsExport
public class Page
internal constructor(private val content: PageContent, private val namespaces: NamespaceMap) {

    /** The page's title, prefix included. */
    public val title: String
        get() = namespaces.format(content.ref.title)

    /** The text. */
    public val text: String
        get() = content.text

    /** The revision this text is, to pass back to `edit` as its base revision. */
    public val revision: Double
        get() = content.revisionId.value.toDouble()

    /** When the revision was saved, or `null` when the wiki did not say. */
    public val timestamp: Date?
        get() = content.timestamp?.let { Date(it.toEpochMilliseconds().toDouble()) }

    /** Whether the page is a redirect. */
    public val isRedirect: Boolean
        get() = content.isRedirect

    /** Where the page redirects, which may be on another wiki, or `null` when it is not a redirect. */
    public val redirectTarget: String?
        get() = content.redirectTarget?.let { Title(it, namespaces).toString() }

    /** What kind of content the text is, such as `"wikitext"`, `"json"` or `"Scribunto"`. */
    public val contentModel: String
        get() = content.contentModel.id

    /** The wiki's own id for the page, or `null` when the wiki did not say. */
    public val pageId: Double?
        get() = content.ref.pageId?.value?.toDouble()

    /** The text, parsed. */
    public fun wikitext(): Wikitext = parse(content.text)
}

/** How the wiki answered an edit. */
@JsExport
public class EditResult
internal constructor(
    /**
     * `"saved"`, `"unchanged"`, or why the wiki refused: `"conflict"`, `"protected"`, `"permission-denied"`,
     * `"rate-limited"`, `"filtered"`, `"captcha"`, `"page-changed"` or `"rejected"`.
     */
    public val status: EditStatus,
    /**
     * The revision saved, or the current one when nothing changed and the wiki said which; `null` when
     * refused.
     */
    public val revision: Double?,
    /** The revision the save replaced, or `null`. */
    public val previousRevision: Double?,
    /** What the wiki said when it refused, or `null`. */
    public val detail: String?,
    private val refused: EditOutcome.Refused? = null,
) {
    private val refusal: EditAnswer?
        get() = refused?.let(::answerOf)

    /** Whether trying again, after reloading or after a wait, could succeed. `false` unless refused. */
    public val isRetryable: Boolean
        get() = refusal?.isRetryable == true

    /** On a `"conflict"`, the revision the page is on now, when the wiki said. */
    public val currentRevision: Double?
        get() = refusal?.currentRevision?.value?.toDouble()

    /** On a `"protected"`, the protection level that blocked the edit, when the wiki named one. */
    public val protectionLevel: String?
        get() = refusal?.protectionLevel

    /** On a `"protected"`, whether the protection comes from a page transcluding this one. */
    public val isCascadingProtection: Boolean?
        get() = refusal?.isCascadingProtection

    /** On a `"filtered"`, the filter name or blacklist entry, when the wiki named one. */
    public val filter: String?
        get() = refusal?.filter

    /** On a `"rate-limited"`, how long the wiki asked to wait, in milliseconds, when it said. */
    public val retryAfter: Double?
        get() = refusal?.retryAfter?.inWholeMilliseconds?.toDouble()

    /** On a `"captcha"`, the captcha the wiki wants solved, when it said. */
    public val captchaId: String?
        get() = refusal?.captchaId

    /**
     * On a `"page-changed"`, whether the page was deleted, where `false` covers both a page never created and
     * one a `createOnly` found existing.
     */
    public val wasDeleted: Boolean?
        get() = refusal?.wasDeleted

    /** On a `"rejected"`, the wiki's code for a refusal this library does not model. */
    public val code: String?
        get() = refusal?.code
}

/** One revision of a page. */
@JsExport
public class Revision internal constructor(private val revision: ClientRevision) {

    /** The revision's id. */
    public val id: Double
        get() = revision.id.value.toDouble()

    /** The revision before it, or `null` for a page's first. */
    public val parentId: Double?
        get() = revision.parentId?.value?.toDouble()

    /** When it was saved. */
    public val timestamp: Date
        get() = Date(revision.timestamp.toEpochMilliseconds().toDouble())

    /** Who saved it, or `null` when that is hidden. */
    public val user: String?
        get() = revision.user

    /** Its edit summary, or `null` when that is hidden. */
    public val comment: String?
        get() = revision.comment

    /** The page's size after it, in bytes. */
    public val size: Int
        get() = revision.size

    /** Whether it was marked minor. */
    public val minor: Boolean
        get() = revision.isMinor

    /** Whether it was marked as a bot's. */
    public val bot: Boolean
        get() = revision.isBot

    /** The change tags on it. */
    public val tags: Array<String>
        get() = revision.tags.toTypedArray()

    /** The hash of its content, which tells two revisions apart without reading them, or `null`. */
    public val sha1: String?
        get() = revision.sha1

    /** Whether who saved it is hidden. */
    public val isUserHidden: Boolean
        get() = revision.isUserHidden

    /** Whether its edit summary is hidden. */
    public val isCommentHidden: Boolean
        get() = revision.isCommentHidden
}

/** An account, or an anonymous user. */
@JsExport
public class User
internal constructor(
    /** The account name, or the address an anonymous user edits from. */
    public val name: String,
    /** The groups the account is in, `"*"` included. */
    public val groups: Array<String>,
    /** The rights the account has. */
    public val rights: Array<String>,
    /** Whether the user is not logged in. */
    public val isAnonymous: Boolean,
)

private fun user(identity: ClientIdentity): User =
    User(
        identity.name,
        identity.groups.sorted().toTypedArray(),
        identity.rights.sorted().toTypedArray(),
        identity.isAnonymous,
    )

private fun user(info: UserInfo): User =
    User(info.name, info.groups.toTypedArray(), info.rights.toTypedArray(), info.isAnonymous)

/** Keeps kotlin-logging from announcing itself on the console, which a JavaScript program did not ask for. */
internal fun withoutLoggingBanner() {
    KotlinLoggingConfiguration.logStartupMessage = false
}

/** [outcome] as the package reports it. */
internal fun editResult(outcome: EditOutcome): EditResult {
    val answer = answerOf(outcome)
    return EditResult(
        answer.status.unsafeCast<EditStatus>(),
        answer.revision?.value?.toDouble(),
        answer.previousRevision?.value?.toDouble(),
        answer.detail,
        answer.refused,
    )
}
