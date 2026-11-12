import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Date
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class RevisionServiceTest {

    private val mock = MockWiki()

    private val start = Date("2026-08-01T00:00:00Z")
    private val end = Date("2026-09-01T00:00:00Z")

    private fun runWiki(block: suspend TestScope.(RevisionService) -> Unit) = runTest {
        val wiki = mock.open()
        block(wiki.revisions)
        wiki.close()
    }

    private fun sentProp(module: String): Map<String, String> = mock.requests.last { it["prop"] == module }

    private fun sentList(module: String): Map<String, String> = mock.requests.last { it["list"] == module }

    @Test
    fun `history sends every option and reads revisions`() = runWiki { revisions ->
        val found =
            revisions
                .history(
                    "Volcano",
                    optionsOf(
                        "order" to "oldest-first",
                        "start" to start,
                        "end" to end,
                        "user" to "Alice",
                        "excludeUser" to "Bob",
                        "limit" to 2,
                    ),
                )
                .take()

        val newest = found.first()
        listOf(newest.id, newest.parentId, newest.user, newest.comment, newest.size, newest.minor) shouldBe
            listOf(9001.0, 9000.0, "Alice", "adding ash", 120, true)
        listOf(newest.sha1, newest.isUserHidden, newest.isCommentHidden) shouldBe listOf(null, false, false)
        val sent = sentProp("revisions")
        listOf(
            sent["titles"],
            sent["rvdir"],
            sent["rvstart"],
            sent["rvend"],
            sent["rvuser"],
            sent["rvexcludeuser"],
            sent["rvlimit"],
        ) shouldBe
            listOf(
                "Volcano",
                "newer",
                "2026-08-01T00:00:00Z",
                "2026-09-01T00:00:00Z",
                "Alice",
                "Bob",
                "2",
            )
    }

    @Test
    fun `history refuses an order it does not know, and an invalid date`() = runWiki { revisions ->
        shouldThrow<IllegalArgumentException> { revisions.history("Volcano", optionsOf("order" to "older")) }
            .message
            .orEmpty() shouldContain "'newest-first', 'oldest-first'"
        shouldThrow<IllegalArgumentException> {
            revisions.history("Volcano", optionsOf("start" to Date("nonsense")))
        }
    }

    @Test
    fun `contents at revisions are read in the order asked for`() = runWiki { revisions ->
        mock.canned["revisions"] =
            """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano","revisions":[
               {"revid":2,"timestamp":"2026-08-31T21:43:26Z","slots":{"main":{"content":"two"}}},
               {"revid":1,"timestamp":"2026-08-30T21:43:26Z","slots":{"main":{"content":"#REDIRECT [[Ash]]"}}}
               ]}]}}"""

        val pages = revisions.contentsAt(arrayOf(1.0, 3.0, 2.0, 1.0)).await()

        pages.map { it.revision } shouldBe listOf(1.0, 2.0)
        pages.map { it.title } shouldBe listOf("Volcano", "Volcano")
        pages.first().redirectTarget shouldBe "Ash"
        sentProp("revisions")["revids"] shouldBe "1|3|2"

        val one = revisions.contentAt(2.0).await() ?: error("no revision")
        one.text shouldBe "two"
        revisions.contentAt(3.0).await() shouldBe null
    }

    @Test
    fun `revisions are read by id in the order asked for`() = runWiki { revisions ->
        val found = revisions.byId(arrayOf(9000.0, 9001.0, 9000.0, 1.0)).await()

        found.map { it.id } shouldBe listOf(9000.0, 9001.0)
        sentProp("revisions")["revids"] shouldBe "9000|9001|1"
    }

    @Test
    fun `compare gives the wiki's diff`() = runWiki { revisions ->
        mock.canned["compare"] = """{"compare":{"body":"<tr>changed</tr>"}}"""

        revisions.compare(1.0, 2.0).await() shouldBe "<tr>changed</tr>"

        val sent = mock.requests.last { it["action"] == "compare" }
        listOf(sent["fromrev"], sent["torev"]) shouldBe listOf("1", "2")
    }

    @Test
    fun `allRevisions and allDeletedRevisions send every option`() = runWiki { revisions ->
        val body = { module: String ->
            """{"query":{"$module":[{"pageid":1,"ns":0,"title":"Volcano","revisions":[
               {"revid":5,"timestamp":"2026-08-30T08:00:00Z","sha1":"abc"}]}]}}"""
        }
        mock.canned["allrevisions"] = body("allrevisions")
        mock.canned["alldeletedrevisions"] = body("alldeletedrevisions")
        val options =
            optionsOf<AllRevisionsOptions>(
                "namespaces" to arrayOf(0, 2),
                "user" to "Alice",
                "order" to "oldest-first",
                "start" to start,
                "end" to end,
                "limit" to 3,
            )

        val live = revisions.allRevisions(options).take().single()
        val deleted = revisions.allDeletedRevisions(options).take().single()

        listOf(live.id, live.sha1, live.isUserHidden, live.isCommentHidden) shouldBe
            listOf(5.0, "abc", true, true)
        deleted.id shouldBe 5.0
        val all = sentList("allrevisions")
        listOf(
            all["arvnamespace"],
            all["arvuser"],
            all["arvdir"],
            all["arvstart"],
            all["arvend"],
            all["arvlimit"],
        ) shouldBe listOf("0|2", "Alice", "newer", "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", "3")
        val gone = sentList("alldeletedrevisions")
        listOf(
            gone["adrnamespace"],
            gone["adruser"],
            gone["adrdir"],
            gone["adrstart"],
            gone["adrend"],
            gone["adrlimit"],
        ) shouldBe listOf("0|2", "Alice", "newer", "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", "3")
    }

    @Test
    fun `deletedHistory sends its order and limit`() = runWiki { revisions ->
        mock.canned["deletedrevisions"] =
            """{"query":{"pages":[{"ns":0,"title":"Volcano","deletedrevisions":[
               {"revid":5,"timestamp":"2026-08-30T08:00:00Z","user":"Alice"}]}]}}"""

        revisions.deletedHistory("Volcano", literal("oldest-first"), 2).take().single().user shouldBe "Alice"

        val sent = sentProp("deletedrevisions")
        listOf(sent["titles"], sent["drvdir"], sent["drvlimit"]) shouldBe listOf("Volcano", "newer", "2")
    }

    @Test
    fun `revisionDelete sends every option`() = runWiki { revisions ->
        mock.canned["revisiondelete"] = """{"revisiondelete":{"status":"Success"}}"""

        revisions
            .revisionDelete(
                "Volcano",
                arrayOf(1.0, 2.0),
                optionsOf(
                    "hide" to arrayOf("content", "user"),
                    "show" to arrayOf("comment"),
                    "reason" to "copyright",
                    "suppress" to true,
                ),
            )
            .await()

        val sent = mock.requests.last { it["action"] == "revisiondelete" }
        listOf(
            sent["target"],
            sent["ids"],
            sent["hide"],
            sent["show"],
            sent["reason"],
            sent["suppress"],
        ) shouldBe listOf("Volcano", "1|2", "content|user", "comment", "copyright", "yes")
    }

    @Test
    fun `revisionDelete rejects what it cannot do`() = runWiki { revisions ->
        shouldThrow<IllegalArgumentException> { revisions.revisionDelete("Volcano", arrayOf(1.0)).await() }
        shouldThrow<IllegalArgumentException> {
                revisions
                    .revisionDelete("Volcano", arrayOf(1.0), optionsOf("hide" to arrayOf("summary")))
                    .await()
            }
            .message
            .orEmpty() shouldContain "'content', 'comment', 'user'"
    }
}
