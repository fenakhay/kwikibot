package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiFileService
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
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** What a file service sends only when asked to, and what it makes of answers that say little. */
class FileAnswersTest {

    private val wiki = WikiId("commonswiki")
    private val file = PageRef(wiki, Title.Local(Namespace.FILE, "Volcano.jpg"))

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
    fun `a version that says nothing has no URL and no size, and an entry with no title is skipped`() =
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
    fun `usage and global usage are asked for in the number wanted, and stop there`() = runTest {
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
    fun `a repository that says nothing has no names and no addresses, and an empty address is none`() =
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
    fun `a duplicate named only by its bare name is still a file, and one with no name is skipped`() =
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
            service.download(file, createTempDirectory("kwikibot-download"))
        }
    }

    @Test
    fun `a chunk size must be positive`() = runTest {
        val service = service { respondJson("{}") }

        shouldThrow<IllegalArgumentException> { service.upload(tempFile(ByteArray(4)), file, chunkSize = 0) }
    }

    @Test
    fun `an upload carries page text, the warning override and the account only when they apply`() = runTest {
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
    fun `a file cut short while it uploads sends only the bytes it still has`() = runTest {
        val path = tempFile("A".repeat(CHUNK_SIZE * 2).toByteArray())
        val chunks = mutableListOf<String>()
        val service = service { request ->
            tokenOr(request) {
                chunks += request.body.toByteArray().decodeToString()
                if (chunks.size == 1) path.writeBytes("B".repeat(CHUNK_SIZE + 2).toByteArray())
                respondJson("""{"upload":{"result":"Continue","filekey":"KEY${chunks.size}"}}""")
            }
        }

        service.upload(path, file, chunkSize = CHUNK_SIZE)

        chunks[1] shouldContain "\r\nBB\r\n"
        chunks[1] shouldNotContain "BBB"
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

    private fun tempFile(bytes: ByteArray): Path {
        val path = createTempDirectory("kwikibot-upload-test").resolve("Volcano.jpg")
        path.writeBytes(bytes)
        return path
    }

    private fun TestScope.service(
        assertion: String? = null,
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
        )
    }

    /** A token for a token request, and [answer] for anything else. */
    private suspend fun MockRequestHandleScope.tokenOr(
        request: HttpRequestData,
        answer: suspend MockRequestHandleScope.() -> HttpResponseData,
    ): HttpResponseData =
        if (request.url.parameters["meta"] == "tokens") {
            respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
        } else {
            answer()
        }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private companion object {
        const val CHUNK_SIZE = 8
        const val MORE_THAN_A_BATCH = 600
    }
}
