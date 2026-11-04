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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** What a file service sends only when asked to, and what it makes of answers that say little. */
class FileAnswersTest {

    internal val wiki = WikiId("commonswiki")
    internal val file = PageRef(wiki, Title.Local(Namespace.FILE, "Volcano.jpg"))

    @Test
    fun `asking about no files asks the wiki nothing`() = runTest {
        var requests = 0
        val service = service {
            requests++
            respondJson("{}")
        }

        service.info(emptyList()) shouldBe emptyMap()
        requests shouldBe 0
    }

    @Test
    fun `a version that says nothing has no URL and no size and an entry with no title is skipped`() =
        runTest {
            val service = service {
                respondJson(
                    """{"query":{"pages":[{"pageid":2,"imageinfo":[{"url":"https://upload.example/x"}]},
                       {"ns":6,"title":"File:Volcano.jpg","imageinfo":[{}]}]}}"""
                )
            }

            service.info(listOf(file)) shouldBe mapOf(file to listOf(FileInfo(url = "")))
        }

    @Test
    fun `usage and global usage are asked for in the number wanted and stop there`() = runTest {
        val asked = mutableListOf<String>()
        val service = service { request ->
            asked += request.url.parameters["iulimit"] ?: request.url.parameters["gulimit"].orEmpty()
            respondJson(
                """{"query":{"imageusage":[{"ns":0,"title":"Volcano"},{"ns":0,"title":"Lava"}],
                   "pages":[{"ns":6,"title":"File:Volcano.jpg","globalusage":[{},{"wiki":"enwiki","title":"x"}]}]}}"""
            )
        }

        service.usage(file, limit = 1).toList().map { it.title.text } shouldBe listOf("Volcano")
        service.globalUsage(file, limit = 1).toList() shouldBe listOf(GlobalUsage(wiki = "", title = ""))
        service.globalUsage(file, limit = MORE_THAN_A_BATCH).toList().size shouldBe 2

        asked shouldBe listOf("1", "1", "max")
    }

    @Test
    fun `a repository that says nothing has no names and no addresses and an empty address is none`() =
        runTest {
            val service = service {
                respondJson("""{"query":{"repos":[{},{"url":"","rootUrl":"","local":"yes"}]}}""")
            }

            service.repositories() shouldBe
                listOf(
                    FileRepository(name = "", displayName = "", isLocal = false),
                    FileRepository(name = "", displayName = "", isLocal = false),
                )
        }

    @Test
    fun `an answer with no query has no repositories`() = runTest {
        service { respondJson("{}") }.repositories() shouldBe emptyList()
    }

    @Test
    fun `a duplicate named only by its bare name is still a file and one with no name is skipped`() =
        runTest {
            val service = service { respondJson("""{"query":{"allimages":[{"name":"Vulcan.jpg"},{}]}}""") }

            service.duplicatesOf("abc").map { it.title } shouldBe
                listOf(Title.Local(Namespace.FILE, "Vulcan.jpg"))
        }

    @Test
    fun `a file whose version has no URL cannot be downloaded`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"pages":[{"ns":6,"title":"File:Volcano.jpg","imageinfo":[{"size":1}]}]}}"""
            )
        }

        shouldThrow<IllegalArgumentException> {
            service.download(file, temporaryDirectory("kwikibot-download"))
        }
    }

    @Test
    fun `a chunk size must be positive`() = runTest {
        val service = service { respondJson("{}") }

        shouldThrow<IllegalArgumentException> { service.upload(tempFile(ByteArray(4)), file, chunkSize = 0) }
    }

    @Test
    fun `an upload carries page text the warning override and the account only when they apply`() = runTest {
        val bodies = mutableListOf<String>()
        val service =
            service(assertion = "bot") { request ->
                tokenOr(request) {
                    bodies += request.body.toByteArray().decodeToString()
                    respondJson("""{"upload":{"result":"Success"}}""")
                }
            }

        service.upload(tempFile(ByteArray(4)), file, text = "== Summary ==", ignoreWarnings = true)
        service.upload(tempFile(ByteArray(4)), file)
        service.publishStashed("STASHED", file, text = "== Licensing ==")

        val (chosen, plain, published) = bodies
        chosen shouldContain "name=\"text\""
        chosen shouldContain "name=\"ignorewarnings\""
        plain shouldNotContain "name=\"text\""
        plain shouldNotContain "name=\"ignorewarnings\""
        published shouldContain "== Licensing =="
        bodies.forEach { it shouldContain "name=\"assert\"" }
    }

    @Test
    fun `a refused chunk ends the upload with the refusal`() = runTest {
        val service = service { request ->
            tokenOr(request) { respondJson("""{"error":{"code":"stashfailed","info":"Could not store."}}""") }
        }

        shouldThrow<WikiError> {
            service.upload(tempFile(ByteArray(CHUNK_SIZE * 2)), file, chunkSize = CHUNK_SIZE)
        }
    }

    @Test
    fun `a chunk answered with no key ends the upload rather than starting a second file`() = runTest {
        for (answer in listOf("""{"upload":{"result":"Continue"}}""", "{}")) {
            val service = service { request -> tokenOr(request) { respondJson(answer) } }

            shouldThrow<WikiError.Api> {
                    service.upload(tempFile(ByteArray(CHUNK_SIZE * 2)), file, chunkSize = CHUNK_SIZE)
                }
                .code shouldBe "nofilekey"
        }
    }

    @Test
    fun `a chunk whose answer was lost is sent again and continues under the same key`() = runTest {
        val offsets = mutableListOf<String?>()
        var dropped = false
        val service = retrying { request ->
            tokenOr(request) {
                val sent = request.fields()
                offsets += sent["offset"]
                when {
                    sent["offset"] == "8" && !dropped -> {
                        dropped = true
                        throw IOException("connection reset")
                    }
                    sent["offset"] == "8" -> respondJson(STORED_ALREADY)
                    sent["checkstatus"] == "1" -> respondJson(UNEXPECTED)
                    else -> respondJson(STASHED)
                }
            }
        }

        service.upload(tempFile(ByteArray(CHUNK_SIZE * 3)), file, chunkSize = CHUNK_SIZE)

        offsets shouldBe listOf("0", "8", "8", "16", null)
    }

    @Test
    fun `a last chunk whose answer was lost finds its key by asking the wiki`() = runTest {
        var dropped = false
        val published = mutableListOf<String?>()
        val service = retrying { request ->
            tokenOr(request) {
                val sent = request.fields()
                when {
                    sent["checkstatus"] == "1" ->
                        respondJson("""{"upload":{"result":"Success","filekey":"whole"}}""")
                    sent["offset"] == "8" && !dropped -> {
                        dropped = true
                        respond("<html>Bad gateway</html>", HttpStatusCode.BadGateway)
                    }
                    sent["offset"] == "8" -> respondJson(STORED_ALREADY)
                    sent["offset"] == null -> {
                        published += sent["filekey"]
                        respondJson("""{"upload":{"result":"Success","filename":"Volcano.png"}}""")
                    }
                    else -> respondJson(STASHED)
                }
            }
        }

        service.upload(tempFile(ByteArray(CHUNK_SIZE * 2)), file, chunkSize = CHUNK_SIZE)

        published shouldBe listOf("whole")
    }

    @Test
    fun `an offset the wiki did not expect is still a failure`() = runTest {
        val service = retrying { request ->
            tokenOr(request) {
                val sent = request.fields()
                if (sent["offset"] == "8") respondJson(STORED_ALREADY.replace(":16", ":2"))
                else respondJson(STASHED)
            }
        }

        shouldThrow<WikiError> {
            service.upload(tempFile(ByteArray(CHUNK_SIZE * 2)), file, chunkSize = CHUNK_SIZE)
        }
    }

    @Test
    fun `a publish whose answer was lost is not sent again but a wiki that said wait is`() = runTest {
        var lost = 0
        val losing = retrying { request ->
            tokenOr(request) {
                lost++
                throw IOException("connection reset")
            }
        }

        shouldThrow<IOException> { losing.upload(tempFile(ByteArray(4)), file) }
        lost shouldBe 1

        var asked = 0
        val waiting = retrying { request ->
            tokenOr(request) {
                asked++
                if (asked == 1) {
                    respondJson("""{"error":{"code":"maxlag","info":"lagged"}}""")
                } else {
                    respondJson("""{"upload":{"result":"Success"}}""")
                }
            }
        }

        waiting.upload(tempFile(ByteArray(4)), file).shouldBeInstanceOf<UploadOutcome.Uploaded>()
        asked shouldBe 2
    }

    @Test
    fun `chunks that keep failing raise the last failure`() = runTest {
        var asked = 0
        val service = retrying { request ->
            tokenOr(request) {
                asked++
                throw IOException("connection reset")
            }
        }

        shouldThrow<IOException> {
            service.upload(tempFile(ByteArray(CHUNK_SIZE * 2)), file, chunkSize = CHUNK_SIZE)
        }
        asked shouldBe 3
    }

    @Test
    fun `an upload answered with something other than JSON is a bad response`() = runTest {
        val service = service { request ->
            tokenOr(request) { respondJson("<html>Service unavailable</html>") }
        }

        shouldThrow<WikiError.Api> { service.upload(tempFile(ByteArray(4)), file) }.code shouldBe
            "badresponse"
    }

    @Test
    fun `an upload answered with no result the library knows is refused with what it said`() = runTest {
        val outcomes =
            listOf("{}", """{"upload":{"result":"Continue"}}""", """{"upload":{}}""").map { answer ->
                service { request -> tokenOr(request) { respondJson(answer) } }
                    .upload(tempFile(ByteArray(4)), file)
            }

        outcomes.map { (it as UploadOutcome.Refused).code } shouldBe listOf("noupload", "Continue", "unknown")
    }

    @Test
    fun `a warning that lists no warnings and no key has neither`() = runTest {
        val service = service { request ->
            tokenOr(request) { respondJson("""{"upload":{"result":"Warning"}}""") }
        }

        service.upload(tempFile(ByteArray(4)), file) shouldBe UploadOutcome.Warned(emptyMap(), fileKey = null)
    }

    internal fun tempFile(bytes: ByteArray): Path {
        val path = Path(temporaryDirectory("kwikibot-upload-test"), "Volcano.jpg")
        path.writeBytes(bytes)
        return path
    }

    /** A service that retries twice, as a wiki's own would. */
    private fun TestScope.retrying(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): FileService = service(retry = RetryPolicy(maxRetries = 2), handler = handler)

    /** The form fields of a multipart request, other than the file. */
    private suspend fun HttpRequestData.fields(): Map<String, String> =
        Regex("""name="?(\w+)"?\r\n(?:[^\r\n]+\r\n)*\r\n([^\r]*)\r\n""")
            .findAll(body.toByteArray().decodeToString())
            .associate { it.groupValues[1] to it.groupValues[2] }

    internal fun TestScope.service(
        assertion: String? = null,
        retry: RetryPolicy = RetryPolicy.NONE,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): FileService {
        val client = HttpClient(MockEngine(handler))
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
            assertion = assertion,
            retry = retry,
        )
    }

    /** A token for a token request, and [answer] for anything else. */
    internal suspend fun MockRequestHandleScope.tokenOr(
        request: HttpRequestData,
        answer: suspend MockRequestHandleScope.() -> HttpResponseData,
    ): HttpResponseData =
        if (request.url.parameters["meta"] == "tokens") {
            respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
        } else {
            answer()
        }

    internal fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    internal companion object {
        const val CHUNK_SIZE = 8
        const val STORED_ALREADY =
            """{"errors":[{"code":"stashfailed","text":"Bad offset","data":{"offset":16}}]}"""
        const val STASHED = """{"upload":{"result":"Continue","filekey":"KEY"}}"""
        const val UNEXPECTED = """{"error":{"code":"unexpected","info":"not asked for"}}"""
        const val MORE_THAN_A_BATCH = 600
    }

    internal fun Path.writeBytes(bytes: ByteArray) {
        SystemFileSystem.sink(this).buffered().use { it.write(bytes) }
    }
}
