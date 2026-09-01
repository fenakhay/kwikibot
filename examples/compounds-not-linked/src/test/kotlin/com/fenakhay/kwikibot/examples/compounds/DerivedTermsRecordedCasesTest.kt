package com.fenakhay.kwikibot.examples.compounds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.fail

class DerivedTermsRecordedCasesTest {

    private val fixture by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/transform.json")) {
            "transform.json missing from test resources"
        }
        Json.parseToJsonElement(stream.reader().readText()).jsonObject
    }

    @Test
    fun `the transform matches every recorded case, byte for byte`() {
        val failures = mutableListOf<String>()
        var checked = 0

        for (element in fixture["cases"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            checked++

            val result = DerivedTerms.add(
                text = case["text"]!!.jsonPrimitive.content,
                title = case["title"]!!.jsonPrimitive.content,
                lang = case["lang"]!!.jsonPrimitive.content,
                terms = case["terms"]!!.jsonArray.map { it.jsonPrimitive.content },
            )

            val wantStatus = case["status"]!!.jsonPrimitive.content
            val wantReason = case["reason"]!!.jsonPrimitive.content
            val wantOutput = case["output"]!!.jsonPrimitive.content

            if (result.status.name.lowercase() != wantStatus) {
                failures += "  $name: status ${result.status.name.lowercase()}, expected $wantStatus"
                continue
            }

            if (result.reason != wantReason) {
                failures += "  $name: reason '${result.reason}', expected '$wantReason'"
            }

            if (result.text != wantOutput) {
                failures += buildString {
                    appendLine("  $name: output differs")
                    appendLine("    expected: ${wantOutput.escaped()}")
                    appendLine("    kotlin: ${result.text.escaped()}")
                }
            }
        }

        check(checked > 0) { "the fixture is empty; regenerate it" }

        if (failures.isNotEmpty()) {
            fail("${failures.size} of $checked cases differ:\n${failures.joinToString("\n")}")
        }
    }

    private fun String.escaped(): String =
        "'" + take(400).replace("\n", "\\n") + "'"
}
