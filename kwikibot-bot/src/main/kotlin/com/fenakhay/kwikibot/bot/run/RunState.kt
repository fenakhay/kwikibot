package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * What a run has done, kept on disk as it happens, so a run that stops halfway can carry on where it left
 * off.
 *
 * Three files in [directory], each appended to and flushed as each page finishes, so a run killed at any
 * moment loses at most the page it was on:
 *
 * - `finished.tsv`: each finished page (saved, already right, skipped or missing) and how. A page that
 *   failed, was refused or was never reached is not finished, and a resumed run tries it again.
 * - `pins.tsv`: each page and the revision its outcome was decided on. A resumed run that reads the page at
 *   that revision leaves it without running the transform.
 * - `saves.jsonl`: each edit saved, with the revision it made and the one it replaced, for undoing a run.
 *
 * Set on a run with [BotRunBuilder.state], and read back by setting [BotRunBuilder.resume]. A run without
 * `resume` only records. Pins assume the transform is the same one that made them; after changing a bot's
 * logic, resume into a fresh directory.
 *
 * Pages are keyed by title, so one directory belongs to one wiki.
 */
public class RunState(
    /** Where the files are kept. Created if it does not exist. */
    public val directory: Path
) : AutoCloseable {

    private val finished: MutableSet<String>
    private val pins: MutableMap<String, Long>

    private val finishedFile: BufferedWriter
    private val pinsFile: BufferedWriter
    private val savesFile: BufferedWriter

    init {
        Files.createDirectories(directory)
        finished = read(FINISHED).mapTo(HashSet()) { it.substringBefore('\t') }
        pins = HashMap()
        for (line in read(PINS)) {
            val revision = line.substringAfterLast('\t').toLongOrNull() ?: continue
            pins[line.substringBeforeLast('\t')] = revision
        }
        finishedFile = open(FINISHED)
        pinsFile = open(PINS)
        savesFile = open(SAVES)
    }

    /** How many pages earlier runs into this directory finished. */
    public val finishedCount: Int
        @Synchronized get() = finished.size

    /** Whether a run into this directory already finished [ref]. */
    @Synchronized public fun isFinished(ref: PageRef): Boolean = key(ref) in finished

    /** The revision [ref]'s outcome was last decided on, or `null` if it never was. */
    @Synchronized public fun pin(ref: PageRef): RevisionId? = pins[key(ref)]?.let { RevisionId(it) }

    /**
     * Records [outcome], decided on [revision] of the page.
     *
     * Saved edits pin the revision they made, since the transform has nothing left to do on it.
     */
    @Synchronized
    public fun record(outcome: PageOutcome, revision: RevisionId?) {
        val title = key(outcome.ref)
        val kind =
            when (outcome) {
                is PageOutcome.Saved -> "saved"
                is PageOutcome.Unchanged -> "unchanged"
                is PageOutcome.Skipped -> "skipped"
                is PageOutcome.Missing -> "missing"
                is PageOutcome.Pending,
                is PageOutcome.Refused,
                is PageOutcome.Failed,
                is PageOutcome.NotAttempted -> return
            }

        if (finished.add(title)) finishedFile.line("$title\t$kind")

        val decidedOn = if (outcome is PageOutcome.Saved) outcome.revision else revision
        if (decidedOn != null && pins.put(title, decidedOn.value) != decidedOn.value) {
            pinsFile.line("$title\t${decidedOn.value}")
        }

        if (outcome is PageOutcome.Saved) {
            val record = buildJsonObject {
                put("title", JsonPrimitive(title))
                put("revision", JsonPrimitive(outcome.revision.value))
                outcome.previousRevision?.let { put("previous", JsonPrimitive(it.value)) }
                outcome.edit?.let { put("summary", JsonPrimitive(it.summary)) }
            }
            savesFile.line(record.toString())
        }
    }

    @Synchronized
    override fun close() {
        finishedFile.close()
        pinsFile.close()
        savesFile.close()
    }

    private fun key(ref: PageRef): String = ref.title.toString()

    private fun read(name: String): List<String> {
        val file = directory.resolve(name)
        return if (file.exists()) file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
        else emptyList()
    }

    private fun open(name: String): BufferedWriter =
        Files.newBufferedWriter(
            directory.resolve(name),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )

    private fun BufferedWriter.line(text: String) {
        append(text).append('\n')
        flush()
    }

    private companion object {
        const val FINISHED = "finished.tsv"
        const val PINS = "pins.tsv"
        const val SAVES = "saves.jsonl"
    }
}
