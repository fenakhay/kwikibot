package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.model.dump.BlockRanges
import com.fenakhay.kwikibot.client.model.dump.BlockScanner
import com.fenakhay.kwikibot.client.model.dump.Bzip2BlockDecoder
import com.fenakhay.kwikibot.client.model.dump.DumpFormatException
import com.fenakhay.kwikibot.client.model.dump.GzipSource
import com.fenakhay.kwikibot.client.model.dump.MarkerKind
import com.fenakhay.kwikibot.client.model.dump.decodeRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.write

/** The corners of the dump format, and what the common reader does when a dump goes wrong in them. */
class XmlDumpEdgesTest {

    private fun source(bytes: ByteArray): Buffer = Buffer().apply { write(bytes) }

    private fun pageOf(body: String): DumpPage =
        XmlDump.pages(source("<mediawiki><page>$body</page></mediawiki>".encodeToByteArray())).single()

    private fun refused(body: String) = assertFailsWith<DumpFormatException> { pageOf(body) }.code

    private fun gunzip(bytes: ByteArray): String =
        GzipSource(source(bytes)).buffered().use { it.readString() }

    @Test
    fun `every kind of character reference reads as what it names`() {
        pageOf("<title>&#65;&#x42;&lt;&gt;&amp;&quot;&apos;&#x1F30B;</title>").title shouldBe
            "AB<>&\"'\uD83C\uDF0B"
    }

    @Test
    fun `references that name nothing are refused and so is one left open`() {
        refused("<title>&nope;</title>") shouldBe DumpFormatException.Code.BAD_DATA
        refused("<title>&#xZZ;</title>") shouldBe DumpFormatException.Code.BAD_DATA
        refused("<title>&#99999999;</title>") shouldBe DumpFormatException.Code.BAD_DATA
        refused("<title>&amp</title>") shouldBe DumpFormatException.Code.BAD_DATA
        refused("<title>&#0;</title>") shouldBe DumpFormatException.Code.BAD_DATA
        refused("<title>&#xD800;</title>") shouldBe DumpFormatException.Code.BAD_DATA
        refused("<title>&#xFFFF;</title>") shouldBe DumpFormatException.Code.BAD_DATA
        refused("<title>&#x+41;</title>") shouldBe DumpFormatException.Code.BAD_DATA
        refused("<title>&#+65;</title>") shouldBe DumpFormatException.Code.BAD_DATA
    }

    @Test
    fun `cdata is read as it stands and one left open is refused`() {
        pageOf("<title>a<![CDATA[<b> & c]]>d &amp; e</title>").title shouldBe "a<b> & cd & e"
        refused("<text><![CDATA[never closed</text>") shouldBe DumpFormatException.Code.UNEXPECTED_EOF
    }

    @Test
    fun `comments processing instructions and unknown elements are passed over`() {
        val page =
            pageOf(
                """
                <!-- a comment with <title>Wrong</title> in it -->
                <?instruction here?>
                <unknown attribute="a > b"><title>Wrong</title></unknown>
                <title>Right</title>
                <revision><id>4</id><model/><comment/><text/></revision>
                """
            )

        page.title shouldBe "Right"
        page.revisionId shouldBe 4L
        page.text shouldBe ""
        page.comment shouldBe ""
    }

    @Test
    fun `a page cut inside a tag or a comment or a declaration is refused`() {
        refused("<!ELEMENT a>") shouldBe DumpFormatException.Code.BAD_DATA
        refused("<!-- never closed") shouldBe DumpFormatException.Code.UNEXPECTED_EOF
        refused("<title>never closed") shouldBe DumpFormatException.Code.UNEXPECTED_EOF
        refused("<title attribute=\"never closed") shouldBe DumpFormatException.Code.UNEXPECTED_EOF
        refused("<unknown>never closed") shouldBe DumpFormatException.Code.UNEXPECTED_EOF
    }

    @Test
    fun `a redirect with no title and a number that is not one read as absent`() {
        val page = pageOf("<title>A</title><ns>x</ns><id>y</id><redirect /><revision><id>z</id></revision>")

        page.isRedirect shouldBe true
        page.redirectTarget.shouldBeNull()
        page.namespace shouldBe 0
        page.pageId shouldBe 0L
        page.revisionId shouldBe 0L
    }

    @Test
    fun `a gzip header with every optional field is passed over`() {
        val plain = DumpFixtures.gzipShort
        val body = plain.copyOfRange(10, plain.size)
        val header =
            byteArrayOf(0x1F, 0x8B.toByte(), 8, 0x1E, 0, 0, 0, 0, 0, 3) +
                byteArrayOf(2, 0, 9, 9) +
                "name\u0000".encodeToByteArray() +
                "comment\u0000".encodeToByteArray() +
                byteArrayOf(0, 0)

        gunzip(header + body) shouldBe "hello hello hello"
    }

    @Test
    fun `a gzip stream that fails its checks or stops short says so`() {
        val good = DumpFixtures.gzipShort
        val flip = { at: Int -> good.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() } }

        assertFailsWith<DumpFormatException> { gunzip(flip(good.size - 8)) }.code shouldBe
            DumpFormatException.Code.BAD_CHECKSUM
        assertFailsWith<DumpFormatException> { gunzip(flip(good.size - 1)) }.code shouldBe
            DumpFormatException.Code.BAD_CHECKSUM
        assertFailsWith<DumpFormatException> { gunzip(flip(2)) }.code shouldBe
            DumpFormatException.Code.BAD_DATA
        assertFailsWith<DumpFormatException> { gunzip(byteArrayOf()) }.code shouldBe
            DumpFormatException.Code.UNEXPECTED_EOF
        assertFailsWith<DumpFormatException> { gunzip(good.copyOf(5)) }.code shouldBe
            DumpFormatException.Code.UNEXPECTED_EOF
    }

    @Test
    fun `a block of a type that does not exist and a stored block with the wrong length are refused`() {
        val header = DumpFixtures.gzipShort.copyOf(10)

        assertFailsWith<DumpFormatException> { gunzip(header + byteArrayOf(0x07)) }.code shouldBe
            DumpFormatException.Code.BAD_DATA
        assertFailsWith<DumpFormatException> { gunzip(header + byteArrayOf(0x01, 1, 0, 0, 0)) }.code shouldBe
            DumpFormatException.Code.BAD_DATA
    }

    private fun blocksOf(ranges: BlockRanges) = ranges.kinds.count { it == MarkerKind.BLOCK }

    @Test
    fun `the scanner finds each block and each stream's end`() {
        val bytes = DumpFixtures.bzip2Streams
        val whole = BlockScanner().feed(bytes, bytes.size, last = true)

        blocksOf(whole) shouldBe 2
        whole.kinds.count { it == MarkerKind.STREAM_END } shouldBe 2
        whole.kinds.first() shouldBe MarkerKind.FILE_START
        whole.ends.last() shouldBe bytes.size * 8L

        val cut = BlockScanner().feed(bytes, bytes.size - 12, last = true)
        blocksOf(cut) shouldBe 2
        cut.kinds.count { it == MarkerKind.STREAM_END } shouldBe 1
    }

    @Test
    fun `the scanner finds the same blocks however the file is cut into pieces`() {
        val bytes = DumpFixtures.bzip2Streams
        val whole = BlockScanner().feed(bytes, bytes.size, last = true)
        for (size in listOf(1, 7, 64, 1000)) {
            val scanner = BlockScanner()
            val found = mutableListOf<Pair<Long, MarkerKind>>()
            var position = 0
            while (position < bytes.size) {
                val length = minOf(size, bytes.size - position)
                val piece = bytes.copyOfRange(position, position + length)
                position += length
                val ranges = scanner.feed(piece, length, last = position == bytes.size)
                ranges.starts.indices.forEach {
                    found += (ranges.base + ranges.starts[it]) to ranges.kinds[it]
                }
            }
            found shouldBe whole.starts.toList().zip(whole.kinds)
        }
    }

    @Test
    fun `a range cut short does not decode and one left whole says where its block ended`() {
        val bytes = DumpFixtures.bzip2
        val ranges = BlockScanner().feed(bytes, bytes.size, last = true)
        val block = ranges.kinds.indexOf(MarkerKind.BLOCK)
        val start = ranges.starts[block]
        val end = ranges.ends[block]
        val decoder = Bzip2BlockDecoder()

        decoder.decodeRange(ranges.bytes, start, (start + end) / 2).shouldBeNull()

        val decoded = decoder.decodeRange(ranges.bytes, start, end)
        decoded?.output?.decodeToString() shouldBe DumpFixtures.xml
        decoded?.endBit shouldBe end
    }
}
