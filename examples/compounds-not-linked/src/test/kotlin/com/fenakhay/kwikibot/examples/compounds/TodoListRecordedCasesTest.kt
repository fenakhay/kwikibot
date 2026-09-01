package com.fenakhay.kwikibot.examples.compounds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.fail

class TodoListRecordedCasesTest {

    private val fixture by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/todo-tasks.json")) {
            "todo-tasks.json missing from test resources"
        }
        Json.parseToJsonElement(stream.reader().readText()).jsonObject
    }

    @Test
    fun `parsing matches every recorded case on the real lists`() {
        val problems = mutableListOf<String>()
        var pages = 0
        var tasks = 0

        for (element in fixture["cases"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            pages++

            val report = TodoList.parsePage(case["input"]!!.jsonPrimitive.content)
            val expected = case["tasks"]!!.jsonArray.map { it.jsonObject }
            tasks += expected.size

            if (report.skippedLines != case["skippedLines"]!!.jsonPrimitive.int) {
                problems += "$name: skipped ${report.skippedLines}, expected " +
                    case["skippedLines"]!!.jsonPrimitive.int
            }

            if (report.tasks.size != expected.size) {
                problems += "$name: ${report.tasks.size} tasks, expected ${expected.size}"
                continue
            }

            report.tasks.zip(expected).forEach { (actual, want) ->
                val wantTitle = want["title"]!!.jsonPrimitive.content
                val wantLang = want["lang"]!!.jsonPrimitive.content
                val wantTerms = want["terms"]!!.jsonArray.map { it.jsonPrimitive.content }

                if (actual.title != wantTitle || actual.lang != wantLang || actual.terms != wantTerms) {
                    problems += "$name: got ${actual.title}/${actual.lang}/${actual.terms}, " +
                        "expected $wantTitle/$wantLang/$wantTerms"
                }
            }
        }

        check(pages > 0 && tasks > 0) { "the fixture is empty; regenerate it" }

        if (problems.isNotEmpty()) {
            fail("the two parsers disagree on $pages page(s):\n" + problems.joinToString("\n"))
        }
    }
}
