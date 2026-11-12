package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.bindings.Calls
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.model.AbuseFilter
import com.fenakhay.kwikibot.client.model.Notification
import com.fenakhay.kwikibot.client.model.TriagePage
import com.fenakhay.kwikibot.client.model.TriageStats
import com.fenakhay.kwikibot.client.service.AbuseFilterOrder
import com.fenakhay.kwikibot.client.service.AbuseFilterProperty
import com.fenakhay.kwikibot.client.service.CategoryMemberType
import com.fenakhay.kwikibot.client.service.CategorySort
import com.fenakhay.kwikibot.client.service.EditBuilder
import com.fenakhay.kwikibot.client.service.ExpandedText
import com.fenakhay.kwikibot.client.service.ExtensionService
import com.fenakhay.kwikibot.client.service.FileService
import com.fenakhay.kwikibot.client.service.HistoryOrder
import com.fenakhay.kwikibot.client.service.ListService
import com.fenakhay.kwikibot.client.service.LogService
import com.fenakhay.kwikibot.client.service.MetaService
import com.fenakhay.kwikibot.client.service.NewPagesOrder
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.client.service.RedirectFilter
import com.fenakhay.kwikibot.client.service.RevisionPart
import com.fenakhay.kwikibot.client.service.RevisionService
import com.fenakhay.kwikibot.client.service.SearchScope
import com.fenakhay.kwikibot.client.service.SearchSort
import com.fenakhay.kwikibot.client.service.TemplateSandbox
import com.fenakhay.kwikibot.client.service.TimeOrder
import com.fenakhay.kwikibot.client.service.UserService
import com.fenakhay.kwikibot.client.service.WatchMode
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.LanguageInfo
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.ActionChecks
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.file.FileInfo
import com.fenakhay.kwikibot.model.file.UploadOutcome
import com.fenakhay.kwikibot.model.log.LogEvent
import com.fenakhay.kwikibot.model.log.RecentChange
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.Revision
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.user.BlockRecord
import com.fenakhay.kwikibot.model.user.Contribution
import com.fenakhay.kwikibot.model.user.UserInfo
import com.fenakhay.kwikibot.protocol.decode.OptionSet
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.files.Path

/**
 * [real] with every service a binding's defaults reach replaced by one that records each call into [calls],
 * and answers with nothing much. `editing`, when given, says what an edit answers, given the builder the edit
 * filled in.
 */
class RecordingWiki(
    real: Wiki,
    val calls: Calls = Calls(),
    private val editing: (suspend (EditBuilder) -> EditOutcome)? = null,
) : Wiki by real {

    override val pages: PageService = Pages(real.pages)

    override val lists: ListService = Lists(real.lists)

    override val revisions: RevisionService = Revisions(real.revisions)

    override val logs: LogService = Logs(real.logs)

    override val users: UserService = Users(real.users)

    override val files: FileService = Files(real.files)

    override val meta: MetaService = Meta(real.meta)

    override val extensions: ExtensionService = Extensions(real.extensions)

    private inner class Pages(base: PageService) : PageService by base {
        override suspend fun edit(ref: PageRef, block: EditBuilder.() -> Unit): EditOutcome {
            val builder = EditBuilder().apply(block)
            calls.record(
                "edit",
                ref,
                builder.text,
                builder.appendText,
                builder.prependText,
                builder.summary,
                builder.minor,
                builder.bot,
                builder.baseRevision,
                builder.noCreate,
                builder.createOnly,
                builder.tags,
                builder.section,
                builder.sectionTitle,
                builder.watchlist,
                builder.watchlistExpiry,
            )
            return editing?.invoke(builder) ?: EditOutcome.NoChange(ref, null)
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
            calls.record("move", from, to, reason, leaveRedirect, moveTalk, moveSubpages, watchlist)
            return to
        }

        override suspend fun delete(ref: PageRef, reason: String, deleteTalk: Boolean, watchlist: WatchMode) {
            calls.record("delete", ref, reason, deleteTalk, watchlist)
        }

        override suspend fun testActions(
            refs: Collection<PageRef>,
            actions: Set<String>,
        ): Map<PageRef, ActionChecks> {
            calls.record("testActions", refs, actions)
            return emptyMap()
        }

        override suspend fun protect(
            ref: PageRef,
            protections: List<Protection>,
            reason: String,
            cascade: Boolean,
            watchlist: WatchMode,
        ) {
            calls.record("protect", ref, protections, reason, cascade, watchlist)
        }

        override suspend fun rollback(
            ref: PageRef,
            user: String,
            summary: String,
            markBot: Boolean,
            watchlist: WatchMode,
        ): EditOutcome {
            calls.record("rollback", ref, user, summary, markBot, watchlist)
            return EditOutcome.NoChange(ref, null)
        }

        override suspend fun undo(
            ref: PageRef,
            revision: RevisionId,
            summary: String,
            through: RevisionId?,
        ): EditOutcome {
            calls.record("undo", ref, revision, summary, through)
            return EditOutcome.NoChange(ref, null)
        }

        override suspend fun undelete(
            ref: PageRef,
            reason: String,
            undeleteTalk: Boolean,
            watchlist: WatchMode,
        ) {
            calls.record("undelete", ref, reason, undeleteTalk, watchlist)
        }

        override suspend fun mergeHistory(from: PageRef, to: PageRef, upTo: Instant?, reason: String) {
            calls.record("mergeHistory", from, to, upTo, reason)
        }

        override suspend fun importPage(
            source: String,
            page: String,
            fullHistory: Boolean,
            includeTemplates: Boolean,
            rootPage: String?,
            summary: String,
        ) {
            calls.record("importPage", source, page, fullHistory, includeTemplates, rootPage, summary)
        }

        override suspend fun expandTexts(
            texts: List<String>,
            title: PageRef?,
            categories: Boolean,
            sandbox: TemplateSandbox?,
            revision: RevisionId?,
        ): List<ExpandedText> {
            calls.record("expandTexts", texts, title, categories, sandbox, revision)
            return emptyList()
        }

        override suspend fun redirectsTo(
            refs: Collection<PageRef>,
            namespaces: Set<Namespace>,
        ): Map<PageRef, List<PageRef>> {
            calls.record("redirectsTo", refs, namespaces)
            return emptyMap()
        }
    }

    private inner class Lists(base: ListService) : ListService by base {
        override fun categoryMembers(
            category: PageRef,
            type: CategoryMemberType,
            namespaces: Set<Namespace>,
            sort: CategorySort,
            startSortKey: String?,
            limit: Int?,
        ): Flow<PageRef> = heard("categoryMembers", category, type, namespaces, sort, startSortKey, limit)

        override fun backlinks(
            target: PageRef,
            namespaces: Set<Namespace>,
            includeRedirects: Boolean,
            limit: Int?,
        ): Flow<PageRef> = heard("backlinks", target, namespaces, includeRedirects, limit)

        override fun transclusions(
            template: PageRef,
            namespaces: Set<Namespace>,
            limit: Int?,
        ): Flow<PageRef> = heard("transclusions", template, namespaces, limit)

        override fun linksFrom(page: PageRef, namespaces: Set<Namespace>, limit: Int?): Flow<PageRef> =
            heard("linksFrom", page, namespaces, limit)

        override fun allPages(
            namespace: Namespace,
            from: String?,
            to: String?,
            prefix: String?,
            descending: Boolean,
            redirects: RedirectFilter,
            limit: Int?,
        ): Flow<PageRef> = heard("allPages", namespace, from, to, prefix, descending, redirects, limit)

        override fun allLinkTargets(
            namespace: Namespace,
            from: String?,
            to: String?,
            prefix: String?,
            unique: Boolean,
            limit: Int?,
        ): Flow<PageRef> = heard("allLinkTargets", namespace, from, to, prefix, unique, limit)

        override fun allRedirectTargets(
            namespace: Namespace,
            from: String?,
            to: String?,
            prefix: String?,
            unique: Boolean,
            limit: Int?,
        ): Flow<PageRef> = heard("allRedirectTargets", namespace, from, to, prefix, unique, limit)

        override fun allTransclusionTargets(
            namespace: Namespace,
            from: String?,
            to: String?,
            prefix: String?,
            unique: Boolean,
            limit: Int?,
        ): Flow<PageRef> = heard("allTransclusionTargets", namespace, from, to, prefix, unique, limit)

        override fun allFileUsages(
            from: String?,
            to: String?,
            prefix: String?,
            unique: Boolean,
            limit: Int?,
        ): Flow<PageRef> = heard("allFileUsages", from, to, prefix, unique, limit)

        override fun prefixSearch(query: String, namespaces: Set<Namespace>, limit: Int?): Flow<PageRef> =
            heard("prefixSearch", query, namespaces, limit)

        override fun search(
            query: String,
            namespaces: Set<Namespace>,
            scope: SearchScope,
            sort: SearchSort,
            limit: Int?,
        ): Flow<PageRef> = heard("search", query, namespaces, scope, sort, limit)

        override fun externalLinkUsage(
            url: String,
            protocol: String?,
            namespaces: Set<Namespace>,
            limit: Int?,
        ): Flow<PageRef> = heard("externalLinkUsage", url, protocol, namespaces, limit)

        override fun randomPages(namespaces: Set<Namespace>, limit: Int?): Flow<PageRef> =
            heard("randomPages", namespaces, limit)

        override fun watchlist(namespaces: Set<Namespace>, limit: Int?): Flow<PageRef> =
            heard("watchlist", namespaces, limit)

        override fun protectedTitles(namespaces: Set<Namespace>, limit: Int?): Flow<PageRef> =
            heard("protectedTitles", namespaces, limit)
    }

    private inner class Revisions(base: RevisionService) : RevisionService by base {
        override fun history(
            page: PageRef,
            order: HistoryOrder,
            start: Instant?,
            end: Instant?,
            user: String?,
            excludeUser: String?,
            limit: Int?,
        ): Flow<Revision> = heard("history", page, order, start, end, user, excludeUser, limit)

        override fun allRevisions(
            namespaces: Set<Namespace>,
            user: String?,
            order: HistoryOrder,
            start: Instant?,
            end: Instant?,
            limit: Int?,
        ): Flow<Revision> = heard("allRevisions", namespaces, user, order, start, end, limit)

        override fun deletedHistory(page: PageRef, order: HistoryOrder, limit: Int?): Flow<Revision> =
            heard("deletedHistory", page, order, limit)

        override fun allDeletedRevisions(
            namespaces: Set<Namespace>,
            user: String?,
            order: HistoryOrder,
            start: Instant?,
            end: Instant?,
            limit: Int?,
        ): Flow<Revision> = heard("allDeletedRevisions", namespaces, user, order, start, end, limit)

        override suspend fun revisionDelete(
            page: PageRef,
            revisions: Collection<RevisionId>,
            hide: Set<RevisionPart>,
            show: Set<RevisionPart>,
            reason: String,
            suppress: Boolean,
        ) {
            calls.record("revisionDelete", page, revisions, hide, show, reason, suppress)
        }
    }

    private inner class Logs(base: LogService) : LogService by base {
        override fun events(
            type: String?,
            action: String?,
            user: String?,
            page: PageRef?,
            namespaces: Set<Namespace>,
            order: TimeOrder,
            start: Instant?,
            end: Instant?,
            limit: Int?,
        ): Flow<LogEvent> = heard("events", type, action, user, page, namespaces, order, start, end, limit)

        override fun recentChanges(
            namespaces: Set<Namespace>,
            types: Set<String>,
            user: String?,
            show: OptionSet,
            tag: String?,
            topOnly: Boolean,
            order: TimeOrder,
            start: Instant?,
            end: Instant?,
            limit: Int?,
        ): Flow<RecentChange> =
            heard("recentChanges", namespaces, types, user, show, tag, topOnly, order, start, end, limit)

        override fun watchlistChanges(
            namespaces: Set<Namespace>,
            types: Set<String>,
            show: OptionSet,
            allRevisions: Boolean,
            order: TimeOrder,
            start: Instant?,
            end: Instant?,
            limit: Int?,
        ): Flow<RecentChange> =
            heard("watchlistChanges", namespaces, types, show, allRevisions, order, start, end, limit)
    }

    private inner class Users(base: UserService) : UserService by base {
        override fun contributions(
            user: String,
            namespaces: Set<Namespace>,
            start: Instant?,
            end: Instant?,
            show: OptionSet,
            tag: String?,
            limit: Int?,
        ): Flow<Contribution> = heard("contributions", user, namespaces, start, end, show, tag, limit)

        override suspend fun block(
            user: String,
            reason: String,
            expiry: Expiry,
            anonymousOnly: Boolean,
            preventAccountCreation: Boolean,
            autoBlock: Boolean,
        ) {
            calls.record("block", user, reason, expiry, anonymousOnly, preventAccountCreation, autoBlock)
        }

        override fun blocks(
            users: Collection<String>,
            show: OptionSet,
            start: Instant?,
            end: Instant?,
            limit: Int?,
        ): Flow<BlockRecord> = heard("blocks", users, show, start, end, limit)

        override fun allUsers(
            from: String?,
            to: String?,
            prefix: String?,
            group: Set<String>,
            withEditsOnly: Boolean,
            excludeTemporary: Boolean,
            limit: Int?,
        ): Flow<UserInfo> = heard("allUsers", from, to, prefix, group, withEditsOnly, excludeTemporary, limit)

        override suspend fun changeRights(
            user: String,
            add: Set<String>,
            remove: Set<String>,
            reason: String,
            expiry: String?,
        ) {
            calls.record("changeRights", user, add, remove, reason, expiry)
        }
    }

    private inner class Files(base: FileService) : FileService by base {
        override suspend fun info(refs: Collection<PageRef>, versions: Int): Map<PageRef, List<FileInfo>> {
            calls.record("info", refs, versions)
            return emptyMap()
        }

        override suspend fun duplicatesOf(sha1: String, limit: Int): List<PageRef> {
            calls.record("duplicatesOf", sha1, limit)
            return emptyList()
        }

        override suspend fun upload(
            file: Path,
            to: PageRef,
            comment: String,
            text: String?,
            ignoreWarnings: Boolean,
            chunkSize: Int,
        ): UploadOutcome {
            calls.record("upload", file, to, comment, text, ignoreWarnings, chunkSize)
            return UPLOADED
        }
    }

    private inner class Meta(base: MetaService) : MetaService by base {
        override suspend fun languages(codes: Collection<LangCode>): Map<LangCode, LanguageInfo> {
            calls.record("languages", codes)
            return emptyMap()
        }

        override suspend fun applyTags(
            add: Set<String>,
            remove: Set<String>,
            revisions: Collection<Long>,
            recentChanges: Collection<Long>,
            logEntries: Collection<Long>,
            reason: String,
        ) {
            calls.record("applyTags", add, remove, revisions, recentChanges, logEntries, reason)
        }
    }

    private inner class Extensions(base: ExtensionService) : ExtensionService by base {
        override suspend fun nearby(
            latitude: Double,
            longitude: Double,
            radius: Int,
            limit: Int,
        ): List<PageRef> {
            calls.record("nearby", latitude, longitude, radius, limit)
            return emptyList()
        }

        override suspend fun extracts(refs: Collection<PageRef>, sentences: Int): Map<PageRef, String> {
            calls.record("extracts", refs, sentences)
            return emptyMap()
        }

        override suspend fun notifications(unreadOnly: Boolean, limit: Int): List<Notification> {
            calls.record("notifications", unreadOnly, limit)
            return emptyList()
        }

        override suspend fun thank(revision: RevisionId, source: String) {
            calls.record("thank", revision, source)
        }

        override suspend fun review(revision: RevisionId, flags: Map<String, Int>, comment: String) {
            calls.record("review", revision, flags, comment)
        }

        override fun abuseFilters(
            show: OptionSet,
            properties: Set<AbuseFilterProperty>,
            order: AbuseFilterOrder,
            limit: Int?,
        ): Flow<AbuseFilter> = heard("abuseFilters", show, properties, order, limit)

        override fun newPages(
            namespace: Namespace,
            reviewed: Boolean?,
            redirects: Boolean,
            creators: Set<String>,
            hideOwnPages: Boolean,
            order: NewPagesOrder,
            limit: Int?,
        ): Flow<TriagePage> =
            heard("newPages", namespace, reviewed, redirects, creators, hideOwnPages, order, limit)

        override suspend fun newPageStats(
            namespace: Namespace,
            creators: Set<String>,
            hideOwnPages: Boolean,
        ): TriageStats {
            calls.record("newPageStats", namespace, creators, hideOwnPages)
            throw Answered
        }
    }

    private fun <T> heard(name: String, vararg arguments: Any?): Flow<T> {
        calls.record(name, *arguments)
        return emptyFlow()
    }

    /** Thrown by a recording that has no answer to give, once it has heard the call. */
    object Answered : RuntimeException("recorded")

    private companion object {
        val UPLOADED: UploadOutcome = UploadOutcome.Refused("recorded", "recorded")
    }
}
