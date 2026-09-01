package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.Expiry
import com.fenakhay.kwikibot.model.LogDetails
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.protocol.ActivityDecoder
import com.fenakhay.kwikibot.protocol.OptionSet
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration

class UserAndLogServiceTest {

    private val wiki = WikiId("enwiktionary")

    @Test
    fun `users are keyed by the name that was asked for`() = runTest {
        val transport = transport {
            respondJson(
                """{"query":{"users":[{"userid":1,"name":"Equinox","editcount":5,
                   "groups":["user"],"rights":["edit"]}]}}""",
            )
        }

        val found = users(transport).info(listOf("equinox"))

        found.keys shouldBe setOf("equinox")
        found.getValue("equinox").hasRight("edit") shouldBe true
    }

    @Test
    fun `a blocked account carries its block`() = runTest {
        val transport = transport {
            respondJson(
                """{"query":{"users":[{"userid":2,"name":"Spammer","blockid":9,
                   "blockedby":"Admin","blockreason":"spam","blockexpiry":"infinity"}]}}""",
            )
        }

        val user = users(transport).info(listOf("Spammer")).getValue("Spammer")

        user.isBlocked shouldBe true
        checkNotNull(user.block).isInfinite shouldBe true
        checkNotNull(user.block).by shouldBe "Admin"
    }

    @Test
    fun `the session reports the rights it actually has`() = runTest {
        val transport = transport {
            respondJson(
                """{"query":{"userinfo":{"id":7,"name":"FenaBot","groups":["bot"],
                   "rights":["edit","bot"],"editcount":12}}}""",
            )
        }

        val me = users(transport).current()

        me.name shouldBe "FenaBot"
        me.hasRight("bot") shouldBe true
        me.hasRight("delete") shouldBe false
    }

    @Test
    fun `contributions are decoded with their pages`() = runTest {
        val transport = transport {
            respondJson(
                """{"query":{"usercontribs":[
                   {"userid":1,"user":"FenaBot","pageid":3,"revid":10,"parentid":0,"ns":0,
                    "title":"volcano","timestamp":"2026-08-01T00:00:00Z","new":true,
                    "comment":"c","size":80,"sizediff":80}]}}""",
            )
        }

        val edits = users(transport).contributions("FenaBot").toList()

        edits.single().page.title.text shouldBe "volcano"
        edits.single().isNew shouldBe true
        edits.single().sizeChange shouldBe 80
    }

    @Test
    fun `contribution filters are applied by the wiki`() = runTest {
        var url = ""
        val transport = transport { request ->
            url = request.url.toString()
            respondJson("""{"query":{"usercontribs":[]}}""")
        }

        users(transport).contributions(
            "FenaBot",
            show = OptionSet().on("new").off("minor"),
        ).toList()

        url.contains("ucshow=new%7C%21minor") shouldBe true
    }

    @Test
    fun `no filter means no show parameter at all`() = runTest {
        var url = ""
        val transport = transport { request ->
            url = request.url.toString()
            respondJson("""{"query":{"usercontribs":[]}}""")
        }

        users(transport).contributions("FenaBot").toList()

        url.contains("ucshow") shouldBe false
    }

    @Test
    fun `a block states its expiry and what it prevents`() = runTest {
        var body = ""
        val transport = transport { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"block":{"user":"Spammer","expiry":"infinite"}}""")
            }
        }

        users(transport).block("Spammer", reason = "spam", expiry = Expiry.Never)

        body.contains("action=block") shouldBe true
        body.contains("expiry=infinity") shouldBe true
        body.contains("nocreate=1") shouldBe true
        body.contains("anononly=0") shouldBe true
    }

    @Test
    fun `blocks are enumerated with their targets`() = runTest {
        val transport = transport {
            respondJson(
                """{"query":{"blocks":[
                   {"id":25494340,"user":"203.0.113.5","by":"Admin",
                    "timestamp":"2026-09-01T03:12:47Z","expiry":"2026-09-02T03:12:47Z",
                    "reason":"Autoblocked","automatic":true},
                   {"id":25494341,"user":"203.0.113.0/24","by":"Admin",
                    "timestamp":"2026-09-01T03:00:00Z","expiry":"infinity","reason":"range"}]}}""",
            )
        }

        val blocks = users(transport).blocks().toList()

        blocks.first().target shouldBe "203.0.113.5"
        blocks.first().isAutomatic shouldBe true
        blocks.first().info.by shouldBe "Admin"
        blocks.last().isRange shouldBe true
        blocks.last().info.isInfinite shouldBe true
    }

    @Test
    fun `a suppressed target is absent rather than empty`() = runTest {
        val transport = transport {
            respondJson("""{"query":{"blocks":[{"id":1,"by":"Admin","reason":"x"}]}}""")
        }

        users(transport).blocks().toList().single().target.shouldBeNull()
    }

    @Test
    fun `the block list can be narrowed to kinds and targets`() = runTest {
        var url = ""
        val transport = transport { request ->
            url = request.url.toString()
            respondJson("""{"query":{"blocks":[]}}""")
        }

        users(transport).blocks(
            users = listOf("203.0.113.5"),
            show = OptionSet().on("ip").off("temp"),
        ).toList()

        url.contains("bkusers=203.0.113.5") shouldBe true
        url.contains("bkshow=ip%7C%21temp") shouldBe true
    }

    @Test
    fun `a log query asks for the log it was given`() = runTest {
        var url = ""
        val transport = transport { request ->
            url = request.url.toString()
            respondJson(
                """{"query":{"logevents":[{"logid":1,"ns":0,"title":"a","type":"move",
                   "action":"move","user":"u","timestamp":"2026-08-01T00:00:00Z","comment":"c",
                   "params":{"target_ns":0,"target_title":"b","suppressredirect":true}}]}}""",
            )
        }

        val events = logs(transport).events(type = "move", limit = 1).toList()

        url.contains("letype=move") shouldBe true
        val details = events.single().details.shouldBeInstanceOf<LogDetails.Move>()
        details.target.text shouldBe "b"
        details.suppressedRedirect shouldBe true
    }

    @Test
    fun `an action names its own log, so the type is not sent alongside it`() = runTest {
        var url = ""
        val transport = transport { request ->
            url = request.url.toString()
            respondJson("""{"query":{"logevents":[]}}""")
        }

        logs(transport).events(type = "move", action = "move_redir").toList()

        url.contains("leaction=move_redir") shouldBe true
        url.contains("letype=") shouldBe false
    }

    @Test
    fun `excluding bots and minor edits is sent as one show parameter`() = runTest {
        var url = ""
        val transport = transport { request ->
            url = request.url.toString()
            respondJson("""{"query":{"recentchanges":[]}}""")
        }

        logs(transport).recentChanges(
            namespaces = setOf(Namespace.MAIN),
            show = OptionSet().off("bot").off("minor"),
        ).toList()

        url.contains("rcshow=%21bot%7C%21minor") shouldBe true
        url.contains("rcnamespace=0") shouldBe true
    }

    @Test
    fun `a tag filter and top-only reach the wire`() = runTest {
        var url = ""
        val transport = transport { request ->
            url = request.url.toString()
            respondJson("""{"query":{"recentchanges":[]}}""")
        }

        logs(transport).recentChanges(tag = "mw-reverted", topOnly = true).toList()

        url.contains("rctag=mw-reverted") shouldBe true
        url.contains("rctoponly=1") shouldBe true
    }

    @Test
    fun `the watchlist is read as changes, not as titles`() = runTest {
        var url = ""
        val transport = transport { request ->
            url = request.url.toString()
            respondJson("""{"query":{"watchlist":[]}}""")
        }

        logs(transport).watchlistChanges(allRevisions = true).toList()

        url.contains("list=watchlist") shouldBe true
        url.contains("wlallrev=1") shouldBe true
    }

    private fun TestScope.transport(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): MediaWikiTransport = KtorTransport(
        client = HttpClient(MockEngine(handler)),
        endpoint = ApiEndpoint("en.wiktionary.org"),
        userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
        throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
        retry = RetryPolicy.NONE,
    )

    @Test
    fun `changing group membership uses its own token type, not csrf`() = runTest {
        var asked = ""
        var body = ""
        val transport = transport { request ->
            if (request.url.parameters["meta"] == "tokens") {
                asked = request.url.parameters["type"].orEmpty()
                respondJson("""{"query":{"tokens":{"userrightstoken":"UR"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"userrights":{"user":"Someone","added":["sysop"]}}""")
            }
        }

        users(transport).changeRights("Someone", add = setOf("sysop"), reason = "RfA")

        asked shouldBe "userrights"
        body shouldContain "token=UR"
        body shouldContain "add=sysop"
    }

    @Test
    fun `a change that neither adds nor removes anything is refused`() = runTest {
        val transport = transport { respondJson("{}") }

        shouldThrow<IllegalArgumentException> { users(transport).changeRights("Someone") }
    }

    private fun users(transport: MediaWikiTransport): UserService = ApiUserService(
        transport = transport,
        tokens = TokenStore(transport),
        activity = ActivityDecoder(PageDecoder(wiki, NamespaceMap.CANONICAL)),
    )

    private fun logs(transport: MediaWikiTransport): LogService = ApiLogService(
        transport = transport,
        activity = ActivityDecoder(PageDecoder(wiki, NamespaceMap.CANONICAL)),
        namespaces = NamespaceMap.CANONICAL,
    )

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
