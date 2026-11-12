import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.js.Date
import kotlin.test.Test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class LogServiceTest {

    private val mock = MockWiki()

    private val start = Date("2026-08-01T00:00:00Z")
    private val end = Date("2026-09-01T00:00:00Z")

    private fun runWiki(block: suspend TestScope.(LogService) -> Unit) = runTest {
        val wiki = mock.open()
        block(wiki.logs)
        wiki.close()
    }

    private fun sent(module: String): Map<String, String> = mock.requests.last { it["list"] == module }

    private fun event(type: String, action: String, params: String): String =
        """{"logid":3,"type":"$type","action":"$action","ns":0,"title":"Volcano","pageid":4,"user":"Alice",
            "timestamp":"2026-08-31T21:43:26Z","comment":"why","tags":["bot"],"params":$params}"""

    private fun answer(vararg events: String) {
        mock.canned["logevents"] = """{"query":{"logevents":[${events.joinToString(",")}]}}"""
    }

    @Test
    fun `events sends every option and reads the shared fields`() = runWiki { logs ->
        answer(event("delete", "delete", "{}"))

        val found =
            logs
                .events(
                    optionsOf(
                        "type" to "delete",
                        "user" to "Alice",
                        "page" to "Volcano",
                        "namespaces" to arrayOf(0, 2),
                        "order" to "oldest-first",
                        "start" to start,
                        "end" to end,
                        "limit" to 5,
                    )
                )
                .take()
                .single()

        listOf(
            found.id,
            found.type,
            found.action,
            found.page,
            found.pageId,
            found.user,
            found.comment,
        ) shouldBe listOf(3.0, "delete", "delete", "Volcano", 4.0, "Alice", "why")
        found.timestamp.getTime() shouldBe Date("2026-08-31T21:43:26Z").getTime()
        found.tags.toList() shouldBe listOf("bot")
        found.isSuppressed shouldBe false
        found.details.kind shouldBe "none"
        val sent = sent("logevents")
        listOf(
            sent["letype"],
            sent["leuser"],
            sent["letitle"],
            sent["lenamespace"],
            sent["ledir"],
            sent["lestart"],
            sent["leend"],
            sent["lelimit"],
        ) shouldBe
            listOf(
                "delete",
                "Alice",
                "Volcano",
                "0|2",
                "newer",
                "2026-08-01T00:00:00Z",
                "2026-09-01T00:00:00Z",
                "5",
            )
    }

    @Test
    fun `events sends an action in place of a type`() = runWiki { logs ->
        answer(event("move", "move_redir", "{}"))

        logs.events(optionsOf("type" to "move", "action" to "move/move_redir")).take()

        listOf(sent("logevents")["leaction"], sent("logevents")["letype"]) shouldBe
            listOf("move/move_redir", null)
    }

    @Test
    fun `a hidden entry says so`() = runWiki { logs ->
        answer("""{"logid":3,"type":"delete","action":"delete","timestamp":"2026-08-31T21:43:26Z"}""")

        val found = logs.events().take().single()

        listOf(found.page, found.pageId, found.user, found.comment) shouldBe listOf(null, null, null, null)
        found.isSuppressed shouldBe true
    }

    @Test
    fun `an order it does not know is refused`() = runWiki { logs ->
        shouldThrow<IllegalArgumentException> { logs.events(optionsOf("order" to "older")) }
        shouldThrow<IllegalArgumentException> { logs.recentChanges(optionsOf("order" to "older")) }
        shouldThrow<IllegalArgumentException> { logs.watchlistChanges(optionsOf("order" to "older")) }
    }

    @Test
    fun `a move reads its target`() = runWiki { logs ->
        answer(
            event(
                "move",
                "move",
                """{"target_ns":10,"target_title":"Template:Ash","suppressredirect":true}""",
            )
        )

        val move = logs.events().take().single().details

        listOf(move.kind, move.target, move.suppressedRedirect) shouldBe listOf("move", "Template:Ash", true)
        listOf(move.flags, move.sha1, move.revision) shouldBe listOf(null, null, null)
    }

    @Test
    fun `a block reads its terms`() = runWiki { logs ->
        answer(
            event(
                "block",
                "block",
                """{"duration":"1 week","expiry":"2026-09-07T00:00:00Z","flags":["nocreate"],
                    "sitewide":false}""",
            )
        )

        val block = logs.events().take().single().details

        listOf(block.kind, block.duration, block.isPartial, block.isInfinite) shouldBe
            listOf("block", "1 week", true, false)
        block.expiry?.getTime() shouldBe Date("2026-09-07T00:00:00Z").getTime()
        block.flags?.toList() shouldBe listOf("nocreate")
    }

    @Test
    fun `a change of protection reads its restrictions`() = runWiki { logs ->
        answer(
            event(
                "protect",
                "protect",
                """{"description":"[edit=sysop]","details":[
                    {"type":"edit","level":"sysop","expiry":"infinity"},
                    {"type":"move","level":"autoconfirmed","expiry":"2026-09-07T00:00:00Z",
                     "cascade":true}]}""",
            )
        )

        val protect = logs.events().take().single().details

        listOf(protect.kind, protect.description) shouldBe listOf("protect", "[edit=sysop]")
        val (edit, moved) = protect.protections?.toList() ?: error("no protections")
        listOf(edit.action, edit.level, edit.expiry, edit.cascading) shouldBe
            listOf("edit", "sysop", null, false)
        edit.isActiveAt(end) shouldBe true
        listOf(moved.action, moved.level, moved.cascading) shouldBe listOf("move", "autoconfirmed", true)
        moved.expiry?.getTime() shouldBe Date("2026-09-07T00:00:00Z").getTime()
        moved.isActiveAt(Date("2026-10-01T00:00:00Z")) shouldBe false
    }

    @Test
    fun `the other kinds of entry read their fields`() = runWiki { logs ->
        answer(
            event("upload", "upload", """{"img_sha1":"abc"}"""),
            event("rights", "rights", """{"oldgroups":["bot"],"newgroups":["sysop"]}"""),
            event("patrol", "patrol", """{"curid":9,"previd":8,"auto":true}"""),
            event("thanks", "thank", """{"who":"Bob"}"""),
        )

        val details = logs.events().take().map { it.details }

        listOf(details[0].kind, details[0].sha1) shouldBe listOf("upload", "abc")
        details[1].kind shouldBe "rights"
        details[1].added?.toList() shouldBe listOf("sysop")
        details[1].removed?.toList() shouldBe listOf("bot")
        listOf(details[2].kind, details[2].revision, details[2].previous, details[2].automatic) shouldBe
            listOf("patrol", 9.0, 8.0, true)
        details[3].kind shouldBe "unknown"
        details[3].fields?.asDynamic()?.who.unsafeCast<String>() shouldBe "Bob"
    }

    @Test
    fun `recentChanges sends every option`() = runWiki { logs ->
        mock.canned["recentchanges"] = """{"query":{"recentchanges":[]}}"""

        logs
            .recentChanges(
                optionsOf(
                    "namespaces" to arrayOf(0),
                    "types" to arrayOf("edit", "log"),
                    "user" to "Alice",
                    "show" to arrayOf("minor", "!bot"),
                    "tag" to "t",
                    "topOnly" to true,
                    "order" to "oldest-first",
                    "start" to start,
                    "end" to end,
                    "limit" to 2,
                )
            )
            .take() shouldBe emptyList()

        val sent = sent("recentchanges")
        listOf(
            sent["rcnamespace"],
            sent["rctype"],
            sent["rcuser"],
            sent["rcshow"],
            sent["rctag"],
            sent["rctoponly"],
            sent["rcdir"],
            sent["rcstart"],
            sent["rcend"],
            sent["rclimit"],
        ) shouldBe
            listOf(
                "0",
                "edit|log",
                "Alice",
                "minor|!bot",
                "t",
                "1",
                "newer",
                "2026-08-01T00:00:00Z",
                "2026-09-01T00:00:00Z",
                "2",
            )
    }

    @Test
    fun `recentChanges reads edits and log actions`() = runWiki { logs ->
        mock.canned["recentchanges"] =
            """{"query":{"recentchanges":[
               {"rcid":1,"type":"edit","ns":0,"title":"Volcano","pageid":4,"user":"Alice",
                "timestamp":"2026-08-31T21:43:26Z","comment":"ash","revid":9,"old_revid":8,"oldlen":10,"newlen":25,
                "minor":true,"bot":true,"tags":["t"]},
               {"rcid":2,"type":"log","logid":3,"logtype":"move","logaction":"move","ns":0,"title":"Volcano",
                "user":"Bob","timestamp":"2026-08-31T21:43:27Z","comment":"moved","patrolled":true,
                "logparams":{"target_ns":0,"target_title":"Ash"}}]}}"""

        val (edit, log) = logs.recentChanges().take()

        listOf(edit.id, edit.type, edit.page, edit.pageId, edit.user, edit.comment) shouldBe
            listOf(1.0, "edit", "Volcano", 4.0, "Alice", "ash")
        edit.timestamp.getTime() shouldBe Date("2026-08-31T21:43:26Z").getTime()
        listOf(edit.revision, edit.previousRevision, edit.sizeChange) shouldBe listOf(9.0, 8.0, 15)
        listOf(edit.minor, edit.bot, edit.isNew, edit.isPatrolled, edit.isLogEntry) shouldBe
            listOf(true, true, false, false, false)
        listOf(edit.patrolled, edit.logEvent) shouldBe listOf(null, null)
        edit.tags.toList() shouldBe listOf("t")

        listOf(log.isLogEntry, log.isPatrolled, log.patrolled) shouldBe listOf(true, true, true)
        val event = log.logEvent ?: error("no log event")
        listOf(event.type, event.details.kind, event.details.target) shouldBe listOf("move", "move", "Ash")
    }

    @Test
    fun `watchlistChanges sends every option`() = runWiki { logs ->
        mock.canned["watchlist"] =
            """{"query":{"watchlist":[{"rcid":1,"type":"new","ns":0,"title":"Volcano",
               "timestamp":"2026-08-31T21:43:26Z","new":true}]}}"""

        val change =
            logs
                .watchlistChanges(
                    optionsOf(
                        "namespaces" to arrayOf(2),
                        "types" to arrayOf("new"),
                        "show" to arrayOf("!minor"),
                        "allRevisions" to true,
                        "order" to "oldest-first",
                        "start" to start,
                        "end" to end,
                        "limit" to 1,
                    )
                )
                .take()
                .single()

        listOf(change.type, change.isNew) shouldBe listOf("new", true)
        val sent = sent("watchlist")
        listOf(
            sent["wlnamespace"],
            sent["wltype"],
            sent["wlshow"],
            sent["wlallrev"],
            sent["wldir"],
            sent["wlstart"],
            sent["wlend"],
            sent["wllimit"],
        ) shouldBe
            listOf(
                "2",
                "new",
                "!minor",
                "1",
                "newer",
                "2026-08-01T00:00:00Z",
                "2026-09-01T00:00:00Z",
                "1",
            )
    }

    @Test
    fun `the streams default to newest first with nothing constrained`() = runWiki { logs ->
        mock.canned["recentchanges"] = """{"query":{"recentchanges":[]}}"""
        mock.canned["watchlist"] = """{"query":{"watchlist":[]}}"""

        logs.recentChanges().take() shouldBe emptyList()
        logs.watchlistChanges().take() shouldBe emptyList()

        val recent = sent("recentchanges")
        listOf(recent["rcdir"], recent["rcshow"], recent["rctoponly"], recent["rcnamespace"]) shouldBe
            listOf("older", null, null, null)
        listOf(sent("watchlist")["wldir"], sent("watchlist")["wlallrev"]) shouldBe listOf("older", null)
    }
}
