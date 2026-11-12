import io.kotest.matchers.shouldBe
import kotlin.js.Date
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class UserServiceTest {

    private val mock = MockWiki()

    private val start = Date("2026-08-01T00:00:00Z")
    private val end = Date("2026-09-01T00:00:00Z")

    private fun runWiki(block: suspend TestScope.(UserService) -> Unit) = runTest {
        val wiki = mock.open()
        block(wiki.users)
        wiki.close()
    }

    private fun sentList(module: String): Map<String, String> = mock.requests.last { it["list"] == module }

    private fun sentAction(action: String): Map<String, String> =
        mock.requests.last { it["action"] == action }

    @Test
    fun `info gives a table by the name asked for`() = runWiki { users ->
        mock.canned["users"] =
            """{"query":{"users":[
               {"userid":7,"name":"Alice","groups":["*","sysop"],"rights":["edit"],"editcount":12,
                "registration":"2020-01-01T00:00:00Z","gender":"female","emailable":true,
                "blockid":5,"blockedby":"Bob","blockreason":"spam","blockedtimestamp":"2026-08-01T00:00:00Z",
                "blockexpiry":"2026-09-01T00:00:00Z","blockpartial":true},
               {"name":"Nobody","missing":true},
               {"name":"192.0.2.1","invalid":true},
               {"userid":8,"name":"~2026-1","groups":["temp"]}]}}"""

        val table = users.info(arrayOf("alice", "Nobody", "192.0.2.1", "~2026-1")).await().asDynamic()

        val alice = table["alice"].unsafeCast<UserInfo>()
        listOf(alice.name, alice.id, alice.editCount, alice.gender, alice.emailable) shouldBe
            listOf("Alice", 7.0, 12.0, "female", true)
        alice.groups.toList() shouldBe listOf("*", "sysop")
        alice.rights.toList() shouldBe listOf("edit")
        alice.registration?.getTime() shouldBe Date("2020-01-01T00:00:00Z").getTime()
        listOf(alice.hasRight("edit"), alice.hasRight("block")) shouldBe listOf(true, false)
        listOf(alice.inGroup("sysop"), alice.inGroup("bot")) shouldBe listOf(true, false)
        listOf(alice.isBlocked, alice.isRegistered) shouldBe listOf(true, true)
        val block = alice.block ?: error("no block")
        listOf(block.id, block.by, block.reason, block.isPartial, block.isInfinite) shouldBe
            listOf(5.0, "Bob", "spam", true, false)
        block.since?.getTime() shouldBe Date("2026-08-01T00:00:00Z").getTime()
        block.expiry?.getTime() shouldBe Date("2026-09-01T00:00:00Z").getTime()

        val missing = table["Nobody"].unsafeCast<UserInfo>()
        listOf(missing.isMissing, missing.isRegistered, missing.block, missing.registration) shouldBe
            listOf(true, false, null, null)
        table["192.0.2.1"].unsafeCast<UserInfo>().isAnonymous shouldBe true
        table["~2026-1"].unsafeCast<UserInfo>().isTemporary shouldBe true
        sentList("users")["ususers"] shouldBe "alice|Nobody|192.0.2.1|~2026-1"
    }

    @Test
    fun `current reads the session's account`() = runTest {
        val wiki = mock.open(BotPassword("TestBot", "task", "secret"))

        val current = wiki.users.current().await()

        listOf(current.name, current.id, current.isAnonymous) shouldBe listOf("TestBot", 7.0, false)
        current.rights.toList() shouldBe listOf("bot", "edit")
        wiki.close()
    }

    @Test
    fun `contributions sends every option`() = runWiki { users ->
        mock.canned["usercontribs"] =
            """{"query":{"usercontribs":[{"userid":7,"user":"Alice","pageid":4,"revid":9,"parentid":8,"ns":0,
               "title":"Volcano","timestamp":"2026-08-31T21:43:26Z","new":true,"top":true,"size":30,"sizediff":30,
               "comment":"c","tags":[]}]}}"""

        val edit =
            users
                .contributions(
                    "Alice",
                    optionsOf(
                        "namespaces" to arrayOf(0),
                        "start" to start,
                        "end" to end,
                        "show" to arrayOf("new", "!minor"),
                        "tag" to "t",
                        "limit" to 1,
                    ),
                )
                .take()
                .single()

        listOf(edit.page, edit.pageId, edit.isNew, edit.isTop, edit.sizeChange) shouldBe
            listOf("Volcano", 4.0, true, true, 30)
        listOf(edit.revision.id, edit.revision.parentId, edit.revision.user) shouldBe
            listOf(9.0, 8.0, "Alice")
        val sent = sentList("usercontribs")
        listOf(
            sent["ucuser"],
            sent["ucnamespace"],
            sent["ucstart"],
            sent["ucend"],
            sent["ucshow"],
            sent["uctag"],
            sent["uclimit"],
        ) shouldBe
            listOf(
                "Alice",
                "0",
                "2026-08-01T00:00:00Z",
                "2026-09-01T00:00:00Z",
                "new|!minor",
                "t",
                "1",
            )
    }

    @Test
    fun `block sends every option, and lasts forever and stops accounts unless told`() = runWiki { users ->
        mock.canned["block"] = """{"block":{"user":"Alice"}}"""

        users.block("Alice", "spam").await()
        val plain = sentAction("block")
        listOf(plain["user"], plain["reason"], plain["expiry"]) shouldBe listOf("Alice", "spam", "infinity")
        listOf(plain["anononly"], plain["nocreate"], plain["autoblock"]) shouldBe listOf("0", "1", "1")

        users
            .block(
                "192.0.2.1",
                "spam",
                optionsOf(
                    "expiry" to end,
                    "anonymousOnly" to true,
                    "preventAccountCreation" to false,
                    "autoBlock" to false,
                ),
            )
            .await()
        val told = sentAction("block")
        listOf(told["expiry"], told["anononly"], told["nocreate"], told["autoblock"]) shouldBe
            listOf("2026-09-01T00:00:00Z", "1", null, null)
    }

    @Test
    fun `unblock sends its reason`() = runWiki { users ->
        mock.canned["unblock"] = """{"unblock":{"user":"Alice"}}"""

        users.unblock("Alice", "done").await()
        listOf(sentAction("unblock")["user"], sentAction("unblock")["reason"]) shouldBe
            listOf("Alice", "done")

        users.unblock("Alice").await()
        sentAction("unblock")["reason"] shouldBe ""
    }

    @Test
    fun `blocks sends every option and reads records`() = runWiki { users ->
        mock.canned["blocks"] =
            """{"query":{"blocks":[{"id":5,"user":"192.0.2.0/24","by":"Bob","timestamp":"2026-08-01T00:00:00Z",
               "expiry":"infinity","reason":"spam","automatic":true}]}}"""

        val record =
            users
                .blocks(
                    optionsOf(
                        "users" to arrayOf("Alice", "Bob"),
                        "show" to arrayOf("!temp"),
                        "start" to start,
                        "end" to end,
                        "limit" to 1,
                    )
                )
                .take()
                .single()

        listOf(record.target, record.isAutomatic, record.isRange) shouldBe listOf("192.0.2.0/24", true, true)
        listOf(record.info.id, record.info.by, record.info.reason) shouldBe listOf(5.0, "Bob", "spam")
        val sent = sentList("blocks")
        listOf(sent["bkusers"], sent["bkshow"], sent["bkstart"], sent["bkend"], sent["bklimit"]) shouldBe
            listOf("Alice|Bob", "!temp", "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", "1")
    }

    @Test
    fun `allUsers sends every option`() = runWiki { users ->
        mock.canned["allusers"] = """{"query":{"allusers":[{"userid":7,"name":"Alice","editcount":3}]}}"""

        val found =
            users
                .allUsers(
                    optionsOf(
                        "from" to "A",
                        "to" to "B",
                        "prefix" to "Al",
                        "group" to arrayOf("sysop", "bot"),
                        "withEditsOnly" to true,
                        "excludeTemporary" to true,
                        "limit" to 1,
                    )
                )
                .take()
                .single()

        listOf(found.name, found.editCount) shouldBe listOf("Alice", 3.0)
        val sent = sentList("allusers")
        listOf(
            sent["aufrom"],
            sent["auto"],
            sent["auprefix"],
            sent["augroup"],
            sent["auwitheditsonly"],
            sent["auexcludetemp"],
            sent["aulimit"],
        ) shouldBe listOf("A", "B", "Al", "sysop|bot", "1", "1", "1")
    }

    @Test
    fun `changeRights sends every option`() = runWiki { users ->
        mock.canned["userrights"] = """{"userrights":{"user":"Alice"}}"""

        users
            .changeRights(
                "Alice",
                optionsOf(
                    "add" to arrayOf("bot"),
                    "remove" to arrayOf("sysop"),
                    "reason" to "approved",
                    "expiry" to "1 month",
                ),
            )
            .await()

        val sent = sentAction("userrights")
        listOf(sent["user"], sent["add"], sent["remove"], sent["reason"], sent["expiry"]) shouldBe
            listOf("Alice", "bot", "sysop", "approved", "1 month")
    }
}
