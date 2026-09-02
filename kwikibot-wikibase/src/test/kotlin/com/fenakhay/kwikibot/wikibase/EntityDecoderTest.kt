package com.fenakhay.kwikibot.wikibase

import com.fenakhay.kwikibot.wikibase.entity.Entity
import com.fenakhay.kwikibot.wikibase.value.DataValue
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Rank
import com.fenakhay.kwikibot.wikibase.value.Snak
import com.fenakhay.kwikibot.wikibase.value.Statement
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class EntityDecoderTest {

    private val entities by lazy {
        val stream =
            checkNotNull(javaClass.getResourceAsStream("/entities.json")) {
                "entities.json missing from test resources"
            }
        val json = Json.parseToJsonElement(stream.reader().readText()).jsonObject
        EntityDecoder.decodeAll(json)
    }

    private val douglasAdams by lazy {
        entities.getValue(EntityId("Q42")).shouldBeInstanceOf<Entity.Item>()
    }

    private val instanceOf by lazy {
        entities.getValue(EntityId("P31")).shouldBeInstanceOf<Entity.Property>()
    }

    private val lexeme by lazy {
        entities.getValue(EntityId("L1")).shouldBeInstanceOf<Entity.Lexeme>()
    }

    @Test
    fun `every entity in the fixture decodes`() {
        entities.keys.map { it.value }.sorted() shouldBe listOf("L1", "P31", "Q42")
    }

    @Test
    fun `an item carries its labels and descriptions`() {
        douglasAdams.label("en") shouldBe "Douglas Adams"
        douglasAdams.description("en")?.isNotEmpty() shouldBe true
    }

    @Test
    fun `an English label may live under mul, so asking for en must find it`() {
        douglasAdams.labelIn("en").shouldBeNull()
        douglasAdams.labelIn("mul") shouldBe "Douglas Adams"
        douglasAdams.label("en") shouldBe "Douglas Adams"
    }

    @Test
    fun `a label that really is per-language is returned as itself`() {
        instanceOf.labelIn("en") shouldBe "instance of"
        instanceOf.label("en") shouldBe "instance of"
    }

    @Test
    fun `entity ids know what they identify`() {
        EntityId("Q42").kind shouldBe EntityId.Kind.ITEM
        EntityId("P31").kind shouldBe EntityId.Kind.PROPERTY
        EntityId("L1").kind shouldBe EntityId.Kind.LEXEME
        EntityId("L1-F1").kind shouldBe EntityId.Kind.FORM
        EntityId("L1-S1").kind shouldBe EntityId.Kind.SENSE
    }

    @Test
    fun `a statement carries its value, qualifiers and references`() {
        val statement = douglasAdams[EntityId("P31")].first()

        statement.value.shouldBeInstanceOf<DataValue.EntityRef>().id shouldBe EntityId("Q5")
        statement.qualifiers.isNotEmpty() shouldBe true
        statement.references.isNotEmpty() shouldBe true
    }

    @Test
    fun `a reference is a group of snaks about its own properties`() {
        val reference = douglasAdams[EntityId("P31")].first().references.first()

        reference.snaks.isNotEmpty() shouldBe true
        reference.snaks.all { it.property.kind == EntityId.Kind.PROPERTY } shouldBe true
    }

    @Test
    fun `a time value keeps the precision it was stated at`() {
        val birth = douglasAdams[EntityId("P569")].first()
        val time = birth.value.shouldBeInstanceOf<DataValue.Time>()

        time.time.startsWith("+1952") shouldBe true
        time.precision shouldBe DataValue.Time.DAY
        time.calendarModel.isNotEmpty() shouldBe true
    }

    @Test
    fun `a string value decodes as text`() {
        val statement = douglasAdams[EntityId("P1442")].first()

        statement.value.shouldBeInstanceOf<DataValue.Text>().value.isNotEmpty() shouldBe true
    }

    @Test
    fun `an item lists the pages it is about`() {
        val link = checkNotNull(douglasAdams.siteLink("enwiki"))

        link.site shouldBe "enwiki"
        link.title shouldBe "Douglas Adams"
    }

    @Test
    fun `a property reports its data type`() {
        instanceOf.dataType shouldBe "wikibase-item"
        instanceOf.label("en") shouldBe "instance of"
    }

    @Test
    fun `a lexeme has lemmas, a language and a lexical category`() {
        lexeme.lemmas.isNotEmpty() shouldBe true
        lexeme.language?.kind shouldBe EntityId.Kind.ITEM
        lexeme.lexicalCategory?.kind shouldBe EntityId.Kind.ITEM
    }

    @Test
    fun `a lexeme's forms and senses decode with their own ids`() {
        lexeme.forms.isNotEmpty() shouldBe true
        lexeme.forms.all { it.id.kind == EntityId.Kind.FORM } shouldBe true
        lexeme.senses.all { it.id.kind == EntityId.Kind.SENSE } shouldBe true

        val form = lexeme.forms.first()
        form.representations.isNotEmpty() shouldBe true
        form.grammaticalFeatures.all { it.kind == EntityId.Kind.ITEM } shouldBe true
    }

    @Test
    fun `the revision an entity was read at is kept, so an edit can detect a conflict`() {
        ((douglasAdams.lastRevisionId ?: 0L) > 0L) shouldBe true
    }

    private fun snak(json: String) = EntityDecoder.decodeSnak(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `no value and unknown value stay different from each other`() {
        snak("""{"snaktype":"novalue","property":"P40"}""").shouldBeInstanceOf<Snak.NoValue>()
        snak("""{"snaktype":"somevalue","property":"P40"}""").shouldBeInstanceOf<Snak.SomeValue>()
    }

    @Test
    fun `a value type this library does not model is kept rather than dropped`() {
        val value =
            EntityDecoder.decodeValue(
                Json.parseToJsonElement("""{"type":"future-type","value":{"a":1}}""").jsonObject
            )

        value.shouldBeInstanceOf<DataValue.Unknown>().type shouldBe "future-type"
    }

    @Test
    fun `an older entity reference with only a numeric id is reassembled`() {
        val value =
            EntityDecoder.decodeValue(
                Json.parseToJsonElement(
                        """{"type":"wikibase-entityid","value":{"entity-type":"item","numeric-id":5}}"""
                    )
                    .jsonObject
            )

        value.shouldBeInstanceOf<DataValue.EntityRef>().id shouldBe EntityId("Q5")
    }

    @Test
    fun `a quantity keeps its amount as written`() {
        val value =
            EntityDecoder.decodeValue(
                Json.parseToJsonElement(
                        """{"type":"quantity","value":{"amount":"+1.00000000000000001","unit":"1"}}"""
                    )
                    .jsonObject
            )

        value.shouldBeInstanceOf<DataValue.Quantity>().amount shouldBe "+1.00000000000000001"
    }

    @Test
    fun `a globe coordinate records which globe it is on`() {
        val value =
            EntityDecoder.decodeValue(
                Json.parseToJsonElement(
                        """{"type":"globecoordinate","value":{"latitude":48.8,"longitude":2.3,
                   "precision":0.0001,"globe":"http://www.wikidata.org/entity/Q2"}}"""
                    )
                    .jsonObject
            )

        val coordinate = value.shouldBeInstanceOf<DataValue.GlobeCoordinate>()
        coordinate.latitude shouldBe 48.8
        coordinate.globe.endsWith("Q2") shouldBe true
    }

    @Test
    fun `the best statement prefers a preferred rank and never a deprecated one`() {
        val preferred = Statement(Snak.NoValue(EntityId("P1")), rank = Rank.PREFERRED)
        val normal = Statement(Snak.NoValue(EntityId("P1")), rank = Rank.NORMAL)
        val deprecated = Statement(Snak.NoValue(EntityId("P1")), rank = Rank.DEPRECATED)

        val item =
            Entity.Item(
                id = EntityId("Q1"),
                statements = mapOf(EntityId("P1") to listOf(deprecated, normal, preferred)),
            )

        item.best(EntityId("P1")) shouldBe preferred
        Entity.Item(
                id = EntityId("Q1"),
                statements = mapOf(EntityId("P1") to listOf(deprecated)),
            )
            .best(EntityId("P1"))
            .shouldBeNull()
    }

    @Test
    fun `a label falls back to the multi-language value, a description does not`() {
        val entity = entities.values.first()

        entity.label("zz") shouldBe entity.labelIn("mul")
        entity.labelIn("zz") shouldBe null
        entity.description("zz") shouldBe null
    }

    @Test
    fun `statements for a property nobody set come back empty rather than null`() {
        entities.values.first()[EntityId("P99999")] shouldBe emptyList()
    }

    @Test
    fun `best prefers a preferred statement and never returns a deprecated one`() {
        val entity = entities.values.first()

        entity.statements.forEach { (property, group) ->
            val best = entity.best(property)
            if (group.any { it.rank != Rank.DEPRECATED }) {
                best?.rank shouldNotBe Rank.DEPRECATED
                if (group.any { it.rank == Rank.PREFERRED }) best?.rank shouldBe Rank.PREFERRED
            } else {
                best shouldBe null
            }
        }
    }
}
