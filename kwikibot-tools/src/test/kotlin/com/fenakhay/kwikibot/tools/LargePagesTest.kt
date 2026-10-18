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

class LargePagesTest {

    private val target = Files.createTempDirectory("kwikibot-large").resolve("large-pages.json.gz")

    @Test
    fun `the first usable large page from each part of the alphabet is recorded, gzipped`() = runTest {
        val wikis =
            FakeWikis(this) { sent ->
                val talk = sent["gapnamespace"] == "1"
                when {
                    talk ->
                        respondJson(pages(listOf(page("Talk:Zqx ${sent["gapfrom"]}", "t".repeat(250_000)))))
                    sent["gapfrom"] == "A" ->
                        respondJson(
                            pages(
                                listOf(
                                    buildJsonObject { put("ns", 0) },
                                    buildJsonObject { put("title", "Zqx no revisions") },
                                    page("Zqx styles.css", "c".repeat(250_000), model = "sanitized-css"),
                                    page("Zqx no content", null),
                                    page("Zqx too small", "a".repeat(199_999)),
                                    page("Zqx too big", "a".repeat(400_001)),
                                    page("Zqx smallest", "a".repeat(200_000)),
                                    page("Zqx second", "a".repeat(300_000)),
                                )
                            )
                        )
                    // A wiki with nothing that large from here on answers without pages at all.
                    sent["gapfrom"] == "F" -> respondJson("""{"batchcomplete":true}""")
                    sent["gapfrom"] == "L" -> respondJson(pages(emptyList()))
                    else -> respondJson(pages(listOf(page("Zqx largest", "s".repeat(400_000)))))
                }
            }

        recordLargePages(arrayOf(target.toString()), wikis.environment)

        val document = read()
        document.getValue("about").jsonPrimitive.content shouldContain "parallel pre-scan"
        document
            .getValue("pages")
            .jsonArray
            .map { it.jsonObject }
            .map {
                Triple(it.text("wiki"), it.text("title"), it.text("text").length)
            } shouldBe
            listOf(
                Triple("en.wikipedia.org", "Zqx smallest", 200_000),
                Triple("en.wikipedia.org", "Zqx largest", 400_000),
                Triple("en.wikipedia.org", "Talk:Zqx A", 250_000),
                Triple("en.wikipedia.org", "Talk:Zqx F", 250_000),
                Triple("en.wikipedia.org", "Talk:Zqx L", 250_000),
                Triple("en.wikipedia.org", "Talk:Zqx S", 250_000),
            )
        wikis.output.toString() shouldBe "wrote $target\n"
        wikis.log.toString() shouldBe
            "  en.wikipedia.org ns0: 2\n  en.wikipedia.org ns1: 4\n1600000 characters over 6 pages\n"
    }

    @Test
    fun `each part of the alphabet is asked for once, for large articles and then large talk pages`() =
        runTest {
            val wikis = FakeWikis(this) { respondJson(pages(emptyList())) }

            recordLargePages(arrayOf(target.toString()), wikis.environment)

            wikis.sent.map { it.host to (it["gapnamespace"] to it["gapfrom"]) } shouldBe
                listOf("0", "1").flatMap { namespace ->
                    listOf("A", "F", "L", "S").map { "en.wikipedia.org" to (namespace to it) }
                }
            wikis.sent.first().parameters - "gapnamespace" - "gapfrom" shouldBe
                mapOf(
                    "action" to "query",
                    "generator" to "allpages",
                    "gapminsize" to "200000",
                    "gaplimit" to "20",
                    "gapfilterredir" to "nonredirects",
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
                if (sent["gapnamespace"] == "0" && sent["gapfrom"] == "A") {
                    respondJson(
                        pages(
                            listOf(
                                // 250,000 characters, and twice that in bytes: over the largest worth
                                // recording.
                                page("Zqx café too big", "é".repeat(250_000)),
                                // 100,000 characters and 200,000 bytes: the wiki's own gapminsize let it
                                // through.
                                page("Zqx café", "é".repeat(100_000)),
                            )
                        )
                    )
                } else {
                    respondJson(pages(emptyList()))
                }
            }

        recordLargePages(arrayOf(target.toString()), wikis.environment)

        read().getValue("pages").jsonArray.map { it.jsonObject.text("title") } shouldBe listOf("Zqx café")
    }

    private fun read(): JsonObject =
        Json.parseToJsonElement(GZIPInputStream(target.inputStream()).bufferedReader().use { it.readText() })
            .jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
