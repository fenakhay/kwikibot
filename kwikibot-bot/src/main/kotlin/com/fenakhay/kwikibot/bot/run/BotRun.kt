package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.bot.BotPolicy
import com.fenakhay.kwikibot.bot.EditPermission
import com.fenakhay.kwikibot.bot.source.chunked
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.EditBuilder
import com.fenakhay.kwikibot.client.service.KwikibotDsl
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The edit a bot decided to make to one page. */
public data class Edit(
    /** The new text: of the whole page, or of [section] when one is named. */
    val text: String,
    /** The edit summary, which is what a watchlist reader sees. */
    val summary: String,
    /** Whether to mark it minor. */
    val minor: Boolean = false,
    /**
     * Change tags to mark the edit with. Each must be one the wiki has defined for manual use, through
     * Special:Tags or `action=managetags`.
     */
    val tags: List<String> = emptyList(),
    /**
     * The section [text] replaces, by the index `RenderService.sections` reports, or `"new"` to add one with
     * the summary as its heading. `null`, the default, replaces the whole page.
     *
     * A section edit is always sent, since only the wiki can say whether it changes the page.
     */
    val section: String? = null,
    /**
     * Whether the page must not exist yet, so the edit is refused rather than overwriting one created in the
     * meantime. Set on every edit [BotRunBuilder.createMissing] makes.
     */
    val createOnly: Boolean = false,
) {
    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        text: String,
        summary: String,
        minor: Boolean = false,
    ) : this(text, summary, minor, emptyList())
}

/** Why a run stopped before it had worked through every page. */
public enum class StopReason {
    /** The stop policy said to stop, as when someone edits the stop page. */
    POLICY,

    /** The stop policy could not be checked, which a run treats as a stop. */
    POLICY_UNREACHABLE,

    /**
     * The wiki refused the account: logged out with the session beyond restoring, blocked, or lacking a
     * right.
     */
    AUTH,

    /** The wiki went read-only. */
    READ_ONLY,

    /** The wiki or the library is set up in a way the run cannot work with. */
    CONFIGURATION,
}

/** What became of one page in a run. */
public sealed interface PageOutcome {

    /** The page this outcome is about. */
    public val ref: PageRef

    /** The page did not exist. */
    public data class Missing(
        /** The page that was not there. */
        override val ref: PageRef
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
        override val ref: PageRef
    ) : PageOutcome

    /** A dry run: the edit was computed but not sent. */
    public data class Pending(
        /** The page that would have been edited. */
        override val ref: PageRef,
        /** What would have been saved. */
        val edit: Edit,
        /** The text as it stands, so a caller can show the difference. Empty for a page not yet created. */
        val before: String,
        /**
         * The revision the edit was computed from, `null` for a page not yet created.
         *
         * Lets a reviewed dry run be saved later only if the page has not changed since.
         */
        val baseRevision: RevisionId? = null,
    ) : PageOutcome {
        /** The constructor 1.1 compiled against, kept so code built then still links. */
        @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
        public constructor(ref: PageRef, edit: Edit, before: String) : this(ref, edit, before, null)
    }

    /** The edit was saved. */
    public data class Saved(
        /** The page edited. */
        override val ref: PageRef,
        /** The revision the edit produced. */
        val revision: RevisionId,
        /** The revision it replaced, `null` for a page the edit created. */
        val previousRevision: RevisionId? = null,
        /** What was saved, so a log can show it. */
        val edit: Edit? = null,
        /**
         * The text the edit replaced, so a log can show the difference. Empty for a page the edit created.
         */
        val before: String? = null,
    ) : PageOutcome {
        /** The constructor 1.1 compiled against, kept so code built then still links. */
        @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
        public constructor(ref: PageRef, revision: RevisionId) : this(ref, revision, null)
    }

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

    /**
     * The run stopped before this page was worked on.
     *
     * Only for pages already taken from the source when the run stopped. A stopped run asks the source for no
     * more, so a stopped sweep of a large category reports a handful of these, not one per page.
     */
    public data class NotAttempted(
        /** The page left as it was. */
        override val ref: PageRef,
        /** Why the run stopped. */
        val reason: StopReason,
    ) : PageOutcome
}

/**
 * What a run did.
 *
 * Counts rather than a list of every outcome: a category sweep is hundreds of thousands of pages, and one
 * object per page would grow with the run. `Pending` is the worst of them, holding the new text *and* the
 * old.
 *
 * A caller that wants every outcome takes [BotRunBuilder.onOutcome], which is given each one as it happens.
 */
public data class BotReport(
    /** How many pages were handled. */
    val processed: Int = 0,
    /** How many edits were saved. */
    val saved: Int = 0,
    /** How many edits were computed but not sent, which is every edit in a dry run. */
    val pending: Int = 0,
    /** How many were left alone, whether deliberately or because they did not exist. */
    val skipped: Int = 0,
    /** How many the wiki refused. */
    val refused: Int = 0,
    /** How many failed for a reason that was not about the page. */
    val failed: Int = 0,
    /**
     * The refusals and failures, up to [PROBLEM_LIMIT] of them.
     *
     * The counts above stay exact however many are kept here; [problemsTruncated] says when this list is not.
     */
    val problems: List<PageOutcome> = emptyList(),
    /** Whether the run ended early; [stopReason] says why. */
    val stopped: Boolean = false,
    /** How many pages taken from the source were not worked on because the run had stopped. */
    val notAttempted: Int = 0,
    /** Why the run stopped, or `null` if it did not. */
    val stopReason: StopReason? = null,
) {
    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        processed: Int = 0,
        saved: Int = 0,
        pending: Int = 0,
        skipped: Int = 0,
        refused: Int = 0,
        failed: Int = 0,
        problems: List<PageOutcome> = emptyList(),
        stopped: Boolean = false,
    ) : this(processed, saved, pending, skipped, refused, failed, problems, stopped, notAttempted = 0)

    /** Whether there were more refusals and failures than [problems] holds. */
    val problemsTruncated: Boolean
        get() = refused + failed > problems.size

    /** Whether every page was handled without a refusal or a failure. */
    val clean: Boolean
        get() = refused == 0 && failed == 0

    override fun toString(): String =
        "processed=$processed saved=$saved pending=$pending skipped=$skipped " +
            "refused=$refused failed=$failed" +
            if (stopped) " (stopped early: ${stopReason?.name?.lowercase()}, $notAttempted not attempted)"
            else ""

    /** How many refusals and failures a report keeps. */
    public companion object {
        /** The cap on [problems]. */
        public const val PROBLEM_LIMIT: Int = 100
    }
}

/**
 * The check that decides whether the bot may keep writing.
 *
 * Fail-closed by design: a check that cannot be performed stops the run. A bot that keeps editing because its
 * emergency stop was unreachable is exactly the failure this prevents.
 */
public fun interface StopPolicy {

    /** Returns `true` when the bot may continue. Throwing is treated as "stop". */
    public suspend fun mayContinue(): Boolean

    /**
     * Fails if the policy already says stop, for a bot to call before it starts work.
     *
     * A run checks before every save on its own. This is for the work before a run, such as building a list
     * of pages or reading a dump, that a stopped bot should not do either.
     *
     * @throws IllegalStateException if the bot may not continue, or the policy could not be read.
     */
    public suspend fun check() {
        val allowed = runCatching {
            mayContinue()
        }
            .getOrElse { if (it is CancellationException) throw it else false }
        if (!allowed) throw IllegalStateException("the stop policy says the bot must not run")
    }

    /** The defaults a run uses when a caller sets nothing. */
    public companion object {
        /** No stop check. Only for dry runs and for wikis you own. */
        public val NONE: StopPolicy = StopPolicy { true }

        /**
         * Stops when a page on the wiki says anything other than `false`.
         *
         * The convention `User:MyBot/Stop` uses: an administrator empties or edits that page and the bot
         * halts within one edit. Read fresh every time, never from a response cache, which may be hours old.
         */
        public fun page(pages: PageService, ref: PageRef): StopPolicy = StopPolicy {
            pages.freshContents(listOf(ref))[ref]?.text?.trim().equals("false", ignoreCase = true)
        }
    }
}

/** Configures a run. */
@KwikibotDsl
public class BotRunBuilder internal constructor() {

    internal var source: Flow<PageRef>? = null
    internal var transform: (suspend (PageContent) -> Edit?)? = null
    internal var create: (suspend (PageRef) -> Edit?)? = null

    /**
     * Whether to compute edits without sending them.
     *
     * On by default. A bot that edits on its first run because someone forgot a flag is a bot that has
     * already made its mistakes.
     */
    public var dryRun: Boolean = true

    /**
     * How many pages, or batches of [readBatch] pages, to work on at once.
     *
     * This bounds the reads and, with them, the transforms: computing an edit runs on `Dispatchers.Default`
     * rather than on the thread the run was started from, so this many pages may genuinely be parsed at the
     * same moment on different cores.
     *
     * A transform that only reads its [PageContent] and returns an [Edit] needs nothing from you. One that
     * writes to something shared - a counter, a set of seen titles, a log - has to say so itself, because
     * nothing here serialises it.
     */
    public var readConcurrency: Int = DEFAULT_READ_CONCURRENCY

    /**
     * How many pages to read per request.
     *
     * One by default, which asks the wiki for a page at a time. Raising it fetches that many together, and
     * for a run that reads far more pages than it edits the difference is the whole cost of the run: a sweep
     * of 283,000 entries is 5,660 requests at fifty rather than 283,000 at one. The account's own cap still
     * applies: [PageService.contents] splits a batch larger than the 500 titles an account with
     * `apihighlimits` may name, such as a bot, or the 50 any other may. This setting is about how many round
     * trips a run makes, not how large a request may be.
     *
     * A batch is fetched together, so a failure to fetch fails the batch rather than a page. Once fetched,
     * its pages are worked on side by side and each is reported when done, so outcomes arrive in the order
     * pages finish, not the order the source gave them.
     */
    public var readBatch: Int = 1

    /** How many edits to have in flight at once. */
    public var writeConcurrency: Int = 1

    /**
     * Stop after this many pages.
     *
     * Applied to the source, so a capped run over a category of a million pages reads only what it needs
     * rather than listing the lot and discarding most of it.
     */
    public var limit: Int? = null

    /** Checked before every save; see [StopPolicy]. */
    public var stopPolicy: StopPolicy = StopPolicy.NONE

    /**
     * Whether to honour `{{nobots}}` and `{{bots}}`, and as which bot.
     *
     * Should be set. Wikis block bots that edit pages carrying `{{nobots}}`. It is `null` here only because
     * this builder does not know the account name — the session does, so a bot sets
     * `BotPolicy(wiki.identity.name)` once it has opened the wiki.
     */
    public var exclusionPolicy: BotPolicy? = null

    /** Called as each page is finished, for progress and logging. */
    public var onOutcome: ((PageOutcome) -> Unit)? = null

    /** Where the run records each outcome as it happens, so it can be resumed; see [RunState]. */
    public var state: RunState? = null

    /**
     * Whether to carry on from where a run into the same [state] stopped.
     *
     * Pages it finished are skipped before they are read, and a page read at the revision its outcome was
     * decided on is left alone without running the transform. [limit] counts only the pages left.
     */
    public var resume: Boolean = false

    /** The pages to work through. */
    public fun source(pages: Flow<PageRef>) {
        source = pages
    }

    /**
     * Computes the edit for one page, or returns `null` to leave it alone.
     *
     * Returning `null` is the normal way to skip: most pages a bot looks at need nothing done.
     *
     * Runs on `Dispatchers.Default`, up to [readConcurrency] pages at a time, so this may be called on
     * several threads at once. Anything it shares with itself needs its own protection.
     */
    public fun transform(block: suspend (PageContent) -> Edit?) {
        transform = block
    }

    /**
     * Computes the page to create where the source names one that does not exist, or returns `null` to leave
     * it missing.
     *
     * Without this, a missing page is reported as [PageOutcome.Missing]. An edit to an existing page is sent
     * so that the wiki refuses it if the page was deleted after it was read. An edit from here is the
     * reverse: the wiki refuses it if somebody created the page in the meantime.
     */
    public fun createMissing(block: suspend (PageRef) -> Edit?) {
        create = block
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
 * [BotRunBuilder.readConcurrency]; writes are bounded separately and paced by the wiki's throttle, so a run
 * overlaps its reads without ever hammering the wiki with edits.
 *
 * A run that saves reads every page fresh, never from a response cache: an edit is computed from the text it
 * replaces, and that has to be the text the wiki has now.
 *
 * The run stops at the first refusal it cannot attribute to the page, such as a session that cannot be
 * restored or a wiki in read-only mode, rather than grinding through thousands of pages failing the same way.
 * Once stopped, it takes no more pages from the source.
 */
public suspend fun botRun(pages: PageService, block: BotRunBuilder.() -> Unit): BotReport {
    val config = BotRunBuilder().apply(block)
    val source = requireNotNull(config.source) { "a run needs a source of pages" }
    val transform = requireNotNull(config.transform) { "a run needs a transform" }

    // Fail-closed before anything is read, so a stopped bot does not even start.
    if (!config.dryRun) {
        check(stopCheck(config.stopPolicy) == null) { "the stop policy refused; not starting" }
    }

    val runner = Runner(pages, config, transform)
    return runner.run(source)
}

/** Runs a bot over the pages of a wiki. */
public suspend fun Wiki.botRun(block: BotRunBuilder.() -> Unit): BotReport = botRun(pages, block)

/** Why [policy] says to stop, or `null` if it says to go on. A check that throws is a reason to stop. */
private suspend fun stopCheck(policy: StopPolicy): StopReason? = runCatching {
    policy.mayContinue()
}
    .fold(onSuccess = { if (it) null else StopReason.POLICY }, onFailure = { StopReason.POLICY_UNREACHABLE })

private class Runner(
    private val pages: PageService,
    private val config: BotRunBuilder,
    private val transform: suspend (PageContent) -> Edit?,
) {
    private val batches = Semaphore(config.readConcurrency)
    private val writes = Semaphore(config.writeConcurrency)
    private val lock = Mutex()

    private var processed = 0
    private var saved = 0
    private var pending = 0
    private var skipped = 0
    private var refused = 0
    private var failed = 0
    private var notAttempted = 0
    private val problems = mutableListOf<PageOutcome>()

    /** Why the run stopped; the first reason found wins. */
    private val stopReason = AtomicReference<StopReason?>(null)

    private val stopped: Boolean
        get() = stopReason.get() != null

    suspend fun run(source: Flow<PageRef>): BotReport {
        val state = config.state
        val remaining =
            if (config.resume && state != null) source.filter { !state.isFinished(it) } else source
        val limited = config.limit?.let { remaining.take(it) } ?: remaining

        channelFlow {
            limited
                // A stopped run asks the source for no more pages, so it does not go on listing a
                // category it will never edit.
                .takeWhile { !stopped }
                .chunked(config.readBatch)
                .collect { batch ->
                    batches.acquire()
                    launch {
                        try {
                            work(batch) { send(it) }
                        } finally {
                            batches.release()
                        }
                    }
                }
        }
            .collect { record(it) }

        return BotReport(
            processed = processed,
            saved = saved,
            pending = pending,
            skipped = skipped,
            refused = refused,
            failed = failed,
            problems = problems.toList(),
            stopped = stopped,
            notAttempted = notAttempted,
            stopReason = stopReason.get(),
        )
    }

    /**
     * One batch: read it, then work on its pages side by side, reporting each as it is done.
     *
     * A batch of one is read as a single page, so a run that has not asked for batching asks the wiki for one
     * page at a time.
     */
    private suspend fun work(batch: List<PageRef>, send: suspend (PageOutcome) -> Unit) {
        if (stopped) return batch.forEach { send(notAttempted(it)) }

        val contents =
            try {
                read(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WikiError) {
                // The batch is one request, so a failure to fetch is a failure for all of it.
                stopOn(e)
                return batch.forEach { send(PageOutcome.Failed(it, e)) }
            }

        if (batch.size == 1) return send(recorded(batch.single(), contents[batch.single()]))

        coroutineScope { batch.forEach { ref -> launch { send(recorded(ref, contents[ref])) } } }
    }

    /** One page, written to the run's [RunState] before it is reported, with the revision it was read at. */
    private suspend fun recorded(ref: PageRef, content: PageContent?): PageOutcome =
        page(ref, content).also { config.state?.record(it, content?.revisionId) }

    /**
     * The batch's pages as the wiki has them.
     *
     * A run that saves reads past any response cache, since an edit replaces the text it was computed from
     * and a cache can be hours behind. A dry run may use one, which is what a cache is for while a bot is
     * being written.
     */
    private suspend fun read(batch: List<PageRef>): Map<PageRef, PageContent> =
        when {
            !config.dryRun -> pages.freshContents(batch)
            batch.size == 1 -> {
                val ref = batch.single()
                pages.content(ref)?.let { mapOf(ref to it) }.orEmpty()
            }
            else -> pages.contents(batch)
        }

    /** One page, from what was read of it. */
    private suspend fun page(ref: PageRef, content: PageContent?): PageOutcome {
        val create = config.create
        return try {
            when {
                stopped -> notAttempted(ref)
                content != null -> decide(ref, content)
                create != null -> create(ref, create)
                else -> PageOutcome.Missing(ref)
            }
        } catch (skip: SkipPage) {
            PageOutcome.Skipped(ref, skip.reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WikiError) {
            stopOn(e)
            PageOutcome.Failed(ref, e)
        }
    }

    /** Why a policy refused a page, carried back out of the dispatched block. */
    private class Refusal(val reason: String)

    /** What to do with a page whose content has been read. */
    private suspend fun decide(ref: PageRef, content: PageContent): PageOutcome {
        // Already decided at this revision, and the transform would decide the same again.
        if (config.resume && config.state?.pin(ref) == content.revisionId) return PageOutcome.Unchanged(ref)

        // Parsing a page and working out an edit is the one part of a run that is real work for
        // the processor rather than waiting on a wiki, and `suspend fun main` gives a bot a
        // single thread. Left where it lands, every page's parse would queue behind the last -
        // the reads would overlap and the thinking would not. Dispatchers.Default is what makes
        // readConcurrency mean pages in parallel rather than only requests in flight.
        val edit =
            withContext(Dispatchers.Default) {
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
            edit.section == null && edit.text == content.text -> PageOutcome.Unchanged(ref)
            config.dryRun -> PageOutcome.Pending(ref, edit, content.text, content.revisionId)
            else -> save(ref, content, edit)
        }
    }

    /** A page the source named that does not exist, offered to [BotRunBuilder.createMissing]. */
    private suspend fun create(ref: PageRef, create: suspend (PageRef) -> Edit?): PageOutcome {
        val edit = withContext(Dispatchers.Default) { create(ref) } ?: return PageOutcome.Missing(ref)
        val creation = edit.copy(createOnly = true)

        return if (config.dryRun) PageOutcome.Pending(ref, creation, before = "")
        else save(ref, null, creation)
    }

    /** Records that the run must stop, if [error] means it must. */
    private fun stopOn(error: WikiError) {
        val reason =
            when (error) {
                is WikiError.Auth -> StopReason.AUTH
                is WikiError.ReadOnly -> StopReason.READ_ONLY
                is WikiError.Configuration -> StopReason.CONFIGURATION
                // A transport hiccup or an error about this page is worth recording and moving on from.
                is WikiError.Transport,
                is WikiError.Api,
                is WikiError.Page -> null
            }
        reason?.let { stopReason.compareAndSet(null, it) }
    }

    private fun notAttempted(ref: PageRef): PageOutcome =
        PageOutcome.NotAttempted(ref, stopReason.get() ?: StopReason.POLICY)

    /**
     * Saves [edit], as an update to [content] or, with no content, as a page that must not exist yet.
     *
     * An update is sent with the revision it was computed from and with `nocreate`. The wiki merges an edit
     * to a page changed since with the change made in between if it can, and refuses it as a conflict if it
     * cannot; a page deleted since is refused, not recreated.
     */
    private suspend fun save(ref: PageRef, content: PageContent?, edit: Edit): PageOutcome {
        // Checked before every save, not once at the start: a run can last hours, and an
        // administrator stopping the bot expects it to stop within one edit.
        stopCheck(config.stopPolicy)?.let { reason ->
            stopReason.compareAndSet(null, reason)
            return notAttempted(ref)
        }

        return writes.withPermit {
            // Re-checked here, under the permit, and not only when the page was picked up.
            // Pages are worked on in parallel, so by the time this one reaches the front of the
            // queue another may have found the session dead - and the whole point of stopping a
            // run is not to make the next edit after the reason to stop is known.
            if (stopped) return@withPermit notAttempted(ref)

            val outcome =
                pages.edit(ref) {
                    fill(edit)
                    if (content != null) {
                        baseRevision = content.revisionId
                        noCreate = !edit.createOnly
                    }
                }

            when (outcome) {
                is EditOutcome.Saved ->
                    PageOutcome.Saved(
                        ref,
                        outcome.revision,
                        outcome.previousRevision ?: content?.revisionId,
                        edit,
                        content?.text.orEmpty(),
                    )
                is EditOutcome.NoChange -> PageOutcome.Unchanged(ref)
                is EditOutcome.Refused -> PageOutcome.Refused(ref, outcome)
            }
        }
    }

    /** Counts one outcome, keeps it if it is a refusal or a failure, and hands it to `onOutcome`. */
    private suspend fun record(outcome: PageOutcome) {
        lock.withLock {
            if (outcome !is PageOutcome.NotAttempted) processed++
            when (outcome) {
                is PageOutcome.Saved -> saved++
                is PageOutcome.Pending -> pending++
                is PageOutcome.Skipped,
                is PageOutcome.Missing -> skipped++
                is PageOutcome.Refused -> refused++
                is PageOutcome.Failed -> failed++
                is PageOutcome.NotAttempted -> notAttempted++
                is PageOutcome.Unchanged -> Unit
            }

            val wentWrong = outcome is PageOutcome.Refused || outcome is PageOutcome.Failed
            if (wentWrong && problems.size < BotReport.PROBLEM_LIMIT) {
                problems += outcome
            }
        }

        config.onOutcome?.invoke(outcome)
    }
}

/** Applies an [Edit]'s fields to a page edit. */
internal fun EditBuilder.fill(edit: Edit) {
    text = edit.text
    summary = edit.summary
    minor = edit.minor
    tags = edit.tags
    section = edit.section
    createOnly = edit.createOnly
}
