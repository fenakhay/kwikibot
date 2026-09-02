package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.raiseBadToken
import com.fenakhay.kwikibot.client.service.EditBuilder
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.client.service.WatchMode
import com.fenakhay.kwikibot.client.service.applyTo
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.ActionChecks
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.page.CategoryInfo
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.model.user.Contributor
import com.fenakhay.kwikibot.model.user.Contributors
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ApiFailure
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.protocol.decode.PageResult
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlin.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
            val request =
                ApiRequest.of(
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
        val request =
            ApiRequest.of(
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
            transport.call(ApiRequest(builder.parameters(title, token), RequestKind.WRITE)).also {
                it.raiseBadToken()
            }
        }

        ApiFailure.from(response)?.let { failure ->
            return failure.toEditRefusal(ref) ?: throw failure.toWikiError()
        }

        val edit =
            response["edit"]?.jsonObject
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
            transport
                .call(
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
                    )
                )
                .also { it.raiseBadToken() }
                .throwOnError()
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
            transport
                .call(
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
                    )
                )
                .also { it.raiseBadToken() }
                .throwOnError()
        }
    }

    override suspend fun protections(refs: Collection<PageRef>): Map<PageRef, List<Protection>> {
        if (refs.isEmpty()) return emptyMap()

        val found = mutableMapOf<Title.Local, List<Protection>>()
        for (batch in refs.map { it.title }.distinct().chunked(batchSize)) {
            val request =
                ApiRequest.of(
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
                }
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
            transport
                .call(
                    ApiRequest(
                        buildMap {
                            put("action", action)
                            params()
                            put("assert", "user")
                            put("token", token)
                        },
                        RequestKind.WRITE,
                    )
                )
                .also { it.raiseBadToken() }
                .throwOnError()
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
                    users =
                        users.mapNotNull { user ->
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
    ): Map<PageRef, List<PageRef>> =
        byPage(refs, module, "${prefix}limit" to "max") { entry ->
            (entry[module] as? JsonArray)
                ?.mapNotNull { decoder.refOf(it.jsonObject) }
                ?.takeIf { it.isNotEmpty() }
        }

    /**
     * Reads one `prop=` module for a collection of pages, in batches.
     *
     * The same shape as [protections]: ask about as many titles as the wiki allows at once, and keep only the
     * pages the reader had something to say about.
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
            val request =
                ApiRequest.of(
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
            transport
                .call(
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
                    )
                )
                .also { it.raiseBadToken() }
                .throwOnError()
        }
    }

    override suspend fun rollback(
        ref: PageRef,
        user: String,
        summary: String,
        markBot: Boolean,
        watchlist: WatchMode,
    ): EditOutcome {
        val response =
            tokens.withFreshToken(TokenStore.ROLLBACK) { token ->
                transport
                    .call(
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
                        )
                    )
                    .also { it.raiseBadToken(TokenStore.ROLLBACK) }
            }

        ApiFailure.from(response)?.let { failure ->
            // A rollback refused because the page was edited first is the mechanism working
            // rather than a fault, and is the reason to prefer a rollback over an undo.
            return failure.toEditRefusal(ref) ?: throw failure.toWikiError()
        }

        val rollback =
            response["rollback"]?.jsonObject
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
            transport
                .call(
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
                    )
                )
                .also { it.raiseBadToken() }
        }

        ApiFailure.from(response)?.let { failure ->
            return failure.toEditRefusal(ref) ?: throw failure.toWikiError()
        }

        val edit =
            response["edit"]?.jsonObject
                ?: throw WikiError.Api("noedit", "no edit block in the response", "edit")

        return edit.toOutcome(ref)
    }

    override suspend fun purge(refs: Collection<PageRef>, forceLinkUpdate: Boolean) {
        if (refs.isEmpty()) return

        for (batch in refs.map { namespaces.format(it.title) }.distinct().chunked(batchSize)) {
            transport
                .call(
                    ApiRequest(
                        buildMap {
                            put("action", "purge")
                            put("titles", batch.joinToString("|"))
                            if (forceLinkUpdate) put("forcelinkupdate", "1")
                        },
                        // Purging changes no content, so it is paced as a read rather than burning
                        // the write budget a bot needs for actual edits.
                        RequestKind.READ,
                    )
                )
                .throwOnError()
        }
    }

    override suspend fun undelete(
        ref: PageRef,
        reason: String,
        undeleteTalk: Boolean,
        watchlist: WatchMode,
    ) {
        tokens.withFreshToken { token ->
            transport
                .call(
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
                    )
                )
                .also { it.raiseBadToken() }
                .throwOnError()
        }
    }

    override suspend fun watch(refs: Collection<PageRef>, watch: Boolean, expiry: String?) {
        if (refs.isEmpty()) return

        for (batch in refs.map { namespaces.format(it.title) }.distinct().chunked(batchSize)) {
            // Watching uses its own token type, as rollback does.
            tokens.withFreshToken(TokenStore.WATCH) { token ->
                transport
                    .call(
                        ApiRequest(
                            buildMap {
                                put("action", "watch")
                                put("titles", batch.joinToString("|"))
                                if (!watch) put("unwatch", "1")
                                expiry?.let { put("expiry", it) }
                                put("token", token)
                            },
                            RequestKind.WRITE,
                        )
                    )
                    .also { it.raiseBadToken(TokenStore.WATCH) }
                    .throwOnError()
            }
        }
    }

    override suspend fun expandText(wikitext: String, title: PageRef?): String {
        val response =
            transport
                .call(
                    ApiRequest.of(
                        "expandtemplates",
                        "text" to wikitext,
                        "title" to title?.let { namespaces.format(it.title) },
                        "prop" to "wikitext",
                        // Expanding changes nothing on the wiki, so it is paced as a read.
                        kind = RequestKind.READ,
                    )
                )
                .throwOnError()

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
                expiry = fields["expiry"]?.jsonPrimitive?.content?.let { Expiry.parse(it) } ?: Expiry.Never,
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
