package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiUserService
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.decode.ActivityDecoder
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.testkit.MockTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest

/** What reading and changing preferences and emailing a user send, and what each answer becomes. */
class PreferenceAndEmailTest {

    @Test
    fun `preferences are read fresh and every value comes back as text`() = runTest {
        val transport =
            answer(
                """{"query":{"userinfo":{"id":1,"name":"Admin","options":{
                   "skin":"vector","rclimit":50,"search-match-redirect":true,"hideminor":false,
                   "nickname":null,"odd":{"a":1},"ratio":"0.5"}}}}"""
            )

        val preferences = users(transport).preferences()

        preferences shouldBe
            mapOf(
                "skin" to "vector",
                "rclimit" to "50",
                "search-match-redirect" to "1",
                "hideminor" to "0",
                "nickname" to "",
                "odd" to """{"a":1}""",
                "ratio" to "0.5",
            )
        val sent = transport.requests.single()
        sent.params shouldBe mapOf("action" to "query", "meta" to "userinfo", "uiprop" to "options")
        sent.cacheable shouldBe false
        sent.isWrite shouldBe false
    }

    @Test
    fun `an answer with no preferences is none`() = runTest {
        users(answer("""{"query":{"userinfo":{"id":1}}}""")).preferences() shouldBe emptyMap()
        users(answer("{}")).preferences() shouldBe emptyMap()
        shouldThrow<WikiError.Auth.PermissionDenied> { users(answer(PERMISSION_DENIED)).preferences() }
    }

    @Test
    fun `changes go as one write split at bars with a null value put back to the default`() = runTest {
        val transport = answer("""{"options":"success"}""")

        val refused =
            users(transport).setPreferences(mapOf("skin" to "vector", "rclimit" to null, "math" to ""))

        refused shouldBe emptyMap()
        val sent = transport.writes().single()
        sent.params shouldBe
            mapOf(
                "action" to "options",
                "change" to "skin=vector|rclimit|math=",
                "assert" to "user",
                "token" to "TOKEN",
            )
        sent.kind shouldBe RequestKind.WRITE
    }

    @Test
    fun `a value holding a bar is sent behind the unit separator`() = runTest {
        val transport = answer("""{"options":"success"}""")

        users(transport).setPreferences(mapOf("nickname" to "[[User:A|A]]", "skin" to "vector"))

        transport.writes().single().params["change"] shouldBe "\u001fnickname=[[User:A|A]]\u001fskin=vector"
    }

    @Test
    fun `the preferences the wiki refused are found by name in its warnings`() = runTest {
        val transport =
            answer(
                """{"warnings":[
                   {"code":"validationfailed","module":"options",
                    "text":"Validation error for \"searchNs10\": not a valid preference."},
                   {"code":"validationfailed","module":"options",
                    "text":"Validation error for \"gender\": The value you specified is not a valid option."},
                   {"code":"global-option-ignored","module":"options",
                    "text":"Option skin-x is globally overridden."},
                   {"code":"validationfailed","module":"options","text":"Validation error for something else."},
                   {"code":"deprecation","module":"main","text":"gender is deprecated."}],
                   "options":"success"}"""
            )

        val refused =
            users(transport)
                .setPreferences(
                    mapOf(
                        "searchNs1" to "1",
                        "searchNs10" to "1",
                        "gender" to "x",
                        "skin" to "y",
                        "skin-x" to "z",
                    )
                )

        refused shouldBe
            mapOf(
                "searchNs10" to "Validation error for \"searchNs10\": not a valid preference.",
                "gender" to "Validation error for \"gender\": The value you specified is not a valid option.",
                "skin-x" to "Option skin-x is globally overridden.",
            )
    }

    @Test
    fun `a name that only appears inside a longer word is not the one refused`() = runTest {
        val transport =
            answer(
                """{"warnings":[{"code":"validationfailed","module":"options",
                   "text":"Validation error for \"xmath_y\": not a valid preference."}],"options":"success"}"""
            )

        users(transport).setPreferences(mapOf("math" to "1", "xmath_y" to "1")) shouldBe
            mapOf("xmath_y" to "Validation error for \"xmath_y\": not a valid preference.")
        users(transport).setPreferences(mapOf("math" to "1")) shouldBe emptyMap()
    }

    @Test
    fun `more than fifty changes go fifty to a write`() = runTest {
        val transport = answer("""{"options":"success"}""")
        val changes = (1..51).associate { "userjs-p$it" to "$it" }

        users(transport).setPreferences(changes)

        val sent = transport.writes().map { it.params.getValue("change").split('|') }
        sent.map { it.size } shouldBe listOf(50, 1)
        sent.last() shouldBe listOf("userjs-p51=51")
    }

    @Test
    fun `no changes ask the wiki nothing and one that cannot be sent is refused before anything is`() =
        runTest {
            val transport = answer("""{"options":"success"}""")
            val service = users(transport)

            service.setPreferences(emptyMap()) shouldBe emptyMap()
            for (bad in
                listOf(
                    mapOf("" to "1"),
                    mapOf("a=b" to "1"),
                    mapOf("a\u001fb" to "1"),
                    mapOf("ok" to "1", "nickname" to "a\u001fb"),
                )) {
                shouldThrow<IllegalArgumentException> { service.setPreferences(bad) }
            }

            transport.requests shouldBe emptyList()
        }

    @Test
    fun `an account that may not change its preferences is refused`() = runTest {
        shouldThrow<WikiError.Auth.PermissionDenied> {
            users(answer(PERMISSION_DENIED)).setPreferences(mapOf("skin" to "vector"))
        }
    }

    @Test
    fun `resetting resets every kind unless told which`() = runTest {
        val transport = answer("""{"options":"success"}""")
        val service = users(transport)

        service.resetPreferences()
        service.resetPreferences(setOf(PreferenceKind.USERJS, PreferenceKind.REGISTERED_MULTISELECT))

        val (all, some) = transport.writes()
        all.params shouldBe
            mapOf("action" to "options", "reset" to "1", "assert" to "user", "token" to "TOKEN")
        some.params["resetkinds"] shouldBe "userjs|registered-multiselect"
        some.kind shouldBe RequestKind.WRITE
    }

    @Test
    fun `an email is a write that copies the sender only when asked`() = runTest {
        val transport = answer("""{"emailuser":{"result":"Success"}}""")
        val service = users(transport)

        service.email("Mailbox", "Hello", "Body") shouldBe EmailOutcome.Sent
        service.email("Mailbox", "Hello", "Body", ccMe = true) shouldBe EmailOutcome.Sent

        val (plain, copied) = transport.writes()
        plain.params shouldBe
            mapOf(
                "action" to "emailuser",
                "target" to "Mailbox",
                "subject" to "Hello",
                "text" to "Body",
                "assert" to "user",
                "token" to "TOKEN",
            )
        plain.kind shouldBe RequestKind.WRITE
        copied.params["ccme"] shouldBe "1"
    }

    @Test
    fun `an email the wiki sent with warnings or answered oddly was still sent`() = runTest {
        users(answer("""{"emailuser":{"result":"Warnings","warnings":[{"code":"x","text":"y"}]}}"""))
            .email("Mailbox", "Hello", "Body") shouldBe EmailOutcome.Sent
        users(answer("{}")).email("Mailbox", "Hello", "Body") shouldBe EmailOutcome.Sent
    }

    @Test
    fun `an account that cannot be emailed is an outcome and not an error`() = runTest {
        val cases =
            mapOf(
                "emailnotarget" to UndeliverableReason.NO_SUCH_USER,
                "notarget" to UndeliverableReason.NO_SUCH_USER,
                "baduser_target" to UndeliverableReason.NO_SUCH_USER,
                "noemailtext" to UndeliverableReason.NO_ADDRESS,
                "noemail" to UndeliverableReason.NO_ADDRESS,
                "nowikiemailtext" to UndeliverableReason.NOT_ACCEPTED,
                "nowikiemail" to UndeliverableReason.NOT_ACCEPTED,
            )

        for ((code, reason) in cases) {
            users(answer(failure(code, "Not this one."))).email("Someone", "Hello", "Body") shouldBe
                EmailOutcome.Undeliverable(reason, code, "Not this one.")
        }
    }

    @Test
    fun `a sender who may not send email is an error saying why`() = runTest {
        suspend fun refusal(code: String): WikiError =
            shouldThrow<WikiError> { users(answer(failure(code, "No."))).email("Mailbox", "Hello", "Body") }

        refusal("usermaildisabled").shouldBeInstanceOf<WikiError.Api>().code shouldBe "usermaildisabled"
        refusal("cantsend").shouldBeInstanceOf<WikiError.Api>().code shouldBe "cantsend"
        refusal("blocked").shouldBeInstanceOf<WikiError.Auth.AccountBlocked>()
        refusal("blockedfrommail").shouldBeInstanceOf<WikiError.Auth.AccountBlocked>()
        refusal("permissiondenied").shouldBeInstanceOf<WikiError.Auth.PermissionDenied>()
        refusal("assertuserfailed").shouldBeInstanceOf<WikiError.Auth.NotLoggedIn>()
        refusal("ratelimited").shouldBeInstanceOf<WikiError.Transport.RateLimited>()
    }

    @Test
    fun `a session without the sendemail right is told which right it lacks`() = runTest {
        fun withRights(rights: String) = MockTransport { request ->
            when {
                request.params["meta"] == "tokens" ->
                    MockTransport.json("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
                request.params["meta"] == "userinfo" ->
                    MockTransport.json("""{"query":{"userinfo":{"id":1,"name":"Admin","rights":$rights}}}""")
                else ->
                    MockTransport.json(failure("permissiondenied", "Limited to users in the group: Users."))
            }
        }

        val lacking = withRights("""["read","edit"]""")
        shouldThrow<WikiError.Auth.MissingRight> { users(lacking).email("Mailbox", "Hello", "Body") }
            .right shouldBe "sendemail"
        lacking.requests.single { it.params["meta"] == "userinfo" }.cacheable shouldBe false

        val holding = withRights("""["read","sendemail"]""")
        shouldThrow<WikiError.Auth.PermissionDenied> { users(holding).email("Mailbox", "Hello", "Body") }
    }

    @Test
    fun `the email rate limit reaches the caller as a rate limit through a real transport`() = runTest {
        val engine = MockEngine { request ->
            val body =
                if (request.url.parameters["meta"] == "tokens") {
                    """{"query":{"tokens":{"csrftoken":"TOKEN"}}}"""
                } else {
                    failure("ratelimited", "You've exceeded your rate limit.")
                }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val transport =
            KtorTransport(
                client = HttpClient(engine),
                endpoint = ApiEndpoint("test.example.org"),
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            )

        shouldThrow<WikiError.Transport.RateLimited> { users(transport).email("Mailbox", "Hello", "Body") }
    }

    @Test
    fun `a mail server that would not take the message is an error with its code`() = runTest {
        val failed =
            shouldThrow<WikiError.Api> {
                users(
                        answer(
                            """{"emailuser":{"result":"Failure",
                               "errors":[{"code":"php-mail-error-unknown","text":"Unknown error in mail()."}]}}"""
                        )
                    )
                    .email("Mailbox", "Hello", "Body")
            }
        failed.code shouldBe "php-mail-error-unknown"
        failed.info shouldBe "Unknown error in mail()."
        failed.module shouldBe "emailuser"

        val silent =
            shouldThrow<WikiError.Api> {
                users(answer("""{"emailuser":{"result":"Failure"}}""")).email("Mailbox", "Hello", "Body")
            }
        silent.code shouldBe "failure"
        silent.info shouldBe ""
    }

    @Test
    fun `an expired token is fetched again and the email sent once`() = runTest {
        var tokens = 0
        val transport = MockTransport { request ->
            when {
                request.params["meta"] == "tokens" ->
                    MockTransport.json("""{"query":{"tokens":{"csrftoken":"TOKEN${++tokens}"}}}""")
                request.params["token"] == "TOKEN1" ->
                    MockTransport.json(failure("badtoken", "Invalid CSRF token."))
                else -> MockTransport.json("""{"emailuser":{"result":"Success"}}""")
            }
        }

        users(transport).email("Mailbox", "Hello", "Body") shouldBe EmailOutcome.Sent

        transport.writes().map { it.params["token"] } shouldBe listOf("TOKEN1", "TOKEN2")
    }

    @Test
    fun `a user service of anyone else's says it cannot do what it does not override`() = runTest {
        val api = users(answer("{}"))
        val service =
            object : UserService by api {
                override suspend fun preferences(): Map<String, String> = super<UserService>.preferences()

                override suspend fun setPreferences(changes: Map<String, String?>): Map<String, String> =
                    super<UserService>.setPreferences(changes)

                override suspend fun resetPreferences(kinds: Set<PreferenceKind>) =
                    super<UserService>.resetPreferences(kinds)

                override suspend fun email(
                    target: String,
                    subject: String,
                    text: String,
                    ccMe: Boolean,
                ): EmailOutcome = super<UserService>.email(target, subject, text, ccMe)
            }

        shouldThrow<UnsupportedOperationException> { service.preferences() }.message shouldBe
            "this UserService cannot read preferences"
        shouldThrow<UnsupportedOperationException> { service.setPreferences(mapOf("a" to "b")) }
            .message shouldBe "this UserService cannot change preferences"
        shouldThrow<UnsupportedOperationException> { service.resetPreferences() }.message shouldBe
            "this UserService cannot reset preferences"
        shouldThrow<UnsupportedOperationException> { service.email("A", "B", "C") }.message shouldBe
            "this UserService cannot send email"
    }

    private fun MockTransport.writes(): List<ApiRequest> = requests.filter { it.isWrite }

    private fun users(transport: MediaWikiTransport): UserService =
        ApiUserService(
            transport = transport,
            tokens = TokenStore(transport),
            activity = ActivityDecoder(PageDecoder(WikiId("testwiki"), NamespaceMap.CANONICAL)),
        )

    private fun answer(body: String) = MockTransport { request ->
        if (request.params["meta"] == "tokens") {
            MockTransport.json("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
        } else {
            MockTransport.json(body)
        }
    }

    private fun failure(code: String, text: String): String =
        """{"errors":[{"code":"$code","text":"$text","module":"emailuser"}]}"""

    private companion object {
        const val PERMISSION_DENIED =
            """{"errors":[{"code":"permissiondenied","text":"You may not.","module":"options"}]}"""
    }
}
