package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ParamInfo
import com.fenakhay.kwikibot.protocol.SiteInfo
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What the LiftWing tests share: the answers LiftWing gave, copied from the live service, and a mock. */
abstract class LiftWingFixtures {

    protected val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")
    protected val asked = mutableListOf<HttpRequestData>()
    protected val sent = mutableListOf<String>()

    /**
     * Held while a request is recorded, since the engine answers requests side by side on several threads.
     */
    private val recording = Mutex()

    protected val einstein = RevisionId(1383620073)

    protected val damagingAnswer =
        """{"enwiki":{"models":{"damaging":{"version":"0.5.1"}},"scores":{"1383620073":{"damaging":{"score":
           {"prediction":false,"probability":{"false":0.7535382250896887,"true":0.2464617749103113}}}}}}}"""

    protected val goodfaithAnswer =
        """{"enwiki":{"models":{"goodfaith":{"version":"0.5.1"}},"scores":{"1250000000":{"goodfaith":{"score":
           {"prediction":true,"probability":{"false":0.0038045151336706384,"true":0.9961954848663294}}}}}}}"""

    protected val revertRiskAnswer =
        """{"model_name":"revertrisk-language-agnostic","model_version":"3","wiki_db":"enwiki",
           "revision_id":1383620073,"output":{"prediction":true,
           "probabilities":{"true":0.9260151982307434,"false":0.07398480176925659}}}"""

    protected val multilingualAnswer =
        """{"model_name":"revertrisk-multilingual","model_version":"4","wiki_db":"simplewiki",
           "revision_id":11053038,"output":{"prediction":false,
           "probabilities":{"true":0.4676410757863918,"false":0.5323589242136082}}}"""

    protected val articleQualityAnswer =
        """{"enwiki":{"models":{"articlequality":{"version":"0.9.2"}},"scores":{"1383620073":{"articlequality":
           {"score":{"prediction":"FA","probability":{"B":0.07774038086784396,"C":0.018569186986051898,
           "FA":0.5381958599075775,"GA":0.3548535903838616,"Start":0.007716886766304666,
           "Stub":0.002924095088360389}}}}}}}"""

    protected val draftQualityAnswer =
        """{"enwiki":{"models":{"draftquality":{"version":"0.2.1"}},"scores":{"1383620073":{"draftquality":
           {"score":{"prediction":"vandalism","probability":{"OK":0.2813630761335944,
           "attack":0.015453317380882344,"spam":0.08684249723206908,"vandalism":0.6163411092534542}}}}}}}"""

    protected val noInfo =
        """{"error":"The MW API does not have any info related to the rev-id provided as input (1383648813), therefore it is not possible to extract features properly. One possible cause is the deletion of the page related to the revision id. Please contact the ML-Team if you need more info."}"""

    protected val textDeleted =
        """{"error":"Missing resource for rev-id 1383426223: TextDeleted: Text deleted (datasource.revision.text)"}"""

    protected val noSuchModel = """{"httpCode":404,"httpReason":"Not Found"}"""

    protected fun jsonOf(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    protected suspend fun bodyOf(request: HttpRequestData): String =
        request.body.toByteArray().decodeToString()

    protected fun engine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            recording.withLock {
                asked += request
                sent += body
            }
            handler(request)
        }

    protected fun client(
        retry: RetryPolicy = RetryPolicy.NONE,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): LiftWing = LiftWing(HttpClient(engine(handler)), userAgent, retry)

    protected fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    /** A wiki served from [server] that answers nothing: only its site information is read. */
    protected fun wikiAt(server: String, id: String = "enwiki", language: String = "en"): Wiki =
        object : Wiki {
            override val id = WikiId(id)
            override val info =
                SiteInfo(
                    id = this.id,
                    siteName = "Wikipedia",
                    language = LangCode(language),
                    server = server,
                    articlePath = "/wiki/$1",
                    mainPage = "Main Page",
                    generator = "MediaWiki 1.47.0",
                    namespaces = NamespaceMap.CANONICAL,
                    interwiki = InterwikiMap.EMPTY,
                )
            override val identity: Identity
                get() = unused("identity")

            override val pages
                get() = unused("pages")

            override val lists
                get() = unused("lists")

            override val revisions
                get() = unused("revisions")

            override val users
                get() = unused("users")

            override val logs
                get() = unused("logs")

            override val files
                get() = unused("files")

            override val extensions
                get() = unused("extensions")

            override val proofread
                get() = unused("proofread")

            override val renderer
                get() = unused("renderer")

            override val meta
                get() = unused("meta")

            override val paramInfo: ParamInfo
                get() = unused("paramInfo")

            override val transport: MediaWikiTransport =
                object : MediaWikiTransport {
                    override val endpoint = ApiEndpoint(server)

                    override suspend fun call(request: ApiRequest): JsonObject = unused("transport")
                }

            override val tokens: TokenStore
                get() = unused("tokens")
        }

    protected fun unused(what: String): Nothing = throw AssertionError("$what was not expected to be used")
}
