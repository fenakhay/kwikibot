package com.fenakhay.kwikibot.testkit

import com.fenakhay.kwikibot.client.service.ListService
import com.fenakhay.kwikibot.client.service.LogService
import com.fenakhay.kwikibot.client.service.MetaService
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.client.service.RenderService
import com.fenakhay.kwikibot.client.service.RevisionService
import com.fenakhay.kwikibot.client.service.UserService
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.TitleRules
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.lang.reflect.Proxy
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class FakeWikiTest {

    @Test
    fun `a page seeded in lower case is reachable through the wiki's own ref`() = runTest {
        val wiki = FakeWiki("volcano" to "==English==")

        wiki.pages.content(wiki.ref("volcano"))?.text shouldBe "==English=="
        wiki.pages.exists(wiki.ref("volcano")) shouldBe true
        wiki.pages.exists(wiki.ref("Nope")) shouldBe false
        wiki.pages.content(wiki.ref("Nope")).shouldBeNull()
    }

    @Test
    fun `the fake capitalises a title the way a wiki does`() = runTest {
        val pages = FakePageService("volcano" to "==English==")

        pages.text("Volcano") shouldBe "==English=="
        pages.text("volcano") shouldBe "==English=="
        pages.exists(pages.ref("Volcano")) shouldBe true
    }

    @Test
    fun `the services the fake does not implement throw, naming what was called`() {
        val wiki = FakeWiki("volcano" to "x")

        val services: List<Pair<String, () -> Any>> =
            listOf(
                "lists" to { wiki.lists },
                "revisions" to { wiki.revisions },
                "users" to { wiki.users },
                "logs" to { wiki.logs },
                "files" to { wiki.files },
                "extensions" to { wiki.extensions },
                "proofread" to { wiki.proofread },
                "renderer" to { wiki.renderer },
                "meta" to { wiki.meta },
            )

        for ((name, read) in services) {
            assertFailsWith<NotImplementedError> { read() }.message.orEmpty() shouldContain name
        }
    }

    @Test
    fun `the transport refuses, so a test cannot reach the network through the fake`() = runTest {
        val wiki = FakeWiki("volcano" to "x")

        val error =
            assertFailsWith<NotImplementedError> {
                wiki.transport.call(ApiRequest.of("query", "meta" to "siteinfo"))
            }

        error.message.orEmpty() shouldContain "query"
        wiki.transport.endpoint.server shouldBe "test.example.org"
    }

    @Test
    fun `paramInfo is real, since it needs no network of its own`() {
        val wiki = FakeWiki("volcano" to "x")

        wiki.paramInfo.toString().isNotEmpty() shouldBe true
        wiki.tokens.toString().isNotEmpty() shouldBe true
    }

    @Test
    fun `the fake identifies as a logged-in bot, not as an anonymous reader`() {
        val wiki = FakeWiki("volcano" to "x")

        wiki.identity.id shouldBe 1L
        wiki.identity.groups.contains("bot") shouldBe true
        wiki.info.siteName shouldBe "Test Wiki"
        wiki.info.language shouldBe LangCode("en")
    }

    @Test
    fun `an edit changes the page and is recorded with the builder that made it`() = runTest {
        val pages = FakePageService("volcano" to "==English==")

        val outcome = pages.edit(pages.ref("volcano")) { text = "==French==" }

        outcome.shouldBeInstanceOf<EditOutcome.Saved>()
        pages.text("volcano") shouldBe "==French=="
        pages.edits.single().second.text shouldBe "==French=="
    }

    @Test
    fun `an edit that writes back the same text is a no-op, as it is on a wiki`() = runTest {
        val pages = FakePageService("volcano" to "==English==")

        pages.edit(pages.ref("volcano")) { text = "==English==" }.shouldBeInstanceOf<EditOutcome.NoChange>()
        pages.edits.isEmpty() shouldBe true
    }

    @Test
    fun `an injected refusal comes back instead of the edit`() = runTest {
        val pages =
            FakePageService(
                texts = mapOf("volcano" to "x"),
                refuse = { EditOutcome.Protected(it, detail = "the page is protected", level = "sysop") },
            )

        pages
            .edit(pages.ref("volcano")) { text = "y" }
            .shouldBeInstanceOf<EditOutcome.Protected>()
            .level shouldBe "sysop"
        pages.text("volcano") shouldBe "x"
    }

    @Test
    fun `an injected failure is thrown, so a bot's error path can be tested`() = runTest {
        val pages =
            FakePageService(
                texts = mapOf("volcano" to "x"),
                failWith = { WikiError.Auth.NotLoggedIn("editing") },
            )

        assertFailsWith<WikiError.Auth.NotLoggedIn> {
            pages.edit(pages.ref("volcano")) { text = "y" }
        }
    }

    @Test
    fun `a rollback puts back the text the page started with`() = runTest {
        val pages = FakePageService("volcano" to "==English==")
        pages.edit(pages.ref("volcano")) { text = "vandalism" }

        pages.rollback(pages.ref("volcano"), user = "Vandal").shouldBeInstanceOf<EditOutcome.Saved>()

        pages.text("volcano") shouldBe "==English=="
    }

    @Test
    fun `rolling back an unedited page changes nothing`() = runTest {
        val pages = FakePageService("volcano" to "==English==")

        pages.rollback(pages.ref("volcano"), user = "Nobody").shouldBeInstanceOf<EditOutcome.NoChange>()
    }

    @Test
    fun `rolling back a page the fake never held is a no-op, not a crash`() = runTest {
        val pages = FakePageService("volcano" to "x")

        pages.rollback(pages.ref("Unknown"), user = "Nobody").shouldBeInstanceOf<EditOutcome.NoChange>()
    }

    @Test
    fun `undo reverts the same way a rollback does, there being no history to undo`() = runTest {
        val pages = FakePageService("volcano" to "==English==")
        pages.edit(pages.ref("volcano")) { text = "vandalism" }

        pages.undo(pages.ref("volcano"), RevisionId(2))

        pages.text("volcano") shouldBe "==English=="
    }

    @Test
    fun `a move carries the text to the new title and leaves nothing behind`() = runTest {
        val pages = FakePageService("volcano" to "==English==")

        val moved = pages.move(pages.ref("volcano"), pages.ref("Vulcan"))

        moved.title.text shouldBe "Vulcan"
        pages.text("Vulcan") shouldBe "==English=="
        pages.text("volcano").shouldBeNull()
    }

    @Test
    fun `a deleted page is kept, so undeleting puts it back`() = runTest {
        val pages = FakePageService("volcano" to "==English==")

        pages.delete(pages.ref("volcano"), reason = "test")
        pages.text("volcano").shouldBeNull()

        pages.undelete(pages.ref("volcano"), reason = "test")
        pages.text("volcano") shouldBe "==English=="
    }

    @Test
    fun `watching is recorded, and unwatching takes it back off`() = runTest {
        val pages = FakePageService("volcano" to "x")

        pages.watch(listOf(pages.ref("volcano")))
        pages.isWatched("volcano") shouldBe true

        pages.watch(listOf(pages.ref("volcano")), watch = false)
        pages.isWatched("volcano") shouldBe false
    }

    @Test
    fun `protections read back what was set, and nothing for a page never protected`() = runTest {
        val pages = FakePageService("volcano" to "x")
        val locked = listOf(Protection(action = "edit", level = "sysop"))

        pages.protect(pages.ref("volcano"), locked, reason = "vandalism")

        pages.protections(listOf(pages.ref("volcano"))).values.single() shouldBe locked
        pages.protections(listOf(pages.ref("other"))) shouldBe emptyMap()
    }

    @Test
    fun `expanding returns the text unchanged, rather than pretending to be a parser`() = runTest {
        val pages = FakePageService("volcano" to "x")

        pages.expandText("{{lang|en}}") shouldBe "{{lang|en}}"
    }

    @Test
    fun `a test can say what expansion produces`() = runTest {
        val pages =
            FakePageService(
                texts = mapOf("volcano" to "x"),
                expander = { text, title -> "${title?.title?.text}: ${text.removeSurrounding("{{", "}}")}" },
            )

        pages.expandText("{{lang|en}}", pages.ref("volcano")) shouldBe "volcano: lang|en"
    }

    @Test
    fun `a page in another namespace is a different page`() = runTest {
        val pages = FakePageService("foo" to "main", "Template:foo" to "template")

        pages.text("foo") shouldBe "main"
        pages.text("Template:Foo") shouldBe "template"
        pages.content(pages.ref("foo", Namespace.TEMPLATE))?.text shouldBe "template"
        pages.content(pages.ref("foo"))?.text shouldBe "main"
    }

    @Test
    fun `each saved edit gives the page a new revision, which a read then reports`() = runTest {
        val pages = FakePageService("volcano" to "a", "vulcan" to "b")
        val ref = pages.ref("volcano")

        val first = pages.edit(ref) { text = "a2" }.shouldBeInstanceOf<EditOutcome.Saved>()
        val second = pages.edit(ref) { text = "a3" }.shouldBeInstanceOf<EditOutcome.Saved>()

        first.previousRevision shouldBe RevisionId(FakePageService.INITIAL_REVISION)
        second.previousRevision shouldBe first.revision
        pages.content(ref)?.revisionId shouldBe second.revision
        pages.revision("volcano") shouldBe second.revision
        pages.revision("vulcan") shouldBe RevisionId(FakePageService.INITIAL_REVISION)
        pages.revision("Nope").shouldBeNull()
    }

    @Test
    fun `an edit based on a revision the page has moved past is a conflict`() = runTest {
        val pages = FakePageService("volcano" to "a")
        val ref = pages.ref("volcano")
        val read = checkNotNull(pages.content(ref))

        pages.edit(ref) { text = "someone else" }
        val outcome =
            pages.edit(ref) {
                text = "mine"
                baseRevision = read.revisionId
            }

        outcome.shouldBeInstanceOf<EditOutcome.Conflict>()
        pages.text("volcano") shouldBe "someone else"
    }

    @Test
    fun `an edit to a page deleted since it was read recreates it unless it says noCreate`() = runTest {
        val pages = FakePageService("volcano" to "a")
        val ref = pages.ref("volcano")
        val read = checkNotNull(pages.content(ref))

        pages.delete(ref, reason = "")
        pages
            .edit(ref) {
                text = "b"
                baseRevision = read.revisionId
                noCreate = true
            }
            .shouldBeInstanceOf<EditOutcome.Rejected>()
            .code shouldBe "missingtitle"
        pages.exists(ref) shouldBe false

        pages
            .edit(ref) {
                text = "b"
                baseRevision = read.revisionId
            }
            .shouldBeInstanceOf<EditOutcome.Saved>()
        pages.text("volcano") shouldBe "b"
    }

    @Test
    fun `noCreate refuses a missing page and createOnly an existing one`() = runTest {
        val pages = FakePageService("volcano" to "a")

        pages
            .edit(pages.ref("Nope")) {
                text = "b"
                noCreate = true
            }
            .shouldBeInstanceOf<EditOutcome.Rejected>()
            .code shouldBe "missingtitle"
        pages
            .edit(pages.ref("volcano")) {
                text = "b"
                createOnly = true
            }
            .shouldBeInstanceOf<EditOutcome.PageStateChanged>()
            .wasDeleted shouldBe false
        pages.edit(pages.ref("Fresh")) {
            text = "new"
            createOnly = true
        }

        pages.exists(pages.ref("Nope")) shouldBe false
        pages.text("Fresh") shouldBe "new"
        pages.text("volcano") shouldBe "a"
    }

    @Test
    fun `an edit a wiki would reject as contradictory is rejected before anything changes`() = runTest {
        val pages = FakePageService("volcano" to "a")

        assertFailsWith<IllegalArgumentException> {
            pages.edit(pages.ref("volcano")) {
                text = "b"
                noCreate = true
                createOnly = true
            }
        }
        assertFailsWith<IllegalArgumentException> { pages.edit(pages.ref("volcano")) { summary = "nothing" } }
        pages.text("volcano") shouldBe "a"
    }

    @Test
    fun `text can be added before or after what is there, or as a new section`() = runTest {
        val pages = FakePageService("volcano" to "body", "talk" to "")

        pages.edit(pages.ref("volcano")) { prependText = "top\n" }
        pages.edit(pages.ref("volcano")) { appendText = "\nend" }
        pages.edit(pages.ref("volcano")) {
            section = "new"
            sectionTitle = "Note"
            text = "Hello."
        }
        pages.edit(pages.ref("talk")) {
            section = "new"
            sectionTitle = "First"
            text = "Hi."
        }
        pages.edit(pages.ref("talk")) {
            section = "new"
            summary = "Second"
            text = "Again."
        }

        pages.text("volcano") shouldBe "top\nbody\nend\n\n== Note ==\nHello."
        pages.text("talk") shouldBe "== First ==\nHi.\n\n== Second ==\nAgain."
    }

    @Test
    fun `a new section with no title or summary has no heading, and one may be appended rather than given`() =
        runTest {
            val pages = FakePageService("talk" to "First.")

            pages.edit(pages.ref("talk")) {
                section = "new"
                text = "Untitled."
            }
            pages.edit(pages.ref("talk")) {
                section = "new"
                sectionTitle = "Added"
                appendText = "Appended."
            }

            pages.text("talk") shouldBe "First.\n\nUntitled.\n\n== Added ==\nAppended."
        }

    @Test
    fun `a new section given as prepended text holds that text, as a wiki makes it`() = runTest {
        val pages = FakePageService("talk" to "First.")

        pages.edit(pages.ref("talk")) {
            section = "new"
            sectionTitle = "Prepended"
            prependText = "Before."
        }

        pages.text("talk") shouldBe "First.\n\n== Prepended ==\nBefore."
    }

    @Test
    fun `a new section or appended text on a page that does not exist creates it with only that`() = runTest {
        val pages = FakePageService()

        pages.edit(pages.ref("talk")) {
            section = "new"
            sectionTitle = "First"
            text = "Hi."
        }
        pages.edit(pages.ref("log")) { appendText = "entry" }

        pages.text("talk") shouldBe "== First ==\nHi."
        pages.text("log") shouldBe "entry"
    }

    @Test
    fun `a numbered section edit is not something the fake can do, so it says so`() = runTest {
        val pages = FakePageService("volcano" to "== a ==\nx")

        assertFailsWith<NotImplementedError> {
            pages.edit(pages.ref("volcano")) {
                section = "1"
                text = "== a ==\ny"
            }
        }
    }

    @Test
    fun `a transport handed in is the one the fake wiki and its tokens talk to`() = runTest {
        val transport = MockTransport { request ->
            MockTransport.json("""{"query":{"tokens":{"csrftoken":"abc+\\"}},"asked":"${request.action}"}""")
        }
        val pages = FakePageService("volcano" to "x")
        val wiki = FakeWiki(pages = pages, transport = transport)

        wiki.transport.call(ApiRequest.of("query", "meta" to "siteinfo"))["asked"].toString() shouldBe
            "\"query\""
        wiki.tokens.token() shouldBe "abc+\\"
        transport.requests.map { it.params["meta"] } shouldBe listOf("siteinfo", "tokens")
        wiki.pages shouldBe pages
        // What was not handed in still refuses, rather than answering with nothing.
        assertFailsWith<NotImplementedError> { wiki.lists }
    }

    @Test
    fun `a list service handed in is the one the fake wiki lists with`() = runTest {
        val member = FakePageService().ref("Volcano")
        val lists = stub<ListService> { method -> if (method == "categoryMembers") flowOf(member) else null }
        val wiki = FakeWiki(pages = FakePageService(), lists = lists)

        wiki.lists shouldBeSameInstanceAs lists
        wiki.lists.categoryMembers(wiki.ref("Category:Volcanoes")).toList() shouldBe listOf(member)
    }

    @Test
    fun `every other service handed in is the one the fake wiki uses`() {
        val revisions = stub<RevisionService>()
        val users = stub<UserService>()
        val logs = stub<LogService>()
        val renderer = stub<RenderService>()
        val meta = stub<MetaService>()

        val wiki =
            FakeWiki(
                pages = FakePageService(),
                revisions = revisions,
                users = users,
                logs = logs,
                renderer = renderer,
                meta = meta,
            )

        wiki.revisions shouldBeSameInstanceAs revisions
        wiki.users shouldBeSameInstanceAs users
        wiki.logs shouldBeSameInstanceAs logs
        wiki.renderer shouldBeSameInstanceAs renderer
        wiki.meta shouldBeSameInstanceAs meta
    }

    @Test
    fun `a fake wiki parses as Wikimedia's wikis do, without asking its transport`() = runTest {
        val wiki = FakeWiki("volcano" to "x")

        wiki.parseOptions() shouldBe ParseOptions.DEFAULT
        wiki.titleRules() shouldBe TitleRules.DEFAULT
    }

    @Test
    fun `code compiled against the 1_1 fake wiki constructor still links`() {
        // Found by its JVM signature: WikiId is a value class, so Kotlin adds a marker parameter.
        val pages = FakePageService("volcano" to "x")
        val identity = Identity(name = "OtherBot", id = 9)
        val parameters =
            arrayOf(
                PageService::class.java,
                String::class.java,
                NamespaceMap::class.java,
                Identity::class.java,
            )

        val full =
            FakeWiki::class
                .java
                .getConstructor(*parameters, DefaultConstructorMarker::class.java)
                .newInstance(pages, "enwiktionary", NamespaceMap.CANONICAL, identity, null)
        // A 1.1 call that gave only the pages, which leaves the other three to their defaults.
        val defaulted =
            FakeWiki::class
                .java
                .getConstructor(*parameters, Int::class.java, DefaultConstructorMarker::class.java)
                .newInstance(pages, null, null, null, 0b1110, null)

        full.pages shouldBeSameInstanceAs pages
        full.id shouldBe WikiId("enwiktionary")
        full.identity shouldBe identity
        defaulted.id shouldBe WikiId("testwiki")
        defaulted.identity.name shouldBe "TestBot"
        assertFailsWith<NotImplementedError> { defaulted.lists }
    }

    @Test
    fun `code compiled against the 1_1 fake page service constructor still links`() = runTest {
        val refused = EditOutcome.Protected(FakePageService().ref("Volcano"), "protected")
        val parameters =
            arrayOf(Map::class.java, String::class.java, Function1::class.java, Function0::class.java)
        val refuse: (PageRef) -> EditOutcome.Refused? = { refused }

        val full =
            FakePageService::class
                .java
                .getConstructor(*parameters, DefaultConstructorMarker::class.java)
                .newInstance(mapOf("volcano" to "x"), "enwiktionary", refuse, null, null)
        val defaulted =
            FakePageService::class
                .java
                .getConstructor(*parameters, Int::class.java, DefaultConstructorMarker::class.java)
                .newInstance(null, null, null, null, 0b1111, null)

        full.text("volcano") shouldBe "x"
        full.ref("volcano").wiki shouldBe WikiId("enwiktionary")
        full.edit(full.ref("volcano")) { text = "y" } shouldBe refused
        // Expansion, which 1.1 did not have, is the identity.
        full.expandText("{{x}}") shouldBe "{{x}}"
        defaulted.ref("volcano").wiki shouldBe WikiId("testwiki")
        defaulted.edit(defaulted.ref("volcano")) { text = "y" }.shouldBeInstanceOf<EditOutcome.Saved>()
    }

    @Test
    fun `the pages redirecting to a page are found in its text, in the namespaces asked for`() = runTest {
        val pages =
            FakePageService(
                "Volcano" to "==English==",
                "Volcanoes" to "#REDIRECT [[Volcano]]",
                "Template:Volcano" to "#redirect [[volcano]]",
                "Lava" to "no redirect here",
            )
        val volcano = pages.ref("Volcano")

        pages.redirectsTo(listOf(volcano)) shouldBe
            mapOf(volcano to listOf(pages.ref("Volcanoes"), pages.ref("Volcano", Namespace.TEMPLATE)))
        pages.redirectsTo(listOf(volcano), setOf(Namespace.TEMPLATE)) shouldBe
            mapOf(volcano to listOf(pages.ref("Volcano", Namespace.TEMPLATE)))
        pages.redirectsTo(listOf(pages.ref("Lava"))) shouldBe emptyMap()
    }

    @Test
    fun `moving, deleting or undeleting a page the fake does not hold changes nothing`() = runTest {
        val pages = FakePageService("volcano" to "x")

        pages.move(pages.ref("Nope"), pages.ref("Elsewhere")).title.text shouldBe "Elsewhere"
        pages.delete(pages.ref("Nope"))
        pages.undelete(pages.ref("Nope"))

        pages.text("Elsewhere").shouldBeNull()
        pages.text("Nope").shouldBeNull()
        pages.revision("Nope").shouldBeNull()
        pages.text("volcano") shouldBe "x"
    }

    @Test
    fun `a title no wiki would accept is kept as written, the fake not being a title validator`() = runTest {
        val pages = FakePageService("a|b" to "x")

        pages.text("a|b") shouldBe "x"
        pages.content(pages.ref("a|b"))?.text shouldBe "x"
    }

    @Test
    fun `the administrative actions run without doing anything`() = runTest {
        val pages = FakePageService("volcano" to "x")
        val ref = pages.ref("volcano")

        pages.purge(listOf(ref))
        pages.mergeHistory(ref, pages.ref("Volcano"))
        pages.importPage(source = "enwiki", page = "Volcano")
        pages.setLanguage(ref, LangCode("fr"), reason = "wrong language")
        pages.changeContentModel(ref, model = "wikitext", summary = "")

        pages.text("volcano") shouldBe "x"
    }

    @Test
    fun `the link and category queries answer empty, so a code path reading them runs`() = runTest {
        val pages = FakePageService("volcano" to "x")
        val refs = listOf(pages.ref("volcano"))

        pages.backlinksOf(refs) shouldBe emptyMap()
        pages.transclusionsOf(refs) shouldBe emptyMap()
        pages.fileUsageOf(refs) shouldBe emptyMap()
        pages.categoryInfo(refs) shouldBe emptyMap()
        pages.contributors(refs) shouldBe emptyMap()
    }

    @Test
    fun `the fake refuses nothing, a bot under test not being tested on its permissions`() = runTest {
        val pages = FakePageService("volcano" to "x")

        val checks = pages.testActions(listOf(pages.ref("volcano")), setOf("edit"))

        checks.values.single().allows("edit") shouldBe true
    }

    @Test
    fun `contents fetches many pages and leaves out the ones that are missing`() = runTest {
        val pages = FakePageService("volcano" to "a", "vulcan" to "b")

        val found = pages.contents(listOf(pages.ref("volcano"), pages.ref("vulcan"), pages.ref("Nope")))

        found.size shouldBe 2
    }

    /**
     * A service that answers [answer] for the methods it knows and refuses every other call, for checking
     * which instance the fake hands out: the service interfaces are too wide to write out by hand for that.
     */
    private inline fun <reified T : Any> stub(crossinline answer: (String) -> Any? = { null }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "stub ${T::class.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> answer(method.name) ?: throw UnsupportedOperationException(method.name)
            }
        } as T
}
