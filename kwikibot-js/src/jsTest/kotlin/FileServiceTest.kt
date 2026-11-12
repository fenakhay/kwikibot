import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Date
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

class FileServiceTest {

    private val mock = MockWiki()

    private fun runWiki(block: suspend TestScope.(FileService) -> Unit) = runTest {
        val wiki = mock.open()
        block(wiki.files)
        wiki.close()
    }

    private val imageInfo =
        """{"query":{"pages":[{"pageid":1,"ns":6,"title":"File:Fire.jpg","imageinfo":[
          {"url":"https://example.org/Fire.jpg","descriptionurl":"https://example.org/wiki/File:Fire.jpg",
           "size":1024,"width":40,"height":30,"mime":"image/jpeg","mediatype":"BITMAP","sha1":"abc",
           "timestamp":"2026-08-31T21:43:26Z","user":"Alice","comment":"first"}]}]}}"""

    @Test
    fun `info and latest read each version`() = runWiki { files ->
        mock.canned["imageinfo"] = imageInfo

        val found = files.info(arrayOf("Fire.jpg"), versions = 3).await().single()
        found.title shouldBe "File:Fire.jpg"
        mock.requests.last { it["prop"] == "imageinfo" }["iilimit"] shouldBe "3"
        mock.requests.last { it["prop"] == "imageinfo" }["titles"] shouldBe "File:Fire.jpg"

        val info = found.versions.single()
        listOf(
            info.url,
            info.descriptionUrl,
            info.mimeType,
            info.mediaType,
            info.sha1,
            info.user,
            info.comment,
        ) shouldBe
            listOf(
                "https://example.org/Fire.jpg",
                "https://example.org/wiki/File:Fire.jpg",
                "image/jpeg",
                "BITMAP",
                "abc",
                "Alice",
                "first",
            )
        listOf(info.size, info.width, info.height, info.hasDimensions) shouldBe listOf(1024.0, 40, 30, true)
        info.timestamp?.getTime() shouldBe Date("2026-08-31T21:43:26Z").getTime()

        files.latest("File:Fire.jpg").await()?.sha1 shouldBe "abc"
    }

    @Test
    fun `usage and globalUsage walk what uses a file`() = runWiki { files ->
        mock.canned["imageusage"] = """{"query":{"imageusage":[{"ns":0,"title":"Volcano"}]}}"""
        mock.canned["globalusage"] =
            """{"query":{"pages":[{"ns":6,"title":"File:Fire.jpg","globalusage":
               [{"wiki":"frwiki","title":"Volcan","url":"https://fr.example.org/wiki/Volcan"}]}]}}"""

        files.usage("Fire.jpg", limit = 5).take() shouldBe listOf("Volcano")
        mock.requests.last { it["list"] == "imageusage" }["iutitle"] shouldBe "File:Fire.jpg"

        val use = files.globalUsage("Fire.jpg").take().single()
        listOf(use.wiki, use.title, use.url) shouldBe
            listOf("frwiki", "Volcan", "https://fr.example.org/wiki/Volcan")
    }

    @Test
    fun `duplicatesOf and deletedFiles send their filters`() = runWiki { files ->
        mock.canned["allimages"] = """{"query":{"allimages":[{"name":"Fire.jpg"}]}}"""
        mock.canned["filearchive"] = """{"query":{"filearchive":[{"ns":6,"title":"File:Gone.jpg"}]}}"""

        files.duplicatesOf("abc").await().toList() shouldBe listOf("File:Fire.jpg")
        mock.requests.last { it["list"] == "allimages" }["ailimit"] shouldBe files.duplicateLimit.toString()

        files
            .deletedFiles(optionsOf("from" to "A", "to" to "Z", "prefix" to "G", "limit" to 2))
            .take() shouldBe listOf("File:Gone.jpg")
        val sent = mock.requests.last { it["list"] == "filearchive" }
        listOf(sent["fafrom"], sent["fato"], sent["faprefix"]) shouldBe listOf("A", "Z", "G")
    }

    @Test
    fun `repositories says where files come from`() = runWiki { files ->
        mock.canned["filerepoinfo"] =
            """{"query":{"repos":[{"name":"shared","displayname":"Commons","rootUrl":"https://r.example.org",
               "url":"https://u.example.org","canUpload":true}]}}"""

        val repo = files.repositories().await().single()
        listOf(repo.name, repo.displayName, repo.isLocal, repo.url, repo.rootUrl, repo.canUpload) shouldBe
            listOf("shared", "Commons", false, "https://u.example.org", "https://r.example.org", true)
    }

    private fun temporary(name: String): Path = Path(SystemTemporaryDirectory, "kwikibot-js-test-$name")

    @Test
    fun `download writes the file and answers with where`() = runWiki { files ->
        if (!hasFileSystem()) return@runWiki
        mock.canned["imageinfo"] = imageInfo
        mock.canned[""] = "the bytes"
        val target = temporary("download")

        files.download("Fire.jpg", target.toString()).await() shouldBe target.toString()

        SystemFileSystem.source(target).buffered().use { it.readString() } shouldBe "the bytes"
        SystemFileSystem.delete(target)
    }

    @Test
    fun `upload sends the file and reports each outcome`() = runWiki { files ->
        if (!hasFileSystem()) return@runWiki
        val source = temporary("upload")
        SystemFileSystem.sink(source).buffered().use { it.writeString("some bytes") }

        mock.canned[""] =
            """{"upload":{"result":"Success","imageinfo":{"url":"https://example.org/Fire.jpg","sha1":"abc"}}}"""
        val uploaded =
            files
                .upload(
                    source.toString(),
                    "Fire.jpg",
                    optionsOf("comment" to "why", "text" to "about", "ignoreWarnings" to true),
                )
                .await()
        uploaded.kind shouldBe "uploaded"
        uploaded.title shouldBe "File:Fire.jpg"
        uploaded.info?.sha1 shouldBe "abc"
        val body = mock.bodies.last()
        listOf("some bytes", "ignorewarnings", "about", "why").forEach { body shouldContain it }

        mock.canned[""] =
            """{"upload":{"result":"Warning","warnings":{"duplicate":"Ash.jpg"},"filekey":"k1"}}"""
        val warned = files.upload(source.toString(), "Fire.jpg").await()
        warned.kind shouldBe "warned"
        (warned.warnings.asDynamic().duplicate as String) shouldBe "Ash.jpg"
        warned.fileKey shouldBe "k1"
        warned.isDuplicateOnly shouldBe true

        mock.canned[""] = """{"error":{"code":"fileexists","info":"no"}}"""
        val refused = files.publishStashed("k1", "Fire.jpg", "why", "about").await()
        refused.kind shouldBe "refused"
        refused.code shouldBe "fileexists"
        refused.detail shouldBe "no"
        mock.bodies.last() shouldContain "k1"

        SystemFileSystem.delete(source)
    }

    @Test
    fun `upload rejects a file that is not there`() = runWiki { files ->
        shouldThrow<Throwable> { files.upload(temporary("absent").toString(), "Fire.jpg").await() }
    }

    @Test
    fun `without a filesystem a file operation says so`() = runTest {
        requireFileSystem("download", available = true)
        shouldThrow<IllegalStateException> { requireFileSystem("download", available = false) }
            .message shouldContain "Node only"
    }

    @Test
    fun `the sizes are the library's own`() = runWiki { files ->
        files.defaultChunk shouldBe 5 * 1024 * 1024
        files.duplicateLimit shouldBe 10
    }
}
