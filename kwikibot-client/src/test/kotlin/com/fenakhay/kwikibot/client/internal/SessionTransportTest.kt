package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.auth.LoginManager
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.protocol.ApiFailure
import com.fenakhay.kwikibot.testkit.MockTransport
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A session that the wiki drops, and the transport that keeps it logged in.
 *
 * The fake wiki behind it answers an asserting request with `assertbotfailed` while the session is down, as
 * MediaWiki answers `assert=bot` once a session has ended and the request comes in anonymous.
 */
class SessionTransportTest {

    private class Wiki(val rights: String = """["edit","bot"]""") {
        var alive = true
        var logins = 0

        val transport = MockTransport { request -> answer(request) }

        private suspend fun answer(request: ApiRequest): JsonObject {
            // Lets concurrent callers interleave, as they would over a network.
            yield()
            val params = request.params
            return when {
                params["type"] == "login" -> json("""{"query":{"tokens":{"logintoken":"L+\\"}}}""")
                request.action == "login" -> {
                    alive = true
                    logins++
                    json("""{"login":{"result":"Success","lgusername":"Bot"}}""")
                }
                params["meta"] == "userinfo" ->
                    if (alive) {
                        json(
                            """{"query":{"userinfo":{"id":5,"name":"Bot",
                               "groups":["bot"],"rights":$rights}}}"""
                        )
                    } else {
                        json("""{"query":{"userinfo":{"id":0,"name":"192.0.2.1","anon":true}}}""")
                    }
                params["assert"] != null && !alive ->
                    json(
                        """{"errors":[{"code":"assertbotfailed",
                           "text":"You are not a bot.","module":"main"}]}"""
                    )
                else -> json("""{"ok":true,"assert":"${params["assert"]}"}""")
            }
        }

        private fun json(text: String) = MockTransport.json(text)
    }

    private suspend fun TestScope.session(wiki: Wiki, relogins: Int = 3): SessionTransport {
        val login =
            LoginManager(
                wiki.transport,
                Credentials.BotPassword("Bot", "kwikibot", "secret"),
                TokenStore(wiki.transport),
            )
        val identity = login.login()
        return SessionTransport(
            wiki.transport,
            login,
            identity,
            relogins = relogins,
            timeSource = testScheduler.timeSource,
        )
    }

    private suspend fun SessionTransport.read(): JsonObject =
        call(ApiRequest.of("query", "titles" to "volcano"))

    @Test
    fun `every request from a logged-in session asserts its account, unless it asserts one itself`() =
        runTest {
            val wiki = Wiki()
            val session = session(wiki)

            session.read()["assert"]?.jsonPrimitive?.content shouldBe "bot"
            session.call(ApiRequest.of("edit", "assert" to "user"))["assert"]?.jsonPrimitive?.content shouldBe
                "user"
        }

    @Test
    fun `a bot-group account whose bot password lacks the bot right asserts only that it is a user`() =
        runTest {
            val session = session(Wiki(rights = """["edit"]"""))

            session.read()["assert"]?.jsonPrimitive?.content shouldBe "user"
        }

    @Test
    fun `a dropped session is restored by logging in again, and the request is repeated`() = runTest {
        val wiki = Wiki()
        val relogged = mutableListOf<Identity>()
        val login =
            LoginManager(
                wiki.transport,
                Credentials.BotPassword("Bot", "kwikibot", "secret"),
                TokenStore(wiki.transport),
            )
        val session = SessionTransport(wiki.transport, login, login.login(), onRelogin = { relogged += it })

        wiki.alive = false
        val response = session.read()

        ApiFailure.from(response).shouldBeNull()
        wiki.logins shouldBe 2
        relogged.single().name shouldBe "Bot"
    }

    @Test
    fun `requests that find the session gone together log in once between them`() = runTest {
        val wiki = Wiki()
        val session = session(wiki)

        wiki.alive = false
        val responses = List(5) { async { session.read() } }.awaitAll()

        responses.all { ApiFailure.from(it) == null } shouldBe true
        wiki.logins shouldBe 2
    }

    @Test
    fun `a session that keeps being dropped stops being restored, and the failure goes back`() = runTest {
        val wiki = Wiki()
        val session = session(wiki, relogins = 2)

        repeat(2) {
            wiki.alive = false
            ApiFailure.from(session.read()).shouldBeNull()
        }
        wiki.alive = false
        val third = session.read()

        ApiFailure.from(third)?.code shouldBe "assertbotfailed"
        wiki.logins shouldBe 3
    }

    @Test
    fun `logins long enough ago no longer count against the cap`() = runTest {
        val wiki = Wiki()
        val session = session(wiki, relogins = 1)

        wiki.alive = false
        ApiFailure.from(session.read()).shouldBeNull()
        testScheduler.advanceTimeBy(SessionTransport.DEFAULT_WINDOW + 1.minutes)
        wiki.alive = false

        ApiFailure.from(session.read()).shouldBeNull()
        wiki.logins shouldBe 3
    }

    @Test
    fun `with re-login off, a dropped session is reported straight away`() = runTest {
        val wiki = Wiki()
        val session = session(wiki, relogins = 0)

        wiki.alive = false

        ApiFailure.from(session.read())?.code shouldBe "assertbotfailed"
        wiki.logins shouldBe 1
    }

    @Test
    fun `an anonymous session has nothing to assert`() = runTest {
        val wiki = Wiki()
        val login = LoginManager(wiki.transport, Credentials.Anonymous, TokenStore(wiki.transport))
        wiki.alive = false
        val session = SessionTransport(wiki.transport, login, login.login())

        session.read()["assert"]?.jsonPrimitive?.content shouldBe "null"
        session.endpoint.server shouldBe "test.example.org"
    }
}
