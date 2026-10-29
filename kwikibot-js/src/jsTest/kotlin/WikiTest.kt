import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFails
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class WikiTest {

    private val login = BotPassword("TestBot", "task", "secret")

    @Test
    fun `a wiki opened anonymously says what it is and who it sees`() = runTest {
        val wiki = MockWiki().open()

        wiki.id shouldBe "testwiki"
        wiki.siteName shouldBe "Test Wiki"
        wiki.mediaWikiVersion shouldBe "1.47.0"
        wiki.user.name shouldBe "192.0.2.1"
        wiki.user.isAnonymous shouldBe true
        wiki.close()
    }

    @Test
    fun `a bot password logs in and whoami asks the wiki afresh`() = runTest {
        val mock = MockWiki()
        val wiki = mock.open(login)

        wiki.user.name shouldBe "TestBot"
        wiki.user.isAnonymous shouldBe false
        val me = wiki.whoami().await()
        me.groups.toList() shouldBe listOf("*", "bot", "user")
        me.rights.toList() shouldBe listOf("bot", "edit")
        login.account shouldBe "TestBot"
        login.botName shouldBe "task"
        mock.requests.single { it["action"] == "login" }["lgname"] shouldBe "TestBot@task"
        wiki.close()
    }

    @Test
    fun `a password the wiki refuses fails the opening`() = runTest {
        assertFails { MockWiki().open(BotPassword("TestBot", "task", "wrong")) }
    }

    @Test
    fun `a page is read with its revision and a missing one is null`() = runTest {
        val wiki = MockWiki(mapOf("Volcano" to "==English==\n{{en-noun}}")).open()

        val page = wiki.page("Volcano").await() ?: error("no page")

        page.title shouldBe "Volcano"
        page.text shouldBe "==English==\n{{en-noun}}"
        page.revision shouldBe 9001.0
        page.timestamp?.toISOString() shouldBe "2026-08-31T21:43:26.000Z"
        page.isRedirect shouldBe false
        page.wikitext().templates().map { it.name } shouldBe listOf("en-noun")
        wiki.page("Nope").await() shouldBe null
        wiki.close()
    }

    @Test
    fun `pages are read together and the missing ones left out`() = runTest {
        val mock = MockWiki(mapOf("Volcano" to "ash", "Etna" to "lava"))
        val wiki = mock.open()

        val pages = wiki.pages(arrayOf("Etna", "Nope", "Volcano")).await()

        pages.map { it.title to it.text } shouldBe listOf("Etna" to "lava", "Volcano" to "ash")
        mock.requests.count { "revisions" in it["prop"].orEmpty() } shouldBe 1
        wiki.close()
    }

    @Test
    fun `an edit saves and reports the revision it made`() = runTest {
        val mock = MockWiki(mapOf("Volcano" to "ash"))
        val wiki = mock.open(login)
        val page = wiki.page("Volcano").await() ?: error("no page")

        val result = wiki.edit("Volcano", "tephra", "naming it", page.revision, minor = true).await()

        result.status shouldBe "saved"
        result.revision shouldBe 9002.0
        result.previousRevision shouldBe 9001.0
        result.detail shouldBe null
        mock.saved shouldBe mapOf("Volcano" to "tephra")
        val sent = mock.requests.single { it["action"] == "edit" }
        listOf(sent["summary"], sent["baserevid"], sent["minor"], sent["bot"]) shouldBe
            listOf("naming it", "9001", "1", "1")
        wiki.close()
    }

    @Test
    fun `an edit computed from a revision since replaced is refused as a conflict`() = runTest {
        val mock = MockWiki(mapOf("Volcano" to "ash"))
        val wiki = mock.open(login)

        val result = wiki.edit("Volcano", "tephra", "naming it", baseRevision = 8000.0).await()

        result.status shouldBe "conflict"
        result.revision shouldBe null
        mock.saved shouldBe emptyMap()
        wiki.close()
    }

    @Test
    fun `a listing is walked to its end or as far as the walk goes`() = runTest {
        val mock = MockWiki(members = listOf("Etna", "Fuji", "Vesuvius"))
        val wiki = mock.open()

        wiki.categoryMembers("Volcanoes").take() shouldBe listOf("Etna", "Fuji", "Vesuvius")
        wiki.categoryMembers("Category:Volcanoes").take(1) shouldBe listOf("Etna")
        mock.requests.filter { it["list"] == "categorymembers" }.map { it["cmtitle"] }.distinct() shouldBe
            listOf("Category:Volcanoes")
        wiki.close()
    }

    @Test
    fun `search and transclusions list titles`() = runTest {
        val mock = MockWiki()
        val wiki = mock.open()

        wiki.search("ash").take() shouldBe listOf("Volcano", "Volcanic ash")
        wiki.embeddedIn("Infobox volcano").take() shouldBe listOf("Volcano")
        mock.requests.single { it["list"] == "search" }["srsearch"] shouldBe "ash"
        mock.requests.single { it["list"] == "embeddedin" }["eititle"] shouldBe "Template:Infobox volcano"
        wiki.close()
    }

    @Test
    fun `a page's history lists its revisions newest first`() = runTest {
        val wiki = MockWiki().open()

        val revisions = wiki.history("Volcano").take()

        revisions.map { it.id } shouldBe listOf(9001.0, 9000.0)
        val latest = revisions.first()
        listOf(latest.parentId, latest.user, latest.comment, latest.size, latest.minor, latest.bot) shouldBe
            listOf(9000.0, "Alice", "adding ash", 120, true, false)
        latest.tags.toList() shouldBe listOf("mobile edit")
        latest.timestamp.toISOString() shouldBe "2026-08-31T21:43:26.000Z"
        wiki.close()
    }

    @Test
    fun `signature times are read in the wiki's own time zone`() = runTest {
        val wiki = MockWiki().open()

        val found = wiki.signatureTimes("Alice 31 août 2026 à 21:43 (CEST)").await().single()

        found.date.toISOString() shouldBe "2026-08-31T19:43:00.000Z"
        wiki.close()
    }

    @Test
    fun `a title is read with the wiki's namespaces and interwiki prefixes`() = runTest {
        val wiki = MockWiki().open()

        wiki.parseTitle("template:infobox").toString() shouldBe "Template:Infobox"
        val elsewhere = wiki.parseTitle("w:Etna")
        elsewhere.kind shouldBe "interwiki"
        elsewhere.interwiki shouldBe "w"
        elsewhere.toString() shouldBe "w:Etna"
        wiki.close()
    }

    @Test
    fun `a closed wiki answers nothing more`() = runTest {
        val wiki = MockWiki(mapOf("Volcano" to "ash")).open()

        wiki.close()

        assertFails { wiki.page("Volcano").await() }
    }
}
