package com.fenakhay.kwikibot.net

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class JsonFieldsTest {

    /** A field of each shape a wiki could send. A key missing from it stands for one the wiki left out. */
    private val fields =
        Json.parseToJsonElement(
                """{"null":null,"object":{},"array":[],"text":"x","number":12,"numeric text":"12",
                   "fraction":1.5,"boolean":true,"boolean text":"true","empty":""}"""
            )
            .jsonObject

    private val keys =
        listOf(
            "absent",
            "null",
            "object",
            "array",
            "text",
            "number",
            "numeric text",
            "fraction",
            "boolean",
            "boolean text",
            "empty",
        )

    private fun <T> read(accessor: JsonObject.(String) -> T): Map<String, T> = keys.associateWith {
        fields.accessor(it)
    }

    /** The table every accessor is checked against, with [values] for the keys it reads a value from. */
    private fun <T> absentBut(vararg values: Pair<String, T>): Map<String, T?> =
        keys.associateWith { null as T? } + values

    @Test
    fun `a string is the text of any value but null, an object or an array`() {
        read { string(it) } shouldBe
            absentBut(
                "text" to "x",
                "number" to "12",
                "numeric text" to "12",
                "fraction" to "1.5",
                "boolean" to "true",
                "boolean text" to "true",
                "empty" to "",
            )
    }

    @Test
    fun `a long is read from a whole number or a string holding one`() {
        read { long(it) } shouldBe absentBut("number" to 12L, "numeric text" to 12L)
    }

    @Test
    fun `an object or an array is the value itself, and nothing else is one`() {
        fields.obj("object") shouldBeSameInstanceAs fields["object"]
        fields.array("array") shouldBeSameInstanceAs fields["array"]
        read { obj(it) } shouldBe absentBut("object" to fields["object"])
        read { array(it) } shouldBe absentBut("array" to fields["array"])
    }

    @Test
    fun `the strings of an array skip null and anything that is not a value`() {
        Json.parseToJsonElement("""["a",1,null,{}]""").jsonArray.strings() shouldBe listOf("a", "1")
        (null as JsonArray?).strings() shouldBe emptyList()
    }

    @Test
    fun `the objects of an array are only the objects in it`() {
        val array = Json.parseToJsonElement("""[{"a":1},"x",null,[],{}]""").jsonArray

        array.objects() shouldBe listOf(array[0], array[4])
        (null as JsonArray?).objects() shouldBe emptyList()
    }
}
