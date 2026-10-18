package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiRevisionService
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageContent
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
import com.fenakhay.kwikibot.wikitext.MagicWords
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
import kotlin.time.Instant
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
    fun `old revisions are read many to a request, following continuation`() = runTest {
        val sent = mutableListOf<Map<String, String>>()
        val service =
            service(batchSize = 3) { request ->
                sent += request.url.parameters.entries().associate { it.key to it.value.first() }
                when (sent.size) {
                    1 ->
                        respondJson(
                            """{"continue":{"rvcontinue":"12","continue":"||"},
                               "query":{"badrevids":{"99":{"revid":99,"missing":true}},
                               "pages":[{"pageid":1,"ns":0,"title":"volcano","revisions":[
                               {"revid":11,"timestamp":"2026-08-01T00:00:00Z",
                                "slots":{"main":{"content":"eleven"}}}]}]}}"""
                        )
                    2 ->
                        respondJson(
                            """{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano","revisions":[
                               {"revid":12,"timestamp":"2026-08-02T00:00:00Z",
                                "slots":{"main":{"content":"twelve"}}}]}]}}"""
                        )
                    else ->
                        respondJson(
                            """{"query":{"pages":[{"pageid":2,"ns":10,"title":"Template:l","revisions":[
                               {"revid":13,"timestamp":"2026-08-03T00:00:00Z",
                                "slots":{"main":{"texthidden":true}}},
                               {"revid":14,"timestamp":"2026-08-04T00:00:00Z",
                                "slots":{"main":{"content":"fourteen"}}}]}]}}"""
                        )
                }
            }

        val found = service.contentsAt(listOf(11, 12, 99, 13, 14, 11).map { RevisionId(it.toLong()) })

        sent.map { it["revids"] } shouldBe listOf("11|12|99", "11|12|99", "13|14")
        sent[1]["rvcontinue"] shouldBe "12"
        sent.first()["rvslots"] shouldBe "main"
        found.mapKeys { it.key.value }.mapValues { it.value.text } shouldBe
            mapOf(11L to "eleven", 12L to "twelve", 14L to "fourteen")
        found.getValue(RevisionId(14)).title.namespace shouldBe Namespace.TEMPLATE
    }

    @Test
    fun `an old revision is a redirect by its own text, not by what the page is now`() = runTest {
        val service =
            service(redirects = MagicWords(caseInsensitive = setOf("#WEITERLEITUNG", "#REDIRECT"))) {
                respondJson(
                    """{"query":{"pages":[
                       {"pageid":1,"ns":0,"title":"Vulkan","revisions":[
                        {"revid":1,"slots":{"main":{"content":"  #weiterleitung [[Feuerberg]]"}}},
                        {"revid":2,"slots":{"main":{"content":"== Vulkan ==\n# [[Berg]]"}}}]},
                       {"pageid":2,"ns":0,"title":"Asche","redirect":true,"revisions":[
                        {"revid":3,"slots":{"main":{"content":"Asche ist #REDIRECT [[x]]"}}},
                        {"revid":4,"slots":{"main":{"content":"#REDIRECT [[Staub]]"}}}]}]}}"""
                )
            }

        val found = service.contentsAt((1L..4L).map { RevisionId(it) })

        found.mapKeys { it.key.value }.mapValues { it.value.redirectTarget?.toString() } shouldBe
            mapOf(1L to "Feuerberg", 2L to null, 3L to null, 4L to "Staub")
    }

    @Test
    fun `nothing is asked for no revisions`() = runTest {
        var requests = 0
        val service = service {
            requests++
            respondJson("{}")
        }

        service.contentsAt(emptyList()) shouldBe emptyMap()
        requests shouldBe 0
    }

    @Test
    fun `a service that reads one revision at a time still reads several`() = runTest {
        val texts = mapOf(1L to "one", 2L to "two")
        val api = service { respondJson("{}") }
        val service =
            object : RevisionService by api {
                override suspend fun contentAt(revision: RevisionId): PageContent? =
                    texts[revision.value]?.let { PageContent(page, revision, it) }

                // Delegation would hand this to the API service too; the interface's own is the one tested.
                override suspend fun contentsAt(revisions: Collection<RevisionId>) =
                    super<RevisionService>.contentsAt(revisions)
            }

        val found = service.contentsAt(listOf(RevisionId(1), RevisionId(3), RevisionId(2)))

        found.mapKeys { it.key.value }.mapValues { it.value.text } shouldBe mapOf(1L to "one", 2L to "two")
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
    fun `deleted revisions across the wiki are narrowed by the wiki and arrive nested under their pages`() =
        runTest {
            var url = ""
            val service = service { request ->
                url = request.url.toString()
                respondJson(
                    """{"query":{"alldeletedrevisions":[
                       {"ns":2,"title":"User:Spammer","revisions":[
                         {"revid":7,"timestamp":"2026-09-01T00:00:00Z","user":"Spammer"},
                         {"revid":6,"timestamp":"2026-08-01T00:00:00Z","user":"Spammer"}]}]}}"""
                )
            }

            val all = service.allDeletedRevisions().toList()
            val narrowed =
                service
                    .allDeletedRevisions(
                        namespaces = setOf(Namespace.USER),
                        user = "Spammer",
                        order = HistoryOrder.OLDEST_FIRST,
                        start = Instant.parse("2026-01-01T00:00:00Z"),
                        end = Instant.parse("2026-12-31T00:00:00Z"),
                        limit = 1,
                    )
                    .toList()

            all.map { it.id.value } shouldBe listOf(7L, 6L)
            narrowed.map { it.id.value } shouldBe listOf(7L)
            url shouldContain "list=alldeletedrevisions"
            url shouldContain "adrnamespace=2"
            url shouldContain "adruser=Spammer"
            url shouldContain "adrdir=newer"
            url shouldContain "adrstart=2026-01-01T00%3A00%3A00Z"
            url shouldContain "adrend=2026-12-31T00%3A00%3A00Z"
            url shouldContain "adrlimit=1"
        }

    @Test
    fun `the deleted history of a page is read from the page, newest first unless asked otherwise`() =
        runTest {
            val asked = mutableListOf<String>()
            val service = service { request ->
                asked += request.url.toString()
                respondJson(
                    """{"query":{"pages":[{"ns":0,"title":"volcano","missing":true,"deletedrevisions":[
                       {"revid":4,"timestamp":"2026-03-01T00:00:00Z","user":"a"},
                       {"revid":3,"timestamp":"2026-02-01T00:00:00Z","user":"b"}]}]}}"""
                )
            }

            val history = service.deletedHistory(page).toList()
            service.deletedHistory(page, order = HistoryOrder.OLDEST_FIRST, limit = 5).toList()

            history.map { it.id.value } shouldBe listOf(4L, 3L)
            asked[0] shouldContain "prop=deletedrevisions"
            asked[0] shouldContain "titles=volcano"
            asked[0] shouldContain "drvdir=older"
            asked[0] shouldContain "drvlimit=max"
            asked[1] shouldContain "drvdir=newer"
            asked[1] shouldContain "drvlimit=5"
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
        redirects: MagicWords = MagicWords(caseInsensitive = setOf("#REDIRECT")),
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
            redirects = { redirects },
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
