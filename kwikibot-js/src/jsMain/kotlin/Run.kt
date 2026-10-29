import com.fenakhay.kwikibot.bot.fix.Diffs
import com.fenakhay.kwikibot.bot.run.BotReport
import com.fenakhay.kwikibot.bot.run.Edit
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.bot.run.botRun
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.model.title.NamespaceMap
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map

/** The run `Wiki.run` describes, on [wiki]. */
internal suspend fun runBot(
    wiki: ClientWiki,
    titles: Array<String>,
    summary: String,
    transform: (text: String, title: String) -> Any?,
    save: Boolean,
    onChange: ((change: Change) -> Unit)?,
): RunReport {
    val report = wiki.botRun {
        source(titles.asList().asFlow().map { wiki.ref(it) })
        dryRun = !save
        onChange?.let { onOutcome = { outcome -> change(outcome, wiki.namespaces)?.let(it) } }
        transform { page ->
            val title = wiki.namespaces.format(page.ref.title)
            when (val text = settled(transform(page.text, title))) {
                null -> null
                is String -> Edit(text, summary)
                else ->
                    throw IllegalArgumentException("the transform for $title returned neither text nor null")
            }
        }
    }
    return RunReport(report, wiki.namespaces)
}

private suspend fun settled(result: Any?): Any? = if (result is Promise<*>) result.await() else result

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

    /** Why the run stopped early, such as `"limit"`, or `null` when it did not. */
    public val stopReason: String?
        get() = report.stopReason?.name?.lowercase()?.replace('_', '-')

    /** The refusals and failures, up to the first hundred. */
    public val problems: Array<Problem>
        get() = report.problems.mapNotNull { problem(it, namespaces) }.toTypedArray()
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
    public val kind: String,
    /** What went wrong. */
    public val detail: String,
)

private fun change(outcome: PageOutcome, namespaces: NamespaceMap): Change? {
    val title = namespaces.format(outcome.ref.title)
    return when (outcome) {
        is PageOutcome.Pending -> Change(title, Diffs.unified(outcome.before, outcome.edit.text, title), null)
        is PageOutcome.Saved -> {
            val before = outcome.before
            val edit = outcome.edit
            val diff = if (before != null && edit != null) Diffs.unified(before, edit.text, title) else ""
            Change(title, diff, outcome.revision.value.toDouble())
        }
        is PageOutcome.Unchanged,
        is PageOutcome.Skipped,
        is PageOutcome.Missing,
        is PageOutcome.Refused,
        is PageOutcome.Failed,
        is PageOutcome.NotAttempted -> null
    }
}

private fun problem(outcome: PageOutcome, namespaces: NamespaceMap): Problem? {
    val title = namespaces.format(outcome.ref.title)
    return when (outcome) {
        is PageOutcome.Refused -> Problem(title, "refused", outcome.outcome.detail)
        is PageOutcome.Failed -> Problem(title, "failed", outcome.error.message ?: outcome.error.toString())
        is PageOutcome.Pending,
        is PageOutcome.Saved,
        is PageOutcome.Unchanged,
        is PageOutcome.Skipped,
        is PageOutcome.Missing,
        is PageOutcome.NotAttempted -> null
    }
}
