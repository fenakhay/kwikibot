package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.model.dump.Bzip2Source
import com.fenakhay.kwikibot.client.model.dump.DumpFormatException
import com.fenakhay.kwikibot.client.model.dump.GzipSource
import com.fenakhay.kwikibot.client.temporaryDirectory
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.write

/** The reader that runs on every platform, on dumps made by the system's own compressors. */
class XmlDumpCommonTest {

    private fun source(bytes: ByteArray): Buffer = Buffer().apply { write(bytes) }

    private fun RawSource.text(): String = buffered().use { it.readString() }

    private fun file(name: String, bytes: ByteArray): Path {
        val path = Path(temporaryDirectory("kwikibot-dump"), name)
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
        return path
    }

    private fun titles(compression: Compression, bytes: ByteArray, namespaces: Set<Int> = emptySet()) =
        XmlDump.pages(source(bytes), namespaces, compression).map { it.title }.toList()

    @Test
    fun `bzip2 decodes to what was compressed and so do concatenated streams`() {
        Bzip2Source(source(DumpFixtures.bzip2)).text() shouldBe DumpFixtures.xml
        Bzip2Source(source(DumpFixtures.bzip2Streams)).text() shouldBe DumpFixtures.xml
    }

    @Test
    fun `gzip decodes to what was compressed and so do concatenated and stored members`() {
        GzipSource(source(DumpFixtures.gzip)).text() shouldBe DumpFixtures.xml
        GzipSource(source(DumpFixtures.gzipMembers)).text() shouldBe DumpFixtures.xml
        GzipSource(source(DumpFixtures.gzipStored)).text() shouldBe DumpFixtures.xml
    }

    @Test
    fun `a page says what the dump said`() {
        val pages = XmlDump.pages(source(DumpFixtures.xml.encodeToByteArray())).toList()

        pages.map { it.title } shouldBe listOf("Volcano", "Talk:Volcano", "Lava")
        val first = pages.first()
        first.namespace shouldBe 0
        first.pageId shouldBe 1L
        first.revisionId shouldBe 10L
        first.text shouldBe "<b>lava</b> café 🌋"
        first.contributor shouldBe "Someone"
        first.comment shouldBe "c & d"
        first.timestamp shouldBe Instant.parse("2026-10-01T12:00:00Z")
        first.isRedirect shouldBe false
        pages.last().isRedirect shouldBe true
        pages.last().redirectTarget shouldBe "Volcano & co"
        pages.last().text.length shouldBe 5000
    }

    @Test
    fun `namespaces are filtered as the dump is read`() {
        titles(Compression.BZIP2, DumpFixtures.bzip2, setOf(1)) shouldBe listOf("Talk:Volcano")
        titles(Compression.GZIP, DumpFixtures.gzip, setOf(0)) shouldBe listOf("Volcano", "Lava")
    }

    @Test
    fun `a dump cut short yields what it held and then says so`() {
        val xml = DumpFixtures.xml.encodeToByteArray()
        val cut = xml.copyOf(xml.size / 2)
        val pages = mutableListOf<String>()

        val failure =
            assertFailsWith<DumpFormatException> {
                XmlDump.pages(source(cut)).forEach { pages += it.title }
            }

        failure.code shouldBe DumpFormatException.Code.UNEXPECTED_EOF
        pages shouldBe listOf("Volcano", "Talk:Volcano")

        for (compressed in
            listOf(DumpFixtures.bzip2 to Compression.BZIP2, DumpFixtures.gzip to Compression.GZIP)) {
            val (bytes, compression) = compressed
            assertFailsWith<DumpFormatException> { titles(compression, bytes.copyOf(bytes.size - 3)) }
                .code shouldBe DumpFormatException.Code.UNEXPECTED_EOF
        }
    }

    @Test
    fun `damaged data fails and never hangs`() {
        val damaged = { bytes: ByteArray, at: Int ->
            bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x55).toByte() }
        }
        for (at in DumpFixtures.bzip2.indices.step(7)) {
            assertFailsWith<DumpFormatException> {
                Bzip2Source(source(damaged(DumpFixtures.bzip2, at))).text()
            }
        }
        for (at in DumpFixtures.gzip.indices.step(7)) {
            val _ = runCatching { GzipSource(source(damaged(DumpFixtures.gzip, at))).text() }
        }
        assertFailsWith<DumpFormatException> { Bzip2Source(source("not bzip2".encodeToByteArray())).text() }
        assertFailsWith<DumpFormatException> { GzipSource(source("not gzip!!".encodeToByteArray())).text() }
    }

    @Test
    fun `a dtd and an entity nobody defined are refused`() {
        val dtd = """<!DOCTYPE foo [<!ENTITY x "y">]><mediawiki></mediawiki>""".encodeToByteArray()
        val entity =
            "<mediawiki><page><title>&x;</title><ns>0</ns><revision><id>1</id></revision></page></mediawiki>"
                .encodeToByteArray()

        assertFailsWith<DumpFormatException> { XmlDump.pages(source(dtd)).toList() }.code shouldBe
            DumpFormatException.Code.BAD_DATA
        assertFailsWith<DumpFormatException> { XmlDump.pages(source(entity)).toList() }.code shouldBe
            DumpFormatException.Code.BAD_DATA
    }

    @Test
    fun `a history dump keeps the first revision and a contributor's id is not the revision's`() {
        val xml =
            """
            <mediawiki><page><title>A</title><ns>0</ns><id>1</id>
            <revision><id>2</id><contributor><username>U</username><id>9</id></contributor><text>one</text></revision>
            <revision><id>3</id><text>two</text></revision>
            </page></mediawiki>
            """
                .trimIndent()
                .encodeToByteArray()

        val page = XmlDump.pages(source(xml)).single()

        page.revisionId shouldBe 2L
        page.text shouldBe "one"
    }

    @Test
    fun `files are read by what their name says at any number of workers`() = runTest {
        if (!filesAvailable()) return@runTest
        val files =
            listOf(
                file("dump.xml", DumpFixtures.xml.encodeToByteArray()),
                file("dump.xml.gz", DumpFixtures.gzip),
                file("dump.xml.bz2", DumpFixtures.bzip2),
                file("multi.xml.bz2", DumpFixtures.bzip2Streams),
            )
        val expected = listOf("Volcano", "Talk:Volcano", "Lava")

        for (path in files) {
            XmlDump.pages(path).map { it.title }.toList() shouldBe expected
            for (workers in listOf(1, 2, 8)) {
                XmlDump.flow(path, workers = workers).toList().map { it.title } shouldBe expected

                val lock = Mutex()
                val seen = mutableListOf<String>()
                XmlDump.scan(path, workers = workers) { page -> lock.withLock { seen += page.title } }
                seen.sorted() shouldBe expected.sorted()
            }
        }
    }

    @Test
    fun `a parallel read of a damaged or cut file fails and says why`() = runTest {
        if (!filesAvailable()) return@runTest
        val bytes = DumpFixtures.bzip2

        assertFailsWith<DumpFormatException> {
                XmlDump.flow(file("cut.xml.bz2", bytes.copyOf(bytes.size - 12)), workers = 4).toList()
            }
            .code shouldBe DumpFormatException.Code.UNEXPECTED_EOF
        val damaged =
            bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2].toInt() xor 0x55).toByte() }
        assertFailsWith<DumpFormatException> {
            XmlDump.flow(file("bad.xml.bz2", damaged), workers = 4).toList()
        }
        assertFailsWith<DumpFormatException> {
            XmlDump.flow(file("not.xml.bz2", "not bzip2 at all".encodeToByteArray()), workers = 4).toList()
        }
    }

    @Test
    fun `a scan needs a worker and the first failure in a worker ends it`() = runTest {
        if (!filesAvailable()) return@runTest
        val path = file("dump.xml", DumpFixtures.xml.encodeToByteArray())

        assertFailsWith<IllegalArgumentException> { XmlDump.scan(path, workers = 0) {} }
        assertFailsWith<IllegalArgumentException> { XmlDump.flow(path, workers = 0) }
        assertFailsWith<IllegalStateException> { XmlDump.scan(path, workers = 2) { error("stop") } }
    }

    private fun filesAvailable(): Boolean = runCatching { temporaryDirectory("kwikibot-dump") }.isSuccess
}
