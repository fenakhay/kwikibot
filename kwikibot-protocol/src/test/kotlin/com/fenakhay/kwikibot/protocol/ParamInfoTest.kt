package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.protocol.decode.OptionSet
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ParamInfoTest {

    // The shape MediaWiki answers with for helpformat=none, trimmed to the parameters under test.
    private val categoryMembers =
        """
        {"paraminfo":{"modules":[{
          "name":"categorymembers","classname":"MediaWiki\\Api\\ApiQueryCategoryMembers",
          "path":"query+categorymembers","group":"list","prefix":"cm","source":"MediaWiki",
          "sourcename":"MediaWiki","generator":true,"helpurls":[],
          "parameters":[
            {"index":1,"name":"title","type":"string","required":false,"multi":false},
            {"index":6,"name":"type","type":["file","page","subcat"],"default":"page|subcat|file",
             "required":false,"multi":true,"lowlimit":50,"highlimit":500,"limit":50},
            {"index":8,"name":"limit","type":"limit","default":10,"required":false,"multi":false,
             "max":500,"highmax":5000,"min":1}]}]}}
        """
            .trimIndent()

    private val parse =
        """
        {"paraminfo":{"modules":[{
          "name":"parse","path":"parse","group":"action","prefix":"","source":"MediaWiki",
          "parameters":[
            {"index":5,"name":"prop","type":["categories","headitems","links","sections","text","tocdata"],
             "default":"text|langlinks|categories|links|templates|images|externallinks|sections|tocdata",
             "required":false,"multi":true,"lowlimit":50,"highlimit":500,"limit":50,
             "deprecatedvalues":["headitems","sections"],"internalvalues":["parseroutput"]}]}]}}
        """
            .trimIndent()

    @Test
    fun `a module reports the parameters it takes`() = runTest {
        val info = paramInfo { respondJson(categoryMembers) }

        val module = info.module("query+categorymembers")

        ("title" in checkNotNull(module)) shouldBe true
        module["type"]?.values shouldBe listOf("file", "page", "subcat")
        module["type"]?.multiValued shouldBe true
    }

    @Test
    fun `a limit depends on the account, which is why it is asked for rather than assumed`() = runTest {
        val info = paramInfo { respondJson(categoryMembers) }

        info.limit("query+categorymembers", "limit", highLimits = false) shouldBe 500
        info.limit("query+categorymembers", "limit", highLimits = true) shouldBe 5000
    }

    @Test
    fun `how many values a parameter takes is not how many results a query returns`() = runTest {
        val info = paramInfo { respondJson(categoryMembers) }

        val type = checkNotNull(info.module("query+categorymembers")?.get("type"))

        type.valueLimit shouldBe 50
        type.highValueLimit shouldBe 500
        type.limit.shouldBeNull()
        info.limit("query+categorymembers", "type", highLimits = true).shouldBeNull()
    }

    @Test
    fun `the values a parameter accepts, and which of them are going away, are read`() = runTest {
        val info = paramInfo { respondJson(parse) }

        info.values("parse", "prop") shouldBe
            listOf("categories", "headitems", "links", "sections", "text", "tocdata")
        info.isDeprecated("parse", "prop", "sections") shouldBe true
        info.isDeprecated("parse", "prop", "tocdata") shouldBe false
        info.module("parse")?.get("prop")?.internalValues shouldBe listOf("parseroutput")
    }

    @Test
    fun `a parameter taking free text has no values to check against`() = runTest {
        val info = paramInfo { respondJson(categoryMembers) }

        info.values("query+categorymembers", "title").shouldBeNull()
        info.values("query+categorymembers", "nonesuch").shouldBeNull()
        info.isDeprecated("query+categorymembers", "nonesuch", "x") shouldBe false
    }

    @Test
    fun `prefetching asks once for many modules and remembers the ones the wiki lacks`() = runTest {
        val asked = mutableListOf<String>()
        val info = paramInfo { request ->
            asked += request.url.parameters["modules"].orEmpty()
            respondJson(categoryMembers)
        }

        info.prefetch(listOf("query+categorymembers", "query+nonesuch", "query+categorymembers"))
        info.module("query+categorymembers").shouldNotBeNull()
        info.module("query+nonesuch").shouldBeNull()
        info.prefetch(listOf("query+nonesuch"))

        asked shouldBe listOf("query+categorymembers|query+nonesuch")
    }

    @Test
    fun `prefetching splits a long list into requests the wiki will accept`() = runTest {
        var requests = 0
        val info = paramInfo {
            requests++
            respondJson("""{"paraminfo":{"modules":[]}}""")
        }

        info.prefetch((1..ParamInfo.MAX_MODULES + 1).map { "query+m$it" })

        requests shouldBe 2
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
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): ParamInfo =
        ParamInfo(
            KtorTransport(
                client = HttpClient(MockEngine(handler)),
                endpoint = ApiEndpoint("en.wiktionary.org"),
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            )
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
