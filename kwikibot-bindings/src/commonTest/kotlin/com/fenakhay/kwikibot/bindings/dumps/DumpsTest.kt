package com.fenakhay.kwikibot.bindings.dumps

import com.fenakhay.kwikibot.client.model.Compression
import com.fenakhay.kwikibot.client.model.DumpPage
import com.fenakhay.kwikibot.client.model.availableWorkers
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

/** Reading dumps as a binding does: what it reads with nothing given, and with everything given. */
class DumpsTest {

    private fun page(title: String, namespace: Int, id: Int, vararg texts: String): String =
        "<page><title>$title</title><ns>$namespace</ns><id>$id</id>" +
            texts
                .mapIndexed { index, text ->
                    "<revision><id>${id * 10 + index}</id>" +
                        "<timestamp>2026-10-0${index + 1}T12:00:00Z</timestamp>" +
                        "<model>wikitext</model><format>text/x-wiki</format>" +
                        "<text xml:space=\"preserve\">$text</text></revision>"
                }
                .joinToString("") +
            "</page>"

    private val xml =
        "<mediawiki xmlns=\"http://www.mediawiki.org/xml/export-0.11/\" version=\"0.11\" xml:lang=\"en\">" +
            "<siteinfo><sitename>Test</sitename></siteinfo>" +
            page("Volcano", 0, 1, "lava") +
            page("Talk:Volcano", 1, 2, "talk") +
            "</mediawiki>"

    private fun source(): Buffer = Buffer().apply { writeString(xml) }

    private fun file(): Path {
        val path = Path(SystemTemporaryDirectory, "kwikibot-bindings-${Random.nextLong().toULong()}.xml")
        SystemFileSystem.sink(path).buffered().use { it.writeString(xml) }
        return path
    }

    private fun Iterable<DumpPage>.texts(): List<String> = map { "${it.title}=${it.text}" }

    @Test
    fun `compressions are read as the definitions spell them`() {
        compressionNamed("none") shouldBe Compression.NONE
        compressionNamed("gzip") shouldBe Compression.GZIP
        compressionNamed("bzip2") shouldBe Compression.BZIP2
        assertFailsWith<IllegalArgumentException> { compressionNamed("zip") }.message shouldBe
            "unknown compression 'zip'; use none, gzip or bzip2"
    }

    @Test
    fun `with nothing given every namespace is read on one worker per processor`() {
        dumpPagesWithDefaults(source()).toList().texts() shouldBe listOf("Volcano=lava", "Talk:Volcano=talk")
        workersOf(null) shouldBe availableWorkers()
        workersOf(3) shouldBe 3
        dumpNamespacesOf(null) shouldBe emptySet()
        dumpNamespacesOf(listOf(1, 1)) shouldBe setOf(1)
    }

    @Test
    fun `everything given is what is read`() {
        dumpPagesWithDefaults(source(), Compression.NONE, listOf(0)).toList().texts() shouldBe
            listOf("Volcano=lava")
    }

    @Test
    fun `a dump file is read as a flow or a scan alike`() = runTest {
        val path = file()
        dumpFlowWithDefaults(path).toList().texts() shouldBe listOf("Volcano=lava", "Talk:Volcano=talk")
        dumpFlowWithDefaults(path, listOf(1), 1).toList().texts() shouldBe listOf("Talk:Volcano=talk")
        val scanned = mutableListOf<DumpPage>()
        val lock = Mutex()
        scanDumpWithDefaults(path) { page -> lock.withLock { scanned += page } }
        scanned.texts().toSet() shouldBe setOf("Volcano=lava", "Talk:Volcano=talk")
        val main = mutableListOf<DumpPage>()
        scanDumpWithDefaults(path, listOf(0), 1) { main += it }
        main.texts() shouldBe listOf("Volcano=lava")
        SystemFileSystem.delete(path)
    }
}
