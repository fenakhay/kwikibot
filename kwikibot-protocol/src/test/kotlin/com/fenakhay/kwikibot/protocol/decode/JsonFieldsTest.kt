package com.fenakhay.kwikibot.protocol.decode

import com.fenakhay.kwikibot.model.MwTimestamp
import io.kotest.matchers.nulls.shouldBeNull
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
        json(
            """{"null":null,"object":{},"array":[],"text":"x","number":12,"numeric text":"12",
           "fraction":1.5,"boolean":true,"boolean text":"true","empty":""}"""
        )

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

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

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
    fun `JSON null is absent, while a string reading null is text`() {
        json("""{"key":null}""").string("key").shouldBeNull()
        json("""{"key":"null"}""").string("key") shouldBe "null"
    }

    @Test
    fun `a long is read from a whole number or a string holding one`() {
        read { long(it) } shouldBe absentBut("number" to 12L, "numeric text" to 12L)
    }

    @Test
    fun `an int is read the same way, and a number too large for one is absent`() {
        read { int(it) } shouldBe absentBut("number" to 12, "numeric text" to 12)

        val large = json("""{"key":1099511627776}""")
        large.int("key").shouldBeNull()
        large.long("key") shouldBe 1_099_511_627_776L
    }

    @Test
    fun `a double is read from any number or a string holding one`() {
        read { double(it) } shouldBe absentBut("number" to 12.0, "numeric text" to 12.0, "fraction" to 1.5)
    }

    @Test
    fun `a boolean is read from true or false, bare or quoted`() {
        read { boolean(it) } shouldBe absentBut("boolean" to true, "boolean text" to true)
        json("""{"key":false}""").boolean("key") shouldBe false
    }

    @Test
    fun `a flag is set by true alone, so an empty string is unset`() {
        read { flag(it) } shouldBe keys.associateWith { it == "boolean" || it == "boolean text" }
        fields.flag("empty") shouldBe false
        json("""{"key":1}""").flag("key") shouldBe false
    }

    @Test
    fun `a time is read in either form MediaWiki writes, and infinity is none`() {
        val times =
            json(
                """{"iso":"2026-08-31T21:43:26Z","compact":"20260831214326","never":"infinity",
                   "text":"yesterday"}"""
            )

        times.time("iso") shouldBe MwTimestamp.parse("2026-08-31T21:43:26Z")
        times.time("compact") shouldBe MwTimestamp.parse("2026-08-31T21:43:26Z")
        times.time("never").shouldBeNull()
        times.time("text").shouldBeNull()
        read { time(it) } shouldBe absentBut()
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
