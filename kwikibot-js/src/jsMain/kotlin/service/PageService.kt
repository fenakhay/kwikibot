import com.fenakhay.kwikibot.bindings.client.deleteWithDefaults
import com.fenakhay.kwikibot.bindings.client.editWithDefaults
import com.fenakhay.kwikibot.bindings.client.expandTextsWithDefaults
import com.fenakhay.kwikibot.bindings.client.importPageWithDefaults
import com.fenakhay.kwikibot.bindings.client.mergeHistoryWithDefaults
import com.fenakhay.kwikibot.bindings.client.moveWithDefaults
import com.fenakhay.kwikibot.bindings.client.protectWithDefaults
import com.fenakhay.kwikibot.bindings.client.protectionOf
import com.fenakhay.kwikibot.bindings.client.redirectsToWithDefaults
import com.fenakhay.kwikibot.bindings.client.rollbackWithDefaults
import com.fenakhay.kwikibot.bindings.client.testActionsWithDefaults
import com.fenakhay.kwikibot.bindings.client.titleOf
import com.fenakhay.kwikibot.bindings.client.undeleteWithDefaults
import com.fenakhay.kwikibot.bindings.client.undoWithDefaults
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.service.ExpandedText as ClientExpandedText
import com.fenakhay.kwikibot.client.service.WatchMode as ClientWatchMode
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.edit.ActionChecks
import com.fenakhay.kwikibot.model.edit.Protection as ClientProtection
import com.fenakhay.kwikibot.model.page.CategoryInfo as ClientCategoryInfo
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.user.Contributor as ClientContributor
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise

/**
 * What one edit says. Exactly one of [text], [appendText] and [prependText] is required, and a contradictory
 * edit rejects before anything is sent.
 */
@JsExport
public external interface EditOptions {
    /** The full new wikitext. */
    public val text: String?

    /** Text to add at the end of the page instead of replacing it. */
    public val appendText: String?

    /** Text to add at the start of the page instead of replacing it. */
    public val prependText: String?

    /** The edit summary. */
    public val summary: String?

    /** Whether to mark the edit minor, `false` unless given. */
    public val minor: Boolean?

    /**
     * Whether to flag the edit as a bot's, hiding it from default recent-changes views, `true` unless given.
     */
    public val bot: Boolean?

    /**
     * The revision the edit was computed from, a page's `revision`, so that the wiki can detect a conflict.
     */
    public val baseRevision: Double?

    /** Whether to refuse to create the page if it does not exist. */
    public val noCreate: Boolean?

    /** Whether to refuse to edit the page if it already exists. */
    public val createOnly: Boolean?

    /** Change tags to attach to the edit. */
    public val tags: Array<String>?

    /** Which section to edit: a section's `index` from `wiki.render.sections`, or `"new"` to start one. */
    public val section: String?

    /** The heading of a `section: "new"` edit. Meaningless on any other section. */
    public val sectionTitle: String?

    /**
     * What the edit does to the account's watchlist: `"no-change"`, `"preferences"`, the default, `"watch"`
     * or `"unwatch"`.
     */
    public val watchlist: WatchMode?

    /** How long to watch the page for, as MediaWiki spells durations: `"1 month"`. Forever unless given. */
    public val watchlistExpiry: String?
}

/** The options of `move`. */
@JsExport
public external interface MoveOptions {
    /** The log summary. */
    public val reason: String?

    /**
     * Whether to leave a redirect behind, `true` unless given; `false` needs the `suppressredirect` right.
     */
    public val leaveRedirect: Boolean?

    /** Whether to move the talk page with it, `true` unless given. */
    public val moveTalk: Boolean?

    /** Whether to move its subpages with it, `false` unless given. */
    public val moveSubpages: Boolean?

    /** `"no-change"`, `"preferences"`, the default, `"watch"` or `"unwatch"`. */
    public val watchlist: WatchMode?
}

/** The options of `delete`. */
@JsExport
public external interface DeleteOptions {
    /** The deletion summary, which the log records. */
    public val reason: String?

    /** Whether to delete the page's talk page with it, `false` unless given. */
    public val deleteTalk: Boolean?

    /** `"no-change"`, `"preferences"`, the default, `"watch"` or `"unwatch"`. */
    public val watchlist: WatchMode?
}

/** The options of `undelete`. */
@JsExport
public external interface UndeleteOptions {
    /** The log summary. */
    public val reason: String?

    /** Whether to restore the page's talk page with it, `false` unless given. */
    public val undeleteTalk: Boolean?

    /** `"no-change"`, `"preferences"`, the default, `"watch"` or `"unwatch"`. */
    public val watchlist: WatchMode?
}

/** One restriction to put on a page with `protect`. */
@JsExport
public external interface ProtectionRequest {
    /** The action to restrict, such as `"edit"` or `"move"`. */
    public val action: String

    /** The group that may still do it, such as `"sysop"` or `"autoconfirmed"`. */
    public val level: String

    /** When the restriction lapses. It does not unless given. */
    public val expiry: Date?

    /** Whether it propagates to transcluded pages, `false` unless given. */
    public val cascading: Boolean?
}

/** The options of `protect`. */
@JsExport
public external interface ProtectOptions {
    /** The log summary. */
    public val reason: String?

    /** Whether to protect the pages this one transcludes too, `false` unless given. */
    public val cascade: Boolean?

    /** `"no-change"`, `"preferences"`, the default, `"watch"` or `"unwatch"`. */
    public val watchlist: WatchMode?
}

/** The options of `rollback`. */
@JsExport
public external interface RollbackOptions {
    /** The edit summary, the wiki's own unless given. */
    public val summary: String?

    /** Whether to mark the reverted edits and the revert as a bot's, `true` unless given. */
    public val markBot: Boolean?

    /** `"no-change"`, `"preferences"`, the default, `"watch"` or `"unwatch"`. */
    public val watchlist: WatchMode?
}

/** The options of `undo`. */
@JsExport
public external interface UndoOptions {
    /** The edit summary. */
    public val summary: String?

    /** The older end of the range to undo. Only the one revision unless given. */
    public val through: Double?
}

/** The options of `mergeHistory`. */
@JsExport
public external interface MergeHistoryOptions {
    /** Merge only revisions at or before this moment. The whole history unless given. */
    public val upTo: Date?

    /** The log summary. */
    public val reason: String?
}

/** The options of `importPage`. */
@JsExport
public external interface ImportPageOptions {
    /** Whether to bring every revision rather than only the latest, `true` unless given. */
    public val fullHistory: Boolean?

    /** Whether to bring the templates the page uses as well, `false` unless given. */
    public val includeTemplates: Boolean?

    /** Import beneath this title instead of at the page's own. */
    public val rootPage: String?

    /** The log summary. */
    public val summary: String?
}

/** The options of `expandTexts`. */
@JsExport
public external interface ExpandTextsOptions {
    /** The page to expand them as, which decides what `{{PAGENAME}}` and relative transclusions mean. */
    public val title: String?

    /**
     * Whether to report the categories each text would put a page in, `false` unless given. Each text is then
     * sent alone.
     */
    public val categories: Boolean?

    /** Unsaved templates or modules to expand with. */
    public val sandbox: TemplateSandboxOptions?

    /** The revision to expand as, which `{{REVISIONID}}` reads. As a preview unless given. */
    public val revision: Double?
}

/**
 * Reading and writing pages, reached as `wiki.pagesService`. Every fetch is one explicit call. A title has
 * its namespace prefix; those that are read as a category or a file need none.
 *
 * What a wiki refuses to edit is a result, an `EditResult`. Any other failure rejects the promise, as does an
 * option that names none of its values.
 */
@JsExport
public class PageService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    /** The current text of [title], or `null` when there is no such page. */
    public fun content(title: String): Promise<Page?> = scope.promise {
        wiki.pages.content(wiki.ref(title))?.let { Page(it, wiki.namespaces) }
    }

    /**
     * The current text of each of [titles] that exists, in the order asked for, in as few requests as the
     * wiki allows.
     */
    public fun contents(titles: Array<String>): Promise<Array<Page>> = scope.promise {
        val refs = refs(titles)
        pagesIn(refs, wiki.pages.contents(refs))
    }

    /**
     * [contents], never from a response cache: for reads whose answer must be current, such as the text an
     * edit about to be saved is computed from.
     */
    public fun freshContents(titles: Array<String>): Promise<Array<Page>> = scope.promise {
        val refs = refs(titles)
        pagesIn(refs, wiki.pages.freshContents(refs))
    }

    /** Whether a page exists, in one request and without fetching its text. */
    public fun exists(title: String): Promise<Boolean> = scope.promise { wiki.pages.exists(wiki.ref(title)) }

    /**
     * Applies an edit and says how the wiki answered: a refusal is a result, not an error. Rejects without
     * sending anything when [options] contradict themselves.
     */
    public fun edit(title: String, options: EditOptions): Promise<EditResult> = scope.promise {
        val outcome =
            wiki.editWithDefaults(
                title,
                options.text,
                options.appendText,
                options.prependText,
                options.summary,
                options.minor,
                options.bot,
                options.baseRevision?.let(::revisionIdOf),
                options.noCreate,
                options.createOnly,
                options.tags?.asList(),
                options.section,
                options.sectionTitle,
                watchMode(options.watchlist),
                options.watchlistExpiry,
            )
        editResult(outcome)
    }

    /** Renames a page, answering with its new title. A refused move rejects. */
    public fun move(from: String, to: String, options: MoveOptions? = null): Promise<String> = scope.promise {
        wiki.moveWithDefaults(
            from,
            to,
            options?.reason,
            options?.leaveRedirect,
            options?.moveTalk,
            options?.moveSubpages,
            watchMode(options?.watchlist),
        )
    }

    /** Deletes a page. Needs the `delete` right. */
    public fun delete(title: String, options: DeleteOptions? = null): Promise<Unit> = scope.promiseVoid {
        wiki.deleteWithDefaults(title, options?.reason, options?.deleteTalk, watchMode(options?.watchlist))
    }

    /** The protections in force on each of [titles] the wiki knows. */
    public fun protections(titles: Array<String>): Promise<Array<PageProtections>> = scope.promise {
        wiki.pages
            .protections(refs(titles))
            .map { (ref, protections) -> PageProtections(wiki.titleOf(ref), protections) }
            .toTypedArray()
    }

    /**
     * What the logged-in account may do to each of [titles]. [actions] are the actions to test, named as the
     * API names them, `"edit"` alone unless given.
     */
    public fun testActions(
        titles: Array<String>,
        actions: Array<String>? = null,
    ): Promise<Array<PageActions>> = scope.promise {
        wiki
            .testActionsWithDefaults(titles.asList(), actions?.asList())
            .map { (ref, checks) -> PageActions(wiki.titleOf(ref), checks) }
            .toTypedArray()
    }

    /** Who has edited each of [titles]. */
    public fun contributors(titles: Array<String>): Promise<Array<PageContributors>> = scope.promise {
        wiki.pages
            .contributors(refs(titles))
            .map { (ref, found) -> PageContributors(wiki.titleOf(ref), found.users, found.anonymous) }
            .toTypedArray()
    }

    /**
     * How much each of [categories], which need no `Category:` prefix, holds. One that is empty or is no
     * category is left out.
     */
    public fun categoryInfo(categories: Array<String>): Promise<Array<CategoryInfo>> = scope.promise {
        wiki.pages
            .categoryInfo(categories.map { wiki.ref(it, Namespace.CATEGORY) })
            .map { (ref, info) -> CategoryInfo(wiki.titleOf(ref), info) }
            .toTypedArray()
    }

    /** What links to each of [titles], fifty titles to a request. One that nothing links to is left out. */
    public fun backlinksOf(titles: Array<String>): Promise<Array<RelatedPages>> = scope.promise {
        related(wiki.pages.backlinksOf(refs(titles)))
    }

    /** What transcludes each of [titles], as [backlinksOf] does, which take their prefix. */
    public fun transclusionsOf(titles: Array<String>): Promise<Array<RelatedPages>> = scope.promise {
        related(wiki.pages.transclusionsOf(refs(titles)))
    }

    /** What uses each of [files], which need no `File:` prefix, as [backlinksOf] does. */
    public fun fileUsageOf(files: Array<String>): Promise<Array<RelatedPages>> = scope.promise {
        related(wiki.pages.fileUsageOf(files.map { wiki.ref(it, Namespace.FILE) }))
    }

    /**
     * Sets the protections on a page, replacing whatever was there. An empty array unprotects it.
     *
     * @param title the page.
     * @param protections the restrictions to put on it.
     * @param options the rest.
     */
    public fun protect(
        title: String,
        protections: Array<ProtectionRequest>,
        options: ProtectOptions? = null,
    ): Promise<Unit> = scope.promiseVoid {
        wiki.protectWithDefaults(
            title,
            protections.map { protectionOf(it.action, it.level, it.expiry?.let(::instantOf), it.cascading) },
            options?.reason,
            options?.cascade,
            watchMode(options?.watchlist),
        )
    }

    /**
     * Reverts every consecutive edit by [user] at the top of a page, in one action that refuses if the page
     * has been edited since. Needs the `rollback` right.
     */
    public fun rollback(
        title: String,
        user: String,
        options: RollbackOptions? = null,
    ): Promise<EditResult> = scope.promise {
        editResult(
            wiki.rollbackWithDefaults(
                title,
                user,
                options?.summary,
                options?.markBot,
                watchMode(options?.watchlist),
            )
        )
    }

    /**
     * Undoes [revision], or the range from `through` to it. The wiki does the merge, and refuses when later
     * edits touched the same lines.
     */
    public fun undo(
        title: String,
        revision: Double,
        options: UndoOptions? = null,
    ): Promise<EditResult> = scope.promise {
        editResult(
            wiki.undoWithDefaults(
                title,
                revisionIdOf(revision),
                options?.summary,
                options?.through?.let(::revisionIdOf),
            )
        )
    }

    /** Purges the parser cache of [titles], re-rendering their links tables too when [forceLinkUpdate]. */
    public fun purge(titles: Array<String>, forceLinkUpdate: Boolean = false): Promise<Unit> =
        scope.promiseVoid {
            wiki.pages.purge(refs(titles), forceLinkUpdate)
        }

    /** Restores every deleted revision of a page. Needs the `undelete` right. */
    public fun undelete(title: String, options: UndeleteOptions? = null): Promise<Unit> = scope.promiseVoid {
        wiki.undeleteWithDefaults(
            title,
            options?.reason,
            options?.undeleteTalk,
            watchMode(options?.watchlist),
        )
    }

    /** Merges the history of page [from] into page [to]. Needs the `mergehistory` right. */
    public fun mergeHistory(
        from: String,
        to: String,
        options: MergeHistoryOptions? = null,
    ): Promise<Unit> = scope.promiseVoid {
        wiki.mergeHistoryWithDefaults(from, to, options?.upTo?.let(::instantOf), options?.reason)
    }

    /**
     * Copies [page], as the other wiki spells it, here from the wiki with the interwiki prefix [source], such
     * as `"commons"`. Needs the `import` right.
     */
    public fun importPage(
        source: String,
        page: String,
        options: ImportPageOptions? = null,
    ): Promise<Unit> = scope.promiseVoid {
        wiki.importPageWithDefaults(
            source,
            page,
            options?.fullHistory,
            options?.includeTemplates,
            options?.rootPage,
            options?.summary,
        )
    }

    /** Sets a page's language, which decides its reading direction. Needs the `pagelang` right. */
    public fun setLanguage(title: String, language: String, reason: String = ""): Promise<Unit> =
        scope.promiseVoid {
            wiki.pages.setLanguage(wiki.ref(title), LangCode(language), reason)
        }

    /** Changes a page's content model, such as to `"Scribunto"`. Needs the `editcontentmodel` right. */
    public fun changeContentModel(title: String, model: String, summary: String = ""): Promise<Unit> =
        scope.promiseVoid {
            wiki.pages.changeContentModel(wiki.ref(title), model, summary)
        }

    /**
     * Adds [titles] to this session's watchlist, or removes them when [watch] is `false`.
     *
     * @param titles the pages.
     * @param watch `true` to add, `false` to remove.
     * @param expiry how long to watch for, as MediaWiki spells durations: `"1 month"`. Forever unless given.
     */
    public fun watch(
        titles: Array<String>,
        watch: Boolean = true,
        expiry: String? = null,
    ): Promise<Unit> = scope.promiseVoid { wiki.pages.watch(refs(titles), watch, expiry) }

    /**
     * Expands templates and parser functions in [wikitext] as the wiki would, as the page [title] if given.
     */
    public fun expandText(wikitext: String, title: String? = null): Promise<String> = scope.promise {
        wiki.pages.expandText(wikitext, title?.let { wiki.ref(it) })
    }

    /** Expands many [texts] as [expandText] does, in as few requests as it can, in the order given. */
    public fun expandTexts(
        texts: Array<String>,
        options: ExpandTextsOptions? = null,
    ): Promise<Array<ExpandedText>> = scope.promise {
        wiki
            .expandTextsWithDefaults(
                texts.toList(),
                options?.title,
                options?.categories,
                options?.sandbox?.let { sandboxFrom(wiki, it) },
                options?.revision?.let(::revisionIdOf),
            )
            .map(::ExpandedText)
            .toTypedArray()
    }

    /**
     * The redirects to each of [titles], only those in [namespaces] when given. A page no redirect points to
     * is left out.
     */
    public fun redirectsTo(
        titles: Array<String>,
        namespaces: Array<Int>? = null,
    ): Promise<Array<RelatedPages>> = scope.promise {
        related(wiki.redirectsToWithDefaults(titles.asList(), namespaces?.asList()))
    }

    private fun refs(titles: Array<String>): List<PageRef> = titles.map { wiki.ref(it) }

    private fun pagesIn(refs: List<PageRef>, found: Map<PageRef, PageContent>): Array<Page> =
        refs.mapNotNull { found[it] }.map { Page(it, wiki.namespaces) }.toTypedArray()

    private fun related(found: Map<PageRef, List<PageRef>>): Array<RelatedPages> =
        found
            .map { (ref, pages) -> RelatedPages(wiki.titleOf(ref), pages.map(wiki::titleOf).toTypedArray()) }
            .toTypedArray()

    private fun watchMode(mode: WatchMode?): ClientWatchMode? = enumOf("watchlist", mode)
}

/** The protections in force on one page. */
@JsExport
public class PageProtections
internal constructor(
    /** The page. */
    public val title: String,
    private val found: List<ClientProtection>,
) {
    /** Its protections. */
    public val protections: Array<Protection>
        get() = found.map(::Protection).toTypedArray()
}

/** What the logged-in account may do to one page. */
@JsExport
public class PageActions
internal constructor(
    /** The page. */
    public val title: String,
    private val checks: ActionChecks,
) {
    /** Each action that was tested, with the error codes refusing it: none when it is permitted. */
    public val refusals: Record<String, Array<String>>
        get() = checks.refusals.toRecord { it.toTypedArray() }

    /** Whether [action] is permitted. `false` for an action that was not tested. */
    public fun allows(action: String): Boolean = checks.allows(action)

    /** Why [action] was refused, or nothing if it is permitted or was not tested. */
    public fun reasons(action: String): Array<String> = checks.reasons(action).toTypedArray()
}

/** Someone who has edited a page. */
@JsExport
public class Contributor internal constructor(private val contributor: ClientContributor) {

    /** The account name. */
    public val name: String
        get() = contributor.name

    /** The account's id, `0` when the wiki did not report one. */
    public val id: Double
        get() = contributor.id.toDouble()
}

/** Who has edited one page. */
@JsExport
public class PageContributors
internal constructor(
    /** The page. */
    public val title: String,
    private val found: List<ClientContributor>,
    /** How many edits came from addresses rather than accounts. */
    public val anonymous: Int,
) {
    /** The named accounts that have edited it. */
    public val users: Array<Contributor>
        get() = found.map(::Contributor).toTypedArray()
}

/** How much one category holds, as the wiki counts it. */
@JsExport
public class CategoryInfo
internal constructor(
    /** The category, prefix included. */
    public val title: String,
    private val info: ClientCategoryInfo,
) {
    /** Everything in it: pages, files and subcategories together. */
    public val size: Int
        get() = info.size

    /** Ordinary pages. */
    public val pages: Int
        get() = info.pages

    /** Files. */
    public val files: Int
        get() = info.files

    /** Subcategories. */
    public val subcategories: Int
        get() = info.subcategories

    /** Whether it is hidden from the box at the foot of its members. */
    public val hidden: Boolean
        get() = info.hidden
}

/** One page, and the pages that point at it. */
@JsExport
public class RelatedPages
internal constructor(
    /** The page. */
    public val title: String,
    /** The titles pointing at it. */
    public val pages: Array<String>,
)

/** What `expandTexts` made of one text. */
@JsExport
public class ExpandedText internal constructor(private val expanded: ClientExpandedText) {

    /** The expanded wikitext. */
    public val text: String
        get() = expanded.text

    /** The categories it would put a page in, without their prefix; none when they were not asked for. */
    public val categories: Array<String>
        get() = expanded.categories.toTypedArray()
}
