package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.internal.temporaryDirectory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.SystemFileSystem

class SpoolTest {

    private val kinds =
        listOf("a file" to { FileSpool("kwikibot-spool-test") }, "memory" to { MemorySpool() })

    @Test
    fun `what was filled in is read back a line at a time whatever ends the lines`() = runTest {
        for ((kind, spool) in kinds) {
            spool().use { spooled ->
                spooled.fill(ByteReadChannel("first\nsecond\r\nthird"))

                Pair(kind, spooled.lines().toList()) shouldBe Pair(kind, listOf("first", "second", "third"))
            }
        }
    }

    @Test
    fun `an empty answer reads back as no lines`() = runTest {
        for ((_, spool) in kinds) {
            spool().use { spooled ->
                spooled.fill(ByteReadChannel(ByteArray(0)))

                spooled.lines().toList().shouldBeEmpty()
            }
        }
    }

    @Test
    fun `a file spool keeps its file in the temporary directory until closed`() = runTest {
        val prefix = "kwikibot-spool-closed"
        fun files() = SystemFileSystem.list(temporaryDirectory).filter { it.name.startsWith(prefix) }

        FileSpool(prefix).use { spooled ->
            spooled.fill(ByteReadChannel("kept until closed"))
            files() shouldHaveSize 1
            spooled.lines().first() shouldBe "kept until closed"
        }

        files().shouldBeEmpty()
    }

    @Test
    fun `the temporary directory is an absolute path to a directory`() {
        temporaryDirectory.isAbsolute shouldBe true
        SystemFileSystem.metadataOrNull(temporaryDirectory)?.isDirectory shouldBe true
    }

    @Test
    fun `the platform's spool round-trips too`() = runTest {
        spool("kwikibot-spool-platform").use { spooled ->
            spooled.fill(ByteReadChannel("only line"))

            spooled.lines().toList() shouldBe listOf("only line")
        }
    }
}
