package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.client.EditBuilder
import com.fenakhay.kwikibot.client.KwikibotDsl
import com.fenakhay.kwikibot.client.PageService
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.EditOutcome
import com.fenakhay.kwikibot.model.PageContent
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The edit a bot decided to make to one page. */
public data class Edit(
    /** The whole new page text. */
    val text: String,
    /** The edit summary, which is what a watchlist reader sees. */
    val summary: String,
    /** Whether to mark it minor. */
    val minor: Boolean = false,
)

/** What became of one page in a run. */
public sealed interface PageOutcome {

    /** The page this outcome is about. */
    public val ref: PageRef

    /** The page did not exist. */
    public data class Missing(
        /** The page that was not there. */
        override val ref: PageRef,
    ) : PageOutcome

    /** The bot chose not to edit this page, and said why. */
    public data class Skipped(
        /** The page left alone. */
        override val ref: PageRef,
        /** Why, which is what a run report is mostly made of. */
        val reason: String,
    ) : PageOutcome

    /** The bot produced text identical to what was already there. */
    public data class Unchanged(
        /** The page whose text the transform did not alter. */
        override val ref: PageRef,
    ) : PageOutcome

    /** A dry run: the edit was computed but not sent. */
    public data class Pending(
        /** The page that would have been edited. */
        override val ref: PageRef,
        /** What would have been saved. */
        val edit: Edit,
        /** The text as it stands, so a caller can show the difference. */
        val before: String,
    ) : PageOutcome

    /** The edit was saved. */
    public data class Saved(
        /** The page edited. */
        override val ref: PageRef,
        /** The revision the edit produced. */
        val revision: RevisionId,
    ) : PageOutcome

    /** The wiki refused the edit. */
    public data class Refused(
        /** The page the wiki would not accept. */
        override val ref: PageRef,
        /** What the wiki said, which distinguishes a conflict from a filter. */
        val outcome: EditOutcome.Refused,
    ) : PageOutcome

    /** Something went wrong that was not about this page's content. */
    public data class Failed(
        /** The page being handled when it went wrong. */
        override val ref: PageRef,
        /** What went wrong, which is not about the page's content. */
        val error: Throwable,
    ) : PageOutcome
}

/** What a run did. */
public data class BotReport(
    /** What became of each page, in the order handled. */
    val outcomes: List<PageOutcome>,
    /** Whether the run ended early because a stop policy said so. */
    val stopped: Boolean = false,
) {
    /** How many pages were handled. */
    val processed: Int get() = outcomes.size

    /** How many edits were saved. */
    val saved: Int get() = outcomes.count { it is PageOutcome.Saved }

    /** How many edits were computed but not sent, which is every edit in a dry run. */
    val pending: Int get() = outcomes.count { it is PageOutcome.Pending }

    /** How many were left alone, whether deliberately or because they did not exist. */
    val skipped: Int get() = outcomes.count { it is PageOutcome.Skipped || it is PageOutcome.Missing }

    /** How many the wiki refused. */
    val refused: Int get() = outcomes.count { it is PageOutcome.Refused }

    /** How many failed for a reason that was not about the page. */
    val failed: Int get() = outcomes.count { it is PageOutcome.Failed }

    /** Whether every page was handled without a refusal or a failure. */
    val clean: Boolean get() = refused == 0 && failed == 0

    override fun toString(): String =
        "processed=$processed saved=$saved pending=$pending skipped=$skipped " +
            "refused=$refused failed=$failed" + if (stopped) " (stopped early)" else ""
}

/**
 * The check that decides whether the bot may keep writing.
 *
 * Fail-closed by design: a check that cannot be performed stops the run. A bot that keeps
 * editing because its emergency stop was unreachable is exactly the failure this prevents.
 */
public fun interface StopPolicy {

    /** Returns `true` when the bot may continue. Throwing is treated as "stop". */
    public suspend fun mayContinue(): Boolean

    /** The defaults a run uses when a caller sets nothing. */
    public companion object {
        /** No stop check. Only for dry runs and for wikis you own. */
        public val NONE: StopPolicy = StopPolicy { true }

        /**
         * Stops when a page on the wiki says anything other than `false`.
         *
         * The convention `User:MyBot/Stop` uses: an administrator empties or edits that page and
         * the bot halts within one edit.
         */
        public fun page(pages: PageService, ref: PageRef): StopPolicy = StopPolicy {
            pages.content(ref)?.text?.trim().equals("false", ignoreCase = true)
        }
    }
}

/** Configures a run. */
@KwikibotDsl
public class BotRunBuilder internal constructor() {

    internal var source: Flow<PageRef>? = null
    internal var transform: (suspend (PageContent) -> Edit?)? = null

    /**
     * Whether to compute edits without sending them.
     *
     * On by default. A bot that edits on its first run because someone forgot a flag is a bot
     * that has already made its mistakes.
     */
    public var dryRun: Boolean = true

    /**
     * How many pages to work on at once.
     *
     * This bounds the reads and, with them, the transforms: computing an edit runs on
     * `Dispatchers.Default` rather than on the thread the run was started from, so this many
     * pages may genuinely be parsed at the same moment on different cores.
     *
     * A transform that only reads its [PageContent] and returns an [Edit] needs nothing from
     * you. One that writes to something shared - a counter, a set of seen titles, a log - has to
     * say so itself, because nothing here serialises it.
     */
    public var readConcurrency: Int = DEFAULT_READ_CONCURRENCY

    /** How many edits to have in flight at once. */
    public var writeConcurrency: Int = 1

    /**
     * Stop after this many pages.
     *
     * Applied to the source, so a capped run over a category of a million pages reads only what
     * it needs rather than listing the lot and discarding most of it.
     */
    public var limit: Int? = null

    /** Checked before every save; see [StopPolicy]. */
    public var stopPolicy: StopPolicy = StopPolicy.NONE

    /**
     * Whether to honour `{{nobots}}` and `{{bots}}`, and as which bot.
     *
     * Should be set. Wikis block bots that edit pages carrying `{{nobots}}`. It is `null`
     * here only because this builder does not know the account name — the session does, so a bot
     * sets `BotPolicy(wiki.identity.name)` once it has opened the wiki.
     */
    public var exclusionPolicy: BotPolicy? = null

    /** Called as each page is finished, for progress and logging. */
    public var onOutcome: ((PageOutcome) -> Unit)? = null

    /** The pages to work through. */
    public fun source(pages: Flow<PageRef>) {
        source = pages
    }

    /**
     * Computes the edit for one page, or returns `null` to leave it alone.
     *
     * Returning `null` is the normal way to skip: most pages a bot looks at need nothing done.
     *
     * Runs on `Dispatchers.Default`, up to [readConcurrency] pages at a time, so this may be
     * called on several threads at once. Anything it shares with itself needs its own protection.
     */
    public fun transform(block: suspend (PageContent) -> Edit?) {
        transform = block
    }

    /** Skips a page with a reason, which is recorded in the report. */
    public fun skip(reason: String): Nothing = throw SkipPage(reason)

    internal companion object {
        const val DEFAULT_READ_CONCURRENCY = 4
    }
}

/** Thrown by [BotRunBuilder.skip] to abandon one page with a reason. */
internal class SkipPage(val reason: String) : Exception(null, null, false, false)

/**
 * Works through pages: read, transform, save.
 *
 * The shape every bot has, so it is written once. Reads run concurrently up to
 * [BotRunBuilder.readConcurrency]; writes are bounded separately and paced by the wiki's
 * throttle, so a run overlaps its reads without ever hammering the wiki with edits.
 *
 * The run stops at the first refusal it cannot attribute to the page — a dead session, a wiki in
 * read-only mode — rather than grinding through thousands of pages failing the same way.
 */
public suspend fun botRun(pages: PageService, block: BotRunBuilder.() -> Unit): BotReport {
    val config = BotRunBuilder().apply(block)
    val source = requireNotNull(config.source) { "a run needs a source of pages" }
    val transform = requireNotNull(config.transform) { "a run needs a transform" }

    // Fail-closed before anything is read, so a stopped bot does not even start.
    if (!config.dryRun) {
        check(stopAllows(config.stopPolicy)) { "the stop policy refused; not starting" }
    }

    val runner = Runner(pages, config, transform)
    return runner.run(source)
}

/** Runs a bot over the pages of a wiki. */
public suspend fun Wiki.botRun(block: BotRunBuilder.() -> Unit): BotReport = botRun(pages, block)

private suspend fun stopAllows(policy: StopPolicy): Boolean =
    runCatching { policy.mayContinue() }.getOrDefault(false)

private class Runner(
    private val pages: PageService,
    private val config: BotRunBuilder,
    private val transform: suspend (PageContent) -> Edit?,
) {
    private val reads = Semaphore(config.readConcurrency)
    private val writes = Semaphore(config.writeConcurrency)
    private val lock = Mutex()
    private val outcomes = mutableListOf<PageOutcome>()

    @Volatile
    private var stopped = false

    suspend fun run(source: Flow<PageRef>): BotReport {
        val limited = config.limit?.let { source.take(it) } ?: source

        coroutineScope {
            limited
                .map { ref -> async { handle(ref) } }
                // Bounds how far ahead of the collector the reads may run.
                .buffer(config.readConcurrency)
                .map { it.await() }
                .collect { outcome -> record(outcome) }
        }
        return BotReport(outcomes.toList(), stopped)
    }

    private suspend fun handle(ref: PageRef): PageOutcome {
        if (stopped) return PageOutcome.Skipped(ref, "run stopped")

        return try {
            process(ref)
        } catch (skip: SkipPage) {
            PageOutcome.Skipped(ref, skip.reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WikiError) {
            if (e.stopsRun()) stopped = true
            PageOutcome.Failed(ref, e)
        }
    }

    /** Why a policy refused a page, carried back out of the dispatched block. */
    private class Refusal(val reason: String)

    private suspend fun process(ref: PageRef): PageOutcome {
        val content = reads.withPermit { pages.content(ref) } ?: return PageOutcome.Missing(ref)

        // Parsing a page and working out an edit is the one part of a run that is real work for
        // the processor rather than waiting on a wiki, and `suspend fun main` gives a bot a
        // single thread. Left where it lands, every page's parse would queue behind the last -
        // the reads would overlap and the thinking would not. Dispatchers.Default is what makes
        // readConcurrency mean pages in parallel rather than only requests in flight.
        val edit = withContext(Dispatchers.Default) {
            // Checked before the transform runs, not before the save: computing an edit for an
            // excluded page wastes the work and leaves a computed edit that could still be saved.
            val exclusion = config.exclusionPolicy?.check(content.text)
            if (exclusion is EditPermission.Denied) {
                return@withContext Refusal(exclusion.reason)
            }
            transform(content)
        }

        if (edit is Refusal) return PageOutcome.Skipped(ref, "excluded by ${edit.reason}")
        if (edit !is Edit) return PageOutcome.Skipped(ref, "no change needed")

        return when {
            edit.text == content.text -> PageOutcome.Unchanged(ref)
            config.dryRun -> PageOutcome.Pending(ref, edit, content.text)
            else -> save(ref, content, edit)
        }
    }

    /**
     * Whether a failure means the whole run should stop.
     *
     * A dead session, a read-only wiki or a misconfiguration will fail every remaining page the
     * same way, so continuing through thousands of them serves no purpose. A transport
     * hiccup or an
     * error about this particular page is worth recording and moving on from.
     */
    private fun WikiError.stopsRun(): Boolean = when (this) {
        is WikiError.Auth, is WikiError.ReadOnly, is WikiError.Configuration -> true
        is WikiError.Transport, is WikiError.Api, is WikiError.Page -> false
    }

    private suspend fun save(ref: PageRef, content: PageContent, edit: Edit): PageOutcome {
        // Checked before every save, not once at the start: a run can last hours, and an
        // administrator stopping the bot expects it to stop within one edit.
        if (!stopAllows(config.stopPolicy)) {
            stopped = true
            return PageOutcome.Skipped(ref, "stopped by policy")
        }

        return writes.withPermit {
            // Re-checked here, under the permit, and not only when the page was picked up.
            // Pages are worked on in parallel, so by the time this one reaches the front of the
            // queue another may have found the session dead - and the whole point of stopping a
            // run is not to make the next edit after the reason to stop is known.
            if (stopped) return@withPermit PageOutcome.Skipped(ref, "run stopped")

            when (
                val outcome = pages.edit(ref) {
                    text = edit.text
                    summary = edit.summary
                    minor = edit.minor
                    baseRevision = content.revisionId
                }
            ) {
                is EditOutcome.Saved -> PageOutcome.Saved(ref, outcome.revision)
                is EditOutcome.NoChange -> PageOutcome.Unchanged(ref)
                is EditOutcome.Refused -> PageOutcome.Refused(ref, outcome)
            }
        }
    }

    private suspend fun record(outcome: PageOutcome) {
        lock.withLock { outcomes += outcome }
        config.onOutcome?.invoke(outcome)
    }
}

/** Applies the builder's edit fields to a page edit. */
internal fun EditBuilder.apply(edit: Edit) {
    text = edit.text
    summary = edit.summary
    minor = edit.minor
}
