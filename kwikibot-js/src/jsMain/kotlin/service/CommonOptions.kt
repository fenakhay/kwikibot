import com.fenakhay.kwikibot.bindings.bot.commonOptionsOf
import com.fenakhay.kwikibot.bot.BotStart as ClientBotStart
import com.fenakhay.kwikibot.bot.CommonOptions as ClientCommonOptions
import com.fenakhay.kwikibot.bot.botMain as clientBotMain
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.files.Path

/** The flags most bots take, as a TypeScript author sets them. */
@JsExport
public external interface CommonOptionsInit {
    /** Whether to save edits. A run is a dry run without it. */
    public val save: Boolean?

    /** How many pages to work through at most. */
    public val limit: Int?

    /** Where to write diffs. */
    public val diffLog: String?

    /** Where to write the pages left alone. */
    public val skipLog: String?

    /** The page that stops the bot when it says anything but `false`. */
    public val stopPage: String?

    /** The least time between reads, in milliseconds. */
    public val readDelay: Double?

    /** The least time between writes, in milliseconds. */
    public val writeDelay: Double?

    /** How many requests may be in flight at once. */
    public val httpConnections: Int?

    /** Where an operator can be reached, for the user agent. */
    public val contact: String?

    /** Where the run records what it has done. */
    public val state: String?

    /** Whether to carry on from where a run into [state] stopped. Needs [state]. */
    public val resume: Boolean?

    /** Whether to save as an account outside the bot group, for a trial. Needs [save] and [limit]. */
    public val trial: Boolean?
}

/**
 * The flags most bots take: whether to save, how far to go, where to log and which page stops the bot. Give
 * them to a run as its `common`.
 */
@JsExport
public class CommonOptions internal constructor(internal val options: ClientCommonOptions) {

    /** Whether to save edits, `--save`. */
    public val save: Boolean
        get() = options.save

    /** How many pages to work through at most, `--limit`. */
    public val limit: Int?
        get() = options.limit

    /** Where to write diffs, `--diff-log`. */
    public val diffLog: String?
        get() = options.diffLog?.toString()

    /** Where to write the pages left alone, `--skip-log`. */
    public val skipLog: String?
        get() = options.skipLog?.toString()

    /** The page that stops the bot when it says anything but `false`, `--stop-page`. */
    public val stopPage: String?
        get() = options.stopPage

    /** The least time between reads in milliseconds, `--read-delay`. */
    public val readDelay: Double?
        get() = options.readDelay?.inWholeMilliseconds?.toDouble()

    /** The least time between writes in milliseconds, `--write-delay`. */
    public val writeDelay: Double?
        get() = options.writeDelay?.inWholeMilliseconds?.toDouble()

    /** How many requests may be in flight at once, `--http-connections`. */
    public val httpConnections: Int?
        get() = options.httpConnections

    /** Where an operator can be reached, for the user agent, `--contact`. */
    public val contact: String?
        get() = options.contact

    /** Where the run records what it has done, `--state`. */
    public val state: String?
        get() = options.state?.toString()

    /** Whether to carry on from where a run into [state] stopped, `--resume`. */
    public val resume: Boolean
        get() = options.resume

    /** Whether to save as an account outside the bot group, for a trial, `--trial`. */
    public val trial: Boolean
        get() = options.trial

    /**
     * [base] with what these flags change about a client: its pace, its connections and its contact. A
     * contact is applied to [base]'s `userAgent`; with none there, give [contact] to `connectWith`.
     */
    public fun connectOptions(base: ConnectOptions? = null): ConnectOptions {
        val merged: dynamic = js("Object.assign")(js("({})"), base)
        if (options.readDelay != null || options.writeDelay != null) {
            val throttle: dynamic = js("({})")
            throttle.read = readDelay ?: base?.throttle?.read
            throttle.write = writeDelay ?: base?.throttle?.write
            merged.throttle = throttle
        }
        options.httpConnections?.let { connections ->
            val http: dynamic = js("Object.assign")(js("({})"), base?.http)
            http.maxConcurrentRequests = connections
            http.maxIdleConnections = connections
            merged.http = http
        }
        val agent = base?.userAgent
        val contact = options.contact
        if (agent != null && contact != null) {
            merged.userAgent = UserAgent(agent.tool, agent.version, contact)
        }
        return merged.unsafeCast<ConnectOptions>()
    }

    /**
     * The stop policy these flags ask for: the page `--stop-page` names, or none. One told to save with none
     * logs a warning, since it cannot be stopped from the wiki.
     */
    public fun stopPolicy(wiki: Wiki): StopPolicy = StopPolicy(options.stopPolicy(wiki.wiki))

    /** A log writing diffs to [diffLog] and skips to [skipLog], added to whatever is already in them. */
    public fun runLog(wiki: Wiki): RunLog {
        requireFileSystem(
            "a run's log",
            options.diffLog == null && options.skipLog == null || hasFileSystem(),
        )
        return RunLog(options.runLog(wiki.wiki))
    }
}

/**
 * Makes the common flags from [init].
 *
 * @throws IllegalArgumentException when `resume` has no `state`, or `trial` has no `save` and `limit`.
 */
@JsExport
public fun createCommonOptions(init: CommonOptionsInit? = null): CommonOptions =
    CommonOptions(
        commonOptionsOf(
            init?.save,
            init?.limit,
            init?.diffLog?.let(::Path),
            init?.skipLog?.let(::Path),
            init?.stopPage,
            init?.readDelay?.milliseconds,
            init?.writeDelay?.milliseconds,
            init?.httpConnections,
            init?.contact,
            init?.state?.let(::Path),
            init?.resume,
            init?.trial,
        )
    )

/** The common flags read off a command line, and what they left. */
@JsExport
public class ParsedCommandLine
internal constructor(
    /** The common flags. */
    public val options: CommonOptions,
    /** Everything else, in the order it came, for the bot to read itself. */
    public val rest: Array<String>,
)

/** The flags, one per line, for a bot to print in its own help. */
@JsExport public val COMMON_OPTIONS_USAGE: String = ClientCommonOptions.usage

/**
 * Reads the common flags out of [args], and hands back everything else. A flag's value may follow it or be
 * joined to it with `=`, and everything after `--` is handed back untouched.
 *
 * @throws IllegalArgumentException naming the flag, when one is missing its value or has a bad one.
 */
@JsExport
public fun parseCommonOptions(args: Array<String>): ParsedCommandLine {
    val (options, rest) = ClientCommonOptions.parse(args)
    return ParsedCommandLine(CommonOptions(options), rest.toTypedArray())
}

/** What `botMain` hands to a bot. */
@JsExport
public class BotStart internal constructor(start: ClientBotStart) {

    /**
     * The wiki `kwikibot.toml` names, opened as its account. `botMain` closes it, so its `close` does
     * nothing.
     */
    public val wiki: Wiki = Wiki(start.wiki, CoroutineScope(SupervisorJob()), null)

    /** The common flags. */
    public val options: CommonOptions = CommonOptions(start.options)

    /** What the common flags did not take, in order, for the bot to read itself. */
    public val arguments: Array<String> = start.arguments.toTypedArray()

    /** The configuration the bot started from. */
    public val config: BotConfig = BotConfig(start.config)
}

/**
 * Starts a bot, in Node: reads the common flags and `kwikibot.toml`, logs in, checks the account, runs
 * [block] with the wiki, and exits with the status [block] answers, a number or a promise of one, or 0 for
 * neither. Returns at once.
 *
 * With `--save`, [block] runs only if the account is in the bot group, or `--trial` says it is saving the
 * trial edits a wiki asks for first. When the bot cannot start, the reason is one line on standard error and
 * the exit status is 1, or 2 for a bad flag. A failure [block] lets through is printed and also ends with
 * status 1.
 *
 * @param args the command line, without Node's own two entries.
 * @param block the bot.
 * @param anonymous whether the bot may run without logging in, when `kwikibot.toml` names no login. A bot
 *   that saves still may not.
 */
@JsExport
public fun botMain(
    args: Array<String>,
    block: (start: BotStart) -> Awaitable<ExitStatus>,
    anonymous: Boolean = false,
) {
    withoutLoggingBanner()
    clientBotMain(args, anonymous) { statusOf(settledCall { block(BotStart(this)) }) }
}

private fun statusOf(answer: Any?): Int {
    if (answer == null) return 0
    require(jsTypeOf(answer) == "number") { "a bot answers with its exit status, not '$answer'" }
    return answer.unsafeCast<Int>()
}
