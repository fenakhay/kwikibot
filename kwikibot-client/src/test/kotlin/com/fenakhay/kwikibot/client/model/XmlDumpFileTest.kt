package com.fenakhay.kwikibot.client.model

import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.zip.GZIPOutputStream
import kotlin.io.path.Path
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

/**
 * Dumps as Wikimedia publishes them: compressed, split into streams, and too large for the JDK's defaults.
 */
class XmlDumpFileTest {

    private val directory: Path = Files.createTempDirectory("kwikibot-dump")

    private val header =
        """<mediawiki xmlns="http://www.mediawiki.org/xml/export-0.11/" version="0.11">
          <siteinfo><sitename>Wiktionary</sitename></siteinfo>
        """

    private fun page(
        id: Int,
        namespace: Int = 0,
        text: String = "page $id",
        redirect: String? = null,
    ): String =
        """
          <page>
            <title>${if (namespace == 0) "" else "Talk:"}page $id</title>
            <ns>$namespace</ns>
            <id>$id</id>
            ${redirect?.let { """<redirect title="$it" />""" }.orEmpty()}
            <revision><id>${id * 10}</id><text xml:space="preserve">$text</text></revision>
          </page>
        """

    private fun bzip2(text: String): ByteArray =
        ByteArrayOutputStream()
            .also { out -> BZip2CompressorOutputStream(out).use { it.write(text.toByteArray()) } }
            .toByteArray()

    @Test
    fun `a bzip2 dump is read as Wikimedia publishes it`() {
        val file = directory.resolve("dump.xml.BZ2")
        file.writeBytes(bzip2(header + page(1) + page(2) + "</mediawiki>"))

        XmlDump.pages(file).map { it.pageId }.toList() shouldBe listOf(1L, 2L)
    }

    @Test
    fun `a gzip dump is read too`() {
        val file = directory.resolve("dump.xml.gz")
        GZIPOutputStream(file.outputStream()).use {
            it.write((header + page(1) + "</mediawiki>").toByteArray())
        }

        XmlDump.pages(file).single().text shouldBe "page 1"
    }

    @Test
    fun `pages outside the namespaces asked for are passed over`() {
        val file = directory.resolve("dump.xml")
        file.writeBytes((header + page(1) + page(2, namespace = 1) + page(3) + "</mediawiki>").toByteArray())

        XmlDump.pages(file, setOf(0)).map { it.pageId }.toList() shouldBe listOf(1L, 3L)
        XmlDump.pages(file, setOf(1)).map { it.title }.toList() shouldBe listOf("Talk:page 2")
        XmlDump.pages(file).count() shouldBe 3
    }

    @Test
    fun `a redirect says where it points`() {
        val dump = header + page(1, redirect = "Talk:mountain") + page(2) + "</mediawiki>"

        val pages = XmlDump.pages(dump.byteInputStream()).toList()

        pages[0].isRedirect shouldBe true
        pages[0].redirectTarget shouldBe "Talk:mountain"
        pages[1].redirectTarget shouldBe null
    }

    @Test
    fun `a dump file is not opened until its pages are read`() {
        val missing = XmlDump.pages(Path("no-such-dump.xml"))

        assertFailsWith<java.nio.file.NoSuchFileException> { missing.first() }
    }

    @Test
    fun `a scan hands every page to its workers, in any order`() {
        runBlocking {
            val file = directory.resolve("dump.xml.bz2")
            file.writeBytes(
                bzip2(header + (1..50).joinToString("") { page(it, namespace = it % 2) } + "</mediawiki>")
            )
            val seen = Collections.synchronizedList(mutableListOf<Long>())

            XmlDump.scan(file, workers = 4, namespaces = setOf(0)) { seen += it.pageId }

            seen.sorted() shouldBe (2L..50L step 2).toList()
        }
    }

    @Test
    fun `a multistream dump with its index is read stream by stream, in parallel`() {
        runBlocking {
            val file = directory.resolve("wiki-pages-articles-multistream.xml.bz2")
            val index = directory.resolve("wiki-pages-articles-multistream-index.txt.bz2")

            // As Wikimedia writes it: the header in a stream of its own, a hundred pages to each
            // stream after that, and the closing tag in a stream of its own at the end.
            val streams = mutableListOf(bzip2(header))
            val lines = StringBuilder()
            var offset = streams.first().size.toLong()
            for (chunk in (1..250).chunked(100)) {
                val bytes = bzip2(chunk.joinToString("") { page(it) })
                chunk.forEach { lines.append("$offset:$it:page $it\n") }
                streams += bytes
                offset += bytes.size
            }
            streams += bzip2("</mediawiki>\n")
            file.outputStream().use { out -> streams.forEach(out::write) }
            index.writeBytes(bzip2(lines.toString()))

            val seen = Collections.synchronizedList(mutableListOf<Long>())
            XmlDump.scan(file, workers = 3) { seen += it.pageId }

            seen.sorted() shouldBe (1L..250L).toList()
            XmlDump.pages(file).count() shouldBe 250
        }
    }

    @Test
    fun `an exception in a worker ends the scan and is thrown`() {
        val file = directory.resolve("dump.xml")
        file.writeBytes((header + page(1) + page(2) + "</mediawiki>").toByteArray())

        assertFailsWith<IllegalStateException> {
            runBlocking { XmlDump.scan(file, workers = 2) { error("worker failed on ${it.pageId}") } }
        }
    }

    @Test
    fun `a dump with more entities than the JDK allows by default is read to the end`() {
        // Fifty-one million `&amp;`, past the fifty million the JDK stops at, generated as it is read.
        val pages = 5_100
        val text = "&amp;".repeat(10_000)
        val dump = GeneratedDump(header, page(1, text = text), pages, "</mediawiki>")

        var read = 0
        XmlDump.pages(dump).forEach {
            read++
            if (read == 1) it.text.length shouldBe 10_000
        }

        read shouldBe pages
    }

    /** A dump of [count] copies of [page], made as it is read. */
    private class GeneratedDump(header: String, page: String, private val count: Int, footer: String) :
        InputStream() {
        private val header = header.toByteArray()
        private val page = page.toByteArray()
        private val footer = footer.toByteArray()
        private var part = 0
        private var copies = 0
        private var position = 0

        private fun current(): ByteArray? =
            when {
                part == 0 -> header
                part == 1 && copies < count -> page
                part == 1 -> {
                    part = 2
                    footer
                }
                part == 2 -> footer
                else -> null
            }

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytes = current() ?: return -1
            val taken = minOf(length, bytes.size - position)
            System.arraycopy(bytes, position, buffer, offset, taken)
            position += taken
            if (position == bytes.size) {
                position = 0
                when (part) {
                    0 -> part = 1
                    1 -> copies++
                    else -> part = 3
                }
            }
            return taken
        }
    }
}
