package com.fenakhay.kwikibot.client.model.dump

import com.fenakhay.kwikibot.client.model.XmlDump
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import org.khronos.webgl.Uint8Array

class NodeFileSourceTest {

    private val fs: dynamic = js("process.getBuiltinModule('fs')")
    private val os: dynamic = js("process.getBuiltinModule('os')")

    private fun directory(): String = fs.mkdtempSync(os.tmpdir() + "/kwikibot-source-") as String

    private fun encoded(text: String): Uint8Array =
        js("new TextEncoder()").encode(text).unsafeCast<Uint8Array>()

    /**
     * On Windows, a write far past a file's end first fills the gap with zeros, 3 GiB of them here, which
     * outlasts the test's timeout. A file lengthened with `ftruncate` reads as zeros without any being
     * written, on every system.
     */
    @Test
    fun `a file past 2 GiB is read to its end a chunk at a time`() = runTest {
        val folder = directory()
        val path = "$folder/sparse.xml"
        val size = 3.0 * (1L shl 30).toDouble()
        val head = encoded("the start")
        val descriptor = fs.openSync(path, "w")
        fs.writeSync(descriptor, head)
        fs.ftruncateSync(descriptor, size)
        fs.closeSync(descriptor)

        val source = openDumpFile(Path(path))
        val sink = Buffer()
        var total = 0.0
        var largest = 0L
        var first = ""
        while (true) {
            val read = source.readAtMostTo(sink, 1L shl 20)
            if (read < 0) break
            largest = maxOf(largest, sink.size)
            if (total == 0.0) first = sink.readByteArray(head.length).decodeToString()
            total += read.toDouble()
            sink.clear()
        }
        source.close()
        fs.rmSync(folder, js("({ recursive: true })"))

        total shouldBe size
        (largest <= 1L shl 20) shouldBe true
        first shouldBe "the start"
    }

    @Test
    fun `a dump is read through it`() {
        val folder = directory()
        val path = "$folder/dump.xml"
        fs.writeFileSync(path, encoded("<mediawiki><page><title>A</title></page></mediawiki>"))

        XmlDump.pages(Path(path)).single().title shouldBe "A"
        fs.rmSync(folder, js("({ recursive: true })"))
    }

    @Test
    fun `a file that is not there and one that cannot be read say so`() {
        val folder = directory()
        shouldThrow<FileNotFoundException> { openDumpFile(Path("$folder/missing.xml")) }

        val source = openDumpFile(Path(folder))
        shouldThrow<kotlinx.io.IOException> { source.readAtMostTo(Buffer(), 16) }
        source.close()
        source.close()
        shouldThrow<IllegalStateException> { source.readAtMostTo(Buffer(), 16) }
        fs.rmSync(folder, js("({ recursive: true })"))
    }

    @Test
    fun `asking for nothing reads nothing`() {
        val folder = directory()
        val path = "$folder/any.xml"
        fs.writeFileSync(path, encoded("any"))
        val source = openDumpFile(Path(path))

        source.readAtMostTo(Buffer(), 0) shouldBe 0L
        source.close()
        fs.rmSync(folder, js("({ recursive: true })"))
    }
}
