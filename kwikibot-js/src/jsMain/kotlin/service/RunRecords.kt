import com.fenakhay.kwikibot.bot.fix.Diffs
import com.fenakhay.kwikibot.bot.run.Progress as ClientProgress
import com.fenakhay.kwikibot.bot.run.RunLog as ClientRunLog
import com.fenakhay.kwikibot.bot.run.RunState as ClientRunState
import com.fenakhay.kwikibot.net.environmentVariable
import kotlin.js.Promise
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * What a run has done, kept on disk as it happens, so a run that stops halfway can carry on where it left
 * off. Three files in [directory], `finished.tsv`, `pins.tsv` and `saves.jsonl`, each flushed as each page
 * finishes. Set on a run with `state`, and read back by setting `resume`. Pages are keyed by title, so one
 * directory belongs to one wiki.
 *
 * Works in Node only: in a browser, which has no filesystem, making one throws.
 */
@JsExport
@OptIn(DelicateCoroutinesApi::class)
public class RunState(
    /** Where the files are kept. Created if it does not exist. */
    public val directory: String
) {
    init {
        requireFileSystem("a run's state")
    }

    internal val state: ClientRunState = ClientRunState(Path(directory))

    /** How many pages earlier runs into this directory finished. */
    public fun finishedCount(): Promise<Int> = GlobalScope.promise { state.finishedCount() }

    /** Whether a run into this directory already finished page [title] of [wiki]. */
    public fun isFinished(wiki: Wiki, title: String): Promise<Boolean> = GlobalScope.promise {
        state.isFinished(wiki.wiki.ref(title))
    }

    /** The revision the outcome of page [title] of [wiki] was last decided on, or `null` if it never was. */
    public fun pin(wiki: Wiki, title: String): Promise<Double?> = GlobalScope.promise {
        state.pin(wiki.wiki.ref(title))?.value?.toDouble()
    }

    /**
     * Records [outcome], decided on [revision] of the page. Saved edits pin the revision they made. A run
     * does this itself; this is for a bot with a loop of its own.
     */
    public fun record(outcome: PageOutcome, revision: Double? = null): Promise<Unit> =
        GlobalScope.promiseVoid {
            state.record(outcome.outcome, revision?.let(::revisionIdOf))
        }

    /** Closes the files, once the run that records into them is over. */
    public fun close() {
        state.close()
    }
}

/** Where a run's log goes. Each may be a file, a function or neither, which writes none of that record. */
@JsExport
public external interface RunLogOptions {
    /** The file to add unified diffs to, for edits computed in a dry run and edits saved. Node only. */
    public val diffFile: String?

    /** The file to add skip records to, one JSON object per line. Node only. */
    public val skipFile: String?

    /** Takes the diffs as they are written, in place of [diffFile]. */
    public val onDiff: ((text: String) -> Unit)?

    /** Takes the skip records as they are written, in place of [skipFile]. */
    public val onSkip: ((text: String) -> Unit)?

    /** How many unchanged lines to show either side of a change in a diff; 3 unless given. */
    public val context: Int?

    /** The wiki's namespaces, for naming pages the way it does; the canonical English ones unless given. */
    public val namespaces: Namespaces?
}

/**
 * Writes what a run did as it happens: the diffs, to see what the bot changed or would change, and the skips,
 * to see what it left alone and why. Flushed as they are written, so a run that is interrupted still leaves a
 * usable record.
 */
@JsExport
public class RunLog internal constructor(private val log: ClientRunLog) {

    /** Writes what [outcome] calls for: a diff for an edit, a record for a page left alone. */
    public fun record(outcome: PageOutcome) {
        log.record(outcome.outcome)
    }
}

/** Makes a log writing where [options] says, and nowhere unless it says. */
@JsExport
public fun createRunLog(options: RunLogOptions? = null): RunLog =
    RunLog(
        ClientRunLog(
            sinkOf(options?.onDiff, options?.diffFile),
            sinkOf(options?.onSkip, options?.skipFile),
            options?.context ?: Diffs.DEFAULT_CONTEXT,
            options?.namespaces?.namespaces,
        )
    )

/** What a progress display shows, and where. */
@JsExport
public external interface ProgressOptions {
    /** How many pages the run will see, when known, for a bar and the time left. */
    public val total: Int?

    /** Takes the line as it is drawn; standard error, or the console in a browser, unless given. */
    public val write: ((text: String) -> Unit)?

    /** Whether to draw anything; `true` unless given. Off for a run whose output is not a terminal. */
    public val enabled: Boolean?

    /** The widest the line may be: the `COLUMNS` the shell reports, or 100, unless given. */
    public val width: Int?

    /** What the time is, in milliseconds, for the rate and the redraws; the system's clock unless given. */
    public val now: (() -> Double)?
}

/**
 * A one-line progress display, rewritten in place, on standard error so a run's diffs can be piped somewhere
 * while it stays on the terminal. Redrawn at most five times a second. Hand each outcome to [record], and
 * call [finish] when the run is over.
 */
@JsExport
public class Progress internal constructor(private val progress: ClientProgress) {

    /** Counts [outcome], and redraws the line if it has not been drawn for a moment. */
    public fun record(outcome: PageOutcome) {
        progress.record(outcome.outcome)
    }

    /** Writes the final line and moves to the next, so later output starts cleanly. */
    public fun finish() {
        progress.finish()
    }
}

/** Makes a progress display as [options] says. */
@JsExport
public fun createProgress(options: ProgressOptions? = null): Progress {
    val clock = options?.now
    val write = options?.write ?: ::standardError
    val width =
        options?.width
            ?: environmentVariable("COLUMNS")?.toIntOrNull()?.takeIf { it > 0 }
            ?: DEFAULT_PROGRESS_WIDTH
    val sink = TextSink(write).buffered()
    val enabled = options?.enabled ?: true
    return Progress(
        if (clock == null) ClientProgress(options?.total, sink, enabled, width)
        else ClientProgress(options.total, sink, enabled, width, ClockedSource(clock))
    )
}

private class ClockedSource(private val now: () -> Double) :
    AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
    override fun read(): Long = now().toLong()
}

private fun sinkOf(write: ((String) -> Unit)?, file: String?): Sink? =
    when {
        write != null -> TextSink(write).buffered()
        file != null -> {
            requireFileSystem("a log file")
            val path = Path(file)
            path.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(path, append = true).buffered()
        }
        else -> null
    }

/** Hands what is written to it to [write], as text. */
private class TextSink(private val write: (String) -> Unit) : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        write(source.readString(byteCount))
    }

    override fun flush() = Unit

    override fun close() = Unit
}

private fun standardError(text: String) {
    val process: dynamic = js("typeof process === 'undefined' ? null : process")
    if (process != null) process.stderr.write(text) else console.error(text)
}

private const val DEFAULT_PROGRESS_WIDTH = 100
