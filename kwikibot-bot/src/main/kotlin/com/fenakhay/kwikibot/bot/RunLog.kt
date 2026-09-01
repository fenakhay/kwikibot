package com.fenakhay.kwikibot.bot

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.Flushable

/**
 * Writes what a run did, as it happens.
 *
 * Two records matter after a run of thousands of pages: the diffs, to see what the bot changed
 * or would change, and the skips, to see what it left alone and why. Both are written as they
 * happen and flushed, so a run that is interrupted still leaves a usable record.
 *
 * Skips are JSON Lines because that is what survives being grepped, counted and fed back into
 * the next run.
 *
 * @param diffs where unified diffs go; `null` to write none.
 * @param skips where skip records go, one JSON object per line; `null` to write none.
 * @param context how many unchanged lines to show either side of a change in a diff.
 */
public class RunLog(
    private val diffs: Appendable? = null,
    private val skips: Appendable? = null,
    private val context: Int = Diffs.DEFAULT_CONTEXT,
) : (PageOutcome) -> Unit {

    override fun invoke(outcome: PageOutcome) {
        when (outcome) {
            is PageOutcome.Pending -> writeDiff(outcome.ref.title.text, outcome.before, outcome.edit.text)
            is PageOutcome.Skipped -> writeSkip(outcome.ref.title.text, "skipped", outcome.reason)
            is PageOutcome.Missing -> writeSkip(outcome.ref.title.text, "missing", "page does not exist")

            is PageOutcome.Refused ->
                writeSkip(outcome.ref.title.text, "refused", outcome.outcome.detail)

            is PageOutcome.Failed ->
                writeSkip(outcome.ref.title.text, "failed", outcome.error.message.orEmpty())

            is PageOutcome.Saved, is PageOutcome.Unchanged -> Unit
        }
    }

    private fun writeDiff(title: String, before: String, after: String) {
        val sink = diffs ?: return
        val diff = Diffs.unified(before, after, title, context)
        if (diff.isEmpty()) return

        sink.append("=== diff: ").append(title).append(" ===\n")
        sink.append(diff).append("\n\n")
        (sink as? Flushable)?.flush()
    }

    private fun writeSkip(title: String, kind: String, reason: String) {
        val sink = skips ?: return
        val record = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("kind", JsonPrimitive(kind))
            put("reason", JsonPrimitive(reason))
        }

        sink.append(record.toString()).append("\n")
        (sink as? Flushable)?.flush()
    }
}

/**
 * A one-line progress display on standard error.
 *
 * On standard error rather than standard output so a run's diffs can be piped somewhere while
 * the progress stays on the terminal, and rewritten in place with a carriage return so a long
 * run does not fill the scrollback.
 */
public class Progress(
    private val total: Int?,
    private val sink: Appendable = System.err.writer(),
    private val enabled: Boolean = true,
) : (PageOutcome) -> Unit {

    private var processed = 0
    private var changed = 0
    private var saved = 0

    override fun invoke(outcome: PageOutcome) {
        processed++
        when (outcome) {
            is PageOutcome.Pending -> changed++
            is PageOutcome.Saved -> {
                changed++
                saved++
            }

            else -> Unit
        }
        render(done = false)
    }

    /** Writes the final line and moves to the next, so later output starts cleanly. */
    public fun finish() {
        render(done = true)
    }

    private fun render(done: Boolean) {
        if (!enabled) return

        val bar = total?.let { count ->
            val ratio = if (count == 0) 1.0 else processed.toDouble() / count
            val filled = (BAR_WIDTH * ratio).toInt().coerceIn(0, BAR_WIDTH)
            "[${"#".repeat(filled)}${"-".repeat(BAR_WIDTH - filled)}] $processed/$count "
        } ?: "$processed pages "

        sink.append("\r").append(bar)
            .append("changed=").append(changed.toString())
            .append(" saved=").append(saved.toString())
        if (done) sink.append("\n")
        (sink as? Flushable)?.flush()
    }

    private companion object {
        const val BAR_WIDTH = 28
    }
}

/** Reports each outcome to every one of [handlers], in order. */
public fun reportTo(vararg handlers: (PageOutcome) -> Unit): (PageOutcome) -> Unit =
    { outcome -> handlers.forEach { it(outcome) } }
