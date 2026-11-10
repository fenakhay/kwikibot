package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiPageService
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** What an edit's refusal says beyond the error the wiki sent. */
class EditRefusalTest {

    private val wiki = WikiId("enwiktionary")

    private val volcano = PageRef(wiki, Title.Local(Namespace.MAIN, "volcano"))

    private fun refusal(code: String) = """{"errors":[{"code":"$code","text":"no","module":"edit"}]}"""

    private val info =
        """{"query":{"pages":[{"pageid":1,"ns":0,"title":"volcano","lastrevid":9002,
        "protection":[{"type":"move","level":"sysop","expiry":"infinity"},
        {"type":"edit","level":"autoconfirmed","expiry":"infinity"}]}]}}"""

    private fun TestScope.refusing(code: String): PageService = service { request ->
        when {
            request.url.parameters["meta"] == "tokens" -> token()
            request.url.parameters["inprop"] == "protection" -> respondJson(info)
            else -> respondJson(refusal(code))
        }
    }

    @Test
    fun `a conflict says which revision it lost to`() = runTest {
        refusing("editconflict")
            .edit(volcano) { text = "new" }
            .shouldBeInstanceOf<EditOutcome.Conflict>()
            .currentRevision shouldBe RevisionId(9002)
    }

    @Test
    fun `a protected page says the level on editing and not the one on moving`() = runTest {
        refusing("protectedpage")
            .edit(volcano) { text = "new" }
            .shouldBeInstanceOf<EditOutcome.Protected>()
            .level shouldBe "autoconfirmed"
    }

    @Test
    fun `a title protected from creation says at what level`() = runTest {
        val service = service { request ->
            when {
                request.url.parameters["meta"] == "tokens" -> token()
                request.url.parameters["inprop"] == "protection" ->
                    respondJson(
                        """{"query":{"pages":[{"ns":0,"title":"volcano","missing":true,
                        "protection":[{"type":"create","level":"sysop","expiry":"infinity"}]}]}}"""
                    )
                else -> respondJson(refusal("protectedtitle"))
            }
        }

        service.edit(volcano) { text = "new" }.shouldBeInstanceOf<EditOutcome.Protected>().level shouldBe
            "sysop"
    }

    @Test
    fun `a refusal stays a refusal when its details cannot be found out`() = runTest {
        val service = service { request ->
            when {
                request.url.parameters["meta"] == "tokens" -> token()
                request.url.parameters["inprop"] == "protection" -> error("the wiki went away")
                else -> respondJson(refusal("protectedpage"))
            }
        }

        service.edit(volcano) { text = "new" }.shouldBeInstanceOf<EditOutcome.Protected>().level shouldBe null
    }

    @Test
    fun `a refusal with nothing more to say costs no extra request`() = runTest {
        var asked = 0
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                token()
            } else {
                asked++
                respondJson(refusal("contenttoobig"))
            }
        }

        service.edit(volcano) { text = "new" }.shouldBeInstanceOf<EditOutcome.Rejected>()

        asked shouldBe 1
    }

    @Test
    fun `an edit the wiki answers with a captcha says which`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                token()
            } else {
                respondJson(
                    """{"edit":{"result":"Failure","captcha":{"type":"image","id":"123","url":"/c"}}}"""
                )
            }
        }

        service
            .edit(volcano) { text = "new" }
            .shouldBeInstanceOf<EditOutcome.CaptchaRequired>()
            .captchaId shouldBe "123"
    }

    private fun MockRequestHandleScope.token(): HttpResponseData =
        respondJson("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")

    private fun TestScope.service(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): PageService {
        val transport =
            KtorTransport(
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
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
