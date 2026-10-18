package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiPageService
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.testkit.MockTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** What a page service asks of the wiki and makes of its answers, read off the requests themselves. */
class PageRequestsTest {

    private val wiki = WikiId("enwiktionary")

    private fun ref(text: String, namespace: Namespace = Namespace.MAIN) =
        PageRef(wiki, Title.Local(namespace, text))

    @Test
    fun `an update names its base revision and may not create the page, and a creation may only create`() =
        runTest {
            val transport = writing("""{"edit":{"result":"Success","pageid":1,"newrevid":9002}}""")
            val service = pages(transport)

            service.edit(ref("volcano")) {
                text = "new text"
                baseRevision = RevisionId(9001)
                noCreate = true
            }
            service.edit(ref("lava")) {
                text = "first text"
                createOnly = true
            }

            val (update, creation) = transport.requests.filter { it.action == "edit" }
            update.params["baserevid"] shouldBe "9001"
            update.params["nocreate"] shouldBe "1"
            update.params["createonly"].shouldBeNull()
            creation.params["createonly"] shouldBe "1"
            creation.params["nocreate"].shouldBeNull()
            creation.params["baserevid"].shouldBeNull()
        }

    @Test
    fun `a watch an edit adds can be given an end`() = runTest {
        val transport = writing("""{"edit":{"result":"Success","pageid":1,"newrevid":2}}""")

        pages(transport).edit(ref("volcano")) {
            text = "new text"
            watchlist = WatchMode.WATCH
            watchlistExpiry = "1 month"
        }

        val edit = transport.requests.single { it.action == "edit" }
        edit.params["watchlist"] shouldBe "watch"
        edit.params["watchlistexpiry"] shouldBe "1 month"
    }

    @Test
    fun `an edit the wiki answers with anything but success is rejected, with what it said`() = runTest {
        val outcome =
            pages(writing("""{"edit":{"result":"Failure","spamblacklist":"example.org"}}""")).edit(
                ref("volcano")
            ) {
                text = "see example.org"
            }

        outcome.shouldBeInstanceOf<EditOutcome.Rejected>().code shouldBe "failure"
    }

    @Test
    fun `an edit answered with no edit block is an error, not a save`() = runTest {
        val failure =
            shouldThrow<WikiError.Api> {
                pages(writing("""{"batchcomplete":true}""")).edit(ref("volcano")) { text = "x" }
            }

        failure.code shouldBe "noedit"
    }

    @Test
    fun `a fresh read is never answered from a cache, nor its continued requests, but a plain read may be`() =
        runTest {
            val transport = MockTransport { request ->
                if ("rvcontinue" in request.params) {
                    MockTransport.json(
                        """{"query":{"pages":[{"pageid":2,"ns":0,"title":"lava","revisions":[{"revid":2,
                           "timestamp":"2026-01-01T00:00:00Z","slots":{"main":{"content":"hot"}}}]}]}}"""
                    )
                } else {
                    MockTransport.json(
                        """{"continue":{"rvcontinue":"2|2","continue":"||"},"query":{"pages":[
                           {"pageid":1,"ns":0,"title":"volcano","revisions":[{"revid":1,
                           "timestamp":"2026-01-01T00:00:00Z","slots":{"main":{"content":"mountain"}}}]},
                           {"pageid":2,"ns":0,"title":"lava"}]}}"""
                    )
                }
            }
            val service = pages(transport)
            val wanted = listOf(ref("volcano"), ref("lava"))

            val fresh = service.freshContents(wanted)
            val fresher = transport.requests.size
            service.contents(wanted)

            fresh.mapValues { it.value.text } shouldBe
                mapOf(ref("volcano") to "mountain", ref("lava") to "hot")
            transport.requests.take(fresher).map { it.cacheable } shouldBe listOf(false, false)
            transport.requests.drop(fresher).map { it.cacheable } shouldBe listOf(true, true)
        }

    @Test
    fun `undeleting restores the talk page only when asked`() = runTest {
        val transport = writing("""{"undelete":{"title":"volcano","revisions":3}}""")
        val service = pages(transport)

        service.undelete(ref("volcano"))
        service.undelete(
            ref("volcano"),
            reason = "deleted in error",
            undeleteTalk = true,
            watchlist = WatchMode.NO_CHANGE,
        )

        val (plain, full) = transport.requests.filter { it.action == "undelete" }
        plain.params["title"] shouldBe "volcano"
        plain.params["undeletetalk"].shouldBeNull()
        plain.params["watchlist"].shouldBeNull()
        plain.isWrite shouldBe true
        full.params["reason"] shouldBe "deleted in error"
        full.params["undeletetalk"] shouldBe "1"
        full.params["watchlist"] shouldBe "nochange"
    }

    @Test
    fun `a page language is set by its code`() = runTest {
        val transport = writing("""{"setpagelanguage":{"title":"volcano","newlanguage":"de"}}""")

        pages(transport).setLanguage(ref("volcano"), LangCode("de"))

        val sent = transport.requests.single { it.action == "setpagelanguage" }
        sent.params["title"] shouldBe "volcano"
        sent.params["lang"] shouldBe "de"
        sent.params["token"] shouldBe "TOKEN"
    }

    @Test
    fun `what transcludes a template and what uses a file are each asked of their own module`() = runTest {
        val transport = MockTransport { request ->
            val title = request.params.getValue("titles")
            val namespace = if (title.startsWith("File:")) 6 else 10
            MockTransport.json(
                """{"query":{"pages":[{"ns":$namespace,"title":"$title",
                   "${request.params["prop"]}":[{"ns":0,"title":"volcano"}]}]}}"""
            )
        }
        val service = pages(transport)
        val template = ref("x", Namespace.TEMPLATE)
        val file = ref("Lava.jpg", Namespace.FILE)

        service.transclusionsOf(listOf(template)) shouldBe mapOf(template to listOf(ref("volcano")))
        service.fileUsageOf(listOf(file)) shouldBe mapOf(file to listOf(ref("volcano")))

        val (transclusions, usage) = transport.requests
        transclusions.params["prop"] shouldBe "transcludedin"
        transclusions.params["tilimit"] shouldBe "max"
        usage.params["prop"] shouldBe "fileusage"
        usage.params["fulimit"] shouldBe "max"
    }

    @Test
    fun `a rollback that made no new revision is no change`() = runTest {
        val transport = MockTransport { request ->
            if (request.params["meta"] == "tokens") {
                MockTransport.json("""{"query":{"tokens":{"rollbacktoken":"R"}}}""")
            } else {
                MockTransport.json("""{"rollback":{"title":"volcano","old_revid":9}}""")
            }
        }

        val outcome = pages(transport).rollback(ref("volcano"), user = "Vandal")

        outcome shouldBe EditOutcome.NoChange(ref("volcano"), RevisionId(9))
    }

    @Test
    fun `a rollback or an undo answered with nothing to say what it did is an error`() = runTest {
        val transport = MockTransport { request ->
            if (request.params["meta"] == "tokens") {
                MockTransport.json("""{"query":{"tokens":{"csrftoken":"T","rollbacktoken":"R"}}}""")
            } else {
                MockTransport.json("""{"batchcomplete":true}""")
            }
        }
        val service = pages(transport)

        shouldThrow<WikiError.Api> { service.rollback(ref("volcano"), user = "Vandal") }.code shouldBe
            "norollback"
        shouldThrow<WikiError.Api> { service.undo(ref("volcano"), RevisionId(10)) }.code shouldBe "noedit"
    }

    @Test
    fun `a page service that cannot list redirects says so rather than answering none`() = runTest {
        val api = pages(MockTransport { MockTransport.json("{}") })
        val service =
            object : PageService by api {
                // Delegation would hand this to the API service too; the interface's own is the one tested.
                override suspend fun redirectsTo(refs: Collection<PageRef>, namespaces: Set<Namespace>) =
                    super<PageService>.redirectsTo(refs, namespaces)
            }

        shouldThrow<UnsupportedOperationException> { service.redirectsTo(listOf(ref("volcano"))) }
    }

    @Test
    fun `a category is hidden only when the wiki says true, not whenever it says anything`() = runTest {
        val service =
            pages(
                MockTransport {
                    MockTransport.json(
                        """{"query":{"pages":[
                       {"ns":14,"title":"Category:English lemmas","categoryinfo":
                         {"size":2,"pages":2,"files":0,"subcats":0,"hidden":false}},
                       {"ns":14,"title":"Category:Pages with broken file links","categoryinfo":
                         {"size":1,"pages":1,"files":0,"subcats":0,"hidden":true}}]}}"""
                    )
                }
            )

        val lemmas = ref("English lemmas", Namespace.CATEGORY)
        val broken = ref("Pages with broken file links", Namespace.CATEGORY)
        val counts = service.categoryInfo(listOf(lemmas, broken))

        counts.getValue(lemmas).hidden shouldBe false
        counts.getValue(broken).hidden shouldBe true
    }

    private fun pages(transport: MockTransport): PageService =
        ApiPageService(
            transport = transport,
            tokens = TokenStore(transport),
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
        )

    /** A wiki that hands out an edit token and answers every write with [answer]. */
    private fun writing(answer: String) = MockTransport { request ->
        if (request.params["meta"] == "tokens") {
            MockTransport.json("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
        } else {
            MockTransport.json(answer)
        }
    }
}
