package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.ActionChecks
import com.fenakhay.kwikibot.model.CategoryInfo
import com.fenakhay.kwikibot.model.Contributor
import com.fenakhay.kwikibot.model.Contributors
import com.fenakhay.kwikibot.model.EditOutcome
import com.fenakhay.kwikibot.model.Expiry
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageContent
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Protection
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.protocol.ApiFailure
import com.fenakhay.kwikibot.protocol.Continuation
import com.fenakhay.kwikibot.protocol.PageDecoder
import com.fenakhay.kwikibot.protocol.PageResult
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.coroutines.flow.toList
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Marks the builders in this library, so their scopes cannot be nested by accident. */
@DslMarker
public annotation class KwikibotDsl

/**
 * Reading and writing pages.
 *
 * Every fetch is an explicit call rather than a property read, so the number of requests a bot
 * makes is visible in its own source.
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
     * Missing pages are absent from the result rather than present and empty, so a caller
     * cannot mistake "no such page" for "empty page".
     */
    public suspend fun contents(refs: Collection<PageRef>): Map<PageRef, PageContent>

    /** Whether a page exists, in one request and without fetching its text. */
    public suspend fun exists(ref: PageRef): Boolean

    /** Applies an edit and reports what became of it. */
    public suspend fun edit(ref: PageRef, block: EditBuilder.() -> Unit): EditOutcome

    /**
     * Renames a page, returning a reference to its new title.
     *
     * Unlike an edit, a refused move is not routine, so failures are raised rather than
     * reported: a bot that cannot move a page has nothing useful to record about the page.
     *
     * @param from the page to rename.
     * @param to the title to give it.
     * @param reason the log summary.
     * @param leaveRedirect whether to leave a redirect behind, which needs the
     *   `suppressredirect` right to disable.
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
     * @param deleteTalk whether to delete the page's talk page with it, which is usually
     *   what a deletion means.
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
     * Worth reading before an edit run: a page a bot cannot edit is better skipped than
     * attempted, since a refused write still costs a request and a log line.
     */
    public suspend fun protections(refs: Collection<PageRef>): Map<PageRef, List<Protection>>

    /**
     * What the logged-in account may do to each page.
     *
     * Cheaper and quieter than finding out from a refused write, and it carries the reason, so a
     * skipped page can be logged as skipped rather than as a failure.
     *
     * @param refs the pages to ask about.
     * @param actions the actions to test, named as the API names them: `edit`, `move`,
     *   `delete`.
     */
    public suspend fun testActions(
        refs: Collection<PageRef>,
        actions: Set<String> = setOf("edit"),
    ): Map<PageRef, ActionChecks>

    /**
     * Who has edited each page.
     *
     * The logged-out editors come back as a count rather than a list, which is how the API
     * reports them and all a bot could act on anyway.
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
     * The batched answer to the question `ListService.backlinks` answers one page at a time: fifty
     * titles cost one request here rather than fifty.
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
     * Not the same as undoing: a rollback is atomic and refuses if the page has been edited
     * since, which is the guarantee that makes it safe to automate. Needs the `rollback`
     * right.
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
     * The wiki does the merge, so undoing an old edit still applies cleanly when later edits did
     * not touch the same lines, and is refused when they did rather than reverting them too.
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
     * Restores every deleted revision. Restoring a subset is possible in the API and is not
     * offered here: choosing which revisions of a page come back is a decision for a person
     * looking at them, not for a bot passing a list of timestamps.
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
     * @param upTo merge only revisions at or before this moment, leaving the rest behind.
     *   Without it the whole history moves.
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
     * Only the interwiki form. Importing an XML dump is the other half of `action=import`, and is
     * a wiki-migration tool rather than bot work: it uploads a file and needs `importupload`,
     * which is granted almost nowhere.
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
     * @param expiry how long to watch for, as MediaWiki spells durations: `1 month`.
     *   `null` watches indefinitely.
     */
    public suspend fun watch(
        refs: Collection<PageRef>,
        watch: Boolean = true,
        expiry: String? = null,
    )

    /**
     * Expands templates and parser functions in wikitext, as the wiki would.
     *
     * The only way to know what `{{#if:}}` or a Lua module produces is to ask the wiki that
     * runs it. A bot deciding whether a page needs an edit sometimes has to.
     *
     * @param wikitext the text to expand.
     * @param title the page to expand as, which decides what `{{PAGENAME}}` and relative
     *   transclusions mean.
     */
    public suspend fun expandText(wikitext: String, title: PageRef? = null): String
}

/**
 * The parameters of one edit.
 *
 * [baseRevision] is what lets the wiki detect an edit conflict; leaving it unset means "apply
 * this regardless of what the page says now", which is rarely what a bot wants.
 *
 * Normally reached through `pages.edit { }` rather than constructed directly; the constructor is
 * public so that [PageService] can be implemented outside this library, which is what test
 * doubles need.
 */
@KwikibotDsl
public class EditBuilder {

    private companion object {
        const val NEW_SECTION = "new"
    }

    /** The full new wikitext. Exactly one of this, [appendText] or [prependText] is required. */
    public var text: String? = null

    /** Text to add at the end of the page instead of replacing it. */
    public var appendText: String? = null

    /** Text to add at the start of the page instead of replacing it. */
    public var prependText: String? = null

    /** The edit summary. */
    public var summary: String = ""

    /** Whether to mark the edit minor. */
    public var minor: Boolean = false

    /** Whether to flag the edit as a bot edit, hiding it from default recent-changes views. */
    public var bot: Boolean = true

    /** The revision the edit was computed from, so the wiki can detect a conflict. */
    public var baseRevision: RevisionId? = null

    /** Refuse to create the page if it does not exist. */
    public var noCreate: Boolean = false

    /** Refuse to edit the page if it already exists. */
    public var createOnly: Boolean = false

    /** Change tags to attach to the edit. */
    public var tags: List<String> = emptyList()

    /**
     * Which section to edit: a section number, or `"new"` to start one.
     *
     * `"new"` with a [sectionTitle] is how a message is left on a talk page. A number edits one
     * section, which also narrows what an edit conflict can be about.
     */
    public var section: String? = null

    /** The heading for a `section = "new"` edit. Meaningless on any other section. */
    public var sectionTitle: String? = null

    /** What the edit should do to the account's watchlist. */
    public var watchlist: WatchMode = WatchMode.PREFERENCES

    /** How long to watch the page for, when [watchlist] adds it. An expiry-less watch is forever. */
    public var watchlistExpiry: String? = null

    /**
     * Checks the builder describes an edit the wiki could accept.
     *
     * Called before a token is fetched: a contradictory edit is a mistake in the calling code, and
     * finding it should not cost a request.
     */
    internal fun validate() {
        require(body().isNotEmpty()) { "an edit needs text, appendText or prependText" }
        require(body().size == 1) { "set only one of text, appendText and prependText" }
        require(!(noCreate && createOnly)) { "noCreate and createOnly contradict each other" }
        require(sectionTitle == null || section == NEW_SECTION) {
            "sectionTitle applies only to section = \"$NEW_SECTION\""
        }
    }

    private fun body(): List<Pair<String, String>> = listOfNotNull(
        text?.let { "text" to it },
        appendText?.let { "appendtext" to it },
        prependText?.let { "prependtext" to it },
    )

    internal fun parameters(title: String, token: String): Map<String, String> {
        validate()

        return buildMap {
            put("action", "edit")
            put("title", title)
            putAll(body())
            put("summary", summary)
            putFlags()
            putPlacement()
            baseRevision?.let { put("baserevid", it.value.toString()) }
            // Catches a session that died between reading and saving, so the edit is not made
            // anonymously from the bot's IP.
            put("assert", "user")
            put("token", token)
        }
    }

    private fun MutableMap<String, String>.putFlags() {
        if (minor) put("minor", "1") else put("notminor", "1")
        if (bot) put("bot", "1")
        if (noCreate) put("nocreate", "1")
        if (createOnly) put("createonly", "1")
        if (tags.isNotEmpty()) put("tags", tags.joinToString("|"))
    }

    /** Where the text lands, and what that does to the watchlist. */
    private fun MutableMap<String, String>.putPlacement() {
        section?.let { put("section", it) }
        sectionTitle?.let { put("sectiontitle", it) }
        watchlist.applyTo(this)
        watchlistExpiry?.let { put("watchlistexpiry", it) }
    }
}

internal class ApiPageService(
    private val transport: MediaWikiTransport,
    private val tokens: TokenStore,
    private val decoder: PageDecoder,
    private val namespaces: NamespaceMap,
    private val batchSize: Int = DEFAULT_BATCH,
) : PageService {

    private val continuation = Continuation(transport)

    override suspend fun content(ref: PageRef): PageContent? = contents(listOf(ref))[ref]

    override suspend fun contents(refs: Collection<PageRef>): Map<PageRef, PageContent> {
        if (refs.isEmpty()) return emptyMap()

        val found = mutableMapOf<Title.Local, PageContent>()

        for (batch in refs.map { it.title }.distinct().chunked(batchSize)) {
            val request = ApiRequest.of(
                "query",
                "prop" to "revisions",
                "rvprop" to "ids|timestamp|user|comment|size|flags|content",
                "rvslots" to "main",
                // Redirects are deliberately not followed: a bot editing a redirect needs the
                // redirect's own text, not its target's.
                "titles" to batch.joinToString("|") { namespaces.format(it) },
            )

            continuation.pages(request).toList().forEach { entry ->
                val decoded = decoder.decode(entry) as? PageResult.Existing ?: return@forEach
                val content = decoded.content ?: return@forEach
                found[decoded.ref.title] = content
            }
        }

        // Keyed by the caller's own refs, so what goes in is what comes back out.
        return refs.mapNotNull { ref -> found[ref.title]?.let { ref to it } }.toMap()
    }

    override suspend fun exists(ref: PageRef): Boolean {
        val request = ApiRequest.of(
            "query",
            "prop" to "info",
            "titles" to namespaces.format(ref.title),
        )
        val page = continuation.pages(request).toList().firstOrNull() ?: return false
        return decoder.decode(page) is PageResult.Existing
    }

    override suspend fun edit(ref: PageRef, block: EditBuilder.() -> Unit): EditOutcome {
        val builder = EditBuilder().apply(block).also { it.validate() }
        val title = namespaces.format(ref.title)

        val response = tokens.withFreshToken { token ->
            transport.call(ApiRequest(builder.parameters(title, token), RequestKind.WRITE))
                .also { it.raiseBadToken() }
        }

        ApiFailure.from(response)?.let { failure ->
            return failure.toEditRefusal(ref) ?: throw failure.toWikiError()
        }

        val edit = response["edit"]?.jsonObject
            ?: throw WikiError.Api("noedit", "no edit block in the response", "edit")

        return edit.toOutcome(ref)
    }

    override suspend fun move(
        from: PageRef,
        to: PageRef,
        reason: String,
        leaveRedirect: Boolean,
        moveTalk: Boolean,
        moveSubpages: Boolean,
        watchlist: WatchMode,
    ): PageRef {
        tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "move")
                        watchlist.applyTo(this)
                        put("from", namespaces.format(from.title))
                        put("to", namespaces.format(to.title))
                        put("reason", reason)
                        if (!leaveRedirect) put("noredirect", "1")
                        if (moveTalk) put("movetalk", "1")
                        if (moveSubpages) put("movesubpages", "1")
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).also { it.raiseBadToken() }.throwOnError()
        }
        return to
    }

    override suspend fun delete(
        ref: PageRef,
        reason: String,
        deleteTalk: Boolean,
        watchlist: WatchMode,
    ) {
        tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "delete")
                        put("title", namespaces.format(ref.title))
                        put("reason", reason)
                        if (deleteTalk) put("deletetalk", "1")
                        watchlist.applyTo(this)
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).also { it.raiseBadToken() }.throwOnError()
        }
    }

    override suspend fun protections(refs: Collection<PageRef>): Map<PageRef, List<Protection>> {
        if (refs.isEmpty()) return emptyMap()

        val found = mutableMapOf<Title.Local, List<Protection>>()
        for (batch in refs.map { it.title }.distinct().chunked(batchSize)) {
            val request = ApiRequest.of(
                "query",
                "prop" to "info",
                "inprop" to "protection",
                "titles" to batch.joinToString("|") { namespaces.format(it) },
            )

            continuation.pages(request).toList().forEach { entry ->
                val ref = decoder.refOf(entry) ?: return@forEach
                found[ref.title] = entry.protections()
            }
        }

        return refs.mapNotNull { ref -> found[ref.title]?.let { ref to it } }.toMap()
    }

    override suspend fun testActions(
        refs: Collection<PageRef>,
        actions: Set<String>,
    ): Map<PageRef, ActionChecks> {
        if (actions.isEmpty()) return emptyMap()

        return byPage(
            refs,
            "info",
            "intestactions" to actions.joinToString("|"),
            // Without this the wiki answers yes or no, and a bot cannot say why it skipped.
            "intestactionsdetail" to "full",
        ) { entry ->
            val tested = entry["actions"]?.jsonObject ?: return@byPage null
            ActionChecks(
                tested.mapValues { (_, refusals) ->
                    (refusals as? JsonArray)
                        ?.mapNotNull { it.jsonObject["code"]?.jsonPrimitive?.content }
                        .orEmpty()
                },
            )
        }
    }

    override suspend fun mergeHistory(
        from: PageRef,
        to: PageRef,
        upTo: Instant?,
        reason: String,
    ) {
        write("mergehistory") {
            put("from", namespaces.format(from.title))
            put("to", namespaces.format(to.title))
            // Names the last revision to move. Without it the wiki moves the whole history.
            upTo?.let { put("timestamp", MwTimestamp.format(it)) }
            put("reason", reason)
        }
    }

    override suspend fun importPage(
        source: String,
        page: String,
        fullHistory: Boolean,
        includeTemplates: Boolean,
        rootPage: String?,
        summary: String,
    ) {
        write("import") {
            put("interwikisource", source)
            put("interwikipage", page)
            if (fullHistory) put("fullhistory", "1")
            if (includeTemplates) put("templates", "1")
            rootPage?.let { put("rootpage", it) }
            put("summary", summary)
        }
    }

    override suspend fun setLanguage(ref: PageRef, language: LangCode, reason: String) {
        write("setpagelanguage") {
            put("title", namespaces.format(ref.title))
            put("lang", language.code)
            put("reason", reason)
        }
    }

    override suspend fun changeContentModel(ref: PageRef, model: String, summary: String) {
        write("changecontentmodel") {
            put("title", namespaces.format(ref.title))
            put("model", model)
            put("summary", summary)
        }
    }

    /** A write whose only answer is whether it worked, which most of the admin actions are. */
    private suspend fun write(action: String, params: MutableMap<String, String>.() -> Unit) {
        tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", action)
                        params()
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).also { it.raiseBadToken() }.throwOnError()
        }
    }

    override suspend fun contributors(refs: Collection<PageRef>): Map<PageRef, Contributors> =
        byPage(refs, "contributors", "pclimit" to "max") { entry ->
            val users = (entry["contributors"] as? JsonArray).orEmpty().map { it.jsonObject }
            // The anonymous count is a sibling of the array, not a member of it: a decoder that
            // reads only "contributors" loses it silently.
            val anonymous = entry["anoncontributors"]?.jsonPrimitive?.intOrNull ?: 0

            if (users.isEmpty() && anonymous == 0) {
                null
            } else {
                Contributors(
                    users = users.mapNotNull { user ->
                        val name = user["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        Contributor(name, user["userid"]?.jsonPrimitive?.longOrNull ?: 0)
                    },
                    anonymous = anonymous,
                )
            }
        }

    override suspend fun categoryInfo(refs: Collection<PageRef>): Map<PageRef, CategoryInfo> =
        byPage(refs, "categoryinfo") { entry ->
            val info = entry["categoryinfo"]?.jsonObject ?: return@byPage null
            CategoryInfo(
                size = info.number("size"),
                pages = info.number("pages"),
                files = info.number("files"),
                subcategories = info.number("subcats"),
                hidden = info["hidden"] != null,
            )
        }

    override suspend fun backlinksOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>> =
        related(refs, "linkshere", "lh")

    override suspend fun transclusionsOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>> =
        related(refs, "transcludedin", "ti")

    override suspend fun fileUsageOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>> =
        related(refs, "fileusage", "fu")

    /** The three "what points at this" modules differ only in their name and prefix. */
    private suspend fun related(
        refs: Collection<PageRef>,
        module: String,
        prefix: String,
    ): Map<PageRef, List<PageRef>> = byPage(refs, module, "${prefix}limit" to "max") { entry ->
        (entry[module] as? JsonArray)
            ?.mapNotNull { decoder.refOf(it.jsonObject) }
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Reads one `prop=` module for a collection of pages, in batches.
     *
     * The same shape as [protections]: ask about as many titles as the wiki allows at once, and
     * keep only the pages the reader had something to say about.
     */
    private suspend fun <T : Any> byPage(
        refs: Collection<PageRef>,
        module: String,
        vararg params: Pair<String, String?>,
        read: (JsonObject) -> T?,
    ): Map<PageRef, T> {
        if (refs.isEmpty()) return emptyMap()

        val found = mutableMapOf<Title.Local, T>()
        for (batch in refs.map { it.title }.distinct().chunked(batchSize)) {
            val request = ApiRequest.of(
                "query",
                "prop" to module,
                "titles" to batch.joinToString("|") { namespaces.format(it) },
                *params,
            )

            continuation.pages(request).toList().forEach { entry ->
                val ref = decoder.refOf(entry) ?: return@forEach
                read(entry)?.let { found[ref.title] = it }
            }
        }

        return refs.mapNotNull { ref -> found[ref.title]?.let { ref to it } }.toMap()
    }

    private fun JsonObject.number(key: String): Int =
        this[key]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() } ?: 0

    override suspend fun protect(
        ref: PageRef,
        protections: List<Protection>,
        reason: String,
        cascade: Boolean,
        watchlist: WatchMode,
    ) {
        tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "protect")
                        watchlist.applyTo(this)
                        put("title", namespaces.format(ref.title))
                        // An empty restriction set is how MediaWiki spells "unprotect", so an
                        // empty list is passed through rather than refused.
                        put(
                            "protections",
                            protections.joinToString("|") { "${it.action}=${it.level}" },
                        )
                        put("expiry", protections.joinToString("|") { it.expiry.toString() })
                        put("reason", reason)
                        if (cascade) put("cascade", "1")
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).also { it.raiseBadToken() }.throwOnError()
        }
    }

    override suspend fun rollback(
        ref: PageRef,
        user: String,
        summary: String,
        markBot: Boolean,
        watchlist: WatchMode,
    ): EditOutcome {
        val response = tokens.withFreshToken(TokenStore.ROLLBACK) { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "rollback")
                        watchlist.applyTo(this)
                        put("title", namespaces.format(ref.title))
                        put("user", user)
                        if (summary.isNotEmpty()) put("summary", summary)
                        if (markBot) put("markbot", "1")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).also { it.raiseBadToken(TokenStore.ROLLBACK) }
        }

        ApiFailure.from(response)?.let { failure ->
            // A rollback refused because the page was edited first is the mechanism working
            // rather than a fault, and is the reason to prefer a rollback over an undo.
            return failure.toEditRefusal(ref) ?: throw failure.toWikiError()
        }

        val rollback = response["rollback"]?.jsonObject
            ?: throw WikiError.Api("norollback", "no rollback block in the response", "rollback")

        val newRevision = rollback["revid"]?.jsonPrimitive?.longOrNull
        val oldRevision = rollback["old_revid"]?.jsonPrimitive?.longOrNull

        return if (newRevision == null) {
            EditOutcome.NoChange(ref, oldRevision?.let { RevisionId(it) })
        } else {
            EditOutcome.Saved(ref, RevisionId(newRevision), oldRevision?.let { RevisionId(it) })
        }
    }

    override suspend fun undo(
        ref: PageRef,
        revision: RevisionId,
        summary: String,
        through: RevisionId?,
    ): EditOutcome {
        val response = tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "edit")
                        put("title", namespaces.format(ref.title))
                        put("undo", revision.value.toString())
                        // "undoafter" names the older end of the range, so a single-revision
                        // undo leaves it unset rather than repeating the same id.
                        through?.let { put("undoafter", it.value.toString()) }
                        put("summary", summary)
                        put("bot", "1")
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).also { it.raiseBadToken() }
        }

        ApiFailure.from(response)?.let { failure ->
            return failure.toEditRefusal(ref) ?: throw failure.toWikiError()
        }

        val edit = response["edit"]?.jsonObject
            ?: throw WikiError.Api("noedit", "no edit block in the response", "edit")

        return edit.toOutcome(ref)
    }

    override suspend fun purge(refs: Collection<PageRef>, forceLinkUpdate: Boolean) {
        if (refs.isEmpty()) return

        for (batch in refs.map { namespaces.format(it.title) }.distinct().chunked(batchSize)) {
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "purge")
                        put("titles", batch.joinToString("|"))
                        if (forceLinkUpdate) put("forcelinkupdate", "1")
                    },
                    // Purging changes no content, so it is paced as a read rather than burning
                    // the write budget a bot needs for actual edits.
                    RequestKind.READ,
                ),
            ).throwOnError()
        }
    }

    override suspend fun undelete(
        ref: PageRef,
        reason: String,
        undeleteTalk: Boolean,
        watchlist: WatchMode,
    ) {
        tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "undelete")
                        put("title", namespaces.format(ref.title))
                        put("reason", reason)
                        if (undeleteTalk) put("undeletetalk", "1")
                        watchlist.applyTo(this)
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).also { it.raiseBadToken() }.throwOnError()
        }
    }

    override suspend fun watch(refs: Collection<PageRef>, watch: Boolean, expiry: String?) {
        if (refs.isEmpty()) return

        for (batch in refs.map { namespaces.format(it.title) }.distinct().chunked(batchSize)) {
            // Watching uses its own token type, as rollback does.
            tokens.withFreshToken(TokenStore.WATCH) { token ->
                transport.call(
                    ApiRequest(
                        buildMap {
                            put("action", "watch")
                            put("titles", batch.joinToString("|"))
                            if (!watch) put("unwatch", "1")
                            expiry?.let { put("expiry", it) }
                            put("token", token)
                        },
                        RequestKind.WRITE,
                    ),
                ).also { it.raiseBadToken(TokenStore.WATCH) }.throwOnError()
            }
        }
    }

    override suspend fun expandText(wikitext: String, title: PageRef?): String {
        val response = transport.call(
            ApiRequest.of(
                "expandtemplates",
                "text" to wikitext,
                "title" to title?.let { namespaces.format(it.title) },
                "prop" to "wikitext",
                // Expanding changes nothing on the wiki, so it is paced as a read.
                kind = RequestKind.READ,
            ),
        ).throwOnError()

        return response["expandtemplates"]?.jsonObject?.get("wikitext")?.jsonPrimitive?.content
            ?: throw WikiError.Api(
                "noexpansion",
                "the wiki returned no expanded text",
                "expandtemplates",
            )
    }

    /** The protection entries on a page-info result. */
    private fun JsonObject.protections(): List<Protection> =
        this["protection"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val fields = entry.jsonObject
            val action = fields["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Protection(
                action = action,
                level = fields["level"]?.jsonPrimitive?.content.orEmpty(),
                expiry = fields["expiry"]?.jsonPrimitive?.content
                    ?.let { Expiry.parse(it) }
                    ?: Expiry.Never,
                cascading = fields.containsKey("cascade"),
            )
        }

    private fun JsonObject.toOutcome(ref: PageRef): EditOutcome {
        val result = this["result"]?.jsonPrimitive?.content.orEmpty()
        if (!result.equals("Success", ignoreCase = true)) {
            return EditOutcome.Rejected(ref, "edit returned $result", result.lowercase())
        }

        val newRevision = this["newrevid"]?.jsonPrimitive?.longOrNull
        val oldRevision = this["oldrevid"]?.jsonPrimitive?.longOrNull?.takeIf { it != 0L }

        // An edit whose text matched what was already there comes back as a success with
        // nochange set and no new revision. That is a no-op, not a save.
        return if (containsKey("nochange") || newRevision == null) {
            EditOutcome.NoChange(ref, oldRevision?.let { RevisionId(it) })
        } else {
            EditOutcome.Saved(ref, RevisionId(newRevision), oldRevision?.let { RevisionId(it) })
        }
    }

    private companion object {
        /** What the API allows a bot account to request in one query. */
        const val DEFAULT_BATCH = 50
    }
}
