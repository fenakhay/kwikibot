package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiRevisionService
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class RevisionServiceTest {

    private val wiki = WikiId("enwiktionary")
    private val page = PageRef(wiki, Title.Local(Namespace.MAIN, "volcano"))

    @Test
    fun `a history is read across continuations`() = runTest {
        var requests = 0
        val service = service {
            requests++
            if (requests == 1) {
                respondJson(
                    """{"continue":{"rvcontinue":"20260101|2","continue":"||"},
                       "query":{"pages":[{"pageid":1,"ns":0,"title":"volcano","revisions":[
                       {"revid":3,"timestamp":"2026-08-01T00:00:00Z","user":"a","comment":"c"},
                       {"revid":2,"timestamp":"2026-07-01T00:00:00Z","user":"b","comment":"d"}
                       ]}]}}"""
                )
            } else {
                respondJson(
                    """{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano","revisions":[
                       {"revid":1,"timestamp":"2026-06-01T00:00:00Z","user":"c","comment":"e"}
                       ]}]}}"""
                )
            }
        }

        val history = service.history(page).toList()

        history.map { it.id.value } shouldBe listOf(3L, 2L, 1L)
        requests shouldBe 2
    }

    @Test
    fun `asking for one revision stops after the first request`() = runTest {
        var requests = 0
        val service = service {
            requests++
            respondJson(
                """{"continue":{"rvcontinue":"x","continue":"||"},
                   "query":{"pages":[{"pageid":1,"ns":0,"title":"volcano","revisions":[
                   {"revid":3,"timestamp":"2026-08-01T00:00:00Z","user":"a"}]}]}}"""
            )
        }

        val latest = service.history(page, limit = 1).toList()

        latest.single().id shouldBe RevisionId(3)
        requests shouldBe 1
    }

    @Test
    fun `a revision-deleted author is absent rather than empty`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano","revisions":[
                   {"revid":3,"timestamp":"2026-08-01T00:00:00Z","userhidden":true}]}]}}"""
            )
        }

        val revision = service.history(page).toList().single()

        revision.user.shouldBeNull()
        revision.isUserHidden shouldBe true
    }

    @Test
    fun `content can be read at an old revision`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano","revisions":[
                   {"revid":42,"timestamp":"2026-08-01T00:00:00Z",
                   "slots":{"main":{"content":"old text"}}}]}]}}"""
            )
        }

        val content = service.contentAt(RevisionId(42))

        content?.text shouldBe "old text"
        content?.revisionId shouldBe RevisionId(42)
    }

    @Test
    fun `revisions are looked up by id in batches`() = runTest {
        var requests = 0
        val service =
            service(batchSize = 2) {
                requests++
                respondJson(
                    """{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano","revisions":[
                   {"revid":$requests,"timestamp":"2026-08-01T00:00:00Z","user":"a"}]}]}}"""
                )
            }

        val found = service.byId(listOf(RevisionId(1), RevisionId(2), RevisionId(3)))

        requests shouldBe 2
        found.keys.map { it.value }.sorted() shouldBe listOf(1L, 2L)
    }

    @Test
    fun `a comparison returns the diff the wiki drew`() = runTest {
        val service = service {
            respondJson("""{"compare":{"fromrev":1,"torev":2,"body":"<tr>diff</tr>"}}""")
        }

        service.compare(RevisionId(1), RevisionId(2)) shouldBe "<tr>diff</tr>"
    }

    @Test
    fun `wiki-wide revisions arrive nested under their pages`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"allrevisions":[
                   {"pageid":1,"ns":0,"title":"Shanghainese","revisions":[
                     {"revid":92432082,"timestamp":"2026-09-01T18:14:55Z","user":"Someone",
                      "size":3513},
                     {"revid":92432081,"timestamp":"2026-09-01T18:00:00Z","user":"Other",
                      "size":3500}]}]}}"""
            )
        }

        val revisions = service.allRevisions().toList()

        revisions.map { it.id.value } shouldBe listOf(92432082L, 92432081L)
    }

    @Test
    fun `hiding and showing the same part of a revision is refused`() = runTest {
        val service = service { respondJson("{}") }

        shouldThrow<IllegalArgumentException> {
            service.revisionDelete(
                page = page,
                revisions = listOf(RevisionId(1)),
                hide = setOf(RevisionPart.COMMENT),
                show = setOf(RevisionPart.COMMENT),
            )
        }
    }

    @Test
    fun `suppression is sent only when asked, since nochange is the wiki's default`() = runTest {
        val bodies = mutableListOf<String>()
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
            } else {
                bodies += request.body.toByteArray().decodeToString()
                respondJson("""{"revisiondelete":{"status":"Success"}}""")
            }
        }

        service.revisionDelete(page, listOf(RevisionId(7)), hide = setOf(RevisionPart.CONTENT))
        service.revisionDelete(
            page,
            listOf(RevisionId(7)),
            hide = setOf(RevisionPart.CONTENT),
            suppress = true,
        )

        bodies[0].contains("suppress") shouldBe false
        bodies[1] shouldContain "suppress=yes"
        bodies[1] shouldContain "hide=content"
    }

    private fun TestScope.service(
        batchSize: Int = 50,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): RevisionService {
        val transport =
            KtorTransport(
                client = HttpClient(MockEngine(handler)),
                endpoint = ApiEndpoint("en.wiktionary.org"),
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            )
        return ApiRevisionService(
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
