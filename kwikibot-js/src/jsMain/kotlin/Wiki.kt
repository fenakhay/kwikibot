import com.fenakhay.kwikibot.client.ApiDetector
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.client.signatures
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.Revision as ClientRevision
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.user.UserInfo
import com.fenakhay.kwikibot.net.Kwikibot
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.client.engine.HttpClientEngine
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
public class BotPassword(
    /** The account the bot password belongs to. */
    public val account: String,
    /** The bot password's name. */
    public val botName: String,
    private val password: String,
) {
    internal val credentials: Credentials
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
 * `null` when it advertises none.
 */
@JsExport
public fun detect(url: String, contact: String): Promise<Endpoint?> = detect(url, contact, engine = null)

/** [detect], through [engine], or the platform's own when it is `null`. */
@OptIn(DelicateCoroutinesApi::class)
internal fun detect(url: String, contact: String, engine: HttpClientEngine?): Promise<Endpoint?> =
    GlobalScope.promise {
        withoutLoggingBanner()
        WikiHttpClient.create(engine = engine).use { client ->
            ApiDetector.detect(url, client, userAgent(contact))?.let(::Endpoint)
        }
    }

/** Where a wiki's API is. */
@JsExport
public class Endpoint internal constructor(private val endpoint: ApiEndpoint) {

    /** The URL of `api.php`. */
    public val apiUrl: String
        get() = endpoint.apiUrl

    /** The host the wiki is on, such as `"en.wiktionary.org"`. */
    public val server: String
        get() = endpoint.server

    /** Where `api.php` is under the server, such as `"/w"`, or `""` when it is at the root. */
    public val scriptPath: String
        get() = endpoint.scriptPath
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
        val outcome =
            wiki.pages.edit(wiki.ref(title)) {
                this.text = text
                this.summary = summary
                baseRevision?.let { this.baseRevision = RevisionId(it.toLong()) }
                this.minor = minor
            }
        editResult(outcome)
    }

    /** The titles in [category], which needs no `Category:` prefix. */
    public fun categoryMembers(category: String): AsyncIterable<String> =
        wiki.lists.categoryMembers(wiki.ref(category, Namespace.CATEGORY)).toAsyncIterable(scope, ::titleOf)

    /** The titles of the pages a search for [query] finds, best match first. */
    public fun search(query: String): AsyncIterable<String> =
        wiki.lists.search(query).toAsyncIterable(scope, ::titleOf)

    /** The titles of the pages that transclude [template], which needs no `Template:` prefix. */
    public fun embeddedIn(template: String): AsyncIterable<String> =
        wiki.lists.transclusions(wiki.ref(template, Namespace.TEMPLATE)).toAsyncIterable(scope, ::titleOf)

    /** The revisions of [title], newest first. */
    public fun history(title: String): AsyncIterable<Revision> =
        wiki.revisions.history(wiki.ref(title)).toAsyncIterable(scope, ::Revision)

    /** The times of the signatures in [text], read in this wiki's time zone and with its month names. */
    public fun signatureTimes(text: String): Promise<Array<SignatureTime>> = scope.promise {
        wiki.signatures().findAll(text).map(::SignatureTime).toTypedArray()
    }

    /** The Wikibase entity [id], such as `"Q42"`, `"P31"` or `"L7"`, or `null` when there is none. */
    public fun entity(id: String): Promise<Entity?> = scope.promise { entityOf(wiki, id) }

    /**
     * Runs a bot over [titles]: reads each page, gives its text and title to [transform], and saves the text
     * that comes back with [summary]. The transform returns `null` to leave a page alone, and may return a
     * promise of either.
     *
     * A dry run, which only computes each edit, unless [save] is `true`. [onChange] is called with each
     * change as the run computes it, or saves it when it saves. A page the wiki refuses, or cannot serve, is
     * a problem in the report; a transform that throws, or answers with anything else, stops the run, which
     * rejects with that error.
     */
    public fun run(
        titles: Array<String>,
        summary: String,
        transform: (text: String, title: String) -> Any?,
        save: Boolean = false,
        onChange: ((change: Change) -> Unit)? = null,
    ): Promise<RunReport> = scope.promise { runBot(wiki, titles, summary, transform, save, onChange) }

    /** Ends what this wiki is still fetching and releases its connections. */
    public fun close() {
        scope.cancel()
        onClose()
    }

    private fun titleOf(ref: PageRef): String = wiki.namespaces.format(ref.title)
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
    public val status: String,
    /** The revision saved, or the current one when nothing changed; `null` when refused. */
    public val revision: Double?,
    /** The revision the save replaced, or `null`. */
    public val previousRevision: Double?,
    /** What the wiki said when it refused, or `null`. */
    public val detail: String?,
)

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

private fun user(identity: Identity): User =
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

/** How this library identifies itself, for the operator [contact] reaches. */
internal fun userAgent(contact: String): UserAgent = UserAgent(Kwikibot.NAME, Kwikibot.version, contact)

private fun editResult(outcome: EditOutcome): EditResult =
    when (outcome) {
        is EditOutcome.Saved ->
            EditResult(
                "saved",
                outcome.revision.value.toDouble(),
                outcome.previousRevision?.value?.toDouble(),
                null,
            )
        is EditOutcome.NoChange -> EditResult("unchanged", outcome.revision?.value?.toDouble(), null, null)
        is EditOutcome.Refused -> EditResult(refusal(outcome), null, null, outcome.detail)
    }

private fun refusal(outcome: EditOutcome.Refused): String =
    when (outcome) {
        is EditOutcome.Conflict -> "conflict"
        is EditOutcome.Protected -> "protected"
        is EditOutcome.PermissionDenied -> "permission-denied"
        is EditOutcome.RateLimited -> "rate-limited"
        is EditOutcome.Filtered -> "filtered"
        is EditOutcome.CaptchaRequired -> "captcha"
        is EditOutcome.PageStateChanged -> "page-changed"
        is EditOutcome.Rejected -> "rejected"
    }
