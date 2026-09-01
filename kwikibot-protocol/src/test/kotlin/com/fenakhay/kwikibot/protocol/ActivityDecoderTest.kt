package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.model.Expiry
import com.fenakhay.kwikibot.model.LogDetails
import com.fenakhay.kwikibot.model.LogEvent
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.RecentChange
import com.fenakhay.kwikibot.model.WikiId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class ActivityDecoderTest {

    private val decoder =
        ActivityDecoder(PageDecoder(WikiId("enwiktionary"), NamespaceMap.CANONICAL))

    private val queries by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/activity.json")) {
            "activity.json missing from test resources"
        }
        Json.parseToJsonElement(stream.reader().readText())
            .jsonObject.getValue("queries").jsonObject
    }

    private fun entries(query: String, list: String): List<JsonObject> =
        queries.getValue(query).jsonObject.getValue(list).jsonArray.map { it.jsonObject }

    private fun logEvents(query: String): List<LogEvent> =
        entries(query, "logevents").map { decoder.decodeLogEvent(it) }

    private fun recentChanges(query: String): List<RecentChange> =
        entries(query, "recentchanges").map { decoder.decodeRecentChange(it) }

    @Test
    fun `every recorded log entry decodes with an id, a type and a time`() {
        val all = listOf(
            "logevents_move",
            "logevents_block",
            "logevents_delete",
            "logevents_upload",
            "logevents_protect",
            "logevents_all",
        ).flatMap { logEvents(it) }

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
    fun `a block entry keeps its duration, expiry and flags apart`() {
        val block = logEvents("logevents_block")
            .first { (it.details as? LogDetails.Block)?.duration != null }
        val details = block.details.shouldBeInstanceOf<LogDetails.Block>()

        details.duration?.isNotEmpty() shouldBe true
        (details.expiry != null || details.isInfinite) shouldBe true
    }

    @Test
    fun `a protect entry decodes the restrictions and not just their description`() {
        val protect = logEvents("logevents_protect")
            .first { (it.details as? LogDetails.Protect)?.protections?.isNotEmpty() == true }
        val details = protect.details.shouldBeInstanceOf<LogDetails.Protect>()

        details.protections.all { it.action.isNotEmpty() && it.level.isNotEmpty() } shouldBe true
        details.description?.isNotEmpty() shouldBe true
    }

    @Test
    fun `a log type this library does not model keeps its fields`() {
        val unknown = logEvents("logevents_all")
            .map { it.details }
            .filterIsInstance<LogDetails.Unknown>()

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
        val entry = entries("recentchanges", "recentchanges")
            .first { it["type"]?.jsonPrimitive?.content == "edit" }
        val expected = entry.getValue("newlen").jsonPrimitive.int -
            entry.getValue("oldlen").jsonPrimitive.int

        recentChanges("recentchanges").first { it.type == "edit" }.sizeChange shouldBe expected
    }

    @Test
    fun `a contribution carries its page and its revision`() {
        val contributions = entries("usercontribs", "usercontribs")
            .mapNotNull { decoder.decodeContribution(it) }

        contributions.isNotEmpty() shouldBe true
        contributions.all { it.revision.id.value > 0 } shouldBe true
        contributions.all { it.page.title.text.isNotEmpty() } shouldBe true
        contributions.all { it.revision.user == "SemperBlottoBot" } shouldBe true
    }

    @Test
    fun `a bot account reports its groups and rights`() {
        val bot = entries("users", "users")
            .map { decoder.decodeUser(it) }
            .first { it.name == "SemperBlottoBot" }

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
        val row = Json.parseToJsonElement(
            """{"userid":55489830,"name":"~2026-47315-11","editcount":2,
               "groups":["*","temp"]}""",
        ).jsonObject

        val user = decoder.decodeUser(row)

        user.isTemporary shouldBe true
        user.isAnonymous shouldBe false
        user.isRegistered shouldBe false
    }

    @Test
    fun `an ordinary account is not temporary`() {
        val bot = entries("users", "users")
            .map { decoder.decodeUser(it) }
            .first { it.name == "SemperBlottoBot" }

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
}
