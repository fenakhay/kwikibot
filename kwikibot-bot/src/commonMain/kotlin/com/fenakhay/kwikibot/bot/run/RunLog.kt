package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.bot.fix.Diffs
import com.fenakhay.kwikibot.bot.standardError
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.environmentVariable
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.io.Sink
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Writes what a run did, as it happens.
 *
 * Two records matter after a run of thousands of pages: the diffs, to see what the bot changed or would
 * change, and the skips, to see what it left alone and why. Both are written as they happen and flushed, so a
 * run that is interrupted still leaves a usable record.
 *
 * Skips are JSON Lines because that is what survives being grepped, counted and fed back into the next run.
 *
 * Pages are named with their namespace: the wiki's own prefix when [namespaces] is given, the canonical
 * English one otherwise.
 *
 * @param diffs where unified diffs go, for edits computed in a dry run and edits saved; `null` to write none.
 * @param skips where skip records go, one JSON object per line; `null` to write none.
 * @param context how many unchanged lines to show either side of a change in a diff.
 * @param namespaces the wiki's namespaces, usually `wiki.namespaces`, for naming pages the way it does.
 */
public class RunLog(
    private val diffs: Sink? = null,
    private val skips: Sink? = null,
    private val context: Int = Diffs.DEFAULT_CONTEXT,
    private val namespaces: NamespaceMap? = null,
) {

    /** Writes what [outcome] calls for: a diff for an edit, a record for a page left alone. */
    public fun record(outcome: PageOutcome) {
        val title = name(outcome.ref)
        when (outcome) {
            is PageOutcome.Pending -> writeDiff("diff", title, outcome.before, outcome.edit)
            is PageOutcome.Saved ->
                outcome.edit?.let { writeDiff("saved", title, outcome.before.orEmpty(), it) }
            is PageOutcome.Skipped -> writeSkip(title, "skipped", outcome.reason)
            is PageOutcome.Missing -> writeSkip(title, "missing", "page does not exist")
            is PageOutcome.Refused -> writeSkip(title, "refused", outcome.outcome.detail)
            is PageOutcome.Failed -> writeSkip(title, "failed", outcome.error.message.orEmpty())

            // Not a decision about the page, and a stopped run would otherwise log a line for every page
            // it had in hand.
            is PageOutcome.NotAttempted,
            is PageOutcome.Unchanged -> Unit
        }
    }

    private fun name(ref: PageRef): String = namespaces?.format(ref.title) ?: ref.title.toString()

    private fun writeDiff(label: String, title: String, before: String, edit: Edit) {
        val sink = diffs ?: return
        // A section edit carries only the section's new text, which cannot be diffed against the page.
        if (edit.section != null) return
        val diff = Diffs.unified(before, edit.text, title, context)
        if (diff.isEmpty()) return

        sink.writeString("=== $label: $title ===\n")
        sink.writeString("$diff\n\n")
        sink.flush()
    }

    private fun writeSkip(title: String, kind: String, reason: String) {
        val sink = skips ?: return
        val record = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("kind", JsonPrimitive(kind))
            put("reason", JsonPrimitive(reason))
        }

        sink.writeString("$record\n")
        sink.flush()
    }
}

/**
 * A one-line progress display on standard error.
 *
 * Fed one outcome at a time, as a run hands them to [BotRunBuilder.onOutcome]; it is not for several threads
 * at once.
 *
 * On standard error rather than standard output so a run's diffs can be piped somewhere while the progress
 * stays on the terminal, and rewritten in place with a carriage return so a long run does not fill the
 * scrollback. Redrawn at most five times a second, and cut to the terminal's width so a narrow window does
 * not wrap it.
 *
 * ```
 * [######--------------] 3,120/10,000  42.5/s  2m41s left  would change 87  skipped 3,001  failed 2
 * ```
 *
 * A dry run counts its edits as "would change", a saving run as "saved". The rate is over the last ten
 * seconds, so it follows a run that speeds up or is throttled; the time left assumes the rate holds.
 *
 * @param total how many pages the run will see, when known, for a bar and the time left.
 * @param sink where the line is drawn.
 * @param enabled whether to draw anything; off for a run whose output is not a terminal.
 * @param width the widest the line may be: the `COLUMNS` the shell reports, or 100.
 * @param timeSource the clock the rate and the redraws are measured on.
 */
public class Progress(
    private val total: Int?,
    private val sink: Sink = standardError(),
    private val enabled: Boolean = true,
    private val width: Int = terminalWidth(otherwise = DEFAULT_WIDTH),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    private val started = timeSource.markNow()
    private var drawn: TimeMark? = null

    private var processed = 0
    private var wouldChange = 0
    private var saved = 0
    private var unchanged = 0
    private var skipped = 0
    private var failed = 0
    private var notAttempted = 0

    /** How far the run had come at recent moments, oldest first, for the rate. */
    private val samples = ArrayDeque<Pair<Duration, Int>>()

    /** Counts [outcome], and redraws the line if it has not been drawn for a moment. */
    public fun record(outcome: PageOutcome) {
        if (outcome !is PageOutcome.NotAttempted) processed++
        when (outcome) {
            is PageOutcome.Pending -> wouldChange++
            is PageOutcome.Saved -> saved++
            is PageOutcome.Unchanged -> unchanged++
            is PageOutcome.Skipped,
            is PageOutcome.Missing -> skipped++
            is PageOutcome.Refused,
            is PageOutcome.Failed -> failed++
            is PageOutcome.NotAttempted -> notAttempted++
        }
        val last = drawn
        if (last == null || last.elapsedNow() >= REDRAW) render(done = false)
    }

    /** Writes the final line and moves to the next, so later output starts cleanly. */
    public fun finish() {
        render(done = true)
    }

    private fun render(done: Boolean) {
        if (!enabled) return
        drawn = timeSource.markNow()

        sink.writeString("\r" + line())
        if (done) sink.writeString("\n")
        sink.flush()
    }

    /** The line, fitted to [width]: the bar is dropped first, then the text is cut. */
    internal fun line(): String {
        val text = (pace() + counts()).joinToString(SEPARATOR)
        val room = width - 1

        val bar = total?.let { bar(it) }
        val full = if (bar != null) "$bar $text" else text
        return when {
            full.length <= room -> full
            text.length <= room -> text
            else -> text.take(room)
        }
    }

    /** How far the run has come, how fast it is going, and how long it has left. */
    private fun pace(): List<String> {
        val rate = rate(started.elapsedNow())
        val left = total?.let { it - processed } ?: 0
        return listOfNotNull(
            total?.let { "${count(processed)}/${count(it)}" } ?: "${count(processed)} pages",
            "${tenths(rate)}/s".takeIf { rate > 0.0 },
            "${duration(left / rate)} left".takeIf { left > 0 && rate > 0.0 },
        )
    }

    /** What has happened to the pages so far, leaving out what has not happened at all. */
    private fun counts(): List<String> =
        listOf(
                "would change" to wouldChange,
                "saved" to saved,
                "unchanged" to unchanged,
                "skipped" to skipped,
                "failed" to failed,
                "not attempted" to notAttempted,
            )
            .filter { (_, value) -> value > 0 }
            .map { (label, value) -> "$label ${count(value)}" }

    /** Pages a second over the last [RATE_WINDOW], or over the whole run while it is younger than that. */
    private fun rate(elapsed: Duration): Double {
        samples.addLast(elapsed to processed)
        while (samples.size > 1 && elapsed - samples.first().first > RATE_WINDOW) samples.removeFirst()

        val (since, then) = samples.first()
        val window = elapsed - since
        return when {
            window >= MIN_WINDOW -> (processed - then) / window.toDouble(DurationUnit.SECONDS)
            elapsed >= MIN_WINDOW -> processed / elapsed.toDouble(DurationUnit.SECONDS)
            else -> 0.0
        }
    }

    private fun bar(total: Int): String {
        val ratio = if (total == 0) 1.0 else processed.toDouble() / total
        val filled = (BAR_WIDTH * ratio).toInt().coerceIn(0, BAR_WIDTH)
        return "[${"#".repeat(filled)}${"-".repeat(BAR_WIDTH - filled)}]"
    }

    private fun count(value: Int): String = grouped(value)

    private fun duration(seconds: Double): String {
        val whole = seconds.toLong()
        val hours = whole / SECONDS_PER_HOUR
        val minutes = whole % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val rest = whole % SECONDS_PER_MINUTE
        return when {
            hours > 0 -> "${hours}h${twoDigits(minutes)}m"
            minutes > 0 -> "${minutes}m${twoDigits(rest)}s"
            else -> "${rest}s"
        }
    }

    private companion object {
        const val BAR_WIDTH = 20
        const val SEPARATOR = "  "
        const val DEFAULT_WIDTH = 100
        const val SECONDS_PER_HOUR = 3600L
        const val SECONDS_PER_MINUTE = 60L
        val REDRAW = 200.milliseconds
        val RATE_WINDOW = 10.seconds
        val MIN_WINDOW = 1.seconds
    }
}

/**
 * The terminal's width as the shell reports it in `COLUMNS`.
 *
 * @param otherwise the width to use when the shell reports none that makes sense.
 * @param variables reads an environment variable; the process's own environment unless a test says otherwise.
 */
internal fun terminalWidth(otherwise: Int, variables: (String) -> String? = ::environmentVariable): Int =
    variables("COLUMNS")?.toIntOrNull()?.takeIf { it > 0 } ?: otherwise

/** Reports each outcome to every one of [handlers], in order. */
public fun reportTo(vararg handlers: (PageOutcome) -> Unit): (PageOutcome) -> Unit = { outcome ->
    handlers.forEach { it(outcome) }
}

/** [value] to one decimal place, rounding a half up: `42.5`. */
internal fun tenths(value: Double): String {
    val scaled = (value * TENTHS_PER_UNIT).roundToLong()
    return "${scaled / TENTHS_PER_UNIT}.${scaled % TENTHS_PER_UNIT}"
}

/** [value] with its thousands set apart by commas: `3,120`. */
internal fun grouped(value: Int): String {
    val digits = value.toLong().let { if (it < 0) -it else it }.toString()
    val groups = digits.reversed().chunked(DIGITS_PER_GROUP).joinToString(",").reversed()
    return if (value < 0) "-$groups" else groups
}

/** [value] as at least two digits: `07`. */
private fun twoDigits(value: Long): String = value.toString().padStart(2, '0')

private const val TENTHS_PER_UNIT = 10
private const val DIGITS_PER_GROUP = 3
