package com.fenakhay.kwikibot.wikitext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.fail

class RealPageRoundTripTest {

    private val pages by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/roundtrip-pages.json.gz")) {
            "roundtrip-pages.json.gz missing from test resources"
        }
        val text = GZIPInputStream(stream).reader().readText()
        Json.parseToJsonElement(text).jsonObject["pages"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun `every recorded page survives parse and serialize unchanged`() {
        check(pages.isNotEmpty()) { "the recorded corpus is empty" }

        val failures = pages.mapNotNull { page ->
            val where = page["wiki"]!!.jsonPrimitive.content + ": " +
                page["title"]!!.jsonPrimitive.content
            val text = page["text"]!!.jsonPrimitive.content

            val output = runCatching { Wikitext.parse(text).serialize() }
                .getOrElse { thrown ->
                    return@mapNotNull "  $where — threw ${thrown::class.simpleName}: ${thrown.message}"
                }

            if (output == text) return@mapNotNull null

            val at = text.zip(output).indexOfFirst { (a, b) -> a != b }
                .let { if (it == -1) minOf(text.length, output.length) else it }
            "  $where — differs at character $at of ${text.length}\n" +
                "      in:  ${text.around(at)}\n" +
                "      out: ${output.around(at)}"
        }

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of ${pages.size} real pages did not round-trip:\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    private fun String.around(index: Int): String =
        substring((index - CONTEXT).coerceAtLeast(0), (index + CONTEXT).coerceAtMost(length))
            .replace("\\", "\\\\")
            .replace("\n", "\n")
            .replace("\t", "\t")

    private companion object {
        const val CONTEXT = 40
    }
}
