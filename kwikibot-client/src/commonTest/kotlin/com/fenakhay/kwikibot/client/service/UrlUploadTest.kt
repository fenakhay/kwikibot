package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiFileService
import com.fenakhay.kwikibot.client.temporaryDirectory
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.file.FileInfo
import com.fenakhay.kwikibot.model.file.FileRepository
import com.fenakhay.kwikibot.model.file.GlobalUsage
import com.fenakhay.kwikibot.model.file.UploadOutcome
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** An upload the wiki fetches itself: what is sent, how long it may take, and what each answer comes to. */
class UrlUploadTest {

    private val wiki = WikiId("commonswiki")
    private val file = PageRef(wiki, Title.Local(Namespace.FILE, "Volcano.png"))
    private val source = "https://upload.example.org/volcano.png"

    /** The parameters of each upload request, in order, and of each request for the account's rights. */
    private val uploads = mutableListOf<Map<String, String>>()
    private val rightsAsked = mutableListOf<HttpRequestData>()
    private val uploadRequests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    @Test
    fun `the URL goes with the title and nothing else of a file`() = runTest {
        val service = service { respondJson(SUCCESS) }

        val outcome =
            service.uploadFromUrl(source, file, comment = "from the photographer", text = "== Summary ==")

        val sent = uploads.single()
        sent["action"] shouldBe "upload"
        sent["filename"] shouldBe "Volcano.png"
        sent["url"] shouldBe source
        sent["comment"] shouldBe "from the photographer"
        sent["text"] shouldBe "== Summary =="
        sent["token"] shouldBe "T"
        sent["assert"] shouldBe "user"
        sent.keys.contains("ignorewarnings") shouldBe false
        sent.keys.contains("file") shouldBe false
        sent.keys.contains("filekey") shouldBe false
        bodies.single() shouldNotContain "filename=\""
        outcome shouldBe
            UploadOutcome.Uploaded(
                title = file.title,
                info = FileInfo(url = "https://commons.example/Volcano.png", size = 13542, sha1 = "abc"),
            )
    }

    @Test
    fun `warnings are acknowledged only when asked and leave no page text unless given`() = runTest {
        val service = service { respondJson(SUCCESS) }

        service.uploadFromUrl(source, file, ignoreWarnings = true)

        uploads.single()["ignorewarnings"] shouldBe "1"
        uploads.single().keys.contains("text") shouldBe false
        uploads.single()["comment"] shouldBe ""
    }

    @Test
    fun `the wiki is given ten minutes to fetch the file and answer`() = runTest {
        val service = service(timeout = true) { respondJson(SUCCESS) }

        service.uploadFromUrl(source, file)

        val patience = uploadRequests.single().getCapabilityOrNull(HttpTimeoutCapability)
        patience?.requestTimeoutMillis shouldBe 10.minutes.inWholeMilliseconds
        patience?.socketTimeoutMillis shouldBe 10.minutes.inWholeMilliseconds
    }

    @Test
    fun `a warning stashes the file under a key and publishing it costs no second fetch`() = runTest {
        val service = service { upload ->
            if (upload["filekey"] == null) respondJson(DUPLICATE) else respondJson(SUCCESS)
        }

        val warned = service.uploadFromUrl(source, file)

        warned shouldBe UploadOutcome.Warned(mapOf("duplicate" to "[\"Lava.png\"]"), fileKey = "1d28ary.1.")
        (warned as UploadOutcome.Warned).isDuplicateOnly shouldBe true

        val published = service.publishStashed(warned.fileKey.orEmpty(), file)
        published shouldBe
            UploadOutcome.Uploaded(
                file.title,
                FileInfo(url = "https://commons.example/Volcano.png", size = 13542, sha1 = "abc"),
            )
        uploads.last()["filekey"] shouldBe "1d28ary.1."
        uploads.last().keys.contains("url") shouldBe false
    }

    @Test
    fun `a wiki that takes no uploads from a URL says so as an error`() = runTest {
        val service = service { respondJson(refusal("copyuploaddisabled", "Upload by URL disabled.")) }

        val error = shouldThrow<WikiError.Api> { service.uploadFromUrl(source, file) }

        error.code shouldBe "copyuploaddisabled"
        error.module shouldBe "upload"
        error.info shouldContain "\$wgAllowCopyUploads"
        error.info shouldContain "Upload by URL disabled."
        rightsAsked.shouldBeEmpty()
    }

    @Test
    fun `a host the wiki does not take uploads from is named in the error`() = runTest {
        val service = service {
            respondJson(refusal("copyuploadbaddomain", "Uploads by URL are not allowed from this domain."))
        }

        val error =
            shouldThrow<WikiError.Api> {
                service.uploadFromUrl("http://user@photos.example.net:8080/a/b.png?size=2", file)
            }

        error.code shouldBe "copyuploadbaddomain"
        error.info shouldContain "from photos.example.net,"
        error.info shouldContain "\$wgCopyUploadsDomains"
        error.info shouldContain "not allowed from this domain"
    }

    @Test
    fun `a URL the wiki refuses is named in the error`() = runTest {
        val service = service {
            respondJson(refusal("copyuploadbadurl", "Upload not allowed from this URL."))
        }

        val error = shouldThrow<WikiError.Api> { service.uploadFromUrl(source, file) }

        error.code shouldBe "copyuploadbadurl"
        error.info shouldContain source
        error.info shouldContain "Upload not allowed from this URL."
    }

    @Test
    fun `a refusal from an account without upload_by_url is that missing right`() = runTest {
        val service = service(rights = """["read","upload","reupload"]""") { respondJson(DENIED) }

        val error = shouldThrow<WikiError.Auth.MissingRight> { service.uploadFromUrl(source, file) }

        error.right shouldBe "upload_by_url"
        rightsAsked.single().url.parameters["uiprop"] shouldBe "rights"
    }

    @Test
    fun `a refusal from an account that holds the right is the refusal it was`() = runTest {
        val service = service(rights = """["upload","upload_by_url"]""") { respondJson(DENIED) }

        service.uploadFromUrl(source, file) shouldBe UploadOutcome.Refused("permissiondenied", DENIED_TEXT)
        rightsAsked.size shouldBe 1
    }

    @Test
    fun `a refusal whose rights cannot be read is the refusal it was`() = runTest {
        val answers =
            listOf(
                """{"error":{"code":"readapidenied","info":"no"}}""",
                """{"query":{}}""",
            )
        for (answer in answers) {
            val service = service(rights = null, rightsAnswer = answer) { respondJson(DENIED) }

            service.uploadFromUrl(source, file) shouldBe
                UploadOutcome.Refused("permissiondenied", DENIED_TEXT)
        }

        val unreachable = service(rights = null, rightsLost = true) { respondJson(DENIED) }
        unreachable.uploadFromUrl(source, file) shouldBe
            UploadOutcome.Refused("permissiondenied", DENIED_TEXT)
    }

    @Test
    fun `a file the wiki could not fetch or check is refused with what it said`() = runTest {
        val service = service {
            respondJson(
                """{"errors":[{"code":"http-curl-error","text":"Error fetching URL: Could not connect to server",
                   "module":"upload"},{"code":"http-bad-status","text":"0 Error","module":"upload"}]}"""
            )
        }

        service.uploadFromUrl(source, file) shouldBe
            UploadOutcome.Refused("http-curl-error", "Error fetching URL: Could not connect to server")
        rightsAsked.shouldBeEmpty()
    }

    @Test
    fun `an upload whose answer was lost is not sent again since the wiki may have published it`() = runTest {
        val service =
            service(retry = RetryPolicy(maxRetries = 2, initialDelay = 1.seconds)) {
                throw IOException("connection reset")
            }

        shouldThrow<IOException> { service.uploadFromUrl(source, file) }
        uploads.size shouldBe 1
    }

    @Test
    fun `a wiki that says wait is asked again`() = runTest {
        var asked = 0
        val service =
            service(retry = RetryPolicy(maxRetries = 2, initialDelay = 1.seconds)) {
                asked++
                if (asked == 1) respondJson("""{"error":{"code":"maxlag","info":"lagged"}}""")
                else respondJson(SUCCESS)
            }

        service.uploadFromUrl(source, file).shouldBeUploaded()
        uploads.size shouldBe 2
    }

    @Test
    fun `a file service of anyone else's cannot upload from a URL unless it says how`() = runTest {
        val elsewhere =
            object : FileService {
                override suspend fun info(
                    refs: Collection<PageRef>,
                    versions: Int,
                ): Map<PageRef, List<FileInfo>> = unused()

                override suspend fun latest(ref: PageRef): FileInfo? = unused()

                override fun usage(file: PageRef, limit: Int?): Flow<PageRef> = unused()

                override fun globalUsage(file: PageRef, limit: Int?): Flow<GlobalUsage> = unused()

                override suspend fun duplicatesOf(sha1: String, limit: Int): List<PageRef> = unused()

                override fun deletedFiles(
                    from: String?,
                    to: String?,
                    prefix: String?,
                    limit: Int?,
                ): Flow<PageRef> = unused()

                override suspend fun repositories(): List<FileRepository> = unused()

                override suspend fun download(ref: PageRef, destination: Path): Path = unused()

                override suspend fun upload(
                    file: Path,
                    to: PageRef,
                    comment: String,
                    text: String?,
                    ignoreWarnings: Boolean,
                    chunkSize: Int,
                ): UploadOutcome = unused()

                override suspend fun publishStashed(
                    fileKey: String,
                    to: PageRef,
                    comment: String,
                    text: String?,
                ): UploadOutcome = unused()
            }

        shouldThrow<UnsupportedOperationException> { elsewhere.uploadFromUrl(source, file) }.message shouldBe
            "this FileService cannot upload from a URL"
    }

    @Test
    fun `the README's sample uploads a file and has one fetched and publishes what warned`() = runTest {
        val service = service { upload ->
            when {
                upload["url"] != null -> respondJson(EXISTS)
                else -> respondJson(SUCCESS)
            }
        }
        val local = Path(temporaryDirectory("kwikibot-readme-upload"), "volcano.jpg")
        SystemFileSystem.sink(local).buffered().use { it.write(ByteArray(4)) }
        val target = file
        val printed = mutableListOf<String>()

        val uploaded = service.upload(local, target, comment = "a volcano", text = "== Summary ==")
        val fetched =
            service.uploadFromUrl("https://photos.example.org/volcano.jpg", target, comment = "a volcano")

        for (outcome in listOf(uploaded, fetched)) {
            when (outcome) {
                is UploadOutcome.Uploaded -> printed += "uploaded as ${outcome.title}"
                is UploadOutcome.Warned ->
                    if (!outcome.isDuplicateOnly) outcome.fileKey?.let { service.publishStashed(it, target) }
                is UploadOutcome.Refused -> printed += "refused: ${outcome.detail}"
            }
        }

        printed shouldBe listOf("uploaded as ${file.title}")
        uploads.map { (it["url"] != null) to it["filekey"] } shouldBe
            listOf(false to null, true to null, false to "1d28ary.2.")
    }

    private fun UploadOutcome.shouldBeUploaded() {
        (this as? UploadOutcome.Uploaded)?.title shouldBe file.title
    }

    private fun unused(): Nothing = throw AssertionError("not expected to be called")

    /**
     * A file service on a wiki that answers a token request and a rights request itself, and each upload with
     * [answer], given the upload's parameters.
     */
    private fun TestScope.service(
        retry: RetryPolicy = RetryPolicy.NONE,
        timeout: Boolean = false,
        rights: String? = """["upload","upload_by_url"]""",
        rightsAnswer: String? = null,
        rightsLost: Boolean = false,
        answer: suspend MockRequestHandleScope.(Map<String, String>) -> HttpResponseData,
    ): FileService {
        val engine = MockEngine { request ->
            when {
                request.url.parameters["meta"] == "tokens" ->
                    respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
                request.url.parameters["meta"] == "userinfo" -> {
                    rightsAsked += request
                    if (rightsLost) throw IOException("connection reset")
                    respondJson(
                        rightsAnswer ?: """{"query":{"userinfo":{"id":1,"name":"Bot","rights":$rights}}}"""
                    )
                }
                else -> {
                    val fields = request.fields()
                    uploads += fields
                    uploadRequests += request
                    answer(fields)
                }
            }
        }
        val client = if (timeout) HttpClient(engine) { install(HttpTimeout) } else HttpClient(engine)
        val endpoint = ApiEndpoint("commons.wikimedia.org")
        val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")
        val transport =
            KtorTransport(
                client = client,
                endpoint = endpoint,
                userAgent = userAgent,
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            )

        return ApiFileService(
            transport = transport,
            tokens = TokenStore(transport),
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
            http = client,
            endpoint = endpoint,
            userAgent = userAgent,
            assertion = "user",
            throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
            retry = retry,
        )
    }

    /** The form fields of a multipart request, its body kept in [bodies]. */
    private suspend fun HttpRequestData.fields(): Map<String, String> {
        val body = body.toByteArray().decodeToString()
        bodies += body
        return Regex("""name="?(\w+)"?\r\n(?:[^\r\n]+\r\n)*\r\n([^\r]*)\r\n""").findAll(body).associate {
            it.groupValues[1] to it.groupValues[2]
        }
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun refusal(code: String, text: String): String =
        """{"errors":[{"code":"$code","text":"$text","module":"upload"}]}"""

    private companion object {
        const val SUCCESS =
            """{"upload":{"result":"Success","filename":"Volcano.png","imageinfo":{
               "url":"https://commons.example/Volcano.png","size":13542,"sha1":"abc"}}}"""
        const val DUPLICATE =
            """{"upload":{"result":"Warning","warnings":{"duplicate":["Lava.png"]},"filekey":"1d28ary.1.",
               "sessionkey":"1d28ary.1."}}"""
        const val EXISTS =
            """{"upload":{"result":"Warning","warnings":{"exists":"Volcano.png"},"filekey":"1d28ary.2."}}"""
        const val DENIED_TEXT = "You are not allowed to execute the action you have requested."
        const val DENIED =
            """{"errors":[{"code":"permissiondenied","text":"$DENIED_TEXT","module":"upload"}]}"""
    }
}
