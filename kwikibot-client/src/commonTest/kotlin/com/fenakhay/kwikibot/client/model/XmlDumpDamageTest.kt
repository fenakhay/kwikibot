package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.model.dump.BlockChain
import com.fenakhay.kwikibot.client.model.dump.BlockRanges
import com.fenakhay.kwikibot.client.model.dump.BlockScanner
import com.fenakhay.kwikibot.client.model.dump.BlockStretch
import com.fenakhay.kwikibot.client.model.dump.Bzip2BlockDecoder
import com.fenakhay.kwikibot.client.model.dump.Bzip2Source
import com.fenakhay.kwikibot.client.model.dump.DumpFormatException
import com.fenakhay.kwikibot.client.model.dump.MAX_AHEAD_OUTPUT
import com.fenakhay.kwikibot.client.model.dump.MAX_SEGMENT
import com.fenakhay.kwikibot.client.model.dump.MarkerKind
import com.fenakhay.kwikibot.client.model.dump.PageSplitter
import com.fenakhay.kwikibot.client.model.dump.decodeRange
import com.fenakhay.kwikibot.client.model.dump.decodersFor
import com.fenakhay.kwikibot.client.model.dump.gunzipping
import com.fenakhay.kwikibot.client.temporaryDirectory
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write

/** What the readers do with damage, and with the coincidences a marker search runs into. */
class XmlDumpDamageTest {

    private fun source(bytes: ByteArray): Buffer = Buffer().apply { write(bytes) }

    private fun file(name: String, bytes: ByteArray): Path {
        val path = Path(temporaryDirectory("kwikibot-dump"), name)
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
        return path
    }

    private fun filesAvailable(): Boolean = runCatching { temporaryDirectory("kwikibot-dump") }.isSuccess

    private fun flipped(bytes: ByteArray, bit: Long): ByteArray =
        bytes.copyOf().also {
            val at = (bit ushr 3).toInt()
            it[at] = (it[at].toInt() xor (0x80 ushr (bit and 7).toInt())).toByte()
        }

    private fun sequential(bytes: ByteArray): Result<ByteArray> = runCatching {
        Bzip2Source(source(bytes)).buffered().use { it.readByteArray() }
    }

    /** The file through the scanner and the chain, a piece at a time, with decoding ahead on this thread. */
    private suspend fun chained(bytes: ByteArray, pieceSize: Int = 1 shl 20): Result<ByteArray> {
        val stretches = stretchesOf(bytes, pieceSize).iterator()
        val out = Buffer()
        return runCatching {
            BlockChain { if (stretches.hasNext()) stretches.next() else null }
                .follow { piece, count -> out.write(piece, 0, count) }
            out.readByteArray()
        }
    }

    private fun stretchesOf(bytes: ByteArray, pieceSize: Int): List<BlockStretch> {
        val scanner = BlockScanner()
        val decoder = Bzip2BlockDecoder()
        val all = mutableListOf<BlockStretch>()
        var position = 0
        while (true) {
            val length = minOf(pieceSize, bytes.size - position)
            val piece = bytes.copyOfRange(position, position + length)
            position += length
            val ranges = scanner.feed(piece, length, last = position == bytes.size)
            ranges.starts.indices.forEach { all += stretchOf(ranges, it, decoder) }
            if (position == bytes.size || ranges.gaveUp) return all
        }
    }

    private fun stretchOf(ranges: BlockRanges, index: Int, decoder: Bzip2BlockDecoder): BlockStretch {
        val start = ranges.starts[index]
        val end = ranges.ends[index]
        val decoded =
            if (ranges.kinds[index] == MarkerKind.BLOCK) {
                CompletableDeferred(decoder.decodeRange(ranges.bytes, start, end, MAX_AHEAD_OUTPUT))
            } else {
                null
            }
        return BlockStretch(
            ranges.bytes,
            start,
            end,
            ranges.base + start,
            ranges.kinds[index],
            decoded,
            ranges.gaveUp && index == ranges.starts.lastIndex,
        )
    }

    private fun Result<ByteArray>.outcome(): Any =
        fold({ it.decodeToString() }, { (it as DumpFormatException).code })

    private fun markers(bytes: ByteArray): List<Pair<Long, MarkerKind>> {
        val ranges = BlockScanner().feed(bytes, bytes.size, last = true)
        return ranges.starts.toList().zip(ranges.kinds).filter { it.second != MarkerKind.FILE_START }
    }

    private suspend fun parallelTitles(path: Path, workers: Int) =
        XmlDump.flow(path, workers = workers).toList().map { it.title }

    private fun sequentialTitles(path: Path) = XmlDump.pages(path).map { it.title }.toList()

    @Test
    fun `a bit flipped anywhere gives from the chain what it gives from the one thread`() =
        runTest(timeout = 5.minutes) {
            for (bytes in listOf(DumpFixtures.bzip2Streams, DumpFixtures.bzip2FalseEnd)) {
                for (bit in 0L until bytes.size * 8L step 3L) {
                    val damaged = flipped(bytes, bit)
                    chained(damaged).outcome() shouldBe sequential(damaged).outcome()
                }
            }
        }

    @Test
    fun `a file cut anywhere gives from the chain what it gives from the one thread`() = runTest {
        val bytes = DumpFixtures.bzip2Streams
        for (size in 0 until bytes.size step 5) {
            val cut = bytes.copyOf(size)
            chained(cut, pieceSize = 64).outcome() shouldBe sequential(cut).outcome()
        }
    }

    @Test
    fun `a flipped marker is refused on every thread and no page goes missing`() = runTest {
        if (!filesAvailable()) return@runTest
        for (bytes in listOf(DumpFixtures.bzip2Blocks, DumpFixtures.bzip2Streams)) {
            for ((bit, _) in markers(bytes)) {
                val path = file("flipped.xml.bz2", flipped(bytes, bit + 5))
                val alone = assertFailsWith<DumpFormatException> { sequentialTitles(path) }
                val together = assertFailsWith<DumpFormatException> { parallelTitles(path, 4) }
                together.code shouldBe alone.code
            }
        }
    }

    @Test
    fun `a false marker inside a block costs no page`() = runTest {
        if (!filesAvailable()) return@runTest
        val expected = listOf("P1", "P2", "P3")
        for (bytes in listOf(DumpFixtures.bzip2FalseEnd, DumpFixtures.bzip2FalseBlock)) {
            val path = file("marked.xml.bz2", bytes)
            sequentialTitles(path) shouldBe expected
            for (workers in listOf(2, 8)) parallelTitles(path, workers) shouldBe expected
        }
    }

    @Test
    fun `a stream's checksum its header and what follows it are checked on every thread`() = runTest {
        if (!filesAvailable()) return@runTest
        val bytes = DumpFixtures.bzip2Streams
        val end = markers(bytes).first { it.second == MarkerKind.STREAM_END }.first
        val cases =
            listOf(
                flipped(bytes, end + 48 + 3) to DumpFormatException.Code.BAD_CHECKSUM,
                flipped(bytes, ((end + 48 + 32 + 7) ushr 3) * 8 + 1) to DumpFormatException.Code.BAD_DATA,
                flipped(bytes, 1) to DumpFormatException.Code.BAD_DATA,
                (bytes + "trailing".encodeToByteArray()) to DumpFormatException.Code.BAD_DATA,
                (bytes + "BZ".encodeToByteArray()) to DumpFormatException.Code.UNEXPECTED_EOF,
                bytes.copyOf(bytes.size - 3) to DumpFormatException.Code.UNEXPECTED_EOF,
                byteArrayOf() to DumpFormatException.Code.UNEXPECTED_EOF,
            )
        for ((damaged, code) in cases) {
            val path = file("checked.xml.bz2", damaged)
            assertFailsWith<DumpFormatException> { sequentialTitles(path) }.code shouldBe code
            assertFailsWith<DumpFormatException> { parallelTitles(path, 4) }.code shouldBe code
        }
    }

    @Test
    fun `a block that decodes to more than may be held ahead is read all the same`() = runTest {
        if (!filesAvailable()) return@runTest
        val bytes = DumpFixtures.bzip2Bomb
        val ranges = BlockScanner().feed(bytes, bytes.size, last = true)
        val block = ranges.kinds.indexOf(MarkerKind.BLOCK)
        val decoder = Bzip2BlockDecoder()

        decoder.decodeRange(bytes, ranges.starts[block], ranges.ends[block], MAX_AHEAD_OUTPUT) shouldBe null

        val path = file("bomb.xml.bz2", bytes)
        XmlDump.flow(path, workers = 4).toList().single().text.length shouldBe (30 shl 20)
    }

    @Test
    fun `what the scanner holds is bounded and past that it gives up`() {
        val scanner = BlockScanner()
        val piece = ByteArray(1 shl 20)
        val head = "BZh9".encodeToByteArray() + "1AY&SY".encodeToByteArray()
        head.copyInto(piece)

        var fed = 0
        var gaveUp = false
        while (!gaveUp && fed < 16) {
            val ranges = scanner.feed(piece, piece.size, last = false)
            (ranges.bytes.size <= MAX_SEGMENT + 2 * piece.size) shouldBe true
            gaveUp = ranges.gaveUp
            piece.fill(0)
            fed++
        }
        gaveUp shouldBe true
    }

    @Test
    fun `a file with nothing but a marker and zeros is refused as bad data`() = runTest {
        if (!filesAvailable()) return@runTest
        val bytes = ByteArray(MAX_SEGMENT + (2 shl 20))
        ("BZh9" + "1AY&SY").encodeToByteArray().copyInto(bytes)
        val path = file("zeros.xml.bz2", bytes)

        assertFailsWith<DumpFormatException> { parallelTitles(path, 4) }.code shouldBe
            DumpFormatException.Code.BAD_DATA
        assertFailsWith<DumpFormatException> { sequentialTitles(path) }.code shouldBe
            DumpFormatException.Code.BAD_DATA
    }

    @Test
    fun `no marker for longer than any block is refused wherever the scanner stops`() = runTest {
        val good = DumpFixtures.bzip2
        val zeros = ByteArray(2 * MAX_SEGMENT)
        val outcomes =
            listOf(
                good.copyOf(good.size - 10) + zeros,
                good + zeros,
                "BZh9".encodeToByteArray() + zeros,
                zeros,
            )
        for (bytes in outcomes) {
            val failure = chained(bytes).exceptionOrNull() as DumpFormatException
            failure.code shouldBe DumpFormatException.Code.BAD_DATA
        }
    }

    @Test
    fun `every whole page before the damage comes out of the sequence even under a piece's size`() {
        val bytes = DumpFixtures.bzip2Streams
        val second = markers(bytes).filter { it.second == MarkerKind.BLOCK }[1].first
        val cut = bytes.copyOf((second ushr 3).toInt() + 20)
        val titles = mutableListOf<String>()

        assertFailsWith<DumpFormatException> {
            XmlDump.pages(source(cut), compression = Compression.BZIP2).forEach { titles += it.title }
        }

        titles shouldBe firstStreamTitles()
    }

    private fun firstStreamTitles(): List<String> {
        val bytes = DumpFixtures.bzip2Streams
        val end = markers(bytes).first { it.second == MarkerKind.STREAM_END }.first
        val first = bytes.copyOf(((end + 48 + 32 + 7) ushr 3).toInt())
        val titles = mutableListOf<String>()
        val _ = runCatching {
            XmlDump.pages(source(first), compression = Compression.BZIP2).forEach { titles += it.title }
        }
        return titles
    }

    @Test
    fun `every whole page before a cut comes out of a flow however slowly it is collected`() = runTest {
        if (!filesAvailable()) return@runTest
        val xml = DumpFixtures.xml.encodeToByteArray()
        val cut = file("cut.xml", xml.copyOf(xml.size / 2))
        val titles = mutableListOf<String>()

        val failure =
            assertFailsWith<DumpFormatException> {
                XmlDump.flow(cut, workers = 4).collect { page ->
                    delay(50)
                    titles += page.title
                }
            }

        failure.code shouldBe DumpFormatException.Code.UNEXPECTED_EOF
        titles shouldBe listOf("Volcano", "Talk:Volcano")
    }

    @Test
    fun `a damaged page ends a flow in its place and a scan after those before it`() = runTest {
        if (!filesAvailable()) return@runTest
        val page = { title: String -> "<page><title>$title</title><ns>0</ns></page>" }
        val xml = "<mediawiki>${page("A")}${page("B")}${page("&nope;")}${page("D")}</mediawiki>"
        val path = file("bad-page.xml", xml.encodeToByteArray())

        val titles = mutableListOf<String>()
        assertFailsWith<DumpFormatException> {
                XmlDump.flow(path, workers = 4).collect { found ->
                    delay(20)
                    titles += found.title
                }
            }
            .code shouldBe DumpFormatException.Code.BAD_DATA
        titles shouldBe listOf("A", "B")

        val lock = Mutex()
        val scanned = mutableSetOf<String>()
        assertFailsWith<DumpFormatException> {
                XmlDump.scan(path, workers = 1) { found -> lock.withLock { scanned += found.title } }
            }
            .code shouldBe DumpFormatException.Code.BAD_DATA
        scanned.containsAll(listOf("A", "B")) shouldBe true
    }

    @Test
    fun `a scan of a cut file hands on what it holds before it fails`() = runTest {
        if (!filesAvailable()) return@runTest
        val xml = DumpFixtures.xml.encodeToByteArray()
        val path = file("cut-scan.xml", xml.copyOf(xml.size / 2))
        val lock = Mutex()
        val titles = mutableSetOf<String>()

        assertFailsWith<DumpFormatException> {
                XmlDump.scan(path, workers = 4) { page ->
                    delay(20)
                    lock.withLock { titles += page.title }
                }
            }
            .code shouldBe DumpFormatException.Code.UNEXPECTED_EOF
        titles shouldBe setOf("Volcano", "Talk:Volcano")
    }

    @Test
    fun `decoders are as many as there are processors whatever the workers`() {
        decodersFor(1) shouldBe 1
        decodersFor(1000) shouldBe availableWorkers()
    }

    private fun xmlOf(body: String) = body.encodeToByteArray().let { Buffer().apply { write(it) } }

    private fun single(body: String) = XmlDump.pages(xmlOf("<mediawiki>$body</mediawiki>")).single()

    @Test
    fun `a page may have attributes and space in its tags`() {
        single("<page id=\"1\" a='>'><title>A</title></page>").title shouldBe "A"
        single("<page\n><title>B</title></page\n>").title shouldBe "B"
        single("<page><title>C</title><revision><id>1</id></revision >\n</page >").title shouldBe "C"
        single("<page />").title shouldBe ""
        XmlDump.pages(xmlOf("<mediawiki><pages/><page><title>D</title></page></mediawiki >"))
            .single()
            .title shouldBe "D"
    }

    @Test
    fun `tags split across pieces are found`() {
        val xml =
            "<?xml version=\"1.0\"?><mediawiki><page id=\"1\"><title>A</title>" +
                "<revision><id>2</id></revision>" +
                "<revision><id>3</id></revision></page ><page><title>B</title></page></mediawiki>"
        for (size in 1..20) {
            val splitter = PageSplitter()
            val pages = mutableListOf<ByteArray>()
            xml.encodeToByteArray().toList().chunked(size).forEach { chunk ->
                val bytes = chunk.toByteArray()
                splitter.feed(bytes, bytes.size) { pages += it }
            }
            splitter.finish()
            pages.size shouldBe 2
        }
    }

    @Test
    fun `a dump that says it is not in utf-8 is refused and one that says so is read`() {
        val body = "<mediawiki><page><title>A</title></page></mediawiki>"
        val declared = { encoding: String -> "<?xml version=\"1.0\" encoding=\"$encoding\"?>$body" }

        for (encoding in listOf("UTF-8", "utf8", "US-ASCII")) {
            XmlDump.pages(xmlOf(declared(encoding))).single().title shouldBe "A"
        }
        XmlDump.pages(xmlOf("﻿$body")).single().title shouldBe "A"
        XmlDump.pages(xmlOf("<?xml encoding = 'UTF-8' ?>$body")).single().title shouldBe "A"
        XmlDump.pages(xmlOf("<?xml encoding?>$body")).single().title shouldBe "A"
        XmlDump.pages(xmlOf("<?xml encoding=\"UTF-8?>$body")).toList().size shouldBe 1

        assertFailsWith<DumpFormatException> { XmlDump.pages(xmlOf(declared("ISO-8859-1"))).toList() }
            .code shouldBe DumpFormatException.Code.BAD_DATA
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + ByteArray(20)
        assertFailsWith<DumpFormatException> { XmlDump.pages(source(utf16)).toList() }.code shouldBe
            DumpFormatException.Code.BAD_DATA
    }

    @Test
    fun `line ends are read as xml says and a reference to one stays`() {
        val page = single("<page><title>A</title><revision><text>a\r\nb\rc&#13;d</text></revision></page>")
        page.text shouldBe "a\nb\nc\rd"

        val plain = single("<page><title>A</title><revision><text>a\r\nb</text></revision></page>")
        plain.text shouldBe "a\nb"

        val redirect = single("<page><redirect title=\"a\r\nb\tc&#10;d\" /></page>")
        redirect.redirectTarget shouldBe "a b c\nd"
    }

    @Test
    fun `only the first revision of a page is held`() {
        val splitter = PageSplitter(maxPage = 1 shl 16)
        val pages = mutableListOf<ByteArray>()
        val feed = { text: String ->
            val bytes = text.encodeToByteArray()
            splitter.feed(bytes, bytes.size) { pages += it }
        }
        val revision = "<revision><id>9</id><text>${"y".repeat(1 shl 14)}</text></revision>"

        feed("<mediawiki><page><title>H</title><revision><id>1</id><text>first</text></revision>")
        repeat(64) { feed(revision) }
        feed("</page>")
        pages.size shouldBe 1
        feed("<page><title>Next</title></page></mediawiki>")
        splitter.finish()
        pages.size shouldBe 2
    }

    @Test
    fun `a page past what may be held is refused as bad data`() {
        val text = "<page><title>H</title><text>${"y".repeat(1 shl 17)}".encodeToByteArray()
        val inPage = PageSplitter(maxPage = 1 shl 16)
        assertFailsWith<DumpFormatException> { inPage.feed(text, text.size) {} }.code shouldBe
            DumpFormatException.Code.BAD_DATA

        val tag = "<page a=\"${"y".repeat(1 shl 17)}".encodeToByteArray()
        val inTag = PageSplitter(maxPage = 1 shl 16)
        assertFailsWith<DumpFormatException> { inTag.feed(tag, tag.size) {} }.code shouldBe
            DumpFormatException.Code.BAD_DATA
    }

    @Test
    fun `a page cut after its first revision is still a cut page`() {
        val xml =
            "<mediawiki><page><title>A</title><revision><id>1</id></revision><revision>".encodeToByteArray()
        assertFailsWith<DumpFormatException> { XmlDump.pages(source(xml)).toList() }.code shouldBe
            DumpFormatException.Code.UNEXPECTED_EOF
    }

    @Test
    fun `a dtd cut across pieces is refused`() {
        val splitter = PageSplitter()
        val first = "<?xml version=\"1.0\"?>\n<!DOC".encodeToByteArray()
        val second = "TYPE x []><mediawiki>".encodeToByteArray()

        splitter.feed(first, first.size) {}
        assertFailsWith<DumpFormatException> { splitter.feed(second, second.size) {} }.code shouldBe
            DumpFormatException.Code.BAD_DATA
    }

    @Test
    fun `a text full of entities takes time in line with its length`() {
        val started = TimeSource.Monotonic.markNow()
        val text = "&amp;".repeat(400_000)

        val page = single("<page><title>A</title><revision><text>$text</text></revision></page>")

        page.text.length shouldBe 400_000
        (started.elapsedNow().inWholeSeconds < 5) shouldBe true
    }

    private class CountedSource(private val delegate: RawSource) : RawSource by delegate {
        var closed = 0

        override fun close() {
            closed++
            delegate.close()
        }
    }

    private fun gunzipBytes(bytes: ByteArray): ByteArray =
        gunzipping(source(bytes)).buffered().use { it.readByteArray() }

    @Test
    fun `gzip closes its source whether or not the source held gzip`() {
        val bad = CountedSource(source("not gzip at all, an error page".encodeToByteArray()))
        val reading = gunzipping(bad)
        assertFailsWith<DumpFormatException> { reading.readAtMostTo(Buffer(), 16) }.code shouldBe
            DumpFormatException.Code.BAD_DATA
        reading.close()
        bad.closed shouldBe 1

        val unread = CountedSource(source(DumpFixtures.gzip))
        gunzipping(unread).close()
        unread.closed shouldBe 1

        val read = CountedSource(source(DumpFixtures.gzip))
        gunzipping(read).buffered().use { it.readByteArray() }
        read.closed shouldBe 1
    }

    @Test
    fun `gzip through whatever is fastest gives the right code for each failure`() {
        val good = DumpFixtures.gzipShort
        val flip = { at: Int -> good.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() } }

        gunzipBytes(good).decodeToString() shouldBe "hello hello hello"
        assertFailsWith<DumpFormatException> { gunzipBytes(flip(good.size - 8)) }.code shouldBe
            DumpFormatException.Code.BAD_CHECKSUM
        assertFailsWith<DumpFormatException> { gunzipBytes(flip(good.size - 1)) }.code shouldBe
            DumpFormatException.Code.BAD_CHECKSUM
        assertFailsWith<DumpFormatException> { gunzipBytes(flip(2)) }.code shouldBe
            DumpFormatException.Code.BAD_DATA
        assertFailsWith<DumpFormatException> { gunzipBytes(good.copyOf(5)) }.code shouldBe
            DumpFormatException.Code.UNEXPECTED_EOF
        assertFailsWith<DumpFormatException> { gunzipBytes(good.copyOf(good.size - 3)) }.code shouldBe
            DumpFormatException.Code.UNEXPECTED_EOF
        assertFailsWith<DumpFormatException> { gunzipBytes(byteArrayOf()) }.code shouldBe
            DumpFormatException.Code.UNEXPECTED_EOF
    }

    @Test
    fun `a sequence yields the whole of a damaged gzip up to the damage`() {
        val xml = DumpFixtures.gzipMembers
        val titles = mutableListOf<String>()
        assertFailsWith<DumpFormatException> {
            XmlDump.pages(source(xml.copyOf(xml.size - 3)), compression = Compression.GZIP).forEach {
                titles += it.title
            }
        }
        titles.isEmpty() shouldBe false
    }
}
