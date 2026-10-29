package com.fenakhay.kwikibot.bot.source

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The PetScan answer spooled to a temporary file, which only a platform with a filesystem spool to it uses:
 * on JavaScript it is held in memory.
 */
class ExternalSourcesJvmTest {

    @Test
    fun `the petscan answer is deleted from disk even when reading it fails`() = runTest {
        with(ExternalSourcesTest()) {
            val client = HttpClient(MockEngine { respondPlain("Volcano") })
            val before = spooled()
            var during = emptySet<Path>()

            val failure =
                assertFailsWith<IllegalStateException> {
                    ExternalSources.withPetScanTitles(mapOf("psid" to "7"), client, userAgent) { titles ->
                        during = spooled()
                        error("the bot could not use ${titles.first()}")
                    }
                }

            failure.message shouldBe "the bot could not use Volcano"
            (during - before).size shouldBe 1
            spooled() shouldBe before
        }
    }

    private fun spooled(): Set<Path> =
        Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { files ->
            files.filter { it.fileName.toString().startsWith("petscan-") }.toList().toSet()
        }
}
