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

    @Test
    fun `a file past 2 GiB is read to its end a chunk at a time`() = runTest {
        val folder = directory()
        val path = "$folder/sparse.xml"
        val size = 3.0 * (1L shl 30).toDouble()
        val tail = encoded("the end")
        val descriptor = fs.openSync(path, "w")
        fs.writeSync(descriptor, tail, 0, tail.length, size)
        fs.closeSync(descriptor)

        val source = openDumpFile(Path(path))
        val sink = Buffer()
        var total = 0.0
        var largest = 0L
        var last = ""
        while (true) {
            val read = source.readAtMostTo(sink, 1L shl 20)
            if (read < 0) break
            total += read.toDouble()
            largest = maxOf(largest, sink.size)
            if (read < 1L shl 20) last = sink.readByteArray().decodeToString() else sink.clear()
        }
        source.close()
        fs.rmSync(folder, js("({ recursive: true })"))

        total shouldBe size + tail.length
        (largest <= 1L shl 20) shouldBe true
        last.endsWith("the end") shouldBe true
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
