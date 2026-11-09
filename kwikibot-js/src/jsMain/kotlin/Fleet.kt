import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.fleet.Accounts
import com.fenakhay.kwikibot.fleet.Bot as FleetBot
import com.fenakhay.kwikibot.fleet.Declined
import com.fenakhay.kwikibot.fleet.Fleet as ClientFleet
import com.fenakhay.kwikibot.fleet.FleetEvent as ClientFleetEvent
import com.fenakhay.kwikibot.fleet.Question
import com.fenakhay.kwikibot.fleet.RunContext as ClientRunContext
import com.fenakhay.kwikibot.fleet.RunView
import com.fenakhay.kwikibot.fleet.Schedule as ClientSchedule
import com.fenakhay.kwikibot.fleet.SharedStop
import com.fenakhay.kwikibot.fleet.fleetMain as clientFleetMain
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.environmentVariable
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import io.ktor.client.engine.HttpClientEngine
import kotlin.js.Promise
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.promise

/** When `serve` runs a bot. */
@JsExport public class Schedule internal constructor(internal val schedule: ClientSchedule)

/** A schedule firing every [milliseconds]. */
@JsExport
public fun every(milliseconds: Double): Schedule = Schedule(ClientSchedule.every(milliseconds.milliseconds))

/**
 * A schedule on a five-field cron expression, as [zone] tells the time, such as `"Europe/Berlin"`. A firing
 * during a run waits its turn, and firings missed while nothing was running are not made up.
 */
@JsExport
public fun cron(expression: String, zone: String = "UTC"): Schedule =
    Schedule(ClientSchedule.cron(expression, zone))

/** A bot a fleet can run. */
@JsExport
public external interface BotSpec {
    /** Stable and lowercase. It names the bot in everything a fleet reports. */
    public val id: String

    /** One sentence on what it does. */
    public val description: String?

    /** Which account it edits as, by its name in [FleetOptions.accounts], or the default one. */
    public val account: String?

    /** When `serve` runs it. */
    public val schedule: Schedule?

    /**
     * Does the work, and may return a promise. A rejection fails this run only, and a [DeclinedError] says
     * somebody answered no.
     */
    public val run: (context: RunContext) -> Any?
}

/** What a fleet is opened with. */
@JsExport
public external interface FleetOptions {
    /** The wiki's server, such as `"en.wiktionary.org"`. */
    public val server: String

    /** A URL or email address the wiki's operators can reach you at. */
    public val contact: String

    /** Where `api.php` is under the server, `"/w"` unless given. */
    public val scriptPath: String?

    /** The default account. In Node, without one, it is read from `KWIKIBOT_` in the environment. */
    public val login: BotPassword?

    /**
     * Other accounts by name. In Node, a name not here is read from `KWIKIBOT_<NAME>_` in the environment. A
     * browser has no environment, so there it has to be here.
     */
    public val accounts: dynamic

    /** A page of the wiki that has to say `false` for bots to save, read once a minute for all of them. */
    public val stopPage: String?

    /** Answers a run's question, and may return a promise. Without it, the answer is no. */
    public val onAsk: ((bot: String, title: String, summary: String) -> Any?)?

    /** Takes each line the bots say, prefixed by the bot's id. */
    public val onLine: ((line: String) -> Unit)?
}

/** Thrown by a bot whose question was answered no, which ends its run as declined rather than failed. */
@JsExport public class DeclinedError(message: String) : Throwable(message)

/** What a run has of its fleet, and how it talks back. */
@JsExport
public class RunContext
internal constructor(
    private val context: ClientRunContext,
    private val scope: CoroutineScope,
    /** Aborted when the run is cancelled, for handing to `fetch` and the like. */
    public val signal: dynamic,
) {

    /** The wiki this bot's account edits, shared with every bot on the account. */
    public val wiki: Wiki = Wiki(context.wiki, scope) {}

    /** What the run was started with, after the flags. */
    public val arguments: Array<String>
        get() = context.arguments.toTypedArray()

    /** Whether the run was told to save. */
    public val save: Boolean
        get() = context.options.save

    /** Where this run may write files, or `null` where there is no filesystem. */
    public val directory: String?
        get() = context.directory?.toString()

    /** Another wiki, as the same account, by language and family name, such as `"en"`, `"wiktionary"`. */
    public fun open(language: String, family: String): Promise<Wiki> = scope.promise {
        val named = requireNotNull(Family.named(family)) { "there is no family called '$family'" }
        Wiki(context.open(LangCode(language), named), scope) {}
    }

    /** Says one line about what the run is doing. */
    public fun say(message: String) {
        context.say(message)
    }

    /** Says how much more work there is. */
    public fun expect(items: Int) {
        context.expect(items)
    }

    /** Says the run stopped before doing all it set out to. The first reason given is the one kept. */
    public fun stopped(reason: String) {
        context.stopped(reason)
    }

    /** Runs [work] as a named phase of this run. */
    public fun step(name: String, work: (context: RunContext) -> Any?): Promise<Any?> = scope.promise {
        context.step(name) { inner -> settled(work(RunContext(inner, scope, signal))) }
    }

    /** Parks this lane and asks a person, while other bots keep going. */
    public fun ask(title: String, summary: String = ""): Promise<Boolean> = scope.promise {
        context.ask(Question(title, summary))
    }

    /** Whether the run may go on. A stop page that cannot be read answers `false`. */
    public fun mayContinue(): Promise<Boolean> = scope.promise { context.mayContinue() }

    /**
     * Runs over [titles], as `Wiki.run` does, under this fleet's stop page and counted against this run.
     *
     * @param titles the pages.
     * @param summary the edit summary.
     * @param transform takes a page's text and title, and returns its new text, or `null` to leave it alone.
     * @param maxEdits how many edits to save at most.
     */
    public fun run(
        titles: Array<String>,
        summary: String,
        transform: (text: String, title: String) -> Any?,
        maxEdits: Int? = null,
    ): Promise<RunReport> = scope.promise {
        runBot(
            context.wiki,
            titles,
            summary,
            transform,
            context.options.save,
            onChange = null,
            stopPolicy = context.stopPolicy,
            maxEdits = maxEdits,
            onOutcome = context.outcomes,
        )
    }
}

/** One run, as a fleet sees it. */
@JsExport
public class FleetRun internal constructor(private val view: RunView) {

    /** What the fleet calls it. */
    public val id: Double
        get() = view.id.toDouble()

    /** Whose it is. */
    public val bot: String
        get() = view.botId

    /** `"queued"`, `"running"`, `"waiting"`, `"done"`, `"failed"`, `"cancelled"` or `"declined"`. */
    public val status: String
        get() = view.status.name.lowercase()

    /** How many items it said it had to do. */
    public val expected: Int
        get() = view.expected

    /** How many pages it has handled. */
    public val seen: Int
        get() = view.seen

    /** How many of them it saved. */
    public val saved: Int
        get() = view.saved

    /** Why it stopped before doing everything, if it did. */
    public val stoppedBecause: String?
        get() = view.stoppedBecause

    /** What went wrong, if something did. */
    public val failure: String?
        get() = view.failure
}

/** Something a fleet did: a run said a line, or changed status. */
@JsExport
public class FleetEvent internal constructor(private val event: ClientFleetEvent) {

    /** Which run it is about. */
    public val run: Double
        get() = event.runId.toDouble()

    /** Whose run that is. */
    public val bot: String
        get() = event.botId

    /** What the run said, or `null` for a change of status. */
    public val message: String?
        get() = (event as? ClientFleetEvent.Said)?.message

    /** What the run's status became, or `null` for something said. */
    public val status: String?
        get() = (event as? ClientFleetEvent.Changed)?.status?.name?.lowercase()
}

/**
 * Many bots in one process, one lane each: bots run at the same time as each other, and one at a time within
 * a bot. What goes wrong in a run stays in that run. JavaScript has one thread, so lanes take turns at each
 * `await`, and a transform that blocks holds them all.
 */
@JsExport
public class Fleet
internal constructor(
    private val fleet: ClientFleet,
    private val accounts: Accounts,
    private val scope: CoroutineScope,
) {

    /**
     * Queues a run of the bot called [id] in its lane.
     *
     * @param id which bot.
     * @param arguments what to hand it.
     * @param save whether it may save. A dry run unless told.
     * @return what to call the run.
     */
    public fun start(id: String, arguments: Array<String> = emptyArray(), save: Boolean = false): Double =
        fleet.start(id, CommonOptions(save = save), arguments.toList()).toDouble()

    /** Stops a run, and says whether there was such a run still to stop. */
    public fun cancel(run: Double): Boolean = fleet.cancel(run.toLong())

    /** Every run asked for, oldest first. */
    public fun runs(): Array<FleetRun> = fleet.runs.value.map(::FleetRun).toTypedArray()

    /** What is happening, as it happens. A walk sees only what follows its start. */
    public fun events(): AsyncIterable<FleetEvent> = fleet.events.toAsyncIterable(scope, ::FleetEvent)

    /** Resolves once every run asked for so far has ended. */
    public fun idle(): Promise<Unit> = scope.promise { fleet.awaitIdle() }

    /** Runs each bot that has a schedule when it comes round, until the fleet is closed. */
    public fun serve(): Promise<Unit> = scope.promise { fleet.serve() }

    /** Stops every run and closes every account. */
    public fun close() {
        scope.cancel()
        accounts.close()
    }
}

/**
 * Opens a fleet of [bots] on one wiki. Bots on one account share one login and one budget.
 *
 * @param options the wiki, and how to reach it.
 * @param bots the bots.
 */
@JsExport
public fun createFleet(options: FleetOptions, bots: Array<BotSpec>): Promise<Fleet> =
    createFleet(options, bots, engine = null)

/** [createFleet], through [engine], or the platform's own when it is `null`. */
internal fun createFleet(
    options: FleetOptions,
    bots: Array<BotSpec>,
    engine: HttpClientEngine?,
): Promise<Fleet> {
    withoutLoggingBanner()
    val scope = CoroutineScope(SupervisorJob())
    return scope.promise {
        val endpoint = ApiEndpoint(options.server, options.scriptPath ?: DEFAULT_FLEET_SCRIPT_PATH)
        val accounts =
            Accounts(
                WikiConfig(userAgent = userAgent(options.contact)),
                credentials = { account -> credentialsOf(options, account) },
                clients = { config, login -> WikiClient(config, login, engine = engine) },
            )
        val stop =
            options.stopPage?.let { page -> SharedStop.onPage(accounts.wiki(endpoint), page) }
                ?: StopPolicy.NONE
        val fleet =
            ClientFleet(
                bots.map(::SpecBot),
                accounts,
                endpoint,
                stop,
                scope,
                questions = { bot, question ->
                    val ask = options.onAsk
                    ask != null && settled(ask(bot, question.title, question.summary)) == true
                },
                out = { line -> options.onLine?.invoke(line) },
            )
        Fleet(fleet, accounts, scope)
    }
}

/**
 * Starts a program of many bots from its command line, in Node: `list`, `run <bot>`, `run --all` or `serve`,
 * with `kwikibot.toml` and the common flags. Exits with the status: 0 only if every run finished.
 *
 * @param args the command line, without Node's own two entries.
 * @param bots the bots.
 */
@JsExport
public fun fleetMain(args: Array<String>, bots: Array<BotSpec>) {
    withoutLoggingBanner()
    clientFleetMain(args, bots.map(::SpecBot))
}

private const val DEFAULT_FLEET_SCRIPT_PATH = "/w"

private fun credentialsOf(options: FleetOptions, account: String?): Credentials {
    if (account == null) {
        return options.login?.credentials ?: Credentials.fromEnvironment("KWIKIBOT", ::environmentVariable)
    }
    val table: dynamic = options.accounts
    val named: BotPassword? = if (table == null) null else table[account].unsafeCast<BotPassword?>()
    return named?.credentials
        ?: Credentials.fromEnvironment("KWIKIBOT_${account.uppercase()}", ::environmentVariable)
}

private class SpecBot(private val spec: BotSpec) : FleetBot {
    override val id: String
        get() = spec.id

    override val description: String
        get() = spec.description.orEmpty()

    override val account: String?
        get() = spec.account

    override val schedule: ClientSchedule?
        get() = spec.schedule?.schedule

    override suspend fun run(context: ClientRunContext) {
        val controller = js("new AbortController()")
        val job = currentCoroutineContext()[Job]
        val listening = job?.invokeOnCompletion { cause -> if (cause != null) controller.abort() }
        val scope = CoroutineScope(currentCoroutineContext())
        try {
            settled(spec.run(RunContext(context, scope, controller.signal)))
        } catch (e: DeclinedError) {
            throw Declined(e.message ?: "declined")
        } finally {
            listening?.dispose()
        }
    }
}

private suspend fun settled(result: Any?): Any? = if (result is Promise<*>) result.await() else result
