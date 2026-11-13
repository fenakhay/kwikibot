import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.common.enumArgument
import com.fenakhay.kwikibot.bindings.connect.userAgent
import com.fenakhay.kwikibot.bindings.fleet.fleetEndpointOf
import com.fenakhay.kwikibot.bindings.fleet.fleetWithDefaults
import com.fenakhay.kwikibot.bindings.fleet.runOptionsOf
import com.fenakhay.kwikibot.bot.CommonOptions as ClientCommonOptions
import com.fenakhay.kwikibot.client.Family as ClientFamily
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.fleet.Accounts as ClientAccounts
import com.fenakhay.kwikibot.fleet.Bot as FleetBot
import com.fenakhay.kwikibot.fleet.Declined
import com.fenakhay.kwikibot.fleet.Fleet as ClientFleet
import com.fenakhay.kwikibot.fleet.FleetEvent as ClientFleetEvent
import com.fenakhay.kwikibot.fleet.Question as ClientQuestion
import com.fenakhay.kwikibot.fleet.RunContext as ClientRunContext
import com.fenakhay.kwikibot.fleet.RunView
import com.fenakhay.kwikibot.fleet.Schedule as ClientSchedule
import com.fenakhay.kwikibot.fleet.SharedStop
import com.fenakhay.kwikibot.fleet.fleetMain as clientFleetMain
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.environmentVariable
import io.ktor.client.engine.HttpClientEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.coroutines.suspendCancellableCoroutine

/** When `serve` runs a bot. */
@JsExport
public class Schedule internal constructor(internal val schedule: ClientSchedule) {

    /** The first firing after [after]. */
    public fun next(after: Date): Date = dateOf(schedule.next(instantOf(after)))
}

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
    public val run: (context: RunContext) -> Awaitable<Ignored>
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
    public val accounts: Record<String, BotPassword>?

    /** A page of the wiki that has to say `false` for bots to save, read once a minute for all of them. */
    public val stopPage: String?

    /** Answers a run's question, and may return a promise. Without it, the answer is no. */
    public val onAsk: ((bot: String, title: String, summary: String) -> Awaitable<Boolean>)?

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
    /**
     * Aborted the moment the run is cancelled or its fleet closed, for handing to `fetch` and the like, with
     * an `AbortError` as its `reason`. The run does not wait for a bot that ignores it: it ends `"cancelled"`
     * at once and its lane takes the next run, since a promise may never settle, while what the bot is still
     * doing goes on beside that, and every call it makes on this context rejects.
     */
    public val signal: AbortSignal,
) {

    /**
     * The wiki this bot's account edits, shared with every bot on the account. It is the fleet's to close, so
     * its `close` does nothing.
     */
    public val wiki: Wiki = Wiki(context.wiki, scope, null)

    /** What the run was started with, after the flags. */
    public val arguments: Array<String>
        get() = context.arguments.toTypedArray()

    /** Whether the run was told to save. */
    public val save: Boolean
        get() = context.options.save

    /** Where this run may write files, or `null` where there is no filesystem. */
    public val directory: String?
        get() = context.directory?.toString()

    /**
     * Another wiki, as the same account, by language and family name, such as `"en"`, `"wiktionary"`. Its
     * session is shared and the fleet's to close, so its `close` does nothing, and the run goes on.
     */
    public fun open(language: String, family: String): Promise<Wiki> = scope.promise {
        val named = requireNotNull(ClientFamily.named(family)) { "there is no family called '$family'" }
        Wiki(context.open(LangCode(language), named), scope, null)
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
    public fun <T> step(name: String, work: (context: RunContext) -> Awaitable<T>): Promise<T> =
        scope.promise {
            context.step(name) { inner -> settled { work(RunContext(inner, scope, signal)) } }
        }

    /** Parks this lane and asks a person, while other bots keep going. */
    public fun ask(title: String, summary: String = ""): Promise<Boolean> = scope.promise {
        context.ask(ClientQuestion(title, summary))
    }

    /** Parks this lane and asks a person [question], which can show a diff, while other bots keep going. */
    public fun askQuestion(question: Question): Promise<Boolean> = scope.promise {
        context.ask(ClientQuestion(question.title, question.summary.orEmpty(), question.diff))
    }

    /**
     * Says one line about what the run is doing, with how much it matters: `"info"`, `"saved"`, `"skipped"`
     * or `"error"`.
     */
    public fun sayAt(message: String, level: RunLevel) {
        context.say(message, enumArgument<ClientRunContext.Level>("level", level.unsafeCast<String>()))
    }

    /** The fleet's stop page, to give a run as its `stopPolicy`. */
    public val stopPolicy: StopPolicy
        get() = StopPolicy(context.stopPolicy)

    /** The common flags this run was started with. */
    public val options: CommonOptions
        get() = CommonOptions(context.options)

    /** Hand this to a run's `onOutcome`, which counts each page and keeps the heartbeat going. */
    public val outcomes: (outcome: PageOutcome) -> Unit
        get() = { context.outcomes(it.outcome) }

    /**
     * Another wiki, as the same account, in [language] of [family], which may be one of your own. As with
     * `open`, its `close` does nothing, since the session is the fleet's.
     */
    public fun openFamily(language: String, family: Family): Promise<Wiki> = scope.promise {
        Wiki(context.open(LangCode(language), family.family), scope, null)
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
        transform: (text: String, title: String) -> Awaitable<String?>,
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
    public val status: RunStatus
        get() = view.status.name.lowercase().unsafeCast<RunStatus>()

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

    /** Whether the run is over, however it ended. */
    public val isFinished: Boolean
        get() = view.status.isFinished
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
    public val status: RunStatus?
        get() = (event as? ClientFleetEvent.Changed)?.status?.name?.lowercase().unsafeCast<RunStatus?>()

    /** `"said"` for a line, `"changed"` for a change of status. */
    public val kind: FleetEventKind
        get() =
            when (event) {
                is ClientFleetEvent.Said -> "said"
                is ClientFleetEvent.Changed -> "changed"
            }.unsafeCast<FleetEventKind>()

    /** The phases the run was in when it said this, outermost first, or `null` for a change of status. */
    public val steps: Array<String>?
        get() = (event as? ClientFleetEvent.Said)?.steps?.toTypedArray()

    /**
     * How much what the run said matters, `"info"`, `"saved"`, `"skipped"` or `"error"`, or `null` for a
     * change of status.
     */
    public val level: RunLevel?
        get() = (event as? ClientFleetEvent.Said)?.level?.argumentName().unsafeCast<RunLevel?>()
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
    private val accounts: ClientAccounts?,
    private val scope: CoroutineScope,
) {

    /**
     * Queues a run of the bot called [id] in its lane.
     *
     * @param id which bot.
     * @param arguments what to hand it.
     * @param save whether it may save. A dry run unless told.
     * @return what to call the run.
     * @throws IllegalStateException if the fleet is closed, since nothing would ever run it.
     */
    public fun start(id: String, arguments: Array<String> = emptyArray(), save: Boolean = false): Double =
        startRun(id, ClientCommonOptions(save = save), arguments)

    /** Stops a run, and says whether there was such a run still to stop. */
    public fun cancel(run: Double): Boolean = fleet.cancel(run.toLong())

    /** Every run asked for, oldest first. */
    public fun runs(): Array<FleetRun> = fleet.runs.value.map(::FleetRun).toTypedArray()

    /**
     * What is happening, as it happens. A walk sees only what follows its start, and closing the fleet ends
     * it: it finishes as a loop does, rather than rejecting.
     */
    public fun events(): AsyncIterable<FleetEvent> =
        fleet.events.toAsyncIterableEndingWith(scope, ::FleetEvent)

    /** Resolves once every run asked for so far has ended, or the fleet is closed, which ends them all. */
    public fun idle(): Promise<Unit> = untilClosed { fleet.awaitIdle() }

    /** Runs each bot that has a schedule when it comes round, until the fleet is closed. */
    public fun serve(): Promise<Unit> = untilClosed { fleet.serve() }

    /** Stops every run and closes every account. */
    public fun close() {
        scope.cancel()
        accounts?.close()
    }

    /** The bots this fleet runs. */
    public val registry: BotRegistry
        get() = BotRegistry(fleet.registry.all.map { (it as SpecBot).spec }.toTypedArray())

    /**
     * Queues a run of the bot called [id] in its lane, with the common flags and the rest of its command
     * line.
     *
     * @return what to call the run.
     * @throws IllegalStateException if the fleet is closed, since nothing would ever run it.
     */
    public fun startWith(
        id: String,
        options: CommonOptions? = null,
        arguments: Array<String> = emptyArray(),
    ): Double = startRun(id, runOptionsOf(options?.options), arguments)

    private fun startRun(id: String, options: ClientCommonOptions, arguments: Array<String>): Double {
        check(scope.isActive) { "the fleet is closed, so it cannot run '$id'" }
        return fleet.start(id, options, arguments.toList()).toDouble()
    }

    /**
     * Every run, each time one changes, starting with how they are now. Closing the fleet ends a walk, as it
     * does one of `events`.
     */
    public fun watchRuns(): AsyncIterable<Array<FleetRun>> =
        fleet.runs.toAsyncIterableEndingWith(scope) { views -> views.map(::FleetRun).toTypedArray() }

    /**
     * Runs each bot that has a schedule when it comes round, with the common flags, until the fleet is
     * closed.
     */
    public fun serveWith(options: CommonOptions): Promise<Unit> = untilClosed { fleet.serve(options.options) }

    /** [work] until it ends or the fleet is closed, which is how a server ends, so not a failure. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun untilClosed(work: suspend () -> Unit): Promise<Unit> = GlobalScope.promiseVoid {
        try {
            scope.async { work() }.await()
        } catch (_: CancellationException) {
            return@promiseVoid
        }
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
        val endpoint = fleetEndpointOf(options.server, options.scriptPath)
        val accounts =
            ClientAccounts(
                WikiConfig(userAgent = userAgent(options.contact)),
                credentials = { account -> credentialsOf(options, account) },
                clients = { config, login -> WikiClient(config, login, engine = engine) },
            )
        val stop = options.stopPage?.let { page -> SharedStop.onPage(accounts.wiki(endpoint), page) }
        val fleet =
            fleetWithDefaults(
                bots.map(::SpecBot),
                accounts,
                endpoint,
                scope,
                questions = { bot, question ->
                    val ask = options.onAsk
                    ask != null && settledCall { ask(bot, question.title, question.summary) } == true
                },
                out = lineObserver(options.onLine),
                stop = stop,
            )
        Fleet(fleet, accounts, scope)
    }
}

/**
 * Starts a program of many bots from its command line, in Node: `list`, `run <bot>`, `run --all` or `serve`,
 * with `kwikibot.toml` and the common flags. Exits with the status: 0 only if every run ended clean, 1 if one
 * failed, was cancelled or declined, or stopped early, and 2 for a bad command line.
 *
 * @param args the command line, without Node's own two entries.
 * @param bots the bots.
 */
@JsExport
public fun fleetMain(args: Array<String>, bots: Array<BotSpec>) {
    withoutLoggingBanner()
    clientFleetMain(args, bots.map(::SpecBot))
}

/** `fleetMain`, with [prompt] answering a run's question in place of the terminal. */
@JsExport
public fun fleetMainWith(args: Array<String>, bots: Array<BotSpec>, prompt: Prompt) {
    withoutLoggingBanner()
    clientFleetMain(args, bots.map(::SpecBot), prompt.prompt)
}

private fun credentialsOf(options: FleetOptions, account: String?): Credentials {
    if (account == null) {
        return options.login?.credentials
            ?: hideFields(Credentials.fromEnvironment("KWIKIBOT", ::environmentVariable))
    }
    val table: dynamic = options.accounts
    val named: BotPassword? = if (table == null) null else table[account].unsafeCast<BotPassword?>()
    return named?.credentials ?: hideFields(ClientAccounts.namedCredentials(account, ::environmentVariable))
}

/** A bot written in JavaScript, as a fleet runs it. */
internal class SpecBot(val spec: BotSpec) : FleetBot {
    override val id: String
        get() = spec.id

    override val description: String
        get() = spec.description.orEmpty()

    override val account: String?
        get() = spec.account

    override val schedule: ClientSchedule?
        get() = spec.schedule?.schedule

    @Suppress("ThrowsCount", "SwallowedException")
    override suspend fun run(context: ClientRunContext) {
        val job = currentCoroutineContext()[Job]
        job?.ensureActive()
        val calls = SupervisorJob(job)
        val scope = CoroutineScope(currentCoroutineContext() + calls)
        val abort = RunAbort(scope)
        try {
            settledCall { spec.run(RunContext(context, scope, abort.signal)) }
            job?.ensureActive()
        } catch (e: DeclinedError) {
            throw Declined(e.message ?: "declined")
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            throw BotFailure(failure.message ?: failure.toString())
        } finally {
            abort.dispose()
            calls.complete()
        }
    }
}

/**
 * The signal of one run, aborted as soon as [scope] is cancelled. It waits as a child of the scope because a
 * cancellation handler runs at the moment of cancelling, where a job's completion would come only after the
 * run had already given up on its bot, which is too late to say anything to it.
 */
private class RunAbort(scope: CoroutineScope) {
    private val controller: dynamic = js("new AbortController()")
    private var over = false

    val signal: AbortSignal = controller.signal.unsafeCast<AbortSignal>()

    private val waiting =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<Unit> { cancelled ->
                cancelled.invokeOnCancellation { if (!over) abort() }
            }
        }

    private fun abort() {
        controller.abort(js("new DOMException('the run was cancelled', 'AbortError')"))
    }

    /** The run is over, and whatever cancels its scope from here on says nothing to anyone. */
    fun dispose() {
        over = true
        waiting.cancel()
    }
}

/** [onLine] as a fleet's output, which takes each line whatever the callback does with it. */
internal fun lineObserver(onLine: ((line: String) -> Unit)?): (String) -> Unit {
    val report = ObserverReport("onLine")
    return { line -> report.observing { onLine?.invoke(line).unsafeCast<Any?>() } }
}

/**
 * What a bot threw, as an exception: a JavaScript `Error` is not one, and a lane only keeps exceptions to
 * itself.
 */
private class BotFailure(message: String) : Exception(message)
