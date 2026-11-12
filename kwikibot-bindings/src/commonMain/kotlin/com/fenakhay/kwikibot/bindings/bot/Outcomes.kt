package com.fenakhay.kwikibot.bindings.bot

import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bot.fix.Diffs
import com.fenakhay.kwikibot.bot.run.Edit
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.title.NamespaceMap

/**
 * What became of one page, as a binding spells it: `"missing"`, `"skipped"`, `"unchanged"`, `"pending"`,
 * `"saved"`, `"refused"`, `"failed"` or `"not-attempted"`.
 */
public fun kindOf(outcome: PageOutcome): String =
    when (outcome) {
        is PageOutcome.Missing -> "missing"
        is PageOutcome.Skipped -> "skipped"
        is PageOutcome.Unchanged -> "unchanged"
        is PageOutcome.Pending -> "pending"
        is PageOutcome.Saved -> "saved"
        is PageOutcome.Refused -> "refused"
        is PageOutcome.Failed -> "failed"
        is PageOutcome.NotAttempted -> "not-attempted"
    }

/** On a skipped page, why; on one not attempted, why the run stopped, spelled as a binding spells it. */
public fun reasonOf(outcome: PageOutcome): String? =
    when (outcome) {
        is PageOutcome.Skipped -> outcome.reason
        is PageOutcome.NotAttempted -> outcome.reason.argumentName()
        else -> null
    }

/** On a pending or a saved page, the edit. */
public fun editOf(outcome: PageOutcome): Edit? =
    when (outcome) {
        is PageOutcome.Pending -> outcome.edit
        is PageOutcome.Saved -> outcome.edit
        else -> null
    }

/** On a pending or a saved page, the text as it stood, empty for a page not yet created. */
public fun beforeOf(outcome: PageOutcome): String? =
    when (outcome) {
        is PageOutcome.Pending -> outcome.before
        is PageOutcome.Saved -> outcome.before
        else -> null
    }

/** On a pending page, the revision the edit was computed from. */
public fun baseRevisionOf(outcome: PageOutcome): RevisionId? = (outcome as? PageOutcome.Pending)?.baseRevision

/** On a saved page, the revision the edit produced. */
public fun revisionOf(outcome: PageOutcome): RevisionId? = (outcome as? PageOutcome.Saved)?.revision

/** On a saved page, the revision it replaced. */
public fun previousRevisionOf(outcome: PageOutcome): RevisionId? =
    (outcome as? PageOutcome.Saved)?.previousRevision

/** On a refused page, what the wiki answered. */
public fun refusalOf(outcome: PageOutcome): EditOutcome? = (outcome as? PageOutcome.Refused)?.outcome

/** On a failed page, what went wrong. */
public fun errorOf(outcome: PageOutcome): Throwable? = (outcome as? PageOutcome.Failed)?.error

/**
 * One page a plain run changed, or would change.
 *
 * @property title the page's title.
 * @property diff the change as a unified diff, empty when the run did not keep the texts.
 * @property revision the revision saved, or `null` when the run did not save it.
 */
public data class RunChange(val title: String, val diff: String, val revision: RevisionId?)

/** What [outcome] changed, titled with [namespaces], or `null` when it changed nothing. */
public fun changeOf(outcome: PageOutcome, namespaces: NamespaceMap): RunChange? {
    val title = namespaces.format(outcome.ref.title)
    return when (outcome) {
        is PageOutcome.Pending ->
            RunChange(title, Diffs.unified(outcome.before, outcome.edit.text, title), null)
        is PageOutcome.Saved -> {
            val before = outcome.before
            val edit = outcome.edit
            val diff = if (before != null && edit != null) Diffs.unified(before, edit.text, title) else ""
            RunChange(title, diff, outcome.revision)
        }
        is PageOutcome.Unchanged,
        is PageOutcome.Skipped,
        is PageOutcome.Missing,
        is PageOutcome.Refused,
        is PageOutcome.Failed,
        is PageOutcome.NotAttempted -> null
    }
}

/**
 * One page a plain run could not do.
 *
 * @property title the page's title.
 * @property kind `"refused"` when the wiki refused the edit, `"failed"` when the wiki could not serve the
 *   page.
 * @property detail what went wrong.
 */
public data class RunProblem(val title: String, val kind: String, val detail: String)

/** What went wrong with [outcome], titled with [namespaces], or `null` when nothing did. */
public fun problemOf(outcome: PageOutcome, namespaces: NamespaceMap): RunProblem? {
    val title = namespaces.format(outcome.ref.title)
    return when (outcome) {
        is PageOutcome.Refused -> RunProblem(title, "refused", outcome.outcome.detail)
        is PageOutcome.Failed ->
            RunProblem(title, "failed", outcome.error.message ?: outcome.error.toString())
        is PageOutcome.Pending,
        is PageOutcome.Saved,
        is PageOutcome.Unchanged,
        is PageOutcome.Skipped,
        is PageOutcome.Missing,
        is PageOutcome.NotAttempted -> null
    }
}
