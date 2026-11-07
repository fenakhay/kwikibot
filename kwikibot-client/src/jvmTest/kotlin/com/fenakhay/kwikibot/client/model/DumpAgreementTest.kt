@file:Suppress("DEPRECATION")

package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.model.dump.DumpFormatException
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.write
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** The reader on every platform against the one on the JDK's own parser and on commons-compress. */
class DumpAgreementTest {

    private fun page(index: Int): String {
        val text = buildString {
            repeat(index % 40 + 1) { append("café &lt;b&gt; 🌋 &amp;amp; word$it ") }
        }
        val contributor =
            if (index % 3 == 0) "<ip>10.0.0.$index</ip>"
            else "<username>User $index</username><id>${index + 7}</id>"
        val redirect = if (index % 5 == 0) "<redirect title=\"Target &amp; $index\" />" else ""
        val comment =
            if (index % 2 == 0) "<comment>c &lt; $index</comment>" else "<comment deleted=\"deleted\" />"
        return """
            <page>
              <title>Page ${index}</title>
              <ns>${index % 4}</ns>
              <id>$index</id>
              $redirect
              <revision>
                <id>${index + 1000}</id>
                <parentid>1</parentid>
                <timestamp>2026-10-01T12:00:00Z</timestamp>
                <contributor>$contributor</contributor>
                $comment
                <text bytes="1" xml:space="preserve">$text</text>
                <sha1>x</sha1>
              </revision>
              <revision><id>9</id><text>later</text></revision>
            </page>
        """
    }

    private val xml =
        "<mediawiki xmlns=\"x\"><siteinfo><sitename>T</sitename></siteinfo>" +
            (0 until 4_000).joinToString("") { page(it) } +
            "</mediawiki>"

    private fun bzip2(text: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { out -> BZip2CompressorOutputStream(out).use { it.write(text) } }
            .toByteArray()

    @Test
    fun `both readers read the same pages from plain and from bzip2 at any number of workers`() =
        runBlocking {
            val directory = Files.createTempDirectory("kwikibot-agreement")
            val plain = directory.resolve("dump.xml").also { it.writeBytes(xml.encodeToByteArray()) }
            val compressed =
                directory.resolve("dump.xml.bz2").also { it.writeBytes(bzip2(xml.encodeToByteArray())) }

            for (file in listOf(plain, compressed)) {
                val expected = XmlDump.pages(file).toList()
                expected.size shouldBe 4_000
                val path = Path(file.toString())

                XmlDump.pages(path).toList() shouldBe expected
                for (workers in listOf(1, 3, 8)) {
                    XmlDump.flow(path, workers = workers).toList() shouldBe expected
                }
                XmlDump.pages(path, namespaces = setOf(1)).toList() shouldBe
                    expected.filter { it.namespace == 1 }
            }
        }

    @Test
    fun `the JDK's gzip reads every member and a bad file fails as any other does`() {
        val read = { bytes: ByteArray ->
            XmlDump.pages(Buffer().apply { write(bytes) }, compression = Compression.GZIP)
                .map { it.title }
                .toList()
        }
        read(DumpFixtures.gzipMembers) shouldBe listOf("Volcano", "Talk:Volcano", "Lava")

        assertFailsWith<DumpFormatException> { read("not gzip at all".encodeToByteArray()) }
        assertFailsWith<DumpFormatException> { read(byteArrayOf()) }
        val bytes = DumpFixtures.gzip
        assertFailsWith<DumpFormatException> { read(bytes.copyOf(bytes.size - 3)) }
        val damaged =
            bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2].toInt() xor 0x55).toByte() }
        assertFailsWith<DumpFormatException> { read(damaged) }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KWIKI_DUMP", matches = ".+")
    fun `the old reader takes this long on the dump KWIKI_DUMP names`() {
        val path = java.nio.file.Path.of(System.getenv("KWIKI_DUMP"))
        val started = TimeSource.Monotonic.markNow()
        val pages = XmlDump.pages(path).count()
        println("old reader: ${started.elapsedNow().inWholeMilliseconds} ms, $pages pages")
    }
}
