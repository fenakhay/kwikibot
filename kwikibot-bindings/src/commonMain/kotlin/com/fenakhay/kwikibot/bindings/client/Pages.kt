@file:Suppress("TooManyFunctions")

package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.ExpandedText
import com.fenakhay.kwikibot.client.service.TemplateSandbox
import com.fenakhay.kwikibot.client.service.WatchMode
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.ActionChecks
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.page.PageRef
import kotlin.time.Instant

/** What a binding's watchlist option means when it is not given. */
private fun watchModeOf(mode: WatchMode?): WatchMode = mode ?: WatchMode.PREFERENCES

/**
 * Applies an edit to [title] and says how the wiki answered, a rate limit included as a refusal. Unless
 * given, the edit is not minor, is flagged as a bot's, may create the page, has no tags, and leaves the
 * watchlist to the account's preferences. Throws without sending anything when the options contradict
 * themselves.
 */
@Suppress("LongParameterList")
public suspend fun Wiki.editWithDefaults(
    title: String,
    text: String? = null,
    appendText: String? = null,
    prependText: String? = null,
    summary: String? = null,
    minor: Boolean? = null,
    bot: Boolean? = null,
    baseRevision: RevisionId? = null,
    noCreate: Boolean? = null,
    createOnly: Boolean? = null,
    tags: Collection<String>? = null,
    section: String? = null,
    sectionTitle: String? = null,
    watchlist: WatchMode? = null,
    watchlistExpiry: String? = null,
): EditOutcome {
    val mode = watchModeOf(watchlist)
    val ref = ref(title)
    return refusingRateLimits(ref) {
        pages.edit(ref) {
            this.text = text
            this.appendText = appendText
            this.prependText = prependText
            this.summary = summary.orEmpty()
            this.minor = minor ?: false
            this.bot = bot ?: true
            this.baseRevision = baseRevision
            this.noCreate = noCreate ?: false
            this.createOnly = createOnly ?: false
            this.tags = tags?.toList().orEmpty()
            this.section = section
            this.sectionTitle = sectionTitle
            this.watchlist = mode
            this.watchlistExpiry = watchlistExpiry
        }
    }
}

/**
 * Renames [from] to [to] and answers with the new title. Unless given, leaves a redirect, moves the talk page
 * but not the subpages, and leaves the watchlist to the account's preferences.
 */
@Suppress("LongParameterList")
public suspend fun Wiki.moveWithDefaults(
    from: String,
    to: String,
    reason: String? = null,
    leaveRedirect: Boolean? = null,
    moveTalk: Boolean? = null,
    moveSubpages: Boolean? = null,
    watchlist: WatchMode? = null,
): String {
    val moved =
        pages.move(
            ref(from),
            ref(to),
            reason.orEmpty(),
            leaveRedirect ?: true,
            moveTalk ?: true,
            moveSubpages ?: false,
            watchModeOf(watchlist),
        )
    return titleOf(moved)
}

/** Deletes [title], without its talk page unless told. */
public suspend fun Wiki.deleteWithDefaults(
    title: String,
    reason: String? = null,
    deleteTalk: Boolean? = null,
    watchlist: WatchMode? = null,
) {
    pages.delete(ref(title), reason.orEmpty(), deleteTalk ?: false, watchModeOf(watchlist))
}

/** Restores every deleted revision of [title], without its talk page unless told. */
public suspend fun Wiki.undeleteWithDefaults(
    title: String,
    reason: String? = null,
    undeleteTalk: Boolean? = null,
    watchlist: WatchMode? = null,
) {
    pages.undelete(ref(title), reason.orEmpty(), undeleteTalk ?: false, watchModeOf(watchlist))
}

/** What the account may do to each of [titles]: [actions], or only `"edit"` unless given. */
public suspend fun Wiki.testActionsWithDefaults(
    titles: Collection<String>,
    actions: Collection<String>? = null,
): Map<PageRef, ActionChecks> = pages.testActions(refs(titles), actions?.toSet() ?: setOf("edit"))

/**
 * One restriction to put on a page: on [action], for [level], until [expiry] or for ever unless given, and
 * not cascading unless told.
 */
public fun protectionOf(
    action: String,
    level: String,
    expiry: Instant? = null,
    cascading: Boolean? = null,
): Protection = Protection(action, level, expiry?.let(Expiry::At) ?: Expiry.Never, cascading ?: false)

/** Sets the protections on [title] to [protections], without cascading unless told. */
public suspend fun Wiki.protectWithDefaults(
    title: String,
    protections: List<Protection>,
    reason: String? = null,
    cascade: Boolean? = null,
    watchlist: WatchMode? = null,
) {
    pages.protect(ref(title), protections, reason.orEmpty(), cascade ?: false, watchModeOf(watchlist))
}

/** Reverts the edits of [user] at the top of [title], marking them as a bot's unless told. */
public suspend fun Wiki.rollbackWithDefaults(
    title: String,
    user: String,
    summary: String? = null,
    markBot: Boolean? = null,
    watchlist: WatchMode? = null,
): EditOutcome = pages.rollback(ref(title), user, summary.orEmpty(), markBot ?: true, watchModeOf(watchlist))

/** Undoes [revision] of [title], or the range from [through] to it. */
public suspend fun Wiki.undoWithDefaults(
    title: String,
    revision: RevisionId,
    summary: String? = null,
    through: RevisionId? = null,
): EditOutcome = pages.undo(ref(title), revision, summary.orEmpty(), through)

/** Merges the history of [from] into [to], all of it unless [upTo] is given. */
public suspend fun Wiki.mergeHistoryWithDefaults(
    from: String,
    to: String,
    upTo: Instant? = null,
    reason: String? = null,
) {
    pages.mergeHistory(ref(from), ref(to), upTo, reason.orEmpty())
}

/**
 * Imports [page] from the wiki with the interwiki prefix [source]: its whole history but not its templates
 * unless told.
 */
@Suppress("LongParameterList")
public suspend fun Wiki.importPageWithDefaults(
    source: String,
    page: String,
    fullHistory: Boolean? = null,
    includeTemplates: Boolean? = null,
    rootPage: String? = null,
    summary: String? = null,
) {
    pages.importPage(
        source,
        page,
        fullHistory ?: true,
        includeTemplates ?: false,
        rootPage,
        summary.orEmpty(),
    )
}

/** Expands [texts] as the page [title] if given, without their categories unless told. */
public suspend fun Wiki.expandTextsWithDefaults(
    texts: List<String>,
    title: String? = null,
    categories: Boolean? = null,
    sandbox: TemplateSandbox? = null,
    revision: RevisionId? = null,
): List<ExpandedText> =
    pages.expandTexts(texts, title?.let { ref(it) }, categories ?: false, sandbox, revision)

/** The redirects to each of [titles], in every namespace unless [namespaces] are given. */
public suspend fun Wiki.redirectsToWithDefaults(
    titles: Collection<String>,
    namespaces: Collection<Int>? = null,
): Map<PageRef, List<PageRef>> = pages.redirectsTo(refs(titles), namespacesOf(namespaces))

/** A sandbox of unsaved templates or modules: under [prefixes], none unless given, or [title] with [text]. */
public fun Wiki.sandboxOf(
    prefixes: Collection<String>? = null,
    title: String? = null,
    text: String? = null,
    contentModel: String? = null,
): TemplateSandbox = TemplateSandbox(prefixes?.toList().orEmpty(), title?.let { ref(it) }, text, contentModel)
