package com.fenakhay.kwikibot.client.model

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

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
    fun `a file spool leaves nothing behind once closed`() = runTest {
        val prefix = "kwikibot-spool-closed"
        FileSpool(prefix).use { spooled ->
            spooled.fill(ByteReadChannel("kept until closed"))
            spooled.lines().first() shouldBe "kept until closed"
        }

        SystemFileSystem.list(SystemTemporaryDirectory).filter { it.name.startsWith(prefix) }.shouldBeEmpty()
    }

    @Test
    fun `the platform's spool round-trips too`() = runTest {
        spool("kwikibot-spool-platform").use { spooled ->
            spooled.fill(ByteReadChannel("only line"))

            spooled.lines().toList() shouldBe listOf("only line")
        }
    }
}
