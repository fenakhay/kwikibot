@file:Suppress("MatchingDeclarationName")

package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import kotlin.time.Duration

/**
 * How the wiki answered an edit, flattened for a binding: [status] says which, and so which of the other
 * fields are set. Every field a status does not have is `null`, apart from [isRetryable].
 *
 * @property status `"saved"`, `"unchanged"`, or why the wiki refused, as [refusalOf] spells it.
 * @property revision the revision saved, or the current one when nothing changed and the wiki said which.
 * @property previousRevision the revision the save replaced.
 * @property detail what the wiki said when it refused.
 * @property refused the refusal itself, when the wiki refused.
 * @property isRetryable whether trying again, after reloading or after a wait, could succeed.
 * @property currentRevision on a `"conflict"`, the revision the page is on now, when the wiki said.
 * @property protectionLevel on a `"protected"`, the protection level that blocked the edit, when named.
 * @property isCascadingProtection on a `"protected"`, whether the protection comes from a transcluding page.
 * @property filter on a `"filtered"`, the filter name or blacklist entry, when the wiki named one.
 * @property retryAfter on a `"rate-limited"`, how long the wiki asked to wait, when it said.
 * @property captchaId on a `"captcha"`, the captcha the wiki wants solved, when it said.
 * @property wasDeleted on a `"page-changed"`, whether the page was deleted.
 * @property code on a `"rejected"`, the wiki's code for a refusal this library does not model.
 */
@Suppress("LongParameterList")
public data class EditAnswer(
    val status: String,
    val revision: RevisionId? = null,
    val previousRevision: RevisionId? = null,
    val detail: String? = null,
    val refused: EditOutcome.Refused? = null,
    val isRetryable: Boolean = false,
    val currentRevision: RevisionId? = null,
    val protectionLevel: String? = null,
    val isCascadingProtection: Boolean? = null,
    val filter: String? = null,
    val retryAfter: Duration? = null,
    val captchaId: String? = null,
    val wasDeleted: Boolean? = null,
    val code: String? = null,
)

/**
 * What [edit] answers, or a refusal when the wiki rate-limits it: the wiki's client raises that before it
 * reads the edit's answer, but to a caller of a binding's `edit` it is a refusal like any other.
 */
public suspend fun refusingRateLimits(ref: PageRef, edit: suspend () -> EditOutcome): EditOutcome =
    try {
        edit()
    } catch (limited: WikiError.Transport.RateLimited) {
        EditOutcome.RateLimited(ref, limited.message.orEmpty(), limited.retryAfter)
    }

/** [outcome] as a binding reports it. */
public fun answerOf(outcome: EditOutcome): EditAnswer =
    when (outcome) {
        is EditOutcome.Saved -> EditAnswer("saved", outcome.revision, outcome.previousRevision)
        is EditOutcome.NoChange -> EditAnswer("unchanged", outcome.revision)
        is EditOutcome.Refused -> refusedAnswer(outcome)
    }

private fun refusedAnswer(outcome: EditOutcome.Refused): EditAnswer {
    val answer =
        EditAnswer(
            refusalOf(outcome),
            detail = outcome.detail,
            refused = outcome,
            isRetryable = outcome.isRetryable,
        )
    return when (outcome) {
        is EditOutcome.Conflict -> answer.copy(currentRevision = outcome.currentRevision)
        is EditOutcome.Protected ->
            answer.copy(protectionLevel = outcome.level, isCascadingProtection = outcome.cascading)
        is EditOutcome.Filtered -> answer.copy(filter = outcome.filter)
        is EditOutcome.RateLimited -> answer.copy(retryAfter = outcome.retryAfter)
        is EditOutcome.CaptchaRequired -> answer.copy(captchaId = outcome.captchaId)
        is EditOutcome.PageStateChanged -> answer.copy(wasDeleted = outcome.wasDeleted)
        is EditOutcome.Rejected -> answer.copy(code = outcome.code)
        is EditOutcome.PermissionDenied -> answer
    }
}

/**
 * Why the wiki refused [outcome]: `"conflict"`, `"protected"`, `"permission-denied"`, `"rate-limited"`,
 * `"filtered"`, `"captcha"`, `"page-changed"` or `"rejected"`.
 */
public fun refusalOf(outcome: EditOutcome.Refused): String =
    when (outcome) {
        is EditOutcome.Conflict -> "conflict"
        is EditOutcome.Protected -> "protected"
        is EditOutcome.PermissionDenied -> "permission-denied"
        is EditOutcome.RateLimited -> "rate-limited"
        is EditOutcome.Filtered -> "filtered"
        is EditOutcome.CaptchaRequired -> "captcha"
        is EditOutcome.PageStateChanged -> "page-changed"
        is EditOutcome.Rejected -> "rejected"
    }
