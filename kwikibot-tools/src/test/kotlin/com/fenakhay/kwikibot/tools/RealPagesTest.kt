package com.fenakhay.kwikibot.tools

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.io.path.inputStream
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RealPagesTest {

    private val target = Files.createTempDirectory("kwikibot-roundtrip").resolve("roundtrip-pages.json.gz")

    @Test
    fun `each wiki gives as many pages as asked of it, small enough to commit, gzipped`() = runTest {
        val batches = mutableMapOf<Pair<String, String?>, Int>()
        val wikis =
            FakeWikis(this) { sent ->
                val source = sent.host to sent["grnnamespace"]
                val batch = batches.merge(source, 1, Int::plus)
                when {
                    source == ("en.wiktionary.org" to "0") ->
                        respondJson(
                            pages(
                                listOf(
                                    buildJsonObject { put("ns", 0) },
                                    buildJsonObject { put("title", "Zqx no revisions") },
                                    page("Zqx data.json", "{}", model = "json"),
                                    page("Zqx no content", null),
                                    page("Zqx over $batch", "a".repeat(80_001)),
                                    page("Zqx limit $batch", "a".repeat(80_000)),
                                ) + (1..6).map { page("Zqx $batch.$it", "text") }
                            )
                        )
                    // A namespace with nothing in it ends that wiki's share early, however the wiki says so.
                    sent.host == "ja.wikipedia.org" -> respondJson(pages(emptyList()))
                    sent.host == "commons.wikimedia.org" -> respondJson("""{"batchcomplete":true}""")
                    else -> respondJson(pages((1..10).map { page("Zqx $batch.$it", "text") }))
                }
            }

        recordRealPages(arrayOf(target.toString()), wikis.environment)

        val document = read()
        document.getValue("about").jsonPrimitive.content shouldContain "drawn at random"
        val recorded = document.getValue("pages").jsonArray.map { it.jsonObject }
        recorded.groupingBy { it.text("wiki") }.eachCount() shouldBe
            mapOf(
                "en.wiktionary.org" to 85,
                "en.wikipedia.org" to 65,
                "fr.wikipedia.org" to 20,
                "de.wikipedia.org" to 20,
            )
        // The page at the limit is kept and the one past it skipped; the rest go nowhere near the count.
        recorded.take(8).map { it.text("title") } shouldBe
            listOf("Zqx limit 1") + (1..6).map { "Zqx 1.$it" } + "Zqx limit 2"
        // Seven usable pages a batch, so the ninth batch is cut short at the sixtieth.
        recorded[59].text("title") shouldBe "Zqx 9.3"
        recorded[60].text("title") shouldBe "Zqx 1.1"
        wikis.output.toString() shouldBe "wrote $target\n"
        wikis.log.lines() shouldBe
            listOf(
                "  en.wiktionary.org ns0: 60",
                "  en.wiktionary.org ns10: 25",
                "  en.wikipedia.org ns0: 40",
                "  en.wikipedia.org ns10: 25",
                "  fr.wikipedia.org ns0: 20",
                "  de.wikipedia.org ns0: 20",
                "  ja.wikipedia.org ns0: 0",
                "  commons.wikimedia.org ns6: 0",
                "kept 190, skipped 9 over 80000 bytes",
                "",
            )
    }

    @Test
    fun `random pages are asked for ten at a time, with their wikitext`() = runTest {
        val wikis = FakeWikis(this) { respondJson(pages(emptyList())) }

        recordRealPages(arrayOf(target.toString()), wikis.environment)

        wikis.sent.map { it.host to it["grnnamespace"] } shouldBe
            listOf(
                "en.wiktionary.org" to "0",
                "en.wiktionary.org" to "10",
                "en.wikipedia.org" to "0",
                "en.wikipedia.org" to "10",
                "fr.wikipedia.org" to "0",
                "de.wikipedia.org" to "0",
                "ja.wikipedia.org" to "0",
                "commons.wikimedia.org" to "6",
            )
        wikis.sent.first().parameters - "grnnamespace" shouldBe
            mapOf(
                "action" to "query",
                "generator" to "random",
                "grnlimit" to "10",
                "prop" to "revisions",
                "rvprop" to "content",
                "rvslots" to "main",
                "format" to "json",
                "formatversion" to "2",
                "errorformat" to "plaintext",
            )
        read().getValue("pages").jsonArray.size shouldBe 0
    }

    @Test
    fun `a page is measured in bytes, as the wiki measures it`() = runTest {
        val wikis =
            FakeWikis(this) { sent ->
                if (sent.host == "ja.wikipedia.org") {
                    respondJson(
                        pages(
                            listOf(
                                // 30,000 characters and 90,000 bytes: over the largest worth recording.
                                page("Zqx 大", "日".repeat(30_000)),
                                page("Zqx 小", "日".repeat(20_000)),
                            )
                        )
                    )
                } else {
                    respondJson(pages(emptyList()))
                }
            }

        recordRealPages(arrayOf(target.toString()), wikis.environment)

        read().getValue("pages").jsonArray.map { it.jsonObject.text("title") }.toSet() shouldBe setOf("Zqx 小")
        wikis.log.toString() shouldContain "kept 20, skipped 20 over 80000 bytes"
    }

    private fun read(): JsonObject =
        Json.parseToJsonElement(GZIPInputStream(target.inputStream()).bufferedReader().use { it.readText() })
            .jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
