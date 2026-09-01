package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.UploadOutcome
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.protocol.PageDecoder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class FileServiceTest {

    private val wiki = WikiId("commonswiki")
    private val file = PageRef(wiki, Title.Local(Namespace.FILE, "Volcano.jpg"))

    @Test
    fun `file information is decoded, newest version first`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"pages":[{"pageid":1,"ns":6,"title":"File:Volcano.jpg",
                   "imageinfo":[
                     {"timestamp":"2026-08-01T00:00:00Z","user":"A","size":12345,
                      "width":800,"height":600,"url":"https://upload.example/v2.jpg",
                      "sha1":"abc","mime":"image/jpeg","mediatype":"BITMAP"},
                     {"timestamp":"2020-01-01T00:00:00Z","user":"B","size":999,
                      "width":80,"height":60,"url":"https://upload.example/v1.jpg"}]}]}}""",
            )
        }

        val versions = service.info(listOf(file), versions = 2).getValue(file)

        versions.size shouldBe 2
        versions.first().size shouldBe 12345L
        versions.first().sha1 shouldBe "abc"
        versions.first().hasDimensions shouldBe true
        versions.first().user shouldBe "A"
    }

    @Test
    fun `a file that does not exist has no information rather than empty information`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"pages":[{"ns":6,"title":"File:Volcano.jpg","missing":true}]}}""",
            )
        }

        service.latest(file).shouldBeNull()
    }

    @Test
    fun `duplicates are found by content hash`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"allimages":[
                   {"name":"Volcano.jpg","title":"File:Volcano.jpg"},
                   {"name":"Vulcan.jpg","title":"File:Vulcan.jpg"}]}}""",
            )
        }

        val duplicates = service.duplicatesOf("abc")

        duplicates.map { it.title.text } shouldBe listOf("Volcano.jpg", "Vulcan.jpg")
        duplicates.all { it.namespace == Namespace.FILE } shouldBe true
    }

    @Test
    fun `pages using a file are listed`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"imageusage":[{"pageid":1,"ns":0,"title":"volcano"}]}}""",
            )
        }

        service.usage(file).toList().map { it.title.text } shouldBe listOf("volcano")
    }

    @Test
    fun `global usage reports which wiki uses it`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"pages":[{"ns":6,"title":"File:Volcano.jpg","globalusage":[
                   {"title":"Volcano","wiki":"en.wikipedia.org",
                    "url":"https://en.wikipedia.org/wiki/Volcano"}]}]}}""",
            )
        }

        val uses = service.globalUsage(file).toList()

        uses.single().wiki shouldBe "en.wikipedia.org"
        uses.single().title shouldBe "Volcano"
    }

    @Test
    fun `a small file goes in one request, with the bytes and the token`() = runTest {
        var body = ""
        var uploads = 0
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                uploads++
                body = request.body.toByteArray().decodeToString()
                respondJson(
                    """{"upload":{"result":"Success","filename":"Volcano.jpg",
                       "imageinfo":{"url":"https://upload.example/v.jpg","size":9}}}""",
                )
            }
        }

        val outcome = service.upload(tempFile(ByteArray(9)), file, comment = "a volcano")

        uploads shouldBe 1
        body.contains("name=\"filename\"") shouldBe true
        body.contains("Volcano.jpg") shouldBe true
        body.contains("name=\"token\"") shouldBe true
        outcome.shouldBeInstanceOf<UploadOutcome.Uploaded>().info?.size shouldBe 9L
    }

    @Test
    fun `the title supplies the file name without its namespace prefix`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"upload":{"result":"Success"}}""")
            }
        }

        service.upload(tempFile(ByteArray(4)), file)

        body.contains("File:Volcano.jpg") shouldBe false
        body.contains("Volcano.jpg") shouldBe true
    }

    @Test
    fun `a large file is sent in chunks, each carrying the key of the one before`() = runTest {
        val requests = mutableListOf<String>()
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                requests += request.body.toByteArray().decodeToString()
                if (requests.size <= CHUNKS) {
                    respondJson(
                        """{"upload":{"result":"Continue","filekey":"KEY${requests.size}",
                           "offset":${requests.size * CHUNK_SIZE}}}""",
                    )
                } else {
                    respondJson("""{"upload":{"result":"Success"}}""")
                }
            }
        }

        val outcome = service.upload(
            tempFile(ByteArray(CHUNK_SIZE * CHUNKS)),
            file,
            chunkSize = CHUNK_SIZE,
        )

        requests.size shouldBe CHUNKS + 1
        requests[0].contains("name=\"offset\"") shouldBe true
        requests[0].contains("name=\"filekey\"") shouldBe false
        requests[1].contains("KEY1") shouldBe true
        requests[2].contains("KEY2") shouldBe true
        requests.last().contains("KEY$CHUNKS") shouldBe true
        outcome.shouldBeInstanceOf<UploadOutcome.Uploaded>()
    }

    @Test
    fun `a warning is an outcome to decide on, not a failure`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                respondJson(
                    """{"upload":{"result":"Warning","filekey":"STASHED",
                       "warnings":{"duplicate":["File:Same.jpg"]}}}""",
                )
            }
        }

        val outcome = service.upload(tempFile(ByteArray(4)), file)

        val warned = outcome.shouldBeInstanceOf<UploadOutcome.Warned>()
        warned.fileKey shouldBe "STASHED"
        warned.isDuplicateOnly shouldBe true
    }

    @Test
    fun `a stashed upload is published without sending the bytes again`() = runTest {
        var body = ""
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                body = request.body.toByteArray().decodeToString()
                respondJson("""{"upload":{"result":"Success"}}""")
            }
        }

        service.publishStashed("STASHED", file, comment = "acknowledged")

        body.contains("STASHED") shouldBe true
        body.contains("name=\"file\"") shouldBe false
        body.contains("name=\"chunk\"") shouldBe false
    }

    @Test
    fun `a refused upload says which code refused it`() = runTest {
        val service = service { request ->
            if (request.url.parameters["meta"] == "tokens") {
                respondJson("""{"query":{"tokens":{"csrftoken":"T"}}}""")
            } else {
                respondJson(
                    """{"errors":[{"code":"fileexists-no-change","text":"already here"}]}""",
                )
            }
        }

        val outcome = service.upload(tempFile(ByteArray(4)), file)

        outcome.shouldBeInstanceOf<UploadOutcome.Refused>().code shouldBe "fileexists-no-change"
    }

    @Test
    fun `repositories come back under repos, not under the module's own name`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"repos":[
                   {"name":"local","displayname":"Test Wiki","local":true,
                    "url":"https://test.example.org/w/img/${'$'}1","rootUrl":"https://test.example.org",
                    "canUpload":true},
                   {"name":"shared","displayname":"Wikimedia Commons",
                    "url":"https://commons.wikimedia.org/w/img/${'$'}1"}]}}""",
            )
        }

        val repos = service.repositories()

        repos.map { it.name } shouldBe listOf("local", "shared")
        repos.first().isLocal shouldBe true
        repos.first().canUpload shouldBe true
        repos.last().isLocal shouldBe false
        repos.last().canUpload shouldBe false
        repos.last().rootUrl.shouldBeNull()
    }

    @Test
    fun `a wiki that reports no repositories gives an empty list, not a failure`() = runTest {
        val service = service { respondJson("""{"query":{}}""") }

        service.repositories() shouldBe emptyList()
    }

    @Test
    fun `a repository field of the wrong shape is dropped rather than crashing the call`() = runTest {
        val service = service {
            respondJson("""{"query":{"repos":[{"name":"local","displayname":{"nested":1}}]}}""")
        }

        service.repositories().single().displayName shouldBe ""
    }

    @Test
    fun `deleted files are asked for from the archive, not from the live pages`() = runTest {
        var asked = ""
        val service = service { request ->
            asked = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            respondJson(
                """{"query":{"filearchive":[
                   {"ns":6,"title":"File:Gone.jpg"},{"ns":6,"title":"File:Gone2.jpg"}]}}""",
            )
        }

        val gone = service.deletedFiles(prefix = "Gone").toList()

        gone.map { it.title.text } shouldBe listOf("Gone.jpg", "Gone2.jpg")
        asked shouldContain "filearchive"
        asked shouldContain "faprefix"
    }

    @Test
    fun `a range of deleted files passes both ends`() = runTest {
        var asked = ""
        val service = service { request ->
            asked = request.url.encodedQuery + "&" + request.body.toByteArray().decodeToString()
            respondJson("""{"query":{"filearchive":[]}}""")
        }

        service.deletedFiles(from = "A", to = "B").toList()

        asked shouldContain "fafrom"
        asked shouldContain "fato"
    }

    @Test
    fun `a limit on deleted files stops the flow, whatever the wiki sends`() = runTest {
        val service = service {
            respondJson(
                """{"query":{"filearchive":[
                   {"ns":6,"title":"File:A.jpg"},{"ns":6,"title":"File:B.jpg"},
                   {"ns":6,"title":"File:C.jpg"}]}}""",
            )
        }

        service.deletedFiles(limit = 2).toList().size shouldBe 2
    }

    @Test
    fun `downloading writes the bytes to the path it returns`() = runTest {
        val bytes = "not really a jpeg".encodeToByteArray()
        val service = service { request ->
            if (request.url.host == "upload.example") {
                respond(bytes, HttpStatusCode.OK)
            } else {
                respondJson(
                    """{"query":{"pages":[{"pageid":1,"ns":6,"title":"File:Volcano.jpg",
                       "imageinfo":[{"timestamp":"2026-08-01T00:00:00Z","user":"A","size":17,
                       "url":"https://upload.example/v.jpg"}]}]}}""",
                )
            }
        }

        val into = createTempDirectory("kwikibot-download-test")
        val written = service.download(file, into)

        written.name shouldBe "Volcano.jpg"
        written.readBytes() shouldBe bytes
    }

    @Test
    fun `downloading to a named path uses that name, not the file's`() = runTest {
        val service = service { request ->
            if (request.url.host == "upload.example") {
                respond("x".encodeToByteArray(), HttpStatusCode.OK)
            } else {
                respondJson(
                    """{"query":{"pages":[{"pageid":1,"ns":6,"title":"File:Volcano.jpg",
                       "imageinfo":[{"timestamp":"2026-08-01T00:00:00Z","user":"A","size":1,
                       "url":"https://upload.example/v.jpg"}]}]}}""",
                )
            }
        }

        val target = createTempDirectory("kwikibot-download-test").resolve("sub/renamed.jpg")
        val written = service.download(file, target)

        written shouldBe target
        written.readBytes().decodeToString() shouldBe "x"
    }

    @Test
    fun `downloading a file the wiki does not have is a missing page, not an empty file`() = runTest {
        val service = service {
            respondJson("""{"query":{"pages":[{"ns":6,"title":"File:Gone.jpg","missing":true}]}}""")
        }

        assertFailsWith<WikiError.Page.Missing> {
            service.download(file, createTempDirectory("kwikibot-download-test"))
        }
    }

    private fun tempFile(bytes: ByteArray): Path {
        val path = createTempDirectory("kwikibot-upload-test").resolve("Volcano.jpg")
        path.writeBytes(bytes)
        return path
    }

    private fun TestScope.service(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): FileService {
        val client = HttpClient(MockEngine(handler))
        val endpoint = ApiEndpoint("commons.wikimedia.org")
        val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")
        val transport = KtorTransport(
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
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private companion object {
        const val CHUNK_SIZE = 8
        const val CHUNKS = 3
    }
}
