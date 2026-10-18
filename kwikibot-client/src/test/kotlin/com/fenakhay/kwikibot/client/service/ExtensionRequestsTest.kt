package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiExtensionService
import com.fenakhay.kwikibot.client.model.AbuseFilter
import com.fenakhay.kwikibot.client.model.AbuseFilterFlags
import com.fenakhay.kwikibot.client.model.Coordinate
import com.fenakhay.kwikibot.client.model.FlaggedInfo
import com.fenakhay.kwikibot.client.model.LintError
import com.fenakhay.kwikibot.client.model.Notification
import com.fenakhay.kwikibot.client.model.TriageBacklog
import com.fenakhay.kwikibot.client.model.TriageStats
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.testkit.MockTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/** What the extension service asks each extension, and what it makes of the least an answer can say. */
class ExtensionRequestsTest {

    private val wiki = WikiId("enwiki")
    private val volcano = PageRef(wiki, Title.Local(Namespace.MAIN, "Volcano"))
    private val lava = PageRef(wiki, Title.Local(Namespace.MAIN, "Lava"))

    @Test
    fun `a coordinate that gives no position or globe is at the origin of the earth`() = runTest {
        val service =
            extensions(
                answer(
                    """{"query":{"pages":[{"ns":0,"title":"Volcano","coordinates":[{}]},
                       {"ns":0,"title":"Lava"}]}}"""
                )
            )

        service.coordinates(listOf(volcano, lava)) shouldBe
            mapOf(volcano to listOf(Coordinate(0.0, 0.0, "earth", isPrimary = false, type = null)))
    }

    @Test
    fun `asking about no pages asks the wiki nothing`() = runTest {
        val transport = answer("{}")

        extensions(transport).coordinates(emptyList()) shouldBe emptyMap()
        transport.requests shouldBe emptyList()
    }

    @Test
    fun `a page the wiki names that was not asked about, or names without a title, is left out`() = runTest {
        val service =
            extensions(
                answer(
                    """{"query":{"pages":[{"ns":0,"title":"Lava","pageprops":{"wikibase_item":"Q1"}},
                       {"pageid":3,"pageprops":{"wikibase_item":"Q2"}}]}}"""
                )
            )

        service.wikibaseItems(listOf(volcano)) shouldBe emptyMap()
    }

    @Test
    fun `a page with no extract is absent`() = runTest {
        extensions(answer("""{"query":{"pages":[{"ns":0,"title":"Volcano"}]}}"""))
            .extracts(listOf(volcano)) shouldBe emptyMap()
    }

    @Test
    fun `lint errors are asked for by category and limit, and a limit stops the reading`() = runTest {
        val transport =
            answer("""{"query":{"linterrors":[{"lintId":1,"category":"a"},{"lintId":2,"category":"a"}]}}""")
        val service = extensions(transport)

        service.lintErrors(category = "obsolete-tag", limit = 1).toList().map { it.id } shouldBe listOf(1L)
        service.lintErrors(limit = MORE_THAN_A_BATCH).toList().size shouldBe 2

        val (few, many) = transport.requests
        few.params["lntcategories"] shouldBe "obsolete-tag"
        few.params["lntlimit"] shouldBe "1"
        many.params["lntlimit"] shouldBe "max"
        many.params["lntcategories"].shouldBeNull()
    }

    @Test
    fun `a lint error that says almost nothing reads as unknown, and a location not two numbers as none`() =
        runTest {
            val service =
                extensions(
                    answer(
                        """{"query":{"linterrors":[{},{"location":[5]},{"location":["start",7]},
                           {"location":[3,9],"params":[]}]}}"""
                    )
                )

            val errors = service.lintErrors().toList()

            errors.first() shouldBe
                LintError(id = 0, category = "", page = null, range = null, details = emptyMap())
            errors.map { it.range } shouldBe listOf(null, null, null, 3..9)
            errors.last().details shouldBe emptyMap()
        }

    @Test
    fun `notifications are asked for unread only when wanted, and one that says nothing reads as unknown`() =
        runTest {
            val transport = answer("""{"query":{"notifications":{"list":[{}]}}}""")
            val service = extensions(transport)

            service.notifications(unreadOnly = true, limit = 5) shouldBe
                listOf(Notification(0, "", title = null, agent = null, timestamp = null, isRead = false))
            service.notifications()

            val (unread, every) = transport.requests
            unread.params["notfilter"] shouldBe "!read"
            unread.params["notlimit"] shouldBe "5"
            unread.params["notprop"] shouldBe "list"
            every.params["notfilter"].shouldBeNull()
        }

    @Test
    fun `an answer with no query at all is no notifications`() = runTest {
        extensions(answer("{}")).notifications() shouldBe emptyList()
    }

    @Test
    fun `an answer with no short url block is an error`() = runTest {
        shouldThrow<WikiError.Api> { extensions(answer("{}")).shortenUrl("https://example.org") }
            .code shouldBe "noshorturl"
    }

    @Test
    fun `a review state with no stable revision is absent, and one with only that has no level or wait`() =
        runTest {
            val service =
                extensions(
                    answer(
                        """{"query":{"pages":[{"ns":0,"title":"Volcano","flagged":{}},
                           {"ns":0,"title":"Lava","flagged":{"stable_revid":5}}]}}"""
                    )
                )

            service.flagged(listOf(volcano, lava)) shouldBe mapOf(lava to FlaggedInfo(stableRevisionId = 5))
        }

    @Test
    fun `a review with no flags and no comment sends neither`() = runTest {
        val transport = answer("""{"review":{"result":"Success"}}""")

        extensions(transport).review(RevisionId(9001))

        val review = transport.requests.single { it.action == "review" }
        review.params.keys shouldBe setOf("action", "revid", "token")
    }

    @Test
    fun `every abuse filter property goes out as the wiki spells it, with the id always asked for`() =
        runTest {
            val transport = abuseFilters("""{"query":{"abusefilters":[]}}""")
            val service = extensions(transport)

            for (property in AbuseFilterProperty.entries) {
                service.abuseFilters(properties = setOf(property)).toList()
            }

            transport.requests.filter { it.action == "query" }.map { it.params["abfprop"] } shouldBe
                listOf(
                    "id",
                    "description|id",
                    "pattern|id",
                    "actions|id",
                    "hits|id",
                    "comments|id",
                    "lasteditor|id",
                    "lastedittime|id",
                    "flags|id",
                    "flags|id",
                    "flags|id",
                    "flags|id",
                    "flags|id",
                )
        }

    @Test
    fun `abuse filters are asked for in the order and number wanted`() = runTest {
        val transport = abuseFilters("""{"query":{"abusefilters":[{"id":1},{"id":2}]}}""")
        val service = extensions(transport)

        service.abuseFilters(order = AbuseFilterOrder.HIGHEST_ID_FIRST, limit = 1).toList().map {
            it.id
        } shouldBe listOf(1L)
        service.abuseFilters(limit = MORE_THAN_A_BATCH).toList().size shouldBe 2

        val (few, many) = transport.requests.filter { it.action == "query" }
        few.params["abfdir"] shouldBe "older"
        few.params["abflimit"] shouldBe "1"
        few.params["abfshow"].shouldBeNull()
        many.params["abflimit"] shouldBe "max"
    }

    @Test
    fun `an abuse filter that says nothing has no description, actions, flags or redactions`() = runTest {
        val service = extensions(abuseFilters("""{"query":{"abusefilters":[{}]}}"""))

        val filter = service.abuseFilters(properties = setOf(AbuseFilterProperty.FLAGS)).toList().single()

        filter shouldBe AbuseFilter(id = 0, flags = AbuseFilterFlags(false, false, false, false, false))
    }

    @Test
    fun `an abuse filter flag is set by true, an empty string or a 1, and by nothing else`() = runTest {
        val shapes = listOf("true", "\"\"", "\"1\"", "false", "\"0\"", "null", "{}")
        val entries = shapes.mapIndexed { index, shape -> """{"id":$index,"enabled":$shape}""" }
        val service =
            extensions(abuseFilters("""{"query":{"abusefilters":[${entries.joinToString(",")}]}}"""))

        val enabled =
            service.abuseFilters(properties = setOf(AbuseFilterProperty.FLAGS)).toList().map {
                it.flags?.isEnabled
            }

        enabled shouldBe listOf(true, true, true, false, false, false, false)
    }

    @Test
    fun `abuse filter actions skip the gaps in a comma-separated list`() = runTest {
        val service =
            extensions(abuseFilters("""{"query":{"abusefilters":[{"id":1,"actions":"warn,,tag,"}]}}"""))

        service.abuseFilters().toList().single().actions shouldBe listOf("warn", "tag")
    }

    @Test
    fun `the new pages feed is asked for what was chosen, reviewed or not, and redirects or articles`() =
        runTest {
            val transport = answer("""{"pagetriagelist":{"result":"success","pages":[]}}""")
            val service = extensions(transport)

            service
                .newPages(namespace = Namespace.USER, reviewed = true, redirects = true, hideOwnPages = true)
                .toList()
            service.newPages().toList()

            val (reviewed, unreviewed) = transport.requests
            reviewed.params["namespace"] shouldBe "2"
            reviewed.params["showreviewed"] shouldBe "1"
            reviewed.params["showunreviewed"].shouldBeNull()
            reviewed.params["showothers"].shouldBeNull()
            reviewed.params["showdeleted"].shouldBeNull()
            reviewed.params["hideownpages"] shouldBe "1"
            reviewed.params["username"].shouldBeNull()
            unreviewed.params["showreviewed"].shouldBeNull()
            unreviewed.params["showunreviewed"] shouldBe "1"
            unreviewed.params["showothers"] shouldBe "1"
            unreviewed.params["hideownpages"].shouldBeNull()
        }

    @Test
    fun `an answer with no feed is the end of it`() = runTest {
        val transport = answer("{}")

        extensions(transport).newPages().toList() shouldBe emptyList()
        transport.requests.size shouldBe 1
    }

    @Test
    fun `a limit past one batch asks for the rest in the next`() = runTest {
        val transport = MockTransport { request ->
            val wanted = request.params.getValue("limit").toInt()
            val first = if ("offset" in request.params) 1000 else 0
            val pages =
                (first until first + wanted).joinToString(",") {
                    """{"pageid":$it,"title":"P$it","creation_date_utc":"20260930120000"}"""
                }
            MockTransport.json("""{"pagetriagelist":{"result":"success","pages":[$pages]}}""")
        }

        extensions(transport).newPages(limit = 250).toList().size shouldBe 250
        transport.requests.map { it.params["limit"] } shouldBe listOf("200", "50")
    }

    @Test
    fun `a feed entry with no title is skipped, and a reviewer hidden or empty is none`() = runTest {
        val service =
            extensions(
                answer(
                    """{"pagetriagelist":{"result":"success","pages":[
                       {"pageid":1},
                       {"pageid":2,"title":"Volcano","reviewer":"","creator_hidden":"0","user_name":"Alice"},
                       {"pageid":3,"title":"Lava","reviewer":"Carol","reviewer_hidden":"1","is_redirect":"1"}]}}"""
                )
            )

        val pages = service.newPages().toList()

        pages.map { it.page.title.text } shouldBe listOf("Volcano", "Lava")
        pages.map { it.reviewer } shouldBe listOf(null, null)
        pages.map { it.creator } shouldBe listOf("Alice", null)
        pages.map { it.isRedirect } shouldBe listOf(false, true)
        pages.map { it.created } shouldBe listOf(null, null)
        pages.map { it.isOrphan } shouldBe listOf(false, false)
    }

    @Test
    fun `statistics that leave out a queue count nothing in it, nor in a queue that says nothing`() =
        runTest {
            val transport =
                answer(
                    """{"pagetriagestats":{"result":"success","stats":{
                       "unrevieweddraft":{},"reviewedarticle":{}}}}"""
                )

            val stats = extensions(transport).newPageStats(namespace = Namespace(DRAFT), hideOwnPages = true)

            stats shouldBe
                TriageStats(
                    unreviewedArticles = TriageBacklog(0, null),
                    unreviewedRedirects = TriageBacklog(0, null),
                    unreviewedDrafts = TriageBacklog(0, null),
                    reviewedArticles = 0,
                    reviewedRedirects = 0,
                    matching = 0,
                )
            transport.requests.single().params["namespace"] shouldBe "118"
            transport.requests.single().params["hideownpages"] shouldBe "1"
        }

    @Test
    fun `statistics with no statistics block are an error`() = runTest {
        shouldThrow<WikiError.Api> { extensions(answer("{}")).newPageStats() }.code shouldBe "nostats"
    }

    private fun extensions(transport: MockTransport): ExtensionService =
        ApiExtensionService(
            transport = transport,
            tokens = TokenStore(transport),
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
            info =
                SiteInfo(
                    id = wiki,
                    siteName = "Wikipedia",
                    language = LangCode("en"),
                    server = "en.wikipedia.org",
                    articlePath = "/wiki/$1",
                    mainPage = "Main Page",
                    generator = "MediaWiki 1.47.0",
                    namespaces = NamespaceMap.CANONICAL,
                    interwiki = InterwikiMap.EMPTY,
                    extensions =
                        listOf(
                            "GeoData",
                            "WikibaseClient",
                            "TextExtracts",
                            "Linter",
                            "Echo",
                            "UrlShortener",
                            "FlaggedRevs",
                            "Abuse Filter",
                            "PageTriage",
                        ),
                ),
        )

    /** A wiki that hands out a token and answers every other request with [body]. */
    private fun answer(body: String) = MockTransport { request ->
        if (request.params["meta"] == "tokens") {
            MockTransport.json("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
        } else {
            MockTransport.json(body)
        }
    }

    /** A wiki whose AbuseFilter takes `flags`, answering every list request with [body]. */
    private fun abuseFilters(body: String) = MockTransport { request ->
        if (request.action == "paraminfo") {
            MockTransport.json(
                """{"paraminfo":{"modules":[{"name":"abusefilters","path":"query+abusefilters","prefix":"abf",
                   "source":"Abuse Filter","parameters":[{"name":"prop","multi":true,
                   "type":["actions","comments","description","flags","hits","id","lasteditor","lastedittime",
                           "pattern","private","protected","status","suppressed"],
                   "deprecatedvalues":["private","protected","status","suppressed"]}]}]}}"""
            )
        } else {
            MockTransport.json(body)
        }
    }

    private companion object {
        const val MORE_THAN_A_BATCH = 600

        /** The namespace English Wikipedia keeps drafts in, which PageTriage also lists. */
        const val DRAFT = 118
    }
}
