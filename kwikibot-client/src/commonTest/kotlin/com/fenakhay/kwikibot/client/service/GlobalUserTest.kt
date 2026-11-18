package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiExtensionService
import com.fenakhay.kwikibot.client.internal.ApiMetaService
import com.fenakhay.kwikibot.client.internal.ApiUserService
import com.fenakhay.kwikibot.client.model.GlobalUserInfo
import com.fenakhay.kwikibot.client.model.LocalAccount
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.user.BlockInfo
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.decode.ActivityDecoder
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.testkit.MockTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/** What a global account is asked for with, and what CentralAuth's answer becomes. */
class GlobalUserTest {

    private val wiki = WikiId("enwiki")

    @Test
    fun `this session's own account is asked for with the cheap properties unless told otherwise`() =
        runTest {
            val transport = answer("""{"query":{"globaluserinfo":{"id":1843,"name":"Jimbo Wales"}}}""")
            val service = extensions(transport)

            service.globalUser()
            service.globalUser("Jimbo Wales", setOf(GlobalUserProperty.MERGED, GlobalUserProperty.UNATTACHED))
            service.globalUser("Jimbo Wales", emptySet())

            transport.requests.map { it.params } shouldBe
                listOf(
                    mapOf("action" to "query", "meta" to "globaluserinfo", "guiprop" to "groups|editcount"),
                    mapOf(
                        "action" to "query",
                        "meta" to "globaluserinfo",
                        "guiuser" to "Jimbo Wales",
                        "guiprop" to "merged|unattached",
                    ),
                    mapOf("action" to "query", "meta" to "globaluserinfo", "guiuser" to "Jimbo Wales"),
                )
        }

    @Test
    fun `everything CentralAuth says about an account is read`() = runTest {
        val service = extensions(answer(JIMBO))

        service.globalUser("Jimbo Wales", GlobalUserProperty.entries.toSet()) shouldBe
            GlobalUserInfo(
                name = "Jimbo Wales",
                id = 1843,
                home = WikiId("enwiki"),
                registration = Instant.parse("2008-04-12T16:22:49Z"),
                isLocked = true,
                isHidden = true,
                groups = listOf("founder"),
                rights = listOf("read", "edit"),
                editCount = 16306,
                merged =
                    listOf(
                        LocalAccount(
                            wiki = WikiId("commonswiki"),
                            editCount = 214,
                            registration = Instant.parse("2008-04-12T16:22:50Z"),
                            groups = listOf("autopatrolled", "filemover"),
                            url = "https://commons.wikimedia.org",
                            id = 121,
                            attached = Instant.parse("2008-04-12T16:23:10Z"),
                            attachMethod = "password",
                        ),
                        LocalAccount(
                            wiki = WikiId("acewiki"),
                            registration = Instant.parse("2010-07-24T23:10:06Z"),
                            block = BlockInfo(reason = "spam"),
                            url = "https://ace.wikipedia.org",
                            id = 2511,
                            attached = Instant.parse("2010-07-24T23:10:07Z"),
                            attachMethod = "login",
                        ),
                    ),
                unattached =
                    listOf(
                        LocalAccount(
                            wiki = WikiId("testwiki"),
                            editCount = 3,
                            registration = Instant.parse("2006-01-01T00:00:00Z"),
                            groups = listOf("sysop"),
                            block =
                                BlockInfo(reason = "test", expiry = Instant.parse("2027-01-01T00:00:00Z")),
                        )
                    ),
            )
    }

    @Test
    fun `an account that says almost nothing reads as what little it said`() = runTest {
        val account =
            extensions(answer("""{"query":{"globaluserinfo":{"home":"","merged":[{"wiki":"enwiki"}]}}}"""))
                .globalUser("Somebody")

        account shouldBe
            GlobalUserInfo(
                name = "",
                id = 0,
                home = null,
                registration = null,
                merged = listOf(LocalAccount(WikiId("enwiki"))),
            )
        checkNotNull(account).merged.single().block.shouldBeNull()
    }

    @Test
    fun `no such account is null and so is an answer with nothing about one`() = runTest {
        extensions(answer("""{"batchcomplete":true,"query":{"globaluserinfo":{"missing":true}}}"""))
            .globalUser("Nobody has this name")
            .shouldBeNull()
        extensions(answer("""{"query":{}}""")).globalUser("Nobody").shouldBeNull()
        extensions(answer("{}")).globalUser("Nobody").shouldBeNull()
    }

    @Test
    fun `a name no account can have is the wiki's error`() = runTest {
        val refused =
            shouldThrow<WikiError.Api> {
                extensions(
                        answer(
                            """{"errors":[{"code":"invaliduser","text":"Invalid username \"192.0.2.1\".",
                               "module":"query+globaluserinfo"}]}"""
                        )
                    )
                    .globalUser()
            }

        refused.code shouldBe "invaliduser"
    }

    @Test
    fun `a wiki without CentralAuth says so and is asked nothing`() = runTest {
        val transport = answer("{}")

        shouldThrow<WikiError.Configuration.MissingExtension> {
                extensions(transport, emptyList()).globalUser("Jimbo Wales")
            }
            .extension shouldBe "CentralAuth"
        transport.requests shouldBe emptyList()
    }

    @Test
    fun `an extension service of anyone else's cannot read global accounts unless it says how`() = runTest {
        val api = extensions(answer("{}"))
        val service =
            object : ExtensionService by api {
                override suspend fun globalUser(
                    name: String?,
                    properties: Set<GlobalUserProperty>,
                ): GlobalUserInfo? = super<ExtensionService>.globalUser(name, properties)
            }

        shouldThrow<UnsupportedOperationException> { service.globalUser() }.message shouldBe
            "this ExtensionService cannot read global accounts"
    }

    @Test
    fun `the README's sample keeps a preference reads the clock emails and looks an account up`() = runTest {
        val transport = MockTransport { request ->
            MockTransport.json(
                when {
                    request.params["meta"] == "tokens" -> """{"query":{"tokens":{"csrftoken":"TOKEN"}}}"""
                    request.action == "options" -> """{"options":"success"}"""
                    request.action == "emailuser" -> """{"emailuser":{"result":"Success"}}"""
                    request.params["curtimestamp"] == "1" -> """{"curtimestamp":"2026-11-04T10:02:45Z"}"""
                    request.params["meta"] == "globaluserinfo" ->
                        """{"query":{"globaluserinfo":{"home":"enwiki","id":7,"name":"Example","editcount":12}}}"""
                    else -> """{"query":{"userinfo":{"options":{"userjs-mybot-last-run":"2026-11-04"}}}}"""
                }
            )
        }
        val users =
            ApiUserService(
                transport,
                TokenStore(transport),
                ActivityDecoder(PageDecoder(wiki, NamespaceMap.CANONICAL)),
            )
        val meta = ApiMetaService(transport, TokenStore(transport))
        val extensions = extensions(transport)
        val printed = mutableListOf<String>()

        val refused = users.setPreferences(mapOf("userjs-mybot-last-run" to "2026-11-04", "rclimit" to "500"))
        val lastRun = users.preferences()["userjs-mybot-last-run"]
        val drift = meta.serverTime() - Clock.System.now()

        when (val sent = users.email("Example", "Your bot request", "It has been approved.")) {
            is EmailOutcome.Sent -> printed += "sent"
            is EmailOutcome.Undeliverable -> printed += "not sent: ${sent.reason}"
        }

        val global = extensions.globalUser("Example")
        printed += "${global?.home}: ${global?.editCount} edits"

        refused shouldBe emptyMap()
        lastRun shouldBe "2026-11-04"
        (Clock.System.now() + drift - Instant.parse("2026-11-04T10:02:45Z")).absoluteValue shouldBeLessThan
            1.minutes
        printed shouldBe listOf("sent", "enwiki: 12 edits")
    }

    private fun extensions(
        transport: MockTransport,
        installed: List<String> = listOf(ExtensionService.CENTRAL_AUTH),
    ): ExtensionService =
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
                    extensions = installed,
                ),
        )

    private fun answer(body: String) = MockTransport { MockTransport.json(body) }

    private companion object {
        /** A long-standing account as CentralAuth describes it, with one local account of each kind. */
        const val JIMBO =
            """{"batchcomplete":true,"query":{"globaluserinfo":{"home":"enwiki","id":1843,
                "registration":"2008-04-12T16:22:49Z","name":"Jimbo Wales","locked":true,"hidden":true,
                "groups":["founder"],"rights":["read","edit"],"editcount":16306,
                "merged":[
                  {"wiki":"commonswiki","url":"https://commons.wikimedia.org","id":121,
                   "timestamp":"2008-04-12T16:23:10Z","method":"password","editcount":214,
                   "registration":"2008-04-12T16:22:50Z","groups":["autopatrolled","filemover"]},
                  {"wiki":"acewiki","url":"https://ace.wikipedia.org","id":2511,"timestamp":"2010-07-24T23:10:07Z",
                   "method":"login","editcount":0,"registration":"2010-07-24T23:10:06Z",
                   "blocked":{"expiry":"infinity","reason":"spam"}},
                  {"url":"https://nowhere.example.org"},
                  {"wiki":""}],
                "unattached":[{"wiki":"testwiki","editcount":3,"registration":"2006-01-01T00:00:00Z",
                   "groups":["sysop"],"blocked":{"expiry":"2027-01-01T00:00:00Z","reason":"test"}}]}}}"""
    }
}
