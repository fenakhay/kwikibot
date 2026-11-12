package com.fenakhay.kwikibot.bindings.testing

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.net.auth.Identity
import kotlin.time.Duration

private const val FAKE_WIKI = "testwiki"

/** Who a fake wiki says is logged in unless told: a bot called `TestBot`. */
public val DEFAULT_FAKE_IDENTITY: Identity = Identity(name = "TestBot", id = 1, groups = setOf("bot"))

/** The database name of a fake wiki: [id], or `testwiki` unless given. */
public fun fakeWikiIdOf(id: String? = null): WikiId = WikiId(id ?: FAKE_WIKI)

/**
 * An account a fake wiki says is logged in: [name], numbered 1 and in no group, with no right, unless told.
 */
public fun fakeIdentityOf(
    name: String,
    id: Long? = null,
    groups: Collection<String>? = null,
    rights: Collection<String>? = null,
): Identity =
    Identity(
        name = name,
        id = id ?: 1L,
        groups = groups?.toSet().orEmpty(),
        rights = rights?.toSet().orEmpty(),
    )

/**
 * The refusal a fake page service answers an edit of [ref] with: the one [status] names, as an edit's answer
 * spells it, with [detail], empty unless given, and the fields of that refusal. A protection is not
 * cascading, and a changed page was not deleted, unless told.
 *
 * @throws IllegalArgumentException when [status] names no refusal.
 */
@Suppress("LongParameterList")
public fun refusalNamed(
    ref: PageRef,
    status: String,
    detail: String? = null,
    currentRevision: RevisionId? = null,
    protectionLevel: String? = null,
    isCascadingProtection: Boolean? = null,
    retryAfter: Duration? = null,
    filter: String? = null,
    captchaId: String? = null,
    wasDeleted: Boolean? = null,
    code: String? = null,
): EditOutcome.Refused {
    val said = detail.orEmpty()
    return when (status) {
        "conflict" -> EditOutcome.Conflict(ref, said, currentRevision)
        "protected" -> EditOutcome.Protected(ref, said, protectionLevel, isCascadingProtection ?: false)
        "permission-denied" -> EditOutcome.PermissionDenied(ref, said)
        "rate-limited" -> EditOutcome.RateLimited(ref, said, retryAfter)
        "filtered" -> EditOutcome.Filtered(ref, said, filter)
        "captcha" -> EditOutcome.CaptchaRequired(ref, said, captchaId)
        "page-changed" -> EditOutcome.PageStateChanged(ref, said, wasDeleted ?: false)
        "rejected" -> EditOutcome.Rejected(ref, said, code.orEmpty())
        else ->
            throw IllegalArgumentException(
                "a refusal's status cannot be '$status': it is one of 'conflict', 'protected', " +
                    "'permission-denied', 'rate-limited', 'filtered', 'captcha', 'page-changed', 'rejected'"
            )
    }
}
