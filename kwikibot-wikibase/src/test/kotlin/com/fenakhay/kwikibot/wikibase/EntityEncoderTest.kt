package com.fenakhay.kwikibot.wikibase

import com.fenakhay.kwikibot.wikibase.entity.LanguageValue
import com.fenakhay.kwikibot.wikibase.entity.SiteLink
import com.fenakhay.kwikibot.wikibase.value.DataValue
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Snak
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EntityEncoderTest {

    private val entities by lazy {
        val stream =
            checkNotNull(javaClass.getResourceAsStream("/entities.json")) {
                "entities.json missing from test resources"
            }
        val json = Json.parseToJsonElement(stream.reader().readText()).jsonObject
        EntityDecoder.decodeAll(json)
    }

    @Test
    fun `every statement in the fixture survives encoding and decoding`() {
        val statements = entities.values.flatMap { it.statements.values.flatten() }

        (statements.size > 1) shouldBe true

        statements.forEach { statement ->
            EntityDecoder.decodeStatement(EntityEncoder.encodeStatement(statement)) shouldBe statement
        }
    }

    @Test
    fun `qualifiers keep the order they were read in`() {
        val statement =
            entities.values.flatMap { it.statements.values.flatten() }.first { it.qualifiers.size > 1 }

        val encoded = EntityEncoder.encodeStatement(statement)
        val order = checkNotNull(encoded["qualifiers-order"]).jsonArray.map { it.jsonPrimitive.content }

        order shouldBe statement.qualifiers.map { it.property.value }.distinct()
        EntityDecoder.decodeStatement(encoded).qualifiers shouldBe statement.qualifiers
    }

    @Test
    fun `a value type this library does not model is written back byte for byte`() {
        val original =
            Json.parseToJsonElement("""{"type":"future-type","value":{"nested":{"a":1},"list":[1,2]}}""")
                .jsonObject
        val value = EntityDecoder.decodeValue(original)

        value.shouldBeInstanceOf<DataValue.Unknown>()
        EntityEncoder.encodeValue(value) shouldBe original
    }

    @Test
    fun `an entity reference is written with both its id and its number`() {
        val encoded = EntityEncoder.encodeValue(DataValue.EntityRef(EntityId("Q42")))
        val value = checkNotNull(encoded["value"]).jsonObject

        value["entity-type"]?.jsonPrimitive?.content shouldBe "item"
        value["id"]?.jsonPrimitive?.content shouldBe "Q42"
        value["numeric-id"]?.jsonPrimitive?.content shouldBe "42"
    }

    @Test
    fun `a form reference has no number, because its id is not one`() {
        val encoded = EntityEncoder.encodeValue(DataValue.EntityRef(EntityId("L1-F1")))
        val value = checkNotNull(encoded["value"]).jsonObject

        value["entity-type"]?.jsonPrimitive?.content shouldBe "form"
        value["id"]?.jsonPrimitive?.content shouldBe "L1-F1"
        value.containsKey("numeric-id") shouldBe false
    }

    @Test
    fun `no value and unknown value are written back as themselves`() {
        val noValue = Snak.NoValue(EntityId("P40"))
        val someValue = Snak.SomeValue(EntityId("P40"))

        EntityDecoder.decodeSnak(EntityEncoder.encodeSnak(noValue)) shouldBe noValue
        EntityDecoder.decodeSnak(EntityEncoder.encodeSnak(someValue)) shouldBe someValue
    }

    @Test
    fun `an edit sends only what it sets`() {
        val data = EntityEdit().apply { labels = mapOf("en" to "test") }.data()

        data.keys shouldBe setOf("labels")
        data["labels"]?.jsonObject?.get("en")?.jsonObject?.get("value")?.jsonPrimitive?.content shouldBe
            "test"
    }

    @Test
    fun `aliases encode as a list per language, unlike labels`() {
        val encoded =
            EntityEncoder.encodeAliases(
                mapOf(
                    "en" to listOf(LanguageValue("en", "volcano"), LanguageValue("en", "volcanoes")),
                    "fr" to listOf(LanguageValue("fr", "volcan")),
                )
            )

        val english = encoded["en"]!!.jsonArray
        english.map { it.jsonObject["value"]!!.jsonPrimitive.content } shouldBe listOf("volcano", "volcanoes")
        encoded["fr"]!!.jsonArray.single().jsonObject["language"]!!.jsonPrimitive.content shouldBe "fr"
    }

    @Test
    fun `an alias with no language of its own takes the one it is filed under`() {
        val encoded = EntityEncoder.encodeAliases(mapOf("de" to listOf(LanguageValue("", "Vulkan"))))

        encoded["de"]!!.jsonArray.single().jsonObject["language"]!!.jsonPrimitive.content shouldBe "de"
    }

    @Test
    fun `sitelinks encode with their badges`() {
        val encoded =
            EntityEncoder.encodeSiteLinks(
                mapOf(
                    "enwiki" to SiteLink("enwiki", "Volcano", listOf(EntityId("Q17437796"))),
                    "frwiki" to SiteLink("frwiki", "Volcan"),
                )
            )

        val english = encoded["enwiki"]!!.jsonObject
        english["title"]!!.jsonPrimitive.content shouldBe "Volcano"
        english["badges"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Q17437796")

        encoded["frwiki"]!!.jsonObject["badges"]!!.jsonArray.isEmpty() shouldBe true
    }

    @Test
    fun `a sitelink with no site of its own takes the one it is filed under`() {
        val encoded = EntityEncoder.encodeSiteLinks(mapOf("dewiki" to SiteLink("", "Vulkan")))

        encoded["dewiki"]!!.jsonObject["site"]!!.jsonPrimitive.content shouldBe "dewiki"
    }

    @Test
    fun `statements encode grouped under the property they belong to`() {
        val statement = entities.values.first().statements.values.first().first()
        val property = entities.values.first().statements.keys.first()

        val encoded = EntityEncoder.encodeStatements(mapOf(property to listOf(statement, statement)))

        encoded.keys shouldBe setOf(property.value)
        encoded[property.value]!!.jsonArray.size shouldBe 2
    }

    @Test
    fun `encoding nothing produces an empty block rather than null`() {
        EntityEncoder.encodeStatements(emptyMap()).isEmpty() shouldBe true
        EntityEncoder.encodeAliases(emptyMap()).isEmpty() shouldBe true
        EntityEncoder.encodeSiteLinks(emptyMap()).isEmpty() shouldBe true
    }
}
