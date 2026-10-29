package com.fenakhay.kwikibot.protocol.decode

import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.log.LogDetails
import com.fenakhay.kwikibot.model.log.LogEvent
import com.fenakhay.kwikibot.model.log.RecentChange
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.Revision
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.model.user.BlockInfo
import com.fenakhay.kwikibot.model.user.BlockRecord
import com.fenakhay.kwikibot.model.user.Contribution
import com.fenakhay.kwikibot.model.user.UserInfo
import com.fenakhay.kwikibot.protocol.testResource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ActivityDecoderTest {

    private val decoder = ActivityDecoder(PageDecoder(WikiId("enwiktionary"), NamespaceMap.CANONICAL))

    private val queries by lazy {
        Json.parseToJsonElement(testResource("activity.json")).jsonObject.getValue("queries").jsonObject
    }

    private fun entries(query: String, list: String): List<JsonObject> =
        queries.getValue(query).jsonObject.getValue(list).jsonArray.map { it.jsonObject }

    private fun logEvents(query: String): List<LogEvent> =
        entries(query, "logevents").map { decoder.decodeLogEvent(it) }

    private fun recentChanges(query: String): List<RecentChange> =
        entries(query, "recentchanges").map { decoder.decodeRecentChange(it) }

    @Test
    fun `every recorded log entry decodes with an id a type and a time`() {
        val all =
            listOf(
                    "logevents_move",
                    "logevents_block",
                    "logevents_delete",
                    "logevents_upload",
                    "logevents_protect",
                    "logevents_all",
                )
                .flatMap { logEvents(it) }

        (all.size > 20) shouldBe true
        all.all { it.id > 0 } shouldBe true
        all.all { it.type.isNotEmpty() } shouldBe true
    }

    @Test
    fun `a move entry names the page it went to`() {
        val move = logEvents("logevents_move").first()
        val details = move.details.shouldBeInstanceOf<LogDetails.Move>()

        move.type shouldBe "move"
        move.page.shouldNotBeNull().title.text.isNotEmpty() shouldBe true
        details.target.text.isNotEmpty() shouldBe true
    }

    @Test
    fun `a block entry keeps its duration expiry and flags apart`() {
        val block = logEvents("logevents_block").first { (it.details as? LogDetails.Block)?.duration != null }
        val details = block.details.shouldBeInstanceOf<LogDetails.Block>()

        details.duration?.isNotEmpty() shouldBe true
        (details.expiry != null || details.isInfinite) shouldBe true
    }

    @Test
    fun `a protect entry decodes the restrictions and not just their description`() {
        val protect =
            logEvents("logevents_protect").first {
                (it.details as? LogDetails.Protect)?.protections?.isNotEmpty() == true
            }
        val details = protect.details.shouldBeInstanceOf<LogDetails.Protect>()

        details.protections.all { it.action.isNotEmpty() && it.level.isNotEmpty() } shouldBe true
        details.description?.isNotEmpty() shouldBe true
    }

    @Test
    fun `a log type this library does not model keeps its fields`() {
        val unknown = logEvents("logevents_all").map { it.details }.filterIsInstance<LogDetails.Unknown>()

        unknown.all { it.fields.isNotEmpty() } shouldBe true
    }

    @Test
    fun `an edit in recent changes reports what it changed`() {
        val edit = recentChanges("recentchanges").first { it.type == "edit" }

        (edit.id > 0) shouldBe true
        (edit.revisionId.shouldNotBeNull().value > 0) shouldBe true
        edit.previousRevisionId.shouldNotBeNull()
        edit.isLogEntry shouldBe false
    }

    @Test
    fun `a log action in recent changes is decoded from its own field names`() {
        val entries = recentChanges("recentchanges_log")

        entries.isNotEmpty() shouldBe true
        entries.all { it.isLogEntry } shouldBe true
        entries.all { checkNotNull(it.logEvent).type != "log" } shouldBe true
        entries.all { checkNotNull(it.logEvent).id > 0 } shouldBe true
    }

    @Test
    fun `a size change is the difference between the two lengths`() {
        val entry =
            entries("recentchanges", "recentchanges").first { it["type"]?.jsonPrimitive?.content == "edit" }
        val expected = entry.getValue("newlen").jsonPrimitive.int - entry.getValue("oldlen").jsonPrimitive.int

        recentChanges("recentchanges").first { it.type == "edit" }.sizeChange shouldBe expected
    }

    @Test
    fun `an unpatrolled change is told apart from one nobody asked about`() {
        val asked =
            Json.parseToJsonElement(
                    """{"type":"edit","rcid":1,"ns":0,"title":"volcano","timestamp":"2026-08-01T00:00:00Z",
                       "patrolled":false}"""
                )
                .jsonObject

        decoder.decodeRecentChange(asked).patrolled shouldBe false
        // The recorded queries did not ask for the flag.
        recentChanges("recentchanges").all { it.patrolled == null && !it.isPatrolled } shouldBe true
    }

    @Test
    fun `a contribution carries its page and its revision`() {
        val contributions =
            entries("usercontribs", "usercontribs").mapNotNull { decoder.decodeContribution(it) }

        contributions.isNotEmpty() shouldBe true
        contributions.all { it.revision.id.value > 0 } shouldBe true
        contributions.all { it.page.title.text.isNotEmpty() } shouldBe true
        contributions.all { it.revision.user == "SemperBlottoBot" } shouldBe true
    }

    @Test
    fun `a bot account reports its groups and rights`() {
        val bot =
            entries("users", "users").map { decoder.decodeUser(it) }.first { it.name == "SemperBlottoBot" }

        bot.inGroup("bot") shouldBe true
        bot.hasRight("apihighlimits") shouldBe true
        (bot.editCount > 0) shouldBe true
        bot.registration.shouldNotBeNull()

        bot.isBlocked shouldBe false
    }

    @Test
    fun `an address is reported as anonymous and a free name as missing`() {
        val users = entries("users", "users").map { decoder.decodeUser(it) }

        users.first { it.name == "203.0.113.5" }.isAnonymous shouldBe true
        users.first { it.name == "Nosuchuseratallhere" }.isMissing shouldBe true
        users.first { it.name == "Nosuchuseratallhere" }.isAnonymous shouldBe false
    }

    @Test
    fun `a temporary account reports itself through its group`() {
        val row =
            Json.parseToJsonElement(
                    """{"userid":55489830,"name":"~2026-47315-11","editcount":2,
               "groups":["*","temp"]}"""
                )
                .jsonObject

        val user = decoder.decodeUser(row)

        user.isTemporary shouldBe true
        user.isAnonymous shouldBe false
        user.isRegistered shouldBe false
    }

    @Test
    fun `an ordinary account is not temporary`() {
        val bot =
            entries("users", "users").map { decoder.decodeUser(it) }.first { it.name == "SemperBlottoBot" }

        bot.isTemporary shouldBe false
        bot.isRegistered shouldBe true
    }

    @Test
    fun `the logged-out session is described as anonymous with no id`() {
        val info = queries.getValue("userinfo_anon").jsonObject.getValue("userinfo").jsonObject
        val user = decoder.decodeCurrentUser(info)

        user.isAnonymous shouldBe true
        user.id.shouldBeNull()
        user.hasRight("edit") shouldBe true
        user.hasRight("delete") shouldBe false
    }

    @Test
    fun `infinity is not a date`() {
        Expiry.parse("infinity") shouldBe Expiry.Never
        Expiry.parse("2026-09-01T23:59:00Z").shouldBeInstanceOf<Expiry.At>()
    }

    private val epoch = MwTimestamp.parse("1970-01-01T00:00:00Z")

    private val volcano = PageRef(WikiId("enwiktionary"), Title.Local(Namespace.MAIN, "volcano"))

    private fun entry(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun details(json: String) = decoder.decodeLogEvent(entry(json)).details

    @Test
    fun `a log entry carrying no fields decodes with every default`() {
        decoder.decodeLogEvent(entry("{}")) shouldBe
            LogEvent(
                id = 0,
                type = "",
                action = "",
                page = null,
                user = null,
                timestamp = epoch,
                comment = null,
                tags = emptyList(),
                details = LogDetails.None,
            )
    }

    @Test
    fun `a recent change carrying no fields decodes with every default`() {
        decoder.decodeRecentChange(entry("{}")) shouldBe
            RecentChange(id = 0, type = "", page = null, user = null, timestamp = epoch, comment = null)
    }

    @Test
    fun `a log action in recent changes carrying none of its log fields decodes with every default`() {
        decoder.decodeRecentChange(entry("""{"type":"log","ns":0,"title":"volcano"}""")).logEvent shouldBe
            LogEvent(
                id = 0,
                type = "",
                action = "",
                page = volcano,
                user = null,
                timestamp = epoch,
                comment = null,
            )
    }

    @Test
    fun `a contribution carrying only its page decodes with every default`() {
        decoder.decodeContribution(entry("""{"ns":0,"title":"volcano"}""")) shouldBe
            Contribution(page = volcano, revision = Revision(id = RevisionId(0), timestamp = epoch))
    }

    @Test
    fun `a contribution with no page it can name is left out`() {
        decoder.decodeContribution(entry("""{"revid":7,"user":"SemperBlottoBot"}""")).shouldBeNull()
    }

    @Test
    fun `a contribution that created its page has no parent revision`() {
        fun parentOf(json: String) = decoder.decodeContribution(entry(json))?.revision?.parentId

        parentOf("""{"ns":0,"title":"volcano","revid":7,"parentid":0,"new":true}""").shouldBeNull()
        parentOf("""{"ns":0,"title":"volcano","revid":7,"parentid":6}""") shouldBe RevisionId(6)
    }

    @Test
    fun `a user row carrying no fields decodes with every default`() {
        decoder.decodeUser(entry("{}")) shouldBe UserInfo(name = "")
        decoder.decodeUser(entry("""{"name":"Example","userid":0}""")).id.shouldBeNull()
    }

    @Test
    fun `the session's own row carrying no fields decodes with every default`() {
        decoder.decodeCurrentUser(entry("{}")) shouldBe UserInfo(name = "")
        decoder.decodeCurrentUser(entry("""{"id":0,"name":"203.0.113.5","anon":true}""")) shouldBe
            UserInfo(name = "203.0.113.5", isAnonymous = true)
    }

    @Test
    fun `a block is read from a user row that names either the block or who set it`() {
        fun blockOf(json: String) = decoder.decodeUser(entry(json)).block

        blockOf("""{"name":"A","blockedby":"Admin","blockreason":"spam","blockexpiry":"infinite"}""") shouldBe
            BlockInfo(by = "Admin", reason = "spam")
        blockOf("""{"name":"B","blockid":12,"blockpartial":true}""") shouldBe
            BlockInfo(id = 12, isPartial = true)
        blockOf("""{"name":"C"}""").shouldBeNull()
    }

    @Test
    fun `a listed block carrying no fields decodes with every default`() {
        decoder.decodeBlockRecord(entry("{}")) shouldBe BlockRecord(info = BlockInfo(), target = null)
    }

    @Test
    fun `an upload entry keeps the hash of the file when the wiki gave one`() {
        details("""{"type":"upload","params":{"img_sha1":"0a1b2c"}}""") shouldBe LogDetails.Upload("0a1b2c")
        details("""{"type":"upload"}""") shouldBe LogDetails.Upload(null)
    }

    @Test
    fun `a rights entry reports the groups gained and the groups lost`() {
        details(
            """{"type":"rights","params":{"oldgroups":["autopatrolled","rollbacker"],
               "newgroups":["rollbacker","sysop"]}}"""
        ) shouldBe LogDetails.Rights(added = listOf("sysop"), removed = listOf("autopatrolled"))
        details("""{"type":"rights"}""") shouldBe LogDetails.Rights()
    }

    @Test
    fun `a patrol entry names the revision and the one before it and whether the wiki did it itself`() {
        details("""{"type":"patrol","params":{"curid":9001,"previd":8999,"auto":true}}""") shouldBe
            LogDetails.Patrol(RevisionId(9001), previous = RevisionId(8999), automatic = true)
        details("""{"type":"patrol","params":{"curid":9001,"previd":0}}""") shouldBe
            LogDetails.Patrol(RevisionId(9001))
        details("""{"type":"patrol"}""") shouldBe LogDetails.Patrol(RevisionId(0))
    }

    @Test
    fun `a protect restriction missing its level or expiry is unlevelled and never expires`() {
        details(
            """{"type":"protect","params":{"details":[{"type":"edit"},{"level":"sysop"},
               {"type":"move","level":"sysop","expiry":"2026-09-01T00:00:00Z","cascade":true}]}}"""
        ) shouldBe
            LogDetails.Protect(
                protections =
                    listOf(
                        Protection("edit", level = "", expiry = Expiry.Never),
                        Protection(
                            "move",
                            level = Protection.SYSOP,
                            expiry = Expiry.At(MwTimestamp.parse("2026-09-01T00:00:00Z")),
                            cascading = true,
                        ),
                    ),
                description = null,
            )
    }

    @Test
    fun `a move target named without a namespace is in the main namespace`() {
        details("""{"type":"move","params":{"target_title":"Volcanoes","suppressredirect":true}}""") shouldBe
            LogDetails.Move(Title.Local(Namespace.MAIN, "Volcanoes"), suppressedRedirect = true)
    }

    @Test
    fun `a move whose target cannot be read keeps the fields the wiki sent and invents none`() {
        details("""{"type":"move","params":{"target_ns":1,"target_title":"Talk:"}}""") shouldBe
            LogDetails.Unknown(mapOf("target_ns" to "1", "target_title" to "Talk:"))
        details("""{"type":"move","action":"move","actionhidden":true}""") shouldBe LogDetails.None
    }
}
