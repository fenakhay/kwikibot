package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration

class ParamInfoTest {

    private val categoryMembers = """
        {"paraminfo":{"modules":[{
          "name":"categorymembers","path":"query+categorymembers","prefix":"cm",
          "parameters":[
            {"name":"title","type":"string","required":false},
            {"name":"type","type":["page","subcat","file"],"multi":true},
            {"name":"limit","type":"limit","limit":500,"highlimit":5000}]}]}}
    """.trimIndent()

    @Test
    fun `a module reports the parameters it takes`() = runTest {
        val info = paramInfo { respondJson(categoryMembers) }

        val module = info.module("query+categorymembers")

        ("title" in checkNotNull(module)) shouldBe true
        module["type"]?.values shouldBe listOf("page", "subcat", "file")
        module["type"]?.multiValued shouldBe true
    }

    @Test
    fun `a limit depends on the account, which is why it is asked for rather than assumed`() =
        runTest {
            val info = paramInfo { respondJson(categoryMembers) }

            info.limit("query+categorymembers", "limit", highLimits = false) shouldBe 500
            info.limit("query+categorymembers", "limit", highLimits = true) shouldBe 5000
        }

    @Test
    fun `a parameter this wiki does not have is reported as absent`() = runTest {
        val info = paramInfo { respondJson(categoryMembers) }

        info.supports("query+categorymembers", "sort") shouldBe false
        info.supports("query+categorymembers", "title") shouldBe true
    }

    @Test
    fun `a module this wiki does not have is null, not an empty description`() = runTest {
        val info = paramInfo {
            respondJson("""{"paraminfo":{"modules":[{"name":"nope","missing":true}]}}""")
        }

        info.module("nope").shouldBeNull()
    }

    @Test
    fun `an answer is fetched once and reused`() = runTest {
        var requests = 0
        val info = paramInfo {
            requests++
            respondJson(categoryMembers)
        }

        info.module("query+categorymembers")
        info.module("query+categorymembers")
        info.limit("query+categorymembers", "limit", highLimits = true)

        requests shouldBe 1
    }

    @Test
    fun `concurrent callers produce one request, not one each`() = runTest {
        var requests = 0
        val info = paramInfo {
            requests++
            respondJson(categoryMembers)
        }

        List(5) { async { info.module("query+categorymembers") } }.awaitAll()

        requests shouldBe 1
    }

    private fun TestScope.paramInfo(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ParamInfo = ParamInfo(
        KtorTransport(
            client = HttpClient(MockEngine(handler)),
            endpoint = ApiEndpoint("en.wiktionary.org"),
            userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
            throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
            retry = RetryPolicy.NONE,
        ),
    )

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}

class OptionSetTest {

    @Test
    fun `an option that is off is not the same as one nobody mentioned`() {
        OptionSet().on("bot").off("minor").toParam() shouldBe "bot|!minor"
        OptionSet().on("bot").toParam() shouldBe "bot"
    }

    @Test
    fun `nothing constrained is null rather than an empty value`() {
        OptionSet().toParam().shouldBeNull()
        OptionSet().isEmpty shouldBe true
    }

    @Test
    fun `unsetting removes the constraint entirely`() {
        val options = OptionSet().on("bot").off("minor").unset("minor")

        options.toParam() shouldBe "bot"
        options["minor"].shouldBeNull()
    }

    @Test
    fun `a value can be read back`() {
        val parsed = OptionSet.parse("bot|!minor|!redirect")

        parsed["bot"] shouldBe true
        parsed["minor"] shouldBe false
        parsed.names shouldBe setOf("bot", "minor", "redirect")
    }

    @Test
    fun `a set round-trips through its own parameter value`() {
        val options = OptionSet().on("bot", "patrolled").off("minor")

        OptionSet.parse(checkNotNull(options.toParam())) shouldBe options
    }

    @Test
    fun `setting an option twice keeps the last word`() {
        OptionSet().on("bot").off("bot").toParam() shouldBe "!bot"
    }
}
