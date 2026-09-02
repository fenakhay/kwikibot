package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.ActionChecks
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.page.CategoryInfo
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.user.Contributors
import kotlin.time.Instant

/** Marks the builders in this library, so their scopes cannot be nested by accident. */
@DslMarker public annotation class KwikibotDsl

/**
 * Reading and writing pages.
 *
 * Every fetch is an explicit call rather than a property read, so the number of requests a bot makes is
 * visible in its own source.
 */
public interface PageService {

    /**
     * Fetches one page, or `null` if it does not exist.
     *
     * @throws WikiError.Page.BadTitle if the wiki rejects the title.
     */
    public suspend fun content(ref: PageRef): PageContent?

    /**
     * Fetches many pages in as few requests as the wiki allows.
     *
     * Missing pages are absent from the result rather than present and empty, so a caller cannot mistake "no
     * such page" for "empty page".
     */
    public suspend fun contents(refs: Collection<PageRef>): Map<PageRef, PageContent>

    /** Whether a page exists, in one request and without fetching its text. */
    public suspend fun exists(ref: PageRef): Boolean

    /** Applies an edit and reports what became of it. */
    public suspend fun edit(ref: PageRef, block: EditBuilder.() -> Unit): EditOutcome

    /**
     * Renames a page, returning a reference to its new title.
     *
     * Unlike an edit, a refused move is not routine, so failures are raised rather than reported: a bot that
     * cannot move a page has nothing useful to record about the page.
     *
     * @param from the page to rename.
     * @param to the title to give it.
     * @param reason the log summary.
     * @param leaveRedirect whether to leave a redirect behind, which needs the `suppressredirect` right to
     *   disable.
     * @param moveTalk move the talk page with it.
     * @param moveSubpages move its subpages with it.
     * @param watchlist what this should do to the account's watchlist.
     */
    public suspend fun move(
        from: PageRef,
        to: PageRef,
        reason: String = "",
        leaveRedirect: Boolean = true,
        moveTalk: Boolean = true,
        moveSubpages: Boolean = false,
        watchlist: WatchMode = WatchMode.PREFERENCES,
    ): PageRef

    /**
     * Deletes a page. Needs the `delete` right.
     *
     * @param ref the page to delete.
     * @param reason the deletion summary, which the log records.
     * @param deleteTalk whether to delete the page's talk page with it, which is usually what a deletion
     *   means.
     * @param watchlist what this should do to the account's watchlist.
     */
    public suspend fun delete(
        ref: PageRef,
        reason: String = "",
        deleteTalk: Boolean = false,
        watchlist: WatchMode = WatchMode.PREFERENCES,
    )

    /**
     * The protections in force on pages.
     *
     * Worth reading before an edit run: a page a bot cannot edit is better skipped than attempted, since a
     * refused write still costs a request and a log line.
     */
    public suspend fun protections(refs: Collection<PageRef>): Map<PageRef, List<Protection>>

    /**
     * What the logged-in account may do to each page.
     *
     * Cheaper and quieter than finding out from a refused write, and it carries the reason, so a skipped page
     * can be logged as skipped rather than as a failure.
     *
     * @param refs the pages to ask about.
     * @param actions the actions to test, named as the API names them: `edit`, `move`, `delete`.
     */
    public suspend fun testActions(
        refs: Collection<PageRef>,
        actions: Set<String> = setOf("edit"),
    ): Map<PageRef, ActionChecks>

    /**
     * Who has edited each page.
     *
     * The logged-out editors come back as a count rather than a list, which is how the API reports them and
     * all a bot could act on anyway.
     */
    public suspend fun contributors(refs: Collection<PageRef>): Map<PageRef, Contributors>

    /**
     * How much each category holds.
     *
     * Absent from the result for anything that is not a category, or is one with nothing in it.
     */
    public suspend fun categoryInfo(refs: Collection<PageRef>): Map<PageRef, CategoryInfo>

    /**
     * What links to each page.
     *
     * The batched answer to the question `ListService.backlinks` answers one page at a time: fifty titles
     * cost one request here rather than fifty.
     */
    public suspend fun backlinksOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>>

    /** What transcludes each page, batched the way [backlinksOf] is. */
    public suspend fun transclusionsOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>>

    /** What uses each file, batched the way [backlinksOf] is. */
    public suspend fun fileUsageOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>>

    /**
     * Sets the protections on a page, replacing whatever was there.
     *
     * An empty list unprotects it, which is how MediaWiki spells removing every restriction.
     */
    public suspend fun protect(
        ref: PageRef,
        protections: List<Protection>,
        reason: String = "",
        cascade: Boolean = false,
        watchlist: WatchMode = WatchMode.PREFERENCES,
    )

    /**
     * Reverts every consecutive edit by [user] at the top of a page, in one action.
     *
     * Not the same as undoing: a rollback is atomic and refuses if the page has been edited since, which is
     * the guarantee that makes it safe to automate. Needs the `rollback` right.
     */
    public suspend fun rollback(
        ref: PageRef,
        user: String,
        summary: String = "",
        markBot: Boolean = true,
        watchlist: WatchMode = WatchMode.PREFERENCES,
    ): EditOutcome

    /**
     * Undoes one revision, or the range from [through] to [revision].
     *
     * The wiki does the merge, so undoing an old edit still applies cleanly when later edits did not touch
     * the same lines, and is refused when they did rather than reverting them too.
     */
    public suspend fun undo(
        ref: PageRef,
        revision: RevisionId,
        summary: String = "",
        through: RevisionId? = null,
    ): EditOutcome

    /**
     * Purges the parser cache for pages, optionally re-rendering their links tables.
     *
     * The one write that needs no token and no edit, so it is paced as a read.
     */
    public suspend fun purge(refs: Collection<PageRef>, forceLinkUpdate: Boolean = false)

    /**
     * Restores a deleted page. Needs the `undelete` right.
     *
     * Restores every deleted revision. Restoring a subset is possible in the API and is not offered here:
     * choosing which revisions of a page come back is a decision for a person looking at them, not for a bot
     * passing a list of timestamps.
     */
    public suspend fun undelete(
        ref: PageRef,
        reason: String = "",
        undeleteTalk: Boolean = false,
        watchlist: WatchMode = WatchMode.PREFERENCES,
    )

    /**
     * Merges one page's history into another. Needs the `mergehistory` right.
     *
     * Untested against a live wiki: this account does not hold the right.
     *
     * @param from the page whose history moves.
     * @param to the page it moves into.
     * @param upTo merge only revisions at or before this moment, leaving the rest behind. Without it the
     *   whole history moves.
     * @param reason the log summary.
     */
    public suspend fun mergeHistory(
        from: PageRef,
        to: PageRef,
        upTo: Instant? = null,
        reason: String = "",
    )

    /**
     * Copies a page here from another Wikimedia wiki, history and all. Needs the `import` right.
     *
     * Untested against a live wiki: this account does not hold the right.
     *
     * Only the interwiki form. Importing an XML dump is the other half of `action=import`, and is a
     * wiki-migration tool rather than bot work: it uploads a file and needs `importupload`, which is granted
     * almost nowhere.
     *
     * @param source the wiki to copy from, as its interwiki prefix: `commons`, `meta`, `fr`.
     * @param page the title to copy, as that wiki spells it.
     * @param fullHistory bring every revision rather than only the latest.
     * @param includeTemplates bring the templates the page uses as well.
     * @param rootPage import beneath this title instead of at the page's own.
     * @param summary the log summary.
     */
    public suspend fun importPage(
        source: String,
        page: String,
        fullHistory: Boolean = true,
        includeTemplates: Boolean = false,
        rootPage: String? = null,
        summary: String = "",
    )

    /**
     * Sets a page's language, which decides its reading direction and its collation.
     *
     * Untested against a live wiki: needs the `pagelang` right, which most wikis do not grant.
     */
    public suspend fun setLanguage(ref: PageRef, language: LangCode, reason: String = "")

    /**
     * Changes the content model of a page, `wikitext` to `Scribunto` and the like.
     *
     * Untested against a live wiki: needs the `editcontentmodel` right.
     */
    public suspend fun changeContentModel(ref: PageRef, model: String, summary: String = "")

    /**
     * Adds pages to this session's watchlist, or removes them.
     *
     * @param refs the pages to watch or unwatch.
     * @param watch true to add, false to remove.
     * @param expiry how long to watch for, as MediaWiki spells durations: `1 month`. `null` watches
     *   indefinitely.
     */
    public suspend fun watch(
        refs: Collection<PageRef>,
        watch: Boolean = true,
        expiry: String? = null,
    )

    /**
     * Expands templates and parser functions in wikitext, as the wiki would.
     *
     * The only way to know what `{{#if:}}` or a Lua module produces is to ask the wiki that runs it. A bot
     * deciding whether a page needs an edit sometimes has to.
     *
     * @param wikitext the text to expand.
     * @param title the page to expand as, which decides what `{{PAGENAME}}` and relative transclusions mean.
     */
    public suspend fun expandText(wikitext: String, title: PageRef? = null): String
}
