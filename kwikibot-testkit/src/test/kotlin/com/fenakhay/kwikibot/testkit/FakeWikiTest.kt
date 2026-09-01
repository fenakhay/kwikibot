package com.fenakhay.kwikibot.testkit

import com.fenakhay.kwikibot.model.EditOutcome
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.Protection
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.ApiRequest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

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

        val services: List<Pair<String, () -> Any>> = listOf(
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

        val error = assertFailsWith<NotImplementedError> {
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

        pages.edit(pages.ref("volcano")) { text = "==English==" }
            .shouldBeInstanceOf<EditOutcome.NoChange>()
        pages.edits.isEmpty() shouldBe true
    }

    @Test
    fun `an injected refusal comes back instead of the edit`() = runTest {
        val pages = FakePageService(
            texts = mapOf("volcano" to "x"),
            refuse = { EditOutcome.Protected(it, detail = "the page is protected", level = "sysop") },
        )

        pages.edit(pages.ref("volcano")) { text = "y" }
            .shouldBeInstanceOf<EditOutcome.Protected>().level shouldBe "sysop"
        pages.text("volcano") shouldBe "x"
    }

    @Test
    fun `an injected failure is thrown, so a bot's error path can be tested`() = runTest {
        val pages = FakePageService(
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

        pages.rollback(pages.ref("volcano"), user = "Vandal")
            .shouldBeInstanceOf<EditOutcome.Saved>()

        pages.text("volcano") shouldBe "==English=="
    }

    @Test
    fun `rolling back an unedited page changes nothing`() = runTest {
        val pages = FakePageService("volcano" to "==English==")

        pages.rollback(pages.ref("volcano"), user = "Nobody")
            .shouldBeInstanceOf<EditOutcome.NoChange>()
    }

    @Test
    fun `rolling back a page the fake never held is a no-op, not a crash`() = runTest {
        val pages = FakePageService("volcano" to "x")

        pages.rollback(pages.ref("Unknown"), user = "Nobody")
            .shouldBeInstanceOf<EditOutcome.NoChange>()
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
    fun `the fake refuses nothing, a bot under test not being tested on its permissions`() =
        runTest {
            val pages = FakePageService("volcano" to "x")

            val checks = pages.testActions(listOf(pages.ref("volcano")), setOf("edit"))

            checks.values.single().allows("edit") shouldBe true
        }

    @Test
    fun `contents fetches many pages and leaves out the ones that are missing`() = runTest {
        val pages = FakePageService("volcano" to "a", "vulcan" to "b")

        val found = pages.contents(
            listOf(pages.ref("volcano"), pages.ref("vulcan"), pages.ref("Nope")),
        )

        found.size shouldBe 2
    }
}
