@file:Suppress("LargeClass", "LongMethod")

package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.bindings.common.Setting
import com.fenakhay.kwikibot.bindings.openWiki
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.AbuseFilterOrder
import com.fenakhay.kwikibot.client.service.AbuseFilterProperty
import com.fenakhay.kwikibot.client.service.CategoryMemberType
import com.fenakhay.kwikibot.client.service.CategorySort
import com.fenakhay.kwikibot.client.service.FileService
import com.fenakhay.kwikibot.client.service.HistoryOrder
import com.fenakhay.kwikibot.client.service.NewPagesOrder
import com.fenakhay.kwikibot.client.service.RedirectFilter
import com.fenakhay.kwikibot.client.service.RevisionPart
import com.fenakhay.kwikibot.client.service.SearchScope
import com.fenakhay.kwikibot.client.service.SearchSort
import com.fenakhay.kwikibot.client.service.TemplateSandbox
import com.fenakhay.kwikibot.client.service.TimeOrder
import com.fenakhay.kwikibot.client.service.WatchMode
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.protocol.decode.OptionSet
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

/**
 * Every operation of the client area, called once with nothing given and once with everything given: the
 * first has to reach the core with npm's defaults, the second with what it was given.
 */
class ServiceDefaultsTest {

    private class Case(val name: String, val expected: List<Any?>, val call: suspend Wiki.() -> Unit)

    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val end = Instant.parse("2026-02-01T00:00:00Z")
    private val all = emptySet<Namespace>()
    private val mainOnly = setOf(Namespace.MAIN)
    private val talk = setOf(Namespace(1), Namespace(3))

    private fun cases(w: Wiki): List<Case> {
        val volcano = w.ref("Volcano")
        val ash = w.ref("Ash")
        val revision = RevisionId(7)
        val older = RevisionId(5)
        val sandbox = TemplateSandbox(listOf("User:Me/sandbox"), null, null, null)
        val protection = Protection("edit", "sysop", Expiry.Never, false)
        return listOf(
            Case(
                "edit, nothing given",
                listOf(
                    "edit",
                    volcano,
                    "lava",
                    null,
                    null,
                    "",
                    false,
                    true,
                    null,
                    false,
                    false,
                    emptyList<String>(),
                    null,
                    null,
                    WatchMode.PREFERENCES,
                    null,
                ),
            ) {
                editWithDefaults("Volcano", text = "lava")
            },
            Case(
                "edit, everything given",
                listOf(
                    "edit",
                    volcano,
                    "lava",
                    "after",
                    "before",
                    "s",
                    true,
                    false,
                    revision,
                    true,
                    true,
                    listOf("t"),
                    "2",
                    "heading",
                    WatchMode.WATCH,
                    "1 month",
                ),
            ) {
                editWithDefaults(
                    "Volcano",
                    "lava",
                    "after",
                    "before",
                    "s",
                    true,
                    false,
                    revision,
                    true,
                    true,
                    listOf("t"),
                    "2",
                    "heading",
                    WatchMode.WATCH,
                    "1 month",
                )
            },
            Case(
                "move, nothing given",
                listOf("move", volcano, ash, "", true, true, false, WatchMode.PREFERENCES),
            ) {
                moveWithDefaults("Volcano", "Ash") shouldBe "Ash"
            },
            Case(
                "move, everything given",
                listOf("move", volcano, ash, "r", false, false, true, WatchMode.UNWATCH),
            ) {
                moveWithDefaults("Volcano", "Ash", "r", false, false, true, WatchMode.UNWATCH)
            },
            Case("delete, nothing given", listOf("delete", volcano, "", false, WatchMode.PREFERENCES)) {
                deleteWithDefaults("Volcano")
            },
            Case("delete, everything given", listOf("delete", volcano, "r", true, WatchMode.WATCH)) {
                deleteWithDefaults("Volcano", "r", true, WatchMode.WATCH)
            },
            Case("undelete, nothing given", listOf("undelete", volcano, "", false, WatchMode.PREFERENCES)) {
                undeleteWithDefaults("Volcano")
            },
            Case("undelete, everything given", listOf("undelete", volcano, "r", true, WatchMode.NO_CHANGE)) {
                undeleteWithDefaults("Volcano", "r", true, WatchMode.NO_CHANGE)
            },
            Case("testActions, nothing given", listOf("testActions", listOf(volcano), setOf("edit"))) {
                testActionsWithDefaults(listOf("Volcano"))
            },
            Case(
                "testActions, everything given",
                listOf("testActions", listOf(volcano), setOf("move", "delete")),
            ) {
                testActionsWithDefaults(listOf("Volcano"), listOf("move", "delete"))
            },
            Case(
                "protect, nothing given",
                listOf("protect", volcano, listOf(protection), "", false, WatchMode.PREFERENCES),
            ) {
                protectWithDefaults("Volcano", listOf(protectionOf("edit", "sysop")))
            },
            Case(
                "protect, everything given",
                listOf(
                    "protect",
                    volcano,
                    listOf(Protection("move", "autoconfirmed", Expiry.At(end), true)),
                    "r",
                    true,
                    WatchMode.WATCH,
                ),
            ) {
                protectWithDefaults(
                    "Volcano",
                    listOf(protectionOf("move", "autoconfirmed", end, true)),
                    "r",
                    true,
                    WatchMode.WATCH,
                )
            },
            Case(
                "rollback, nothing given",
                listOf("rollback", volcano, "Vandal", "", true, WatchMode.PREFERENCES),
            ) {
                rollbackWithDefaults("Volcano", "Vandal")
            },
            Case(
                "rollback, everything given",
                listOf("rollback", volcano, "Vandal", "s", false, WatchMode.UNWATCH),
            ) {
                rollbackWithDefaults("Volcano", "Vandal", "s", false, WatchMode.UNWATCH)
            },
            Case("undo, nothing given", listOf("undo", volcano, revision, "", null)) {
                undoWithDefaults("Volcano", revision)
            },
            Case("undo, everything given", listOf("undo", volcano, revision, "s", older)) {
                undoWithDefaults("Volcano", revision, "s", older)
            },
            Case("mergeHistory, nothing given", listOf("mergeHistory", volcano, ash, null, "")) {
                mergeHistoryWithDefaults("Volcano", "Ash")
            },
            Case("mergeHistory, everything given", listOf("mergeHistory", volcano, ash, end, "r")) {
                mergeHistoryWithDefaults("Volcano", "Ash", end, "r")
            },
            Case("importPage, nothing given", listOf("importPage", "commons", "Foo", true, false, null, "")) {
                importPageWithDefaults("commons", "Foo")
            },
            Case(
                "importPage, everything given",
                listOf("importPage", "commons", "Foo", false, true, "Root", "s"),
            ) {
                importPageWithDefaults("commons", "Foo", false, true, "Root", "s")
            },
            Case(
                "expandTexts, nothing given",
                listOf("expandTexts", listOf("{{x}}"), null, false, null, null),
            ) {
                expandTextsWithDefaults(listOf("{{x}}"))
            },
            Case(
                "expandTexts, everything given",
                listOf("expandTexts", listOf("{{x}}"), volcano, true, sandbox, revision),
            ) {
                expandTextsWithDefaults(
                    listOf("{{x}}"),
                    "Volcano",
                    true,
                    sandboxOf(listOf("User:Me/sandbox")),
                    revision,
                )
            },
            Case("redirectsTo, nothing given", listOf("redirectsTo", listOf(volcano), all)) {
                redirectsToWithDefaults(listOf("Volcano"))
            },
            Case("redirectsTo, everything given", listOf("redirectsTo", listOf(volcano), talk)) {
                redirectsToWithDefaults(listOf("Volcano"), listOf(1, 3))
            },
        ) + lists(w) + revisionsAndLogs(w) + users() + filesAndMeta(w) + extensions(w)
    }

    private fun lists(w: Wiki): List<Case> {
        val volcano = w.ref("Volcano")
        val volcanoes = w.ref("Volcanoes", Namespace.CATEGORY)
        val infobox = w.ref("Infobox", Namespace.TEMPLATE)
        return listOf(
            Case(
                "categoryMembers, nothing given",
                listOf(
                    "categoryMembers",
                    volcanoes,
                    CategoryMemberType.PAGE,
                    all,
                    CategorySort.SORTKEY,
                    null,
                    null,
                ),
            ) {
                categoryMembersWithDefaults("Volcanoes")
            },
            Case(
                "categoryMembers, everything given",
                listOf(
                    "categoryMembers",
                    volcanoes,
                    CategoryMemberType.entries.last(),
                    talk,
                    CategorySort.TIMESTAMP,
                    "M",
                    9,
                ),
            ) {
                categoryMembersWithDefaults(
                    "Volcanoes",
                    CategoryMemberType.entries.last(),
                    listOf(1, 3),
                    CategorySort.TIMESTAMP,
                    "M",
                    9,
                )
            },
            Case("backlinks, nothing given", listOf("backlinks", volcano, all, true, null)) {
                backlinksWithDefaults("Volcano")
            },
            Case("backlinks, everything given", listOf("backlinks", volcano, talk, false, 9)) {
                backlinksWithDefaults("Volcano", listOf(1, 3), false, 9)
            },
            Case("transclusions, nothing given", listOf("transclusions", infobox, all, null)) {
                transclusionsWithDefaults("Infobox")
            },
            Case("transclusions, everything given", listOf("transclusions", infobox, talk, 9)) {
                transclusionsWithDefaults("Infobox", listOf(1, 3), 9)
            },
            Case("linksFrom, nothing given", listOf("linksFrom", volcano, all, null)) {
                linksFromWithDefaults("Volcano")
            },
            Case("linksFrom, everything given", listOf("linksFrom", volcano, talk, 9)) {
                linksFromWithDefaults("Volcano", listOf(1, 3), 9)
            },
            Case(
                "allPages, nothing given",
                listOf("allPages", Namespace.MAIN, null, null, null, false, RedirectFilter.ALL, null),
            ) {
                allPagesWithDefaults()
            },
            Case(
                "allPages, everything given",
                listOf("allPages", Namespace(4), "A", "B", "C", true, RedirectFilter.entries.last(), 9),
            ) {
                allPagesWithDefaults(4, "A", "B", "C", true, RedirectFilter.entries.last(), 9)
            },
            Case(
                "allLinkTargets, nothing given",
                listOf("allLinkTargets", Namespace.MAIN, null, null, null, true, null),
            ) {
                allLinkTargetsWithDefaults()
            },
            Case(
                "allLinkTargets, everything given",
                listOf("allLinkTargets", Namespace(4), "A", "B", "C", false, 9),
            ) {
                allLinkTargetsWithDefaults(4, "A", "B", "C", false, 9)
            },
            Case(
                "allRedirectTargets, nothing given",
                listOf("allRedirectTargets", Namespace.MAIN, null, null, null, true, null),
            ) {
                allRedirectTargetsWithDefaults()
            },
            Case(
                "allRedirectTargets, everything given",
                listOf("allRedirectTargets", Namespace(4), "A", "B", "C", false, 9),
            ) {
                allRedirectTargetsWithDefaults(4, "A", "B", "C", false, 9)
            },
            Case(
                "allTransclusionTargets, nothing given",
                listOf("allTransclusionTargets", Namespace.TEMPLATE, null, null, null, true, null),
            ) {
                allTransclusionTargetsWithDefaults()
            },
            Case(
                "allTransclusionTargets, everything given",
                listOf("allTransclusionTargets", Namespace(4), "A", "B", "C", false, 9),
            ) {
                allTransclusionTargetsWithDefaults(4, "A", "B", "C", false, 9)
            },
            Case("allFileUsages, nothing given", listOf("allFileUsages", null, null, null, true, null)) {
                allFileUsagesWithDefaults()
            },
            Case("allFileUsages, everything given", listOf("allFileUsages", "A", "B", "C", false, 9)) {
                allFileUsagesWithDefaults("A", "B", "C", false, 9)
            },
            Case("prefixSearch, nothing given", listOf("prefixSearch", "Vol", mainOnly, null)) {
                prefixSearchWithDefaults("Vol")
            },
            Case("prefixSearch, everything given", listOf("prefixSearch", "Vol", talk, 9)) {
                prefixSearchWithDefaults("Vol", listOf(1, 3), 9)
            },
            Case(
                "search, nothing given",
                listOf("search", "lava", mainOnly, SearchScope.TEXT, SearchSort.RELEVANCE, null),
            ) {
                searchWithDefaults("lava")
            },
            Case(
                "search, everything given",
                listOf("search", "lava", talk, SearchScope.TITLE, SearchSort.entries.last(), 9),
            ) {
                searchWithDefaults("lava", listOf(1, 3), SearchScope.TITLE, SearchSort.entries.last(), 9)
            },
            Case(
                "externalLinkUsage, nothing given",
                listOf("externalLinkUsage", "example.org", null, all, null),
            ) {
                externalLinkUsageWithDefaults("example.org")
            },
            Case(
                "externalLinkUsage, everything given",
                listOf("externalLinkUsage", "example.org", "https", talk, 9),
            ) {
                externalLinkUsageWithDefaults("example.org", "https", listOf(1, 3), 9)
            },
            Case("randomPages, nothing given", listOf("randomPages", mainOnly, null)) {
                randomPagesWithDefaults()
            },
            Case("randomPages, everything given", listOf("randomPages", talk, 9)) {
                randomPagesWithDefaults(listOf(1, 3), 9)
            },
            Case("watchlist, nothing given", listOf("watchlist", all, null)) { watchlistWithDefaults() },
            Case("watchlist, everything given", listOf("watchlist", talk, 9)) {
                watchlistWithDefaults(listOf(1, 3), 9)
            },
            Case("protectedTitles, nothing given", listOf("protectedTitles", all, null)) {
                protectedTitlesWithDefaults()
            },
            Case("protectedTitles, everything given", listOf("protectedTitles", talk, 9)) {
                protectedTitlesWithDefaults(listOf(1, 3), 9)
            },
        )
    }

    private fun revisionsAndLogs(w: Wiki): List<Case> {
        val volcano = w.ref("Volcano")
        val ids = listOf(RevisionId(1), RevisionId(2))
        val newest = HistoryOrder.NEWEST_FIRST
        val oldest = HistoryOrder.OLDEST_FIRST
        val minor = OptionSet.parse("minor|!bot")
        return listOf(
            Case("history, nothing given", listOf("history", volcano, newest, null, null, null, null, null)) {
                historyWithDefaults("Volcano")
            },
            Case("history, everything given", listOf("history", volcano, oldest, start, end, "A", "B", 9)) {
                historyWithDefaults("Volcano", oldest, start, end, "A", "B", 9)
            },
            Case("allRevisions, nothing given", listOf("allRevisions", all, null, newest, null, null, null)) {
                allRevisionsWithDefaults()
            },
            Case("allRevisions, everything given", listOf("allRevisions", talk, "A", oldest, start, end, 9)) {
                allRevisionsWithDefaults(listOf(1, 3), "A", oldest, start, end, 9)
            },
            Case("deletedHistory, nothing given", listOf("deletedHistory", volcano, newest, null)) {
                deletedHistoryWithDefaults("Volcano")
            },
            Case("deletedHistory, everything given", listOf("deletedHistory", volcano, oldest, 9)) {
                deletedHistoryWithDefaults("Volcano", oldest, 9)
            },
            Case(
                "allDeletedRevisions, nothing given",
                listOf("allDeletedRevisions", all, null, newest, null, null, null),
            ) {
                allDeletedRevisionsWithDefaults()
            },
            Case(
                "allDeletedRevisions, everything given",
                listOf("allDeletedRevisions", talk, "A", oldest, start, end, 9),
            ) {
                allDeletedRevisionsWithDefaults(listOf(1, 3), "A", oldest, start, end, 9)
            },
            Case(
                "revisionDelete, nothing given",
                listOf(
                    "revisionDelete",
                    volcano,
                    ids,
                    emptySet<RevisionPart>(),
                    emptySet<RevisionPart>(),
                    "",
                    false,
                ),
            ) {
                revisionDeleteWithDefaults("Volcano", ids)
            },
            Case(
                "revisionDelete, everything given",
                listOf(
                    "revisionDelete",
                    volcano,
                    ids,
                    setOf(RevisionPart.CONTENT),
                    setOf(RevisionPart.USER),
                    "r",
                    true,
                ),
            ) {
                revisionDeleteWithDefaults(
                    "Volcano",
                    ids,
                    listOf(RevisionPart.CONTENT),
                    listOf(RevisionPart.USER),
                    "r",
                    true,
                )
            },
            Case(
                "events, nothing given",
                listOf("events", null, null, null, null, all, TimeOrder.NEWEST_FIRST, null, null, null),
            ) {
                eventsWithDefaults()
            },
            Case(
                "events, everything given",
                listOf(
                    "events",
                    "block",
                    "block/block",
                    "A",
                    volcano,
                    talk,
                    TimeOrder.OLDEST_FIRST,
                    start,
                    end,
                    9,
                ),
            ) {
                eventsWithDefaults(
                    "block",
                    "block/block",
                    "A",
                    "Volcano",
                    listOf(1, 3),
                    TimeOrder.OLDEST_FIRST,
                    start,
                    end,
                    9,
                )
            },
            Case(
                "recentChanges, nothing given",
                listOf(
                    "recentChanges",
                    all,
                    emptySet<String>(),
                    null,
                    OptionSet(),
                    null,
                    false,
                    TimeOrder.NEWEST_FIRST,
                    null,
                    null,
                    null,
                ),
            ) {
                recentChangesWithDefaults()
            },
            Case(
                "recentChanges, everything given",
                listOf(
                    "recentChanges",
                    talk,
                    setOf("edit"),
                    "A",
                    minor,
                    "t",
                    true,
                    TimeOrder.OLDEST_FIRST,
                    start,
                    end,
                    9,
                ),
            ) {
                recentChangesWithDefaults(
                    listOf(1, 3),
                    listOf("edit"),
                    "A",
                    listOf("minor", "!bot"),
                    "t",
                    true,
                    TimeOrder.OLDEST_FIRST,
                    start,
                    end,
                    9,
                )
            },
            Case(
                "watchlistChanges, nothing given",
                listOf(
                    "watchlistChanges",
                    all,
                    emptySet<String>(),
                    OptionSet(),
                    false,
                    TimeOrder.NEWEST_FIRST,
                    null,
                    null,
                    null,
                ),
            ) {
                watchlistChangesWithDefaults()
            },
            Case(
                "watchlistChanges, everything given",
                listOf(
                    "watchlistChanges",
                    talk,
                    setOf("edit"),
                    minor,
                    true,
                    TimeOrder.OLDEST_FIRST,
                    start,
                    end,
                    9,
                ),
            ) {
                watchlistChangesWithDefaults(
                    listOf(1, 3),
                    listOf("edit"),
                    listOf("minor", "!bot"),
                    true,
                    TimeOrder.OLDEST_FIRST,
                    start,
                    end,
                    9,
                )
            },
        )
    }

    private fun users(): List<Case> {
        val top = OptionSet.parse("top")
        return listOf(
            Case(
                "contributions, nothing given",
                listOf("contributions", "A", all, null, null, OptionSet(), null, null),
            ) {
                contributionsWithDefaults("A")
            },
            Case(
                "contributions, everything given",
                listOf("contributions", "A", talk, start, end, top, "t", 9),
            ) {
                contributionsWithDefaults("A", listOf(1, 3), start, end, listOf("top"), "t", 9)
            },
            Case("block, nothing given", listOf("block", "A", "r", Expiry.Never, false, true, true)) {
                blockWithDefaults("A", "r")
            },
            Case("block, everything given", listOf("block", "A", "r", Expiry.At(end), true, false, false)) {
                blockWithDefaults("A", "r", end, true, false, false)
            },
            Case(
                "blocks, nothing given",
                listOf("blocks", emptyList<String>(), OptionSet(), null, null, null),
            ) {
                blocksWithDefaults()
            },
            Case(
                "blocks, everything given",
                listOf("blocks", listOf("A"), OptionSet.parse("ip"), start, end, 9),
            ) {
                blocksWithDefaults(listOf("A"), listOf("ip"), start, end, 9)
            },
            Case(
                "allUsers, nothing given",
                listOf("allUsers", null, null, null, emptySet<String>(), false, false, null),
            ) {
                allUsersWithDefaults()
            },
            Case(
                "allUsers, everything given",
                listOf("allUsers", "A", "B", "C", setOf("bot"), true, true, 9),
            ) {
                allUsersWithDefaults("A", "B", "C", listOf("bot"), true, true, 9)
            },
            Case(
                "changeRights, nothing given",
                listOf("changeRights", "A", emptySet<String>(), emptySet<String>(), "", null),
            ) {
                changeRightsWithDefaults("A")
            },
            Case(
                "changeRights, everything given",
                listOf("changeRights", "A", setOf("bot"), setOf("sysop"), "r", "1 week"),
            ) {
                changeRightsWithDefaults("A", listOf("bot"), listOf("sysop"), "r", "1 week")
            },
        )
    }

    private fun filesAndMeta(w: Wiki): List<Case> {
        val photo = w.ref("Photo.jpg", Namespace.FILE)
        val path = Path("photo.jpg")
        return listOf(
            Case("fileInfo, nothing given", listOf("info", listOf(photo), 1)) {
                fileInfoWithDefaults(listOf("Photo.jpg"))
            },
            Case("fileInfo, everything given", listOf("info", listOf(photo), 3)) {
                fileInfoWithDefaults(listOf("File:Photo.jpg"), 3)
            },
            Case("duplicatesOf, nothing given", listOf("duplicatesOf", "abc", FileService.DUPLICATE_LIMIT)) {
                duplicatesOfWithDefaults("abc")
            },
            Case("duplicatesOf, everything given", listOf("duplicatesOf", "abc", 9)) {
                duplicatesOfWithDefaults("abc", 9)
            },
            Case(
                "upload, nothing given",
                listOf("upload", path, photo, "", null, false, FileService.DEFAULT_CHUNK),
            ) {
                uploadWithDefaults(path, "Photo.jpg")
            },
            Case("upload, everything given", listOf("upload", path, photo, "c", "t", true, 1024)) {
                uploadWithDefaults(path, "Photo.jpg", "c", "t", true, 1024)
            },
            Case("languages, nothing given", listOf("languages", emptyList<LangCode>())) {
                languagesWithDefaults()
            },
            Case("languages, everything given", listOf("languages", listOf(LangCode("fr")))) {
                languagesWithDefaults(listOf("fr"))
            },
            Case(
                "applyTags, nothing given",
                listOf(
                    "applyTags",
                    emptySet<String>(),
                    emptySet<String>(),
                    emptyList<Long>(),
                    emptyList<Long>(),
                    emptyList<Long>(),
                    "",
                ),
            ) {
                applyTagsWithDefaults()
            },
            Case(
                "applyTags, everything given",
                listOf("applyTags", setOf("a"), setOf("b"), listOf(1L), listOf(2L), listOf(3L), "r"),
            ) {
                applyTagsWithDefaults(listOf("a"), listOf("b"), listOf(1L), listOf(2L), listOf(3L), "r")
            },
        )
    }

    private fun extensions(w: Wiki): List<Case> {
        val volcano = w.ref("Volcano")
        val revision = RevisionId(7)
        return listOf(
            Case("nearby, nothing given", listOf("nearby", 1.0, 2.0, 1000, 10)) {
                nearbyWithDefaults(1.0, 2.0)
            },
            Case("nearby, everything given", listOf("nearby", 1.0, 2.0, 50, 3)) {
                nearbyWithDefaults(1.0, 2.0, 50, 3)
            },
            Case("extracts, nothing given", listOf("extracts", listOf(volcano), 2)) {
                extractsWithDefaults(listOf("Volcano"))
            },
            Case("extracts, everything given", listOf("extracts", listOf(volcano), 0)) {
                extractsWithDefaults(listOf("Volcano"), 0)
            },
            Case("notifications, nothing given", listOf("notifications", false, 25)) {
                notificationsWithDefaults()
            },
            Case("notifications, everything given", listOf("notifications", true, 5)) {
                notificationsWithDefaults(true, 5)
            },
            Case("thank, nothing given", listOf("thank", revision, "kwikibot")) {
                thankWithDefaults(revision)
            },
            Case("thank, everything given", listOf("thank", revision, "diff")) {
                thankWithDefaults(revision, "diff")
            },
            Case("review, nothing given", listOf("review", revision, emptyMap<String, Int>(), "")) {
                reviewWithDefaults(revision)
            },
            Case("review, everything given", listOf("review", revision, mapOf("accuracy" to 2), "ok")) {
                reviewWithDefaults(revision, mapOf("accuracy" to 2), "ok")
            },
            Case(
                "abuseFilters, nothing given",
                listOf(
                    "abuseFilters",
                    OptionSet(),
                    AbuseFilterProperty.DEFAULT,
                    AbuseFilterOrder.LOWEST_ID_FIRST,
                    null,
                ),
            ) {
                abuseFiltersWithDefaults()
            },
            Case(
                "abuseFilters, everything given",
                listOf(
                    "abuseFilters",
                    OptionSet.parse("enabled"),
                    setOf(AbuseFilterProperty.ID),
                    AbuseFilterOrder.entries.last(),
                    9,
                ),
            ) {
                abuseFiltersWithDefaults(
                    listOf("enabled"),
                    listOf(AbuseFilterProperty.ID),
                    AbuseFilterOrder.entries.last(),
                    9,
                )
            },
            Case(
                "newPages, nothing given",
                listOf(
                    "newPages",
                    Namespace.MAIN,
                    false,
                    false,
                    emptySet<String>(),
                    false,
                    NewPagesOrder.NEWEST_FIRST,
                    null,
                ),
            ) {
                newPagesWithDefaults()
            },
            Case(
                "newPages, everything given",
                listOf(
                    "newPages",
                    Namespace(2),
                    null,
                    true,
                    setOf("A"),
                    true,
                    NewPagesOrder.entries.last(),
                    9,
                ),
            ) {
                newPagesWithDefaults(2, Setting.Off, true, listOf("A"), true, NewPagesOrder.entries.last(), 9)
            },
            Case(
                "newPageStats, nothing given",
                listOf("newPageStats", Namespace.MAIN, emptySet<String>(), false),
            ) {
                val _ = runCatching { newPageStatsWithDefaults() }
            },
            Case("newPageStats, everything given", listOf("newPageStats", Namespace(2), setOf("A"), true)) {
                val _ = runCatching { newPageStatsWithDefaults(2, listOf("A"), true) }
            },
        )
    }

    @Test
    fun `each operation reaches the core with npm's defaults or with what it was given`() = runTest {
        val wiki = RecordingWiki(openWiki())
        for (case in cases(wiki)) {
            wiki.calls.clear()
            case.call(wiki)
            (case.name to wiki.calls.only) shouldBe (case.name to case.expected)
        }
    }

    @Test
    fun `new pages are unreviewed unless told and all of them is either`() {
        reviewedNamed(null) shouldBe Setting.Default
        reviewedNamed("unreviewed") shouldBe Setting.Value(false)
        reviewedNamed("reviewed") shouldBe Setting.Value(true)
        reviewedNamed("all") shouldBe Setting.Off
        assertFailsWith<IllegalArgumentException> { reviewedNamed("some") }.message shouldBe
            "reviewed cannot be 'some': it is one of 'unreviewed', 'reviewed', 'all'"
    }

    @Test
    fun `a sandbox names nothing unless given and a page to stand in for when given`() = runTest {
        val wiki = openWiki()
        assertFailsWith<IllegalArgumentException> { wiki.sandboxOf() }
        wiki.sandboxOf(null, "Module:Foo", "return {}", "Scribunto") shouldBe
            TemplateSandbox(emptyList(), wiki.ref("Module:Foo"), "return {}", "Scribunto")
        wiki.fileRef("Photo.jpg") shouldBe wiki.ref("File:Photo.jpg")
        wiki.titleOf(wiki.ref("Category:Volcanoes")) shouldBe "Category:Volcanoes"
        wiki.refs(listOf("Infobox"), Namespace.TEMPLATE) shouldBe listOf(wiki.ref("Template:Infobox"))
        namespaceOf(null) shouldBe Namespace.MAIN
        namespaceOf(4, Namespace.TEMPLATE) shouldBe Namespace(4)
    }
}
