import com.fenakhay.kwikibot.bindings.bot.baseRevisionOf
import com.fenakhay.kwikibot.bindings.bot.beforeOf
import com.fenakhay.kwikibot.bindings.bot.botRunWithDefaults
import com.fenakhay.kwikibot.bindings.bot.editOf
import com.fenakhay.kwikibot.bindings.bot.errorOf
import com.fenakhay.kwikibot.bindings.bot.kindOf
import com.fenakhay.kwikibot.bindings.bot.previousRevisionOf
import com.fenakhay.kwikibot.bindings.bot.reasonOf
import com.fenakhay.kwikibot.bindings.bot.refusalOf
import com.fenakhay.kwikibot.bindings.bot.revisionOf
import com.fenakhay.kwikibot.bindings.common.Setting
import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bot.BotPolicy as ClientBotPolicy
import com.fenakhay.kwikibot.bot.run.BotReport as ClientBotReport
import com.fenakhay.kwikibot.bot.run.BotRunBuilder
import com.fenakhay.kwikibot.bot.run.Edit
import com.fenakhay.kwikibot.bot.run.PageOutcome as ClientPageOutcome
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.NamespaceMap

/** The edit a bot decided to make to one page. */
@JsExport
public external interface PageEdit {
    /** The new text: of the whole page, or of [section] when one is named. */
    public val text: String

    /** The edit summary, which is what a watchlist reader sees. */
    public val summary: String

    /** Whether to mark it minor; not unless given. */
    public val minor: Boolean?

    /** Change tags to mark the edit with, each one the wiki has defined for manual use. */
    public val tags: Array<String>?

    /**
     * The section [text] replaces, by the index `RenderService.sections` reports, or `"new"` to add one with
     * the summary as its heading. The whole page unless given.
     */
    public val section: String?

    /**
     * Whether the page must not exist yet, so the edit is refused rather than overwriting one created in the
     * meantime. Set on every edit `createMissing` makes.
     */
    public val createOnly: Boolean?
}

/** Everything a run can be told, as `Wiki.botRun` takes it. */
@JsExport
public external interface BotRunOptions {
    /** The pages to work through. */
    public val source: PageSource

    /**
     * Computes the edit for one page, or returns `null` to leave it alone, and may return a promise of
     * either. `skipPage` leaves it alone with a reason, which the outcome records. Throwing anything else
     * stops the run, which rejects with that error.
     */
    public val transform: (page: Page) -> Awaitable<PageEdit?>

    /**
     * Computes the page to create where the source names one that does not exist, or returns `null` to leave
     * it missing, and may return a promise of either. A missing page is reported as `"missing"` unless given.
     */
    public val createMissing: ((title: String) -> Awaitable<PageEdit?>)?

    /** Whether to compute edits without sending them; `true` unless given. */
    public val dryRun: Boolean?

    /** How many pages, or batches of [readBatch] pages, to work on at once; 4 unless given. */
    public val readConcurrency: Int?

    /** How many pages to read per request; 1 unless given. */
    public val readBatch: Int?

    /** How many edits to have in flight at once; 1 unless given. */
    public val writeConcurrency: Int?

    /** Stops after this many pages, read from the source only as far as that. */
    public val limit: Int?

    /**
     * How many edits to save at most. The run stops with `"policy"` on reaching it. A save the wiki refuses
     * still counts.
     */
    public val maxEdits: Int?

    /**
     * How many times to try a page again when its save meets an edit conflict, reading it again and running
     * the exclusion check and the transform on what it says now, so the transform can run more than once for
     * a page. Two unless given; `0` reports the conflict as a refusal.
     */
    public val conflictRetries: Int?

    /**
     * Checked before every save and before a saving run starts. Unless given, the wiki cannot stop the run,
     * and one that saves logs a warning saying so.
     */
    public val stopPolicy: StopPolicy?

    /**
     * Whether to honour `{{nobots}}` and `{{bots}}`, and as which bot. As this account unless given; `null`
     * opts out.
     */
    public val exclusionPolicy: BotPolicy?

    /**
     * Called as each page finishes, and two calls never overlap. What it returns is not waited for: the run
     * goes on at once, so a promise it returns runs beside the run. Throwing stops the run.
     */
    public val onOutcome: ((outcome: PageOutcome) -> Unit)?

    /** Where the run records each outcome as it happens, so it can be resumed. */
    public val state: RunState?

    /** Whether to carry on from where a run into the same [state] stopped; not unless given. */
    public val resume: Boolean?

    /**
     * Flags that set whether the run saves, how far it goes, its stop page and its state. What this object
     * sets itself comes after them, and wins.
     */
    public val common: CommonOptions?

    /**
     * A configuration whose `[run]` section sets the run's concurrency and batch. What this object sets
     * itself comes after it, and wins.
     */
    public val config: BotConfig?
}

/** The largest number of refusals and failures a report keeps in its `problems`. */
@JsExport public const val BOT_REPORT_PROBLEM_LIMIT: Int = ClientBotReport.PROBLEM_LIMIT

/** What a run did. A caller that wants every page takes `onOutcome`. */
@JsExport
public class BotReport
internal constructor(private val report: ClientBotReport, private val namespaces: NamespaceMap) {

    /** How many pages were handled. */
    public val processed: Int
        get() = report.processed

    /** How many edits were saved. */
    public val saved: Int
        get() = report.saved

    /** How many edits were computed but not sent, which is every edit in a dry run. */
    public val pending: Int
        get() = report.pending

    /** How many were left alone, whether deliberately or because they did not exist. */
    public val skipped: Int
        get() = report.skipped

    /** How many the wiki refused. */
    public val refused: Int
        get() = report.refused

    /** How many failed for a reason that was not about the page. */
    public val failed: Int
        get() = report.failed

    /** The refusals and failures, up to `BOT_REPORT_PROBLEM_LIMIT` of them. */
    public val problems: Array<PageOutcome>
        get() = report.problems.map { PageOutcome(it, namespaces) }.toTypedArray()

    /** Whether there were more refusals and failures than [problems] holds. */
    public val problemsTruncated: Boolean
        get() = report.problemsTruncated

    /** Whether the run ended early. */
    public val stopped: Boolean
        get() = report.stopped

    /** How many pages taken from the source were not worked on because the run had stopped. */
    public val notAttempted: Int
        get() = report.notAttempted

    /**
     * Why the run stopped, or `null` if it did not: `"policy"`, `"policy-unreachable"`, `"auth"`,
     * `"read-only"`, `"configuration"` or `"refused"`.
     */
    public val stopReason: StopReason?
        get() = report.stopReason?.argumentName().unsafeCast<StopReason?>()

    /** Whether every page was handled without a refusal or a failure. */
    public val clean: Boolean
        get() = report.clean

    /** The counts as a line of text. */
    override fun toString(): String = report.toString()
}

/** What became of one page in a run. */
@JsExport
public class PageOutcome
internal constructor(internal val outcome: ClientPageOutcome, private val namespaces: NamespaceMap) {

    /**
     * `"missing"`, `"skipped"`, `"unchanged"`, `"pending"` for an edit a dry run computed, `"saved"`,
     * `"refused"`, `"failed"`, or `"not-attempted"` for a page in hand when the run stopped.
     */
    public val kind: PageOutcomeKind
        get() = kindOf(outcome).unsafeCast<PageOutcomeKind>()

    /** The page's title, prefix included. */
    public val title: String
        get() = namespaces.format(outcome.ref.title)

    /**
     * On a `"skipped"`, why. On a `"not-attempted"`, why the run stopped, as `BotReport.stopReason` spells
     * it.
     */
    public val reason: String?
        get() = reasonOf(outcome)

    /** On a `"pending"` or a `"saved"`, the edit. */
    public val edit: PageEdit?
        get() = editOf(outcome)?.let(::pageEditOf)

    /** On a `"pending"` or a `"saved"`, the text as it stood. Empty for a page not yet created. */
    public val before: String?
        get() = beforeOf(outcome)

    /** On a `"pending"`, the revision the edit was computed from, `null` for a page not yet created. */
    public val baseRevision: Double?
        get() = baseRevisionOf(outcome)?.value?.toDouble()

    /** On a `"saved"`, the revision the edit produced. */
    public val revision: Double?
        get() = revisionOf(outcome)?.value?.toDouble()

    /** On a `"saved"`, the revision it replaced, `null` for a page the edit created. */
    public val previousRevision: Double?
        get() = previousRevisionOf(outcome)?.value?.toDouble()

    /** On a `"refused"`, what the wiki said, which tells a conflict from a filter. */
    public val refusal: EditResult?
        get() = refusalOf(outcome)?.let(::editResult)

    /** On a `"failed"`, what went wrong, which `describeError` reads. */
    public val error: Throwable?
        get() = errorOf(outcome)
}

/** Thrown by `skipPage` to abandon one page with a reason. */
@JsExport
public class SkipPageError(
    /** Why the page was left alone, which the outcome records. */
    public val reason: String
) : Throwable(reason)

/** Leaves the page a transform is working on alone, with a reason the outcome records. Does not return. */
@JsExport public fun skipPage(reason: String): Nothing = throw SkipPageError(reason)

/** A function reporting each outcome to every one of [handlers], in order, as `onOutcome` takes one. */
@JsExport
public fun reportTo(handlers: Array<(outcome: PageOutcome) -> Unit>): (outcome: PageOutcome) -> Unit =
    { outcome ->
        handlers.forEach { it(outcome) }
    }

/** The run [options] describe, on [wiki]. */
internal suspend fun botRunWith(wiki: ClientWiki, options: BotRunOptions): BotReport {
    val namespaces = wiki.namespaces
    val report =
        wiki.botRunWithDefaults(
            transform = { page ->
                try {
                    editFrom(settledCall { options.transform(Page(page, namespaces)) })
                } catch (e: SkipPageError) {
                    skip(e.reason)
                }
            },
            source = options.source.source,
            createMissing = options.createMissing?.let { create -> creating(create, namespaces) },
            dryRun = options.dryRun,
            readConcurrency = options.readConcurrency,
            readBatch = options.readBatch,
            writeConcurrency = options.writeConcurrency,
            limit = options.limit,
            maxEdits = options.maxEdits,
            conflictRetries = options.conflictRetries,
            stopPolicy = options.stopPolicy?.policy,
            exclusionPolicy = exclusionOf(options),
            onOutcome =
                options.onOutcome?.let { handler ->
                    val rejections = ObserverReport("onOutcome")
                    val observer: (ClientPageOutcome) -> Unit = { outcome ->
                        fromJs {
                            rejections.reportRejection(
                                handler(PageOutcome(outcome, namespaces)).unsafeCast<Any?>()
                            )
                        }
                    }
                    observer
                },
            state = options.state?.state,
            resume = options.resume,
            common = options.common?.options,
            config = options.config?.config,
        )
    return BotReport(report, namespaces)
}

/** [create], as the run calls it with each missing page. */
private fun creating(
    create: (title: String) -> Awaitable<PageEdit?>,
    namespaces: NamespaceMap,
): suspend BotRunBuilder.(PageRef) -> Edit? = { ref ->
    try {
        editFrom(settledCall { create(namespaces.format(ref.title)) })
    } catch (e: SkipPageError) {
        skip(e.reason)
    }
}

/** What [options] says about exclusions: left alone when it says nothing, which `null` itself does not. */
private fun exclusionOf(options: BotRunOptions): Setting<ClientBotPolicy> {
    val given: dynamic = options.asDynamic().exclusionPolicy
    if (given === undefined) return Setting.Default
    return given.unsafeCast<BotPolicy?>()?.policy?.let { Setting.Value(it) } ?: Setting.Off
}

private fun editFrom(result: Any?): Edit? {
    if (result == null) return null
    val edit = result.unsafeCast<PageEdit>()
    val text: dynamic = edit.text
    val summary: dynamic = edit.summary
    require(jsTypeOf(text) == "string" && jsTypeOf(summary) == "string") {
        "a transform returns null or an object with a text and a summary"
    }
    return Edit(
        edit.text,
        edit.summary,
        edit.minor ?: false,
        edit.tags?.toList().orEmpty(),
        edit.section,
        edit.createOnly ?: false,
    )
}

/** [edit] as a plain object. */
internal fun pageEditOf(edit: Edit): PageEdit {
    val plain: dynamic = js("({})")
    plain.text = edit.text
    plain.summary = edit.summary
    plain.minor = edit.minor
    plain.tags = edit.tags.toTypedArray()
    plain.section = edit.section
    plain.createOnly = edit.createOnly
    return plain.unsafeCast<PageEdit>()
}
