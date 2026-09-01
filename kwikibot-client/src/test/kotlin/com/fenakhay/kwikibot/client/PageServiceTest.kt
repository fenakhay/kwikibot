package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.EditOutcome
import com.fenakhay.kwikibot.model.Expiry
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Protection
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.model.didChange
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.protocol.PageDecoder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Instant

class PageServiceTest {

    private val wiki = WikiId("enwiktionary")

    private fun ref(text: String, namespace: Namespace = Namespace.MAIN) =
        PageRef(wiki, Title.Local(namespace, text))

    @Test
    fun `content is fetched and decoded`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano",
                   "revisions":[{"revid":9001,"timestamp":"2026-08-31T21:43:26Z",
                   "slots":{"main":{"content":"==English=="}}}]}]}}""",
            )
        }

        val content = service.content(ref("volcano"))

        content?.text shouldBe "==English=="
        content?.revisionId shouldBe RevisionId(9001)
    }

    @Test
    fun `a missing page yields null rather than empty text`() = runTest {
        val service = service {
            respondJson("""{"query":{"pages":[{"ns":0,"title":"Nope","missing":true}]}}""")
        }

        service.content(ref("Nope")).shouldBeNull()
    }

    @Test
    fun `many pages are fetched in one request per batch`() = runTest {
        var requests = 0
        val service = service(batchSize = 2) { request ->
            requests++
            val titles = request.url.parameters["titles"].orEmpty().split("|")
            respondJson(
                """{"query":{"pages":[""" +
                    titles.joinToString(",") { title ->
                        """{"pageid":1,"ns":0,"title":"$title","revisions":[{"revid":1,
                           "timestamp":"2026-01-01T00:00:00Z","slots":{"main":{"content":"$title text"}}}]}"""
                    } +
                    """]}}""",
            )
        }

        val refs = listOf(ref("a"), ref("b"), ref("c"), ref("d"), ref("e"))
        val contents = service.contents(refs)

        contents.size shouldBe 5
        contents[ref("c")]?.text shouldBe "c text"
        requests shouldBe 3
    }

    @Test
    fun `a namespace prefix survives the round trip`() = runTest {
        var sentTitle: String? = null
        val service = service { request ->
            sentTitle = request.url.parameters["titles"]
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":14,"title":"Category:English lemmas",
                   "revisions":[{"revid":1,"timestamp":"2026-01-01T00:00:00Z",
                   "slots":{"main":{"content":"members"}}}]}]}}""",
            )
        }

        val category = ref("English lemmas", Namespace.CATEGORY)
        val content = service.content(category)

        sentTitle shouldBe "Category:English lemmas"
        content?.text shouldBe "members"
    }

    @Test
    fun `a successful edit reports the new revision`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                respondJson(
                    """{"edit":{"result":"Success","pageid":1,"title":"volcano",
                       "oldrevid":9001,"newrevid":9002,"newtimestamp":"2026-08-31T22:00:00Z"}}""",
                )
            }
        }

        val outcome = service.edit(ref("volcano")) {
            text = "new text"
            summary = "adding derived terms"
            baseRevision = RevisionId(9001)
        }

        outcome.shouldBeInstanceOf<EditOutcome.Saved>().revision shouldBe RevisionId(9002)
        outcome.previousRevision shouldBe RevisionId(9001)
        outcome.didChange shouldBe true
    }

    @Test
    fun `an edit that changes nothing is a no-op, not a save`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                respondJson("""{"edit":{"result":"Success","pageid":1,"title":"volcano","nochange":true}}""")
            }
        }

        val outcome = service.edit(ref("volcano")) { text = "unchanged" }

        outcome.shouldBeInstanceOf<EditOutcome.NoChange>()
        outcome.didChange shouldBe false
    }

    @Test
    fun `an edit conflict comes back as an outcome, not an exception`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                respondJson(
                    """{"errors":[{"code":"editconflict","text":"Edit conflict.","module":"edit"}]}""",
                )
            }
        }

        val outcome = service.edit(ref("volcano")) {
            text = "new text"
            baseRevision = RevisionId(9001)
        }

        outcome.shouldBeInstanceOf<EditOutcome.Conflict>().isRetryable shouldBe true
    }

    @Test
    fun `a dead session is raised rather than logged against the page`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                respondJson("""{"errors":[{"code":"assertuserfailed","text":"Assertion failed"}]}""")
            }
        }

        assertFailsWith<WikiError.Auth.NotLoggedIn> {
            service.edit(ref("volcano")) { text = "new text" }
        }
    }

    @Test
    fun `a stale token is refetched and the edit retried`() = runTest {
        var tokens = 0
        var edits = 0
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                tokens++
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN$tokens"}}}""")
            } else {
                edits++
                if (edits == 1) {
                    respondJson("""{"errors":[{"code":"badtoken","text":"Invalid CSRF token."}]}""")
                } else {
                    respondJson("""{"edit":{"result":"Success","newrevid":9002}}""")
                }
            }
        }

        val outcome = service.edit(ref("volcano")) { text = "new text" }

        outcome.shouldBeInstanceOf<EditOutcome.Saved>()
        tokens shouldBe 2
        edits shouldBe 2
    }

    @Test
    fun `the edit asserts a logged-in user and sends its token last`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"edit":{"result":"Success","newrevid":1}}""")
            }
        }

        service.edit(ref("volcano")) {
            text = "new text"
            summary = "s"
            bot = true
        }

        body.contains("assert=user") shouldBe true
        body.contains("bot=1") shouldBe true
        body.endsWith("token=TOKEN") shouldBe true
    }

    @Test
    fun `contradictory edit options are refused before anything is sent`() = runTest {
        var requests = 0
        val service = service {
            requests++
            respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
        }

        assertFailsWith<IllegalArgumentException> {
            service.edit(ref("volcano")) {
                text = "a"
                appendText = "b"
            }
        }
        assertFailsWith<IllegalArgumentException> {
            service.edit(ref("volcano")) { text = "a"; noCreate = true; createOnly = true }
        }
        assertFailsWith<IllegalArgumentException> {
            service.edit(ref("volcano")) { summary = "no text at all" }
        }
    }

    @Test
    fun `a move sends both titles and returns the new reference`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"move":{"from":"volcano","to":"Volcano","reason":"caps"}}""")
            }
        }

        val moved = service.move(
            from = ref("volcano"),
            to = ref("Volcano"),
            reason = "caps",
            leaveRedirect = false,
        )

        moved.title.text shouldBe "Volcano"
        body.contains("from=volcano") shouldBe true
        body.contains("noredirect=1") shouldBe true
    }

    @Test
    fun `a refused move is raised, since a bot has nothing to record about it`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                respondJson("""{"errors":[{"code":"permissiondenied","text":"Not allowed."}]}""")
            }
        }

        assertFailsWith<WikiError.Auth.PermissionDenied> {
            service.move(ref("a"), ref("b"))
        }
    }

    @Test
    fun `purging is batched and paced as a read`() = runTest {
        var requests = 0
        var method = ""
        val service = service(batchSize = 2) { request ->
            requests++
            method = request.method.value
            respondJson("""{"purge":[{"ns":0,"title":"a","purged":true}]}""")
        }

        service.purge(listOf(ref("a"), ref("b"), ref("c")))

        requests shouldBe 2
        method shouldBe "GET"
    }

    @Test
    fun `protections are read with their levels and expiries`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano","protection":[
                   {"type":"edit","level":"sysop","expiry":"infinity"},
                   {"type":"move","level":"autoconfirmed","expiry":"2026-09-01T23:59:00Z"}]}]}}""",
            )
        }

        val protections = service.protections(listOf(ref("volcano"))).getValue(ref("volcano"))

        protections.map { it.action } shouldBe listOf("edit", "move")
        protections.first().expiry shouldBe Expiry.Never
        protections.last().expiry.shouldBeInstanceOf<Expiry.At>()
    }

    @Test
    fun `an unprotected page reports no protections rather than nothing`() = runTest {
        val service = service {
            respondJson("""{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano"}]}}""")
        }

        service.protections(listOf(ref("volcano"))).getValue(ref("volcano")) shouldBe emptyList()
    }

    @Test
    fun `protecting sends the restrictions and their expiries in step`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"protect":{"title":"volcano"}}""")
            }
        }

        service.protect(
            ref("volcano"),
            listOf(
                Protection("edit", Protection.SYSOP),
                Protection("move", Protection.AUTOCONFIRMED),
            ),
            reason = "vandalism",
        )

        body.contains("protections=edit%3Dsysop%7Cmove%3Dautoconfirmed") shouldBe true
        body.contains("expiry=infinity%7Cinfinity") shouldBe true
    }

    @Test
    fun `unprotecting sends an empty restriction set rather than refusing`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"protect":{"title":"volcano"}}""")
            }
        }

        service.protect(ref("volcano"), emptyList())

        body.contains("protections=&") shouldBe true
    }

    @Test
    fun `a rollback uses its own token, not the csrf one`() = runTest {
        var requested = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                requested = request.url.parameters["type"].orEmpty()
                respondJson("""{"query":{"tokens":{"rollbacktoken":"R"}}}""")
            } else {
                respondJson(
                    """{"rollback":{"title":"volcano","old_revid":9,"revid":10}}""",
                )
            }
        }

        val outcome = service.rollback(ref("volcano"), user = "Vandal")

        requested shouldBe "rollback"
        outcome.shouldBeInstanceOf<EditOutcome.Saved>().revision shouldBe RevisionId(10)
    }

    @Test
    fun `a rollback refused because somebody edited first is an outcome, not a crash`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"rollbacktoken":"R"}}}""")
            } else {
                respondJson(
                    """{"errors":[{"code":"editconflict","text":"somebody edited first"}]}""",
                )
            }
        }

        val outcome = service.rollback(ref("volcano"), user = "Vandal")

        outcome.shouldBeInstanceOf<EditOutcome.Refused>()
    }

    @Test
    fun `an undo of one revision does not name a range`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson(
                    """{"edit":{"result":"Success","pageid":1,"title":"volcano",
                       "oldrevid":9,"newrevid":11}}""",
                )
            }
        }

        val outcome = service.undo(ref("volcano"), RevisionId(10), summary = "revert")

        body.contains("undo=10") shouldBe true
        body.contains("undoafter") shouldBe false
        outcome.shouldBeInstanceOf<EditOutcome.Saved>().revision shouldBe RevisionId(11)
    }

    @Test
    fun `an undo whose revisions no longer apply cleanly is reported, not thrown`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                respondJson(
                    """{"errors":[{"code":"undofailure","text":"could not undo"}]}""",
                )
            }
        }

        service.undo(ref("volcano"), RevisionId(10)).shouldBeInstanceOf<EditOutcome.Conflict>()
    }

    @Test
    fun `a new section is posted as one, not appended to the page`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"edit":{"result":"Success","pageid":1,"title":"User talk:Someone"}}""")
            }
        }

        service.edit(ref("User talk:Someone")) {
            section = "new"
            sectionTitle = "About your edit"
            text = "Please see the guideline. ~~~~"
        }

        body shouldContain "section=new"
        body shouldContain "sectiontitle=About+your+edit"
    }

    @Test
    fun `a heading without a new section is refused rather than silently dropped`() = runTest {
        val service = service { respondJson("{}") }

        shouldThrow<IllegalArgumentException> {
            service.edit(ref("volcano")) {
                section = "2"
                sectionTitle = "ignored"
                text = "x"
            }
        }
    }

    @Test
    fun `the watchlist is left alone only when asked, since the wiki assumes preferences`() =
        runTest {
            val bodies = mutableListOf<String>()
            val service = service { request ->
                if (request.url.parameters["meta"] == "tokens") {
                    respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
                } else {
                    bodies += request.body.toByteArray().decodeToString()
                    respondJson("""{"edit":{"result":"Success","pageid":1,"title":"volcano"}}""")
                }
            }

            service.edit(ref("volcano")) { text = "a" }
            service.edit(ref("volcano")) {
                text = "b"
                watchlist = WatchMode.NO_CHANGE
            }

            bodies[0].contains("watchlist") shouldBe false
            bodies[1] shouldContain "watchlist=nochange"
        }

    @Test
    fun `deleting a page can take its talk page with it`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"delete":{"title":"volcano","reason":"spam"}}""")
            }
        }

        service.delete(ref("volcano"), reason = "spam", deleteTalk = true)

        body shouldContain "deletetalk=1"
    }

    @Test
    fun `category counts come back per category, and absent for one that holds nothing`() =
        runTest {
            val service = service {
                respondJson(
                    """{"query":{"pages":[
                       {"ns":14,"title":"Category:Empty"},
                       {"ns":14,"title":"Category:English lemmas","categoryinfo":
                         {"size":878176,"pages":878160,"files":0,"subcats":16,"hidden":false}}]}}""",
                )
            }

            val counts = service.categoryInfo(
                listOf(
                    ref("English lemmas", Namespace.CATEGORY),
                    ref("Empty", Namespace.CATEGORY),
                ),
            )

            counts.values.single().subcategories shouldBe 16
            counts.values.single().pages shouldBe 878160
        }

    @Test
    fun `what links here is asked for every page in one request`() = runTest {
        var titles = ""
        val service = service { request ->
            titles = request.url.parameters["titles"].orEmpty()
            respondJson(
                """{"query":{"pages":[
                   {"ns":0,"title":"volcano","linkshere":[{"ns":0,"title":"lava"}]},
                   {"ns":0,"title":"magma"}]}}""",
            )
        }

        val links = service.backlinksOf(listOf(ref("volcano"), ref("magma")))

        titles shouldBe "volcano|magma"
        links.keys.single().title.text shouldBe "volcano"
        links.values.single().single().title.text shouldBe "lava"
    }

    @Test
    fun `asking about nothing costs no request`() = runTest {
        var calls = 0
        val service = service {
            calls++
            respondJson("{}")
        }

        service.categoryInfo(emptyList()) shouldBe emptyMap()
        service.backlinksOf(emptyList()) shouldBe emptyMap()
        calls shouldBe 0
    }

    @Test
    fun `an action the wiki refuses comes back with the reason, not just a no`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"pages":[{"ns":0,"title":"Main Page","actions":{
                   "edit":[{"code":"protectedpage","text":"protected"},
                           {"code":"cascadeprotected","text":"cascading"}],
                   "move":[]}}]}}""",
            )
        }

        val checks = service.testActions(
            listOf(ref("Main Page")),
            setOf("edit", "move"),
        ).values.single()

        checks.allows("edit") shouldBe false
        checks.allows("move") shouldBe true
        checks.reasons("edit") shouldBe listOf("protectedpage", "cascadeprotected")
    }

    @Test
    fun `an action nobody asked about is not reported as allowed`() = runTest {
        val service = service {
            respondJson("""{"query":{"pages":[{"ns":0,"title":"volcano","actions":{"edit":[]}}]}}""")
        }

        val checks = service.testActions(listOf(ref("volcano"))).values.single()

        checks.allows("edit") shouldBe true
        checks.allows("delete") shouldBe false
    }

    @Test
    fun `merging a history can stop at a moment, leaving the rest behind`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"mergehistory":{"from":"old","to":"new"}}""")
            }
        }

        service.mergeHistory(
            from = ref("old"),
            to = ref("new"),
            upTo = Instant.parse("2026-01-01T00:00:00Z"),
            reason = "split",
        )

        body shouldContain "timestamp=2026-01-01T00%3A00%3A00Z"
        body shouldContain "action=mergehistory"
    }

    @Test
    fun `changing a content model names the model the wiki asked for`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"changecontentmodel":{"result":"Success"}}""")
            }
        }

        service.changeContentModel(ref("Module:x"), model = "Scribunto")

        body shouldContain "model=Scribunto"
    }

    @Test
    fun `the anonymous contributor count is not part of the contributor list`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"pages":[{"ns":0,"title":"volcano",
                   "anoncontributors":51,
                   "contributors":[{"userid":394541,"name":"NadandoBot"},
                                   {"userid":21371,"name":"Rua"}]}]}}""",
            )
        }

        val who = service.contributors(listOf(ref("volcano"))).values.single()

        who.users.map { it.name } shouldBe listOf("NadandoBot", "Rua")
        who.users.first().id shouldBe 394541L
        who.anonymous shouldBe 51
    }

    private fun TestScope.service(
        batchSize: Int = 50,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PageService {
        val transport = KtorTransport(
            client = HttpClient(MockEngine(handler)),
            endpoint = ApiEndpoint("en.wiktionary.org"),
            userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
            throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
            retry = RetryPolicy.NONE,
        )
        return ApiPageService(
            transport = transport,
            tokens = TokenStore(transport),
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
            batchSize = batchSize,
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
