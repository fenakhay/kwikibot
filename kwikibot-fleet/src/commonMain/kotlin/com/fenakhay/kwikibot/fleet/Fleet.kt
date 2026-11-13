package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

/** Where a run has got to. */
public enum class RunStatus {
    /** Waiting for its bot's lane. */
    QUEUED,

    /** Being worked on. */
    RUNNING,

    /** Stopped until somebody answers a question. */
    WAITING,

    /** Its work returned. Check [RunView.stoppedBecause], since it may have stopped before doing it all. */
    DONE,

    /** Stopped by something going wrong. */
    FAILED,

    /** Stopped because somebody asked it to stop. */
    CANCELLED,

    /** Stopped because somebody said no, which is an answer rather than a fault. */
    DECLINED;

    /** Whether the run is over, however it ended. */
    public val isFinished: Boolean
        get() = this == DONE || this == FAILED || this == CANCELLED || this == DECLINED
}

/**
 * One run, as a fleet sees it.
 *
 * @param id what the fleet calls it.
 * @param botId whose it is.
 * @param status where it has got to.
 * @param expected how many items it said it had to do.
 * @param seen how many pages it has handled.
 * @param saved how many of them it saved.
 * @param stoppedBecause why it stopped before doing everything, if it did.
 * @param failure what went wrong, if something did.
 */
public data class RunView(
    val id: Long,
    val botId: String,
    val status: RunStatus,
    val expected: Int = 0,
    val seen: Int = 0,
    val saved: Int = 0,
    val stoppedBecause: String? = null,
    val failure: String? = null,
)

/** Something a host may want to show as it happens. */
public sealed interface FleetEvent {
    /** Which run it is about. */
    public val runId: Long

    /** Which bot's run that is. */
    public val botId: String

    /** A run said something. */
    public data class Said(
        override val runId: Long,
        override val botId: String,
        /** The phases the run is in, outermost first. */
        val steps: List<String>,
        /** What it said. */
        val message: String,
        /** How much it matters. */
        val level: RunContext.Level,
    ) : FleetEvent

    /** A run changed status. */
    public data class Changed(
        override val runId: Long,
        override val botId: String,
        /** Where it has got to. */
        val status: RunStatus,
    ) : FleetEvent
}

/**
 * Runs many bots in one process.
 *
 * One lane per bot: bots run at the same time as each other, and one at a time within a bot, since a bot's
 * runs would contend for its own state and gain nothing. What goes wrong in a run stays in that run, and its
 * lane takes the next. Bots on one account share one session through [Accounts], and all of them answer to
 * one [SharedStop].
 *
 * A host: it knows nothing of terminals, databases or web pages. [runs] and [events] are what one is built
 * on. Nothing is kept once the process ends.
 *
 * Its methods may be called from any thread, and [scope] may be on any dispatcher.
 *
 * @param bots the bots.
 * @param accounts where their wikis come from.
 * @param endpoint which wiki they edit.
 * @param stop what every run answers to.
 * @param scope where the lanes run; closing the fleet means cancelling it, which ends every run still to end
 *   as [RunStatus.CANCELLED].
 * @param directory where runs may write files, or `null` where there is no filesystem.
 * @param questions who answers a run's question. No one, by default, so the answer is no.
 * @param clock what the time is, for schedules.
 * @param out where each line goes. Lines start with the bot's id, since lanes share the output.
 * @param heartbeat how often a run that hands pages to [RunContext.outcomes] says how far it has got.
 */
@OptIn(ExperimentalAtomicApi::class)
public class Fleet(
    bots: List<Bot>,
    private val accounts: Accounts,
    private val endpoint: ApiEndpoint,
    private val stop: StopPolicy,
    private val scope: CoroutineScope,
    private val directory: Path? = null,
    private val questions: suspend (String, Question) -> Boolean = { _, _ -> false },
    private val clock: Clock = Clock.System,
    private val out: (String) -> Unit = {},
    private val heartbeat: Duration = 30.seconds,
) {

    /** The bots this fleet runs. */
    public val registry: BotRegistry = BotRegistry(bots)

    private val state = MutableStateFlow<List<RunView>>(emptyList())
    private val happening = MutableSharedFlow<FleetEvent>(extraBufferCapacity = EVENT_BUFFER)
    private val jobs = MutableStateFlow<Map<Long, Job>>(emptyMap())
    private val nextId = AtomicLong(1L)

    private val lanes = bots.associate { it.id to Channel<Job>(Channel.UNLIMITED) }

    init {
        bots.forEach { bot -> scope.launch { work(lanes.getValue(bot.id)) } }
    }

    /** Every run this fleet has been asked for, oldest first. */
    public val runs: StateFlow<List<RunView>> = state

    /** What is happening, as it happens. A collector sees only what follows its collecting. */
    public val events: SharedFlow<FleetEvent> = happening

    /**
     * Queues a run of the bot called [id] in its lane.
     *
     * @param id which bot.
     * @param options the common flags to give it.
     * @param arguments the rest of its command line.
     * @return what to call the run.
     */
    public fun start(
        id: String,
        options: CommonOptions = CommonOptions(),
        arguments: List<String> = emptyList(),
    ): Long {
        val bot = registry.get(id)
        val runId = nextId.fetchAndAdd(1L)
        val run = QueuedRun(runId, options, arguments)
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(bot, run) }
        jobs.update { it + (runId to job) }
        state.update { it + RunView(runId, bot.id, RunStatus.QUEUED) }
        job.invokeOnCompletion { cause -> ended(runId, bot.id, cause) }
        val _ = lanes.getValue(bot.id).trySend(job)
        return runId
    }

    /**
     * Stops a run: one in its lane stops at once, and one being worked on at its next chance.
     *
     * @return whether there was such a run still to stop.
     */
    public fun cancel(runId: Long): Boolean {
        val run = state.value.firstOrNull { it.id == runId } ?: return false
        if (run.status.isFinished) return false
        jobs.value[runId]?.cancel()
        return true
    }

    /** Waits until every run asked for so far has ended. */
    public suspend fun awaitIdle() {
        val _ = state.first { views -> views.all { it.status.isFinished } }
    }

    /** Runs each bot that has a schedule when it comes round, until the calling coroutine is cancelled. */
    public suspend fun serve(options: CommonOptions = CommonOptions()): Unit = coroutineScope {
        registry.all.forEach { bot ->
            val schedule = bot.schedule ?: return@forEach
            launch {
                var after = clock.now()
                while (true) {
                    val firing = schedule.next(after)
                    delay(firing - clock.now())
                    after = firing
                    val waiting = state.value.any { it.botId == bot.id && it.status == RunStatus.QUEUED }
                    if (!waiting) {
                        val _ = start(bot.id, options)
                    }
                }
            }
        }
        awaitCancellation()
    }

    /** Starts each run in turn. One cancelled before now ended when it was cancelled, and takes no time. */
    private suspend fun work(lane: Channel<Job>) {
        for (job in lane) job.join()
    }

    /**
     * Settles a run however its job came to end. Most have said how by now. One cancelled before its body
     * began has not, nor has one that met something other than an `Exception`.
     */
    private fun ended(runId: Long, botId: String, cause: Throwable?) {
        jobs.update { it - runId }
        if (cause == null || cause is CancellationException) {
            finish(runId, botId, RunStatus.CANCELLED)
        } else {
            update(runId) { if (it.status.isFinished) it else it.copy(failure = cause.toString()) }
            finish(runId, botId, RunStatus.FAILED)
        }
    }

    private suspend fun execute(bot: Bot, run: QueuedRun) {
        finish(run.id, bot.id, RunStatus.RUNNING)
        try {
            val wiki = accounts.wiki(endpoint, bot.account).also { requireMaySave(it, run.options) }
            val context = HostedContext(bot, run, wiki)
            bot.run(context)
            finish(run.id, bot.id, RunStatus.DONE)
        } catch (e: CancellationException) {
            finish(run.id, bot.id, RunStatus.CANCELLED)
            throw e
        } catch (e: Declined) {
            update(run.id) { it.copy(stoppedBecause = e.message) }
            finish(run.id, bot.id, RunStatus.DECLINED)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            update(run.id) { it.copy(failure = e.message ?: e.toString()) }
            line(bot.id, emptyList(), "failed: ${e.message ?: e}")
            finish(run.id, bot.id, RunStatus.FAILED)
        }
    }

    /**
     * Refuses an account a run told to save may not save as, as `botMain` does for the account it logs in
     * with, since each bot here may have its own.
     */
    private fun requireMaySave(wiki: Wiki, options: CommonOptions) {
        if (!options.save) return
        val identity = wiki.identity
        check(!identity.isAnonymous) { "not logged in: the wiki sees an anonymous user" }
        check(identity.isBot || options.trial) {
            "${identity.name} is not in the bot group; not saving without --trial"
        }
    }

    /** Moves a run on to [status], unless it has already ended, since an ending is final. */
    private fun finish(runId: Long, botId: String, status: RunStatus) {
        var changed = false
        state.update { views ->
            changed = false
            views.map { view ->
                if (view.id != runId || view.status.isFinished) {
                    view
                } else {
                    changed = true
                    view.copy(status = status)
                }
            }
        }
        if (changed) announce(runId, botId, status)
    }

    private fun announce(runId: Long, botId: String, status: RunStatus) {
        val _ = happening.tryEmit(FleetEvent.Changed(runId, botId, status))
    }

    private fun update(runId: Long, change: (RunView) -> RunView) {
        state.update { views -> views.map { if (it.id == runId) change(it) else it } }
    }

    private fun line(botId: String, steps: List<String>, message: String) {
        out("[$botId${steps.joinToString("") { " > $it" }}] $message")
    }

    private class QueuedRun(val id: Long, val options: CommonOptions, val arguments: List<String>)

    private inner class HostedContext(
        private val bot: Bot,
        private val run: QueuedRun,
        override val wiki: Wiki,
        private val steps: List<String> = emptyList(),
    ) : RunContext {
        private val started = TimeSource.Monotonic.markNow()
        private var lastBeat = started.elapsedNow()

        override val stopPolicy: StopPolicy = StopPolicy {
            val allowed = runCatching {
                stop.mayContinue()
            }
                .getOrElse { failure ->
                    if (failure !is CancellationException) stopped("the stop policy could not be read")
                    throw failure
                }
            if (!allowed) stopped("the stop policy says to stop")
            allowed
        }

        override val options: CommonOptions
            get() = run.options

        override val arguments: List<String>
            get() = run.arguments

        override val directory: Path?
            get() = this@Fleet.directory?.let { Path(it, bot.id, run.id.toString()) }

        override suspend fun open(language: LangCode, family: Family): Wiki =
            accounts.wiki(family.endpoint(language), bot.account).also { requireMaySave(it, run.options) }

        override fun say(message: String, level: RunContext.Level) {
            line(bot.id, steps, message)
            val _ = happening.tryEmit(FleetEvent.Said(run.id, bot.id, steps, message, level))
        }

        override fun expect(items: Int) {
            update(run.id) { it.copy(expected = it.expected + items) }
        }

        override fun stopped(reason: String) {
            update(run.id) { it.copy(stoppedBecause = it.stoppedBecause ?: reason) }
        }

        override suspend fun <T> step(name: String, work: suspend (RunContext) -> T): T =
            work(HostedContext(bot, run, wiki, steps + name))

        override suspend fun ask(question: Question): Boolean {
            finish(run.id, bot.id, RunStatus.WAITING)
            val answer = questions(bot.id, question)
            finish(run.id, bot.id, RunStatus.RUNNING)
            return answer
        }

        override suspend fun mayContinue(): Boolean = runCatching {
            stopPolicy.mayContinue()
        }
            .getOrElse { failure ->
                if (failure is CancellationException) throw failure
                false
            }

        override val outcomes: (PageOutcome) -> Unit = { outcome ->
            if (outcome is PageOutcome.NotAttempted) {
                stopped("the run stopped early: ${outcome.reason.name.lowercase()}")
            }
            update(run.id) {
                it.copy(
                    seen = it.seen + if (outcome is PageOutcome.NotAttempted) 0 else 1,
                    saved = it.saved + if (outcome is PageOutcome.Saved) 1 else 0,
                )
            }
            val now = started.elapsedNow()
            if (now - lastBeat >= heartbeat) {
                lastBeat = now
                val view = state.value.first { it.id == run.id }
                val of = if (view.expected > 0) " of ${view.expected}" else ""
                say("${view.seen}$of pages, ${view.saved} saved")
            }
        }
    }

    private companion object {
        const val EVENT_BUFFER = 256
    }
}
