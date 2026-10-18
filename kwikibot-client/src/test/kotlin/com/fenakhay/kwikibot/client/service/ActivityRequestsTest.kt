package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiLogService
import com.fenakhay.kwikibot.client.internal.ApiRevisionService
import com.fenakhay.kwikibot.client.internal.ApiUserService
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.model.user.UserInfo
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.protocol.decode.ActivityDecoder
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.testkit.MockTransport
import com.fenakhay.kwikibot.wikitext.MagicWords
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/** The filters users, logs and revisions are asked for with, sent only when chosen. */
class ActivityRequestsTest {

    private val wiki = WikiId("enwiktionary")
    private val volcano = PageRef(wiki, Title.Local(Namespace.MAIN, "volcano"))
    private val start = Instant.parse("2026-09-01T00:00:00Z")
    private val end = Instant.parse("2026-09-30T00:00:00Z")
    private val articlesAndTemplates = setOf(Namespace.MAIN, Namespace.TEMPLATE)

    @Test
    fun `users are found however the wiki capitalizes them, and one it has no name for is not`() = runTest {
        val service = users(answer("""{"query":{"users":[{"name":"Alice"},{"name":"Bob"},{}]}}"""))

        val found = service.info(listOf("alice", "Bob", "Nobody"))

        found.keys shouldBe setOf("alice", "Bob")
        found.getValue("alice").name shouldBe "Alice"
    }

    @Test
    fun `asking about no users or no revisions asks the wiki nothing`() = runTest {
        val transport = answer("{}")

        users(transport).info(emptyList()) shouldBe emptyMap()
        revisions(transport).byId(emptyList()) shouldBe emptyMap()
        transport.requests shouldBe emptyList()
    }

    @Test
    fun `an answer with no user information is an anonymous user`() = runTest {
        val anonymous = UserInfo(name = "", isAnonymous = true)

        users(answer("{}")).current() shouldBe anonymous
        users(answer("""{"query":{}}""")).current() shouldBe anonymous
    }

    @Test
    fun `contributions and blocks send their namespaces and time bounds only when given`() = runTest {
        val transport = answer("""{"query":{"usercontribs":[],"blocks":[]}}""")
        val service = users(transport)

        service.contributions("Alice", namespaces = articlesAndTemplates, start = start, end = end).toList()
        service.contributions("Alice").toList()
        service.blocks(start = start, end = end).toList()
        service.blocks().toList()

        val (bounded, unbounded, boundedBlocks) = transport.requests
        val unboundedBlocks = transport.requests.last()
        bounded.params["ucnamespace"] shouldBe "0|10"
        bounded.params["ucstart"] shouldBe MwTimestamp.format(start)
        bounded.params["ucend"] shouldBe MwTimestamp.format(end)
        unbounded.params.keys.intersect(setOf("ucnamespace", "ucstart", "ucend")) shouldBe emptySet()
        boundedBlocks.params["bkstart"] shouldBe MwTimestamp.format(start)
        boundedBlocks.params["bkend"] shouldBe MwTimestamp.format(end)
        unboundedBlocks.params.keys.intersect(setOf("bkstart", "bkend")) shouldBe emptySet()
    }

    @Test
    fun `a change of rights sends only the side that changes, and an end only when given`() = runTest {
        val transport = answer("""{"userrights":{}}""", token = "userrightstoken")
        val service = users(transport)

        service.changeRights("Alice", add = setOf("bot"), expiry = "1 month")
        service.changeRights("Alice", remove = setOf("bot"))

        val (added, removed) = transport.requests.filter { it.action == "userrights" }
        added.params["add"] shouldBe "bot"
        added.params["remove"].shouldBeNull()
        added.params["expiry"] shouldBe "1 month"
        removed.params["add"].shouldBeNull()
        removed.params["remove"] shouldBe "bot"
        removed.params["expiry"].shouldBeNull()
    }

    @Test
    fun `a group cannot be added and removed at once`() = runTest {
        shouldThrow<IllegalArgumentException> {
            users(answer("{}")).changeRights("Alice", add = setOf("bot"), remove = setOf("bot"))
        }
    }

    @Test
    fun `a block sends each restriction as chosen`() = runTest {
        val transport = answer("""{"block":{}}""")
        val service = users(transport)

        service.block("Vandal", "spam", Expiry.Never)
        service.block(
            "Vandal",
            "spam",
            anonymousOnly = true,
            preventAccountCreation = false,
            autoBlock = false,
        )

        val (strict, light) = transport.requests.filter { it.action == "block" }
        strict.params["anononly"] shouldBe "0"
        strict.params["nocreate"] shouldBe "1"
        strict.params["autoblock"] shouldBe "1"
        light.params["anononly"] shouldBe "1"
        light.params["nocreate"].shouldBeNull()
        light.params["autoblock"].shouldBeNull()
    }

    @Test
    fun `a log is asked for by type only when no action names it already`() = runTest {
        val transport = answer("""{"query":{"logevents":[]}}""")
        val service = logs(transport)

        service.events(type = "delete").toList()
        service.events(type = "delete", action = "delete/restore").toList()
        service.events().toList()

        val (typed, acted, every) = transport.requests
        typed.params["letype"] shouldBe "delete"
        acted.params["letype"].shouldBeNull()
        acted.params["leaction"] shouldBe "delete/restore"
        every.params["letype"].shouldBeNull()
    }

    @Test
    fun `a log is asked about one page, some namespaces and a time range only when given`() = runTest {
        val transport = answer("""{"query":{"logevents":[]}}""")

        logs(transport)
            .events(page = volcano, namespaces = articlesAndTemplates, start = start, end = end)
            .toList()

        val sent = transport.requests.single().params
        sent["letitle"] shouldBe "volcano"
        sent["lenamespace"] shouldBe "0|10"
        sent["lestart"] shouldBe MwTimestamp.format(start)
        sent["leend"] shouldBe MwTimestamp.format(end)
    }

    @Test
    fun `recent and watched changes send their types and time range only when given`() = runTest {
        val transport = answer("""{"query":{"recentchanges":[],"watchlist":[]}}""")
        val service = logs(transport)
        val types = setOf("edit", "new")

        service.recentChanges(types = types, start = start, end = end).toList()
        service
            .watchlistChanges(namespaces = articlesAndTemplates, types = types, start = start, end = end)
            .toList()
        service.watchlistChanges().toList()

        val (recent, watched, plain) = transport.requests
        recent.params["rctype"] shouldBe "edit|new"
        recent.params["rcstart"] shouldBe MwTimestamp.format(start)
        recent.params["rcend"] shouldBe MwTimestamp.format(end)
        watched.params["wlnamespace"] shouldBe "0|10"
        watched.params["wltype"] shouldBe "edit|new"
        watched.params["wlstart"] shouldBe MwTimestamp.format(start)
        watched.params["wlend"] shouldBe MwTimestamp.format(end)
        plain.params.keys.intersect(setOf("wlnamespace", "wltype", "wlstart", "wlend")) shouldBe emptySet()
    }

    @Test
    fun `a history and every revision send their time range and namespaces only when given`() = runTest {
        val transport = answer("""{"query":{"pages":[],"allrevisions":[]}}""")
        val service = revisions(transport)

        service.history(volcano, start = start, end = end).toList()
        service.allRevisions(namespaces = articlesAndTemplates, start = start, end = end).toList()
        service.allRevisions().toList()

        val (history, bounded, every) = transport.requests
        history.params["rvstart"] shouldBe MwTimestamp.format(start)
        history.params["rvend"] shouldBe MwTimestamp.format(end)
        bounded.params["arvnamespace"] shouldBe "0|10"
        bounded.params["arvstart"] shouldBe MwTimestamp.format(start)
        bounded.params["arvend"] shouldBe MwTimestamp.format(end)
        every.params.keys.intersect(setOf("arvnamespace", "arvstart", "arvend")) shouldBe emptySet()
    }

    @Test
    fun `a comparison with no body is empty`() = runTest {
        revisions(answer("{}")).compare(RevisionId(1), RevisionId(2)) shouldBe ""
        revisions(answer("""{"compare":{}}""")).compare(RevisionId(1), RevisionId(2)) shouldBe ""
    }

    @Test
    fun `a revision deletion needs revisions and a part to change, and sends only the side that changes`() =
        runTest {
            val transport = answer("""{"revisiondelete":{}}""")
            val service = revisions(transport)

            shouldThrow<IllegalArgumentException> {
                service.revisionDelete(volcano, emptyList(), hide = setOf(RevisionPart.CONTENT))
            }
            shouldThrow<IllegalArgumentException> { service.revisionDelete(volcano, listOf(RevisionId(1))) }
            service.revisionDelete(volcano, listOf(RevisionId(1)), hide = setOf(RevisionPart.USER))
            service.revisionDelete(volcano, listOf(RevisionId(1)), show = setOf(RevisionPart.COMMENT))

            val (hidden, shown) = transport.requests.filter { it.action == "revisiondelete" }
            hidden.params["hide"] shouldBe "user"
            hidden.params["show"].shouldBeNull()
            shown.params["hide"].shouldBeNull()
            shown.params["show"] shouldBe "comment"
        }

    @Test
    fun `a redirect spelled with a case-sensitive word is one only in that case`() = runTest {
        val transport =
            answer(
                """{"query":{"pages":[{"ns":0,"title":"volcano","revisions":[
               {"revid":1,"slots":{"main":{"content":"  #WEITERLEITUNG [[lava]]"}}},
               {"revid":2,"slots":{"main":{"content":"#weiterleitung [[lava]]"}}}]}]}}"""
            )
        val service = revisions(transport, MagicWords(caseSensitive = setOf("#WEITERLEITUNG")))

        val contents = service.contentsAt(listOf(RevisionId(1), RevisionId(2)))

        contents.getValue(RevisionId(1)).isRedirect shouldBe true
        contents.getValue(RevisionId(2)).isRedirect shouldBe false
    }

    private fun users(transport: MockTransport): UserService =
        ApiUserService(
            transport = transport,
            tokens = TokenStore(transport),
            activity = ActivityDecoder(PageDecoder(wiki, NamespaceMap.CANONICAL)),
        )

    private fun logs(transport: MockTransport): LogService =
        ApiLogService(
            transport = transport,
            activity = ActivityDecoder(PageDecoder(wiki, NamespaceMap.CANONICAL)),
            namespaces = NamespaceMap.CANONICAL,
            identity = Identity("192.0.2.1", 0),
        )

    private fun revisions(transport: MockTransport, redirects: MagicWords = MagicWords()): RevisionService =
        ApiRevisionService(
            transport = transport,
            tokens = TokenStore(transport),
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
            redirects = { redirects },
        )

    /** A wiki that hands out a [token] and answers every other request with [body]. */
    private fun answer(body: String, token: String = "csrftoken") = MockTransport { request ->
        if (request.params["meta"] == "tokens") {
            MockTransport.json("""{"query":{"tokens":{"$token":"TOKEN"}}}""")
        } else {
            MockTransport.json(body)
        }
    }
}
