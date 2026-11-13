import com.fenakhay.kwikibot.bindings.fleet.fleetWithDefaults
import com.fenakhay.kwikibot.bindings.fleet.recordingContextWithDefaults
import com.fenakhay.kwikibot.client.WikiClient as ClientWikiClient
import com.fenakhay.kwikibot.fleet.Accounts as ClientAccounts
import com.fenakhay.kwikibot.fleet.BotRegistry as ClientBotRegistry
import com.fenakhay.kwikibot.fleet.Question as ClientQuestion
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.environmentVariable
import io.ktor.client.engine.HttpClientEngine
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.io.files.Path

/** The bots a fleet runs, by id. Throws when two of them have the same id. */
@JsExport
public class BotRegistry(bots: Array<BotSpec>) {
    private val registry = ClientBotRegistry(bots.map(::SpecBot))

    /** Every bot, in the order given. */
    public val all: Array<BotSpec>
        get() = registry.all.map { (it as SpecBot).spec }.toTypedArray()

    /** The bot called [id], or `null`. */
    public fun find(id: String): BotSpec? = (registry.find(id) as SpecBot?)?.spec

    /** The bot called [id], or a failure naming those there are. */
    public fun get(id: String): BotSpec = (registry.get(id) as SpecBot).spec
}

/** Something a run stopped to ask a person. */
@JsExport
public external interface Question {
    /** The question itself. */
    public val title: String

    /** What saying yes does, and what saying no does. */
    public val summary: String?

    /** What is about to change, as a unified diff, when there is one to show. */
    public val diff: String?
}

private fun questionOf(question: ClientQuestion): Question {
    val plain: dynamic = js("({})")
    plain.title = question.title
    plain.summary = question.summary
    plain.diff = question.diff
    return plain.unsafeCast<Question>()
}

/**
 * The wikis a fleet edits through, one session per account and wiki, so bots on one account share one login
 * and one budget. A wrong password shows up when a session opens, so it fails the bot pointed at it, not the
 * fleet. Close it when done.
 */
@JsExport
@OptIn(DelicateCoroutinesApi::class)
public class Accounts internal constructor(internal val accounts: ClientAccounts) {

    /**
     * The wiki at [endpoint], as [account], or the default one, logging in if this is the first time it is
     * wanted. Closing the accounts closes it.
     */
    public fun wiki(endpoint: Endpoint, account: String? = null): Promise<Wiki> = GlobalScope.promise {
        Wiki(accounts.wiki(endpoint.endpoint, account), CoroutineScope(SupervisorJob())) {}
    }

    /** Closes every session. */
    public fun close() {
        accounts.close()
    }
}

/**
 * Makes the wikis a fleet edits through.
 *
 * @param contact a URL or email address the wiki's operators can reach you at.
 * @param options the transport settings every account shares. Its `login` is the default account, and its
 *   `scriptPath` and `secure` are ignored, since each wiki comes with an `Endpoint`.
 * @param credentials gives an account's login by name, or `null` for the default account, and `null` for an
 *   anonymous session. Unless given, in Node, an account not otherwise known is read from the environment,
 *   under `KWIKIBOT_` and `KWIKIBOT_<NAME>_`.
 */
@JsExport
public fun createAccounts(
    contact: String,
    options: ConnectOptions? = null,
    credentials: ((account: String?) -> BotPassword?)? = null,
): Accounts = createAccounts(contact, options, credentials, engine = null)

/** [createAccounts], through [engine], or the platform's own when it is `null`. */
internal fun createAccounts(
    contact: String,
    options: ConnectOptions?,
    credentials: ((account: String?) -> BotPassword?)?,
    engine: HttpClientEngine?,
): Accounts {
    withoutLoggingBanner()
    return Accounts(
        ClientAccounts(
            configOf(contact, options),
            credentials = { account ->
                val login = if (account == null) options?.login else null
                login?.credentials
                    ?: if (credentials != null) {
                        fromJs { credentials(account) }?.credentials ?: Credentials.Anonymous
                    } else if (account == null) {
                        hideFields(Credentials.fromEnvironment("KWIKIBOT", ::environmentVariable))
                    } else {
                        hideFields(ClientAccounts.namedCredentials(account, ::environmentVariable))
                    }
            },
            clients = { config, login -> ClientWikiClient(config, login, engine = engine) },
        )
    )
}

/** What a fleet is opened with by `createFleetWith`, which is everything a fleet can be told. */
@JsExport
public external interface FleetHostOptions {
    /** Where the bots' wikis come from. Closing the fleet leaves them open. */
    public val accounts: Accounts

    /** Which wiki they edit. */
    public val endpoint: Endpoint

    /** What every run answers to; nothing unless given. */
    public val stop: StopPolicy?

    /** Where runs may write files, each under `<bot>/<run>`. Nowhere unless given. Node only. */
    public val directory: String?

    /** Who answers a run's question, and may return a promise. Without it, the answer is no. */
    public val onQuestion: ((bot: String, question: Question) -> Awaitable<Boolean>)?

    /** What the time is, for schedules; the system's clock unless given. */
    public val now: (() -> Date)?

    /** Takes each line the bots say, prefixed by the bot's id. */
    public val onLine: ((line: String) -> Unit)?

    /**
     * How often a run that hands pages to `outcomes` says how far it has got, in milliseconds; 30 seconds
     * unless given.
     */
    public val heartbeat: Double?
}

/**
 * Opens a fleet of [bots], with everything a fleet can be told. Unlike `createFleet`, it shares [options]'s
 * `accounts` with whatever else uses them.
 */
@JsExport
public fun createFleetWith(options: FleetHostOptions, bots: Array<BotSpec>): Fleet {
    withoutLoggingBanner()
    if (options.directory != null) requireFileSystem("a directory for runs")
    val scope = CoroutineScope(SupervisorJob())
    val fleet =
        fleetWithDefaults(
            bots.map(::SpecBot),
            options.accounts.accounts,
            options.endpoint.endpoint,
            scope,
            questions = { bot, question ->
                val ask = options.onQuestion
                ask != null && settledCall { ask(bot, questionOf(question)) } == true
            },
            out = lineObserver(options.onLine),
            stop = options.stop?.policy,
            directory = options.directory?.let(::Path),
            clock = options.now?.let(::clockOf),
            heartbeat = options.heartbeat?.milliseconds,
        )
    return Fleet(fleet, null, scope)
}

/** How a recording context behaves. */
@JsExport
public external interface RecordingOptions {
    /** What the bot answers to; nothing unless given. */
    public val stopPolicy: StopPolicy?

    /** The common flags to give it. */
    public val options: CommonOptions?

    /** The rest of its command line. */
    public val arguments: Array<String>?

    /** Where it may write files. */
    public val directory: String?

    /** What to say to a question; no unless given. */
    public val answer: ((question: Question) -> Boolean)?
}

/**
 * A `RunContext` for testing a bot with no fleet behind it: it writes down what the bot says, and answers as
 * told. `open` gives back another handle on the same wiki, and `step` runs in the same context.
 */
@JsExport
public class RecordingContext(wiki: Wiki, options: RecordingOptions? = null) {
    private val recording =
        recordingContextWithDefaults(
            wiki.wiki,
            { question -> fromJs { options?.answer?.invoke(questionOf(question)) } == true },
            options?.stopPolicy?.policy,
            options?.options?.options,
            options?.arguments?.asList(),
            options?.directory?.let(::Path),
        )

    private val namespaces = wiki.wiki.namespaces

    /** What to hand the bot. */
    public val context: RunContext = RunContext(recording, wiki.scope, js("new AbortController()").signal)

    /** Everything said, in order. */
    public val said: Array<String>
        get() = recording.said.toTypedArray()

    /** Everything asked, in order. */
    public val asked: Array<Question>
        get() = recording.asked.map(::questionOf).toTypedArray()

    /** Every page handed to `outcomes`, in order. */
    public val pages: Array<PageOutcome>
        get() = recording.pages.map { PageOutcome(it, namespaces) }.toTypedArray()

    /** The total of what was passed to `expect`. */
    public val expected: Int
        get() = recording.expected

    /** The first reason passed to `stopped`. */
    public val stoppedBecause: String?
        get() = recording.stoppedBecause
}
