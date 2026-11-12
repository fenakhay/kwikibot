import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Date
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class PageServiceTest {

    private val mock = MockWiki(mapOf("Volcano" to "lava", "Ash" to "grey"))

    private fun runWiki(block: suspend TestScope.(PageService) -> Unit) = runTest {
        val wiki = mock.open()
        block(wiki.pagesService)
        wiki.close()
    }

    private fun sent(action: String): Map<String, String> = mock.requests.last { it["action"] == action }

    private fun sentProp(prop: String): Map<String, String> = mock.requests.last { it["prop"] == prop }

    private fun pages(vararg entries: String): String =
        """{"query":{"pages":[${entries.joinToString(",")}]}}"""

    @Test
    fun `content and contents read the pages that exist`() = runWiki { pages ->
        pages.content("Volcano").await()?.text shouldBe "lava"
        pages.content("Nothing").await() shouldBe null
        pages.contents(arrayOf("Ash", "Nothing", "Volcano")).await().map { it.title } shouldBe
            listOf("Ash", "Volcano")
        pages.freshContents(arrayOf("Volcano")).await().single().text shouldBe "lava"
    }

    @Test
    fun `exists says whether the page is there`() = runWiki { pages ->
        mock.canned["info"] =
            pages("""{"pageid":1,"ns":0,"title":"Volcano","lastrevid":5,"contentmodel":"wikitext"}""")
        pages.exists("Volcano").await() shouldBe true
        mock.canned["info"] = pages("""{"ns":0,"title":"Nothing","missing":true}""")
        pages.exists("Nothing").await() shouldBe false
    }

    @Test
    fun `edit sends everything an edit can say`() = runWiki { pages ->
        mock.canned["edit"] = """{"edit":{"result":"Success","newrevid":9002,"oldrevid":9001}}"""

        pages
            .edit(
                "Volcano",
                optionsOf(
                    "appendText" to "more",
                    "summary" to "why",
                    "minor" to true,
                    "bot" to false,
                    "baseRevision" to 9001.0,
                    "noCreate" to true,
                    "tags" to arrayOf("a", "b"),
                    "section" to "new",
                    "sectionTitle" to "Heading",
                    "watchlist" to "no-change",
                    "watchlistExpiry" to "1 month",
                ),
            )
            .await()
            .status shouldBe "saved"

        val sent = sent("edit")
        listOf(
            sent["appendtext"],
            sent["summary"],
            sent["minor"],
            sent["baserevid"],
            sent["nocreate"],
            sent["tags"],
            sent["section"],
            sent["sectiontitle"],
            sent["watchlist"],
            sent["watchlistexpiry"],
        ) shouldBe listOf("more", "why", "1", "9001", "1", "a|b", "new", "Heading", "nochange", "1 month")
        sent["bot"] shouldBe null
    }

    @Test
    fun `edit replaces the text and reports the revisions`() = runWiki { pages ->
        val result = pages.edit("Volcano", optionsOf("text" to "new", "createOnly" to true)).await()

        result.status shouldBe "saved"
        result.revision shouldBe 9002.0
        result.previousRevision shouldBe 9001.0
        mock.saved["Volcano"] shouldBe "new"
        sent("edit")["createonly"] shouldBe "1"
        sent("edit")["bot"] shouldBe "1"
        sent("edit")["notminor"] shouldBe "1"
    }

    @Test
    fun `edit rejects a contradiction and an unknown watchlist before sending`() = runWiki { pages ->
        shouldThrow<Throwable> { pages.edit("Volcano", optionsOf()).await() }.message shouldContain "text"
        shouldThrow<Throwable> {
                pages.edit("Volcano", optionsOf("text" to "x", "watchlist" to "maybe")).await()
            }
            .message shouldContain "'no-change'"
        mock.requests.none { it["action"] == "edit" } shouldBe true
    }

    @Test
    fun `a refusal says what the wiki said about it`() = runWiki { pages ->
        fun refuse(code: String) {
            mock.canned["edit"] = """{"error":{"code":"$code","info":"no"}}"""
        }

        refuse("abusefilter-disallowed")
        val filtered = pages.edit("Volcano", optionsOf("text" to "x")).await()
        filtered.status shouldBe "filtered"
        filtered.isRetryable shouldBe false

        refuse("editconflict")
        val conflict = pages.edit("Volcano", optionsOf("text" to "x")).await()
        conflict.status shouldBe "conflict"
        conflict.isRetryable shouldBe true
        conflict.currentRevision shouldBe null

        refuse("protectedpage")
        val protected = pages.edit("Volcano", optionsOf("text" to "x")).await()
        protected.status shouldBe "protected"
        protected.isCascadingProtection shouldBe false
        protected.protectionLevel shouldBe null

        refuse("pagedeleted")
        pages.edit("Volcano", optionsOf("text" to "x")).await().wasDeleted shouldBe true

        mock.canned["edit"] = """{"edit":{"result":"Failure"}}"""
        val rejected = pages.edit("Volcano", optionsOf("text" to "x")).await()
        rejected.status shouldBe "rejected"
        rejected.code shouldBe "failure"
        rejected.filter shouldBe null
    }

    @Test
    fun `a refusal carries what its kind knows`() = runTest {
        val wiki = mock.open()
        val ref = wiki.wiki.ref("Volcano")

        editResult(EditOutcome.Conflict(ref, "d", RevisionId(5))).currentRevision shouldBe 5.0
        val protected = editResult(EditOutcome.Protected(ref, "d", "sysop", true))
        protected.protectionLevel shouldBe "sysop"
        protected.isCascadingProtection shouldBe true
        editResult(EditOutcome.Filtered(ref, "d", "spam")).filter shouldBe "spam"
        val limited = editResult(EditOutcome.RateLimited(ref, "d", 2.seconds))
        limited.status shouldBe "rate-limited"
        limited.retryAfter shouldBe 2000.0
        limited.isRetryable shouldBe true
        editResult(EditOutcome.CaptchaRequired(ref, "d", "c1")).captchaId shouldBe "c1"
        editResult(EditOutcome.PageStateChanged(ref, "d", wasDeleted = false)).wasDeleted shouldBe false
        wiki.close()
    }

    @Test
    fun `move sends its options and answers with the new title`() = runWiki { pages ->
        mock.canned["move"] = """{"move":{"from":"Volcano","to":"Vulcan"}}"""

        pages
            .move(
                "Volcano",
                "Vulcan",
                optionsOf(
                    "reason" to "why",
                    "leaveRedirect" to false,
                    "moveTalk" to false,
                    "moveSubpages" to true,
                    "watchlist" to "watch",
                ),
            )
            .await() shouldBe "Vulcan"

        val sent = sent("move")
        listOf(
            sent["from"],
            sent["to"],
            sent["reason"],
            sent["noredirect"],
            sent["movesubpages"],
            sent["watchlist"],
        ) shouldBe listOf("Volcano", "Vulcan", "why", "1", "1", "watch")
        sent["movetalk"] shouldBe null
    }

    @Test
    fun `delete and undelete send their options`() = runWiki { pages ->
        mock.canned["delete"] = """{"delete":{"title":"Volcano"}}"""
        mock.canned["undelete"] = """{"undelete":{"title":"Volcano"}}"""

        pages
            .asDynamic()
            .delete(
                "Volcano",
                optionsOf<DeleteOptions>("reason" to "why", "deleteTalk" to true, "watchlist" to "unwatch"),
            )
            .unsafeCast<kotlin.js.Promise<Unit>>()
            .await()
        pages
            .undelete(
                "Volcano",
                optionsOf("reason" to "back", "undeleteTalk" to true, "watchlist" to "watch"),
            )
            .await()

        val deleted = sent("delete")
        listOf(deleted["title"], deleted["reason"], deleted["deletetalk"], deleted["watchlist"]) shouldBe
            listOf("Volcano", "why", "1", "unwatch")
        val restored = sent("undelete")
        listOf(restored["reason"], restored["undeletetalk"], restored["watchlist"]) shouldBe
            listOf("back", "1", "watch")
    }

    @Test
    fun `protections reads each page's restrictions`() = runWiki { pages ->
        mock.canned["info"] =
            pages(
                """{"pageid":1,"ns":0,"title":"Volcano","protection":[
                  {"type":"edit","level":"sysop","expiry":"infinity"},
                  {"type":"move","level":"autoconfirmed","expiry":"2030-01-01T00:00:00Z","cascade":true}]}"""
            )

        val found = pages.protections(arrayOf("Volcano")).await().single()

        found.title shouldBe "Volcano"
        found.protections.map { it.action } shouldBe listOf("edit", "move")
        found.protections[0].expiry shouldBe null
        found.protections[1].expiry?.getTime() shouldBe Date("2030-01-01T00:00:00Z").getTime()
        found.protections[1].cascading shouldBe true
        sentProp("info")["inprop"] shouldBe "protection"
    }

    @Test
    fun `testActions says what is allowed and why not`() = runWiki { pages ->
        mock.canned["info"] =
            pages(
                """{"pageid":1,"ns":0,"title":"Volcano",
                   "actions":{"edit":[],"move":[{"code":"protectedpage"}]}}"""
            )

        val found = pages.testActions(arrayOf("Volcano"), arrayOf("edit", "move")).await().single()

        found.title shouldBe "Volcano"
        found.allows("edit") shouldBe true
        found.allows("move") shouldBe false
        found.reasons("move").toList() shouldBe listOf("protectedpage")
        (found.refusals.asDynamic().move[0] as String) shouldBe "protectedpage"
        sentProp("info")["intestactions"] shouldBe "edit|move"

        pages.testActions(arrayOf("Volcano")).await()
        sentProp("info")["intestactions"] shouldBe "edit"
    }

    @Test
    fun `contributors counts the anonymous apart`() = runWiki { pages ->
        mock.canned["contributors"] =
            pages(
                """{"pageid":1,"ns":0,"title":"Volcano","anoncontributors":3,
                   "contributors":[{"userid":7,"name":"Alice"}]}"""
            )

        val found = pages.contributors(arrayOf("Volcano")).await().single()

        found.title shouldBe "Volcano"
        found.anonymous shouldBe 3
        found.users.single().name shouldBe "Alice"
        found.users.single().id shouldBe 7.0
    }

    @Test
    fun `categoryInfo takes names without their prefix`() = runWiki { pages ->
        mock.canned["categoryinfo"] =
            pages(
                """{"pageid":1,"ns":14,"title":"Category:Fire","categoryinfo":
                   {"size":6,"pages":3,"files":2,"subcats":1,"hidden":true}}"""
            )

        val found = pages.categoryInfo(arrayOf("Fire")).await().single()

        listOf(found.title, found.size, found.pages, found.files, found.subcategories, found.hidden) shouldBe
            listOf("Category:Fire", 6, 3, 2, 1, true)
        sentProp("categoryinfo")["titles"] shouldBe "Category:Fire"
    }

    @Test
    fun `the batched relations read what points at each page`() = runWiki { pages ->
        fun answer(module: String, title: String, ns: Int) {
            mock.canned[module] =
                pages("""{"pageid":1,"ns":$ns,"title":"$title","$module":[{"ns":0,"title":"Ash"}]}""")
        }

        answer("linkshere", "Volcano", 0)
        pages.backlinksOf(arrayOf("Volcano")).await().single().pages.toList() shouldBe listOf("Ash")
        answer("transcludedin", "Template:Fire", 10)
        val transcluded = pages.transclusionsOf(arrayOf("Template:Fire")).await().single()
        transcluded.title shouldBe "Template:Fire"
        transcluded.pages.toList() shouldBe listOf("Ash")
        answer("fileusage", "File:Fire.jpg", 6)
        pages.fileUsageOf(arrayOf("Fire.jpg")).await().single().title shouldBe "File:Fire.jpg"
        sentProp("fileusage")["titles"] shouldBe "File:Fire.jpg"
        answer("redirects", "Volcano", 0)
        pages.redirectsTo(arrayOf("Volcano"), arrayOf(0, 2)).await().single().pages.toList() shouldBe
            listOf("Ash")
        sentProp("redirects")["rdnamespace"] shouldBe "0|2"
    }

    @Test
    fun `protect sends each restriction and its expiry`() = runWiki { pages ->
        mock.canned["protect"] = """{"protect":{"title":"Volcano"}}"""

        pages
            .protect(
                "Volcano",
                arrayOf(
                    optionsOf("action" to "edit", "level" to "sysop"),
                    optionsOf(
                        "action" to "move",
                        "level" to "autoconfirmed",
                        "expiry" to Date("2030-01-01T00:00:00Z"),
                        "cascading" to true,
                    ),
                ),
                optionsOf("reason" to "why", "cascade" to true, "watchlist" to "no-change"),
            )
            .await()

        val sent = sent("protect")
        sent["protections"] shouldBe "edit=sysop|move=autoconfirmed"
        sent["expiry"]?.startsWith("infinity|2030-01-01") shouldBe true
        listOf(sent["reason"], sent["cascade"], sent["watchlist"]) shouldBe listOf("why", "1", "nochange")
    }

    @Test
    fun `rollback and undo report what became of them`() = runWiki { pages ->
        mock.canned["rollback"] = """{"rollback":{"revid":10,"old_revid":9}}"""

        val rolled =
            pages
                .rollback(
                    "Volcano",
                    "Alice",
                    optionsOf("summary" to "why", "markBot" to false, "watchlist" to "watch"),
                )
                .await()

        rolled.status shouldBe "saved"
        rolled.revision shouldBe 10.0
        val sentRollback = sent("rollback")
        listOf(sentRollback["user"], sentRollback["summary"], sentRollback["watchlist"]) shouldBe
            listOf("Alice", "why", "watch")
        sentRollback["markbot"] shouldBe null

        mock.canned["edit"] = """{"edit":{"result":"Success","newrevid":11,"oldrevid":10}}"""
        pages
            .undo("Volcano", 10.0, optionsOf("summary" to "back", "through" to 8.0))
            .await()
            .revision shouldBe 11.0
        listOf(sent("edit")["undo"], sent("edit")["undoafter"], sent("edit")["summary"]) shouldBe
            listOf("10", "8", "back")
    }

    @Test
    fun `purge and watch send their titles`() = runWiki { pages ->
        mock.canned["purge"] = """{"purge":[]}"""
        mock.canned["watch"] = """{"watch":[]}"""

        pages.purge(arrayOf("Volcano", "Ash"), forceLinkUpdate = true).await()
        sent("purge")["titles"] shouldBe "Volcano|Ash"
        sent("purge")["forcelinkupdate"] shouldBe "1"

        pages.watch(arrayOf("Volcano"), watch = false, expiry = "1 month").await()
        listOf(sent("watch")["titles"], sent("watch")["unwatch"], sent("watch")["expiry"]) shouldBe
            listOf("Volcano", "1", "1 month")
    }

    @Test
    fun `the administrative writes send their options`() = runWiki { pages ->
        listOf("mergehistory", "import", "setpagelanguage", "changecontentmodel").forEach {
            mock.canned[it] = """{"$it":{}}"""
        }

        pages
            .mergeHistory(
                "Ash",
                "Volcano",
                optionsOf("upTo" to Date("2026-01-01T00:00:00Z"), "reason" to "why"),
            )
            .await()
        listOf(
            sent("mergehistory")["from"],
            sent("mergehistory")["to"],
            sent("mergehistory")["reason"],
        ) shouldBe listOf("Ash", "Volcano", "why")
        sent("mergehistory")["timestamp"]?.startsWith("2026-01-01") shouldBe true

        pages
            .importPage(
                "commons",
                "Volcano",
                optionsOf(
                    "fullHistory" to false,
                    "includeTemplates" to true,
                    "rootPage" to "Imports",
                    "summary" to "why",
                ),
            )
            .await()
        val imported = sent("import")
        listOf(
            imported["interwikisource"],
            imported["interwikipage"],
            imported["templates"],
            imported["rootpage"],
        ) shouldBe listOf("commons", "Volcano", "1", "Imports")
        imported["fullhistory"] shouldBe null

        pages.setLanguage("Volcano", "fr", "why").await()
        listOf(sent("setpagelanguage")["lang"], sent("setpagelanguage")["reason"]) shouldBe
            listOf("fr", "why")

        pages.changeContentModel("Volcano", "Scribunto", "why").await()
        sent("changecontentmodel")["model"] shouldBe "Scribunto"
    }

    @Test
    fun `expandText and expandTexts expand as a page and with a sandbox`() = runWiki { pages ->
        mock.canned["expandtemplates"] =
            """{"expandtemplates":{"wikitext":"done","categories":[{"category":"Red_rocks"}]}}"""

        pages.expandText("{{x}}", "Volcano").await() shouldBe "done"
        sent("expandtemplates")["title"] shouldBe "Volcano"

        val expanded =
            pages
                .expandTexts(
                    arrayOf("{{x}}"),
                    optionsOf(
                        "title" to "Volcano",
                        "categories" to true,
                        "revision" to 5.0,
                        "sandbox" to
                            optionsOf<TemplateSandboxOptions>("prefixes" to arrayOf("User:Me/sandbox")),
                    ),
                )
                .await()
                .single()

        expanded.text shouldBe "done"
        expanded.categories.toList() shouldBe listOf("Red rocks")
        val sent = sent("expandtemplates")
        listOf(sent["revid"], sent["templatesandboxprefix"]) shouldBe listOf("5", "User:Me/sandbox")
    }
}
