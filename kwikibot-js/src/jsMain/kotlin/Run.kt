import com.fenakhay.kwikibot.bindings.bot.RunChange
import com.fenakhay.kwikibot.bindings.bot.problemOf
import com.fenakhay.kwikibot.bindings.bot.runWithDefaults
import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bot.run.BotReport
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.model.title.NamespaceMap

/** The run `Wiki.run` describes, on [wiki]. */
internal suspend fun runBot(
    wiki: ClientWiki,
    titles: Array<String>,
    summary: String,
    transform: (text: String, title: String) -> Awaitable<String?>,
    save: Boolean,
    onChange: ((change: Change) -> Unit)?,
    stopPage: String? = null,
    stopPolicy: StopPolicy? = null,
    maxEdits: Int? = null,
    onOutcome: ((PageOutcome) -> Unit)? = null,
): RunReport {
    val report =
        wiki.runWithDefaults(
            titles.asList(),
            summary,
            { text, title ->
                when (val changed = settledCall { transform(text, title) }) {
                    null -> null
                    is String -> changed
                    else ->
                        throw IllegalArgumentException(
                            "the transform for $title returned neither text nor null"
                        )
                }
            },
            save,
            onChange?.let { changes(it) },
            stopPage,
            stopPolicy,
            maxEdits,
            onOutcome,
        )
    return RunReport(report, wiki.namespaces)
}

/** [onChange], as the run calls it with each change, its rejections reported. */
private fun changes(onChange: (change: Change) -> Unit): (RunChange) -> Unit {
    val rejections = ObserverReport("onChange")
    return { changed ->
        fromJs {
            rejections.reportRejection(
                onChange(Change(changed.title, changed.diff, changed.revision?.value?.toDouble()))
                    .unsafeCast<Any?>()
            )
        }
    }
}

/** What a run did, in counts, with its refusals and failures. */
@JsExport
public class RunReport
internal constructor(private val report: BotReport, private val namespaces: NamespaceMap) {

    /** How many pages were handled. */
    public val processed: Int
        get() = report.processed

    /** How many edits were saved. */
    public val saved: Int
        get() = report.saved

    /** How many edits were computed but not saved, which in a dry run is every one. */
    public val pending: Int
        get() = report.pending

    /** How many pages were left alone, deliberately or because they did not exist. */
    public val skipped: Int
        get() = report.skipped

    /** How many edits the wiki refused. */
    public val refused: Int
        get() = report.refused

    /** How many pages failed for a reason that was not about the page. */
    public val failed: Int
        get() = report.failed

    /** How many pages were not worked on because the run had stopped. */
    public val notAttempted: Int
        get() = report.notAttempted

    /**
     * Why the run stopped early, such as `"policy"`, which a reached `maxEdits` also says, or `null` when it
     * did not.
     */
    public val stopReason: StopReason?
        get() = report.stopReason?.argumentName().unsafeCast<StopReason?>()

    /** The refusals and failures, up to the first hundred. */
    public val problems: Array<Problem>
        get() =
            report.problems
                .mapNotNull { problemOf(it, namespaces) }
                .map { Problem(it.title, it.kind.unsafeCast<ProblemKind>(), it.detail) }
                .toTypedArray()
}

/** One page a run changed, or would change. */
@JsExport
public class Change
internal constructor(
    /** The page's title. */
    public val title: String,
    /** The change as a unified diff. */
    public val diff: String,
    /** The revision saved, or `null` when the run did not save it. */
    public val revision: Double?,
)

/** One page a run could not do. */
@JsExport
public class Problem
internal constructor(
    /** The page's title. */
    public val title: String,
    /** `"refused"` when the wiki refused the edit, `"failed"` when the wiki could not serve the page. */
    public val kind: ProblemKind,
    /** What went wrong. */
    public val detail: String,
)
