package com.fenakhay.kwikibot.wikibase

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.wikibase.entity.Entity
import com.fenakhay.kwikibot.wikibase.entity.LanguageValue
import com.fenakhay.kwikibot.wikibase.entity.SiteLink
import com.fenakhay.kwikibot.wikibase.value.DataValue
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Rank
import com.fenakhay.kwikibot.wikibase.value.Reference
import com.fenakhay.kwikibot.wikibase.value.Snak
import com.fenakhay.kwikibot.wikibase.value.Statement
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
    fun `an older reference to a property or lexeme keeps its prefix, and any other is kept as it came`() {
        fun reference(value: String) =
            EntityDecoder.decodeValue(
                Json.parseToJsonElement("""{"type":"wikibase-entityid","value":$value}""").jsonObject
            )

        reference("""{"entity-type":"property","numeric-id":31}""") shouldBe
            DataValue.EntityRef(EntityId("P31"))
        reference("""{"entity-type":"lexeme","numeric-id":1}""") shouldBe DataValue.EntityRef(EntityId("L1"))
        reference("""{"entity-type":"form","numeric-id":1}""").shouldBeInstanceOf<DataValue.Unknown>()
        reference("""{"entity-type":"item"}""").shouldBeInstanceOf<DataValue.Unknown>()
    }

    @Test
    fun `an entity this library does not model, or one with no id, is refused by name`() {
        fun decode(json: String) = EntityDecoder.decode(Json.parseToJsonElement(json).jsonObject)

        assertFailsWith<WikiError.Api> { decode("""{"type":"entity-schema","id":"E10"}""") }.code shouldBe
            "unknownentitytype"
        assertFailsWith<WikiError.Api> { decode("""{"type":"item"}""") }.code shouldBe "malformedentity"
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

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a map Wikibase sends as an empty list, as PHP writes an empty array, reads as empty`() {
        EntityDecoder.decode(
            json("""{"type":"mediainfo","id":"M123","labels":[],"statements":[]}""")
        ) shouldBe Entity.MediaInfo(EntityId("M123"))
        EntityDecoder.decode(
            json(
                """{"type":"item","id":"Q1","labels":[],"descriptions":[],"aliases":[],"claims":[],
                   "sitelinks":[]}"""
            )
        ) shouldBe Entity.Item(EntityId("Q1"))
    }

    @Test
    fun `a field sent as JSON null reads as absent, not as the text null`() {
        EntityDecoder.decodeStatement(
            json("""{"mainsnak":{"snaktype":"novalue","property":"P40","datatype":null},"id":null}""")
        ) shouldBe Statement(Snak.NoValue(EntityId("P40")))
        EntityDecoder.decodeValue(json("""{"type":"string","value":null}"""))
            .shouldBeInstanceOf<DataValue.Unknown>()
    }

    private fun decode(text: String) = EntityDecoder.decode(json(text))

    private fun malformed(text: String) = assertFailsWith<WikiError.Api> { decode(text) }

    @Test
    fun `an entity carrying only its id and type decodes with every default`() {
        decode("""{"type":"item","id":"Q1"}""") shouldBe Entity.Item(EntityId("Q1"))
        decode("""{"type":"property","id":"P1"}""") shouldBe Entity.Property(EntityId("P1"), dataType = "")
        decode("""{"type":"lexeme","id":"L1"}""") shouldBe Entity.Lexeme(EntityId("L1"))
        decode("""{"type":"mediainfo","id":"M1"}""") shouldBe Entity.MediaInfo(EntityId("M1"))
    }

    @Test
    fun `an entity that names no type is refused by name`() {
        val refused = malformed("""{"id":"Q1"}""")

        refused.code shouldBe "unknownentitytype"
        refused.info shouldBe "this library does not model entities of type 'null'"
    }

    @Test
    fun `a lexeme's forms and senses carrying only their ids decode with every default`() {
        decode("""{"type":"lexeme","id":"L1","forms":[{"id":"L1-F1"}],"senses":[{"id":"L1-S1"}]}""") shouldBe
            Entity.Lexeme(
                EntityId("L1"),
                forms = listOf(Entity.Form(EntityId("L1-F1"))),
                senses = listOf(Entity.Sense(EntityId("L1-S1"))),
            )
    }

    @Test
    fun `a lexeme whose form or sense has no id is refused, naming what is missing`() {
        malformed("""{"type":"lexeme","id":"L1","forms":[{"representations":{}}]}""").info shouldBe
            "entity JSON has no form id"
        malformed("""{"type":"lexeme","id":"L1","senses":[{"glosses":{}}]}""").info shouldBe
            "entity JSON has no sense id"
    }

    @Test
    fun `a lexeme names its form and sense ids, and the features that make each form`() {
        val lexeme =
            decode(
                    """{"type":"lexeme","id":"L7","lemmas":{"en":{"language":"en","value":"volcano"}},
                       "language":"Q1860","lexicalCategory":"Q1084",
                       "forms":[{"id":"L7-F2","representations":{"en":{"language":"en","value":"volcanoes"}},
                         "grammaticalFeatures":["Q146786"],"claims":{}}],
                       "senses":[{"id":"L7-S1","glosses":{"en":{"language":"en","value":"a mountain"}}}]}"""
                )
                .shouldBeInstanceOf<Entity.Lexeme>()

        lexeme.lemma("en") shouldBe "volcano"
        lexeme.lemma("fr").shouldBeNull()
        lexeme.language shouldBe EntityId("Q1860")
        lexeme.lexicalCategory shouldBe EntityId("Q1084")
        lexeme.forms.single() shouldBe
            Entity.Form(
                EntityId("L7-F2"),
                representations = mapOf("en" to LanguageValue("en", "volcanoes")),
                grammaticalFeatures = listOf(EntityId("Q146786")),
            )
        lexeme.senses.single().glosses shouldBe mapOf("en" to LanguageValue("en", "a mountain"))
        listOf(lexeme.id, lexeme.forms.single().id, lexeme.senses.single().id).map { it.kind } shouldBe
            listOf(EntityId.Kind.LEXEME, EntityId.Kind.FORM, EntityId.Kind.SENSE)
    }

    @Test
    fun `an item whose labels have neither the language asked for nor mul has no label`() {
        decode("""{"type":"item","id":"Q1","labels":{"fr":{"language":"fr","value":"volcan"}}}""")
            .label("en")
            .shouldBeNull()
    }

    @Test
    fun `text, aliases and sitelinks missing their own fields read as empty or as their key`() {
        val item =
            decode(
                    """{"type":"item","id":"Q1","labels":{"en":{}},"aliases":{"en":[{},{"value":"vent"}]},
                       "sitelinks":{"enwiki":{},
                         "frwiki":{"site":"frwiki","title":"Volcan","badges":["Q17437796"]}}}"""
                )
                .shouldBeInstanceOf<Entity.Item>()

        item.labels shouldBe mapOf("en" to LanguageValue("en", ""))
        item.aliases shouldBe mapOf("en" to listOf(LanguageValue("en", ""), LanguageValue("en", "vent")))
        item.siteLinks shouldBe
            mapOf(
                "enwiki" to SiteLink("enwiki", ""),
                "frwiki" to SiteLink("frwiki", "Volcan", listOf(EntityId("Q17437796"))),
            )
    }

    @Test
    fun `a statement carrying only its main snak decodes with every default`() {
        EntityDecoder.decodeStatement(
            json("""{"mainsnak":{"snaktype":"somevalue","property":"P40"}}""")
        ) shouldBe Statement(Snak.SomeValue(EntityId("P40")), id = null, rank = Rank.NORMAL)
    }

    @Test
    fun `a statement's rank, qualifiers and references are read as given`() {
        val statement =
            EntityDecoder.decodeStatement(
                json(
                    """{"mainsnak":{"snaktype":"novalue","property":"P40"},"rank":"preferred",
                       "qualifiers":{"P580":[{"snaktype":"somevalue","property":"P580"}]},
                       "references":[{"snaks":{"P854":[{"snaktype":"novalue","property":"P854"}]}},
                         {"hash":"x"}]}"""
                )
            )

        statement.rank shouldBe Rank.PREFERRED
        statement.qualifiers shouldBe listOf(Snak.SomeValue(EntityId("P580")))
        statement.references shouldBe
            listOf(Reference(listOf(Snak.NoValue(EntityId("P854")))), Reference(emptyList()))
    }

    @Test
    fun `a statement or snak missing a part it cannot do without is refused, naming the part`() {
        fun statement(text: String) =
            assertFailsWith<WikiError.Api> { EntityDecoder.decodeStatement(json(text)) }

        statement("""{"rank":"normal"}""").info shouldBe "entity JSON has no mainsnak"
        statement("""{"mainsnak":{"snaktype":"novalue"}}""").info shouldBe "entity JSON has no snak property"
        statement("""{"mainsnak":{"snaktype":"value","property":"P31"}}""").info shouldBe
            "entity JSON has no datavalue"
        statement("""{"mainsnak":{"property":"P31"}}""").code shouldBe "malformedentity"
    }

    @Test
    fun `a value snak keeps the datatype it was sent with, and has none when it was sent none`() {
        val value = """{"type":"string","value":"x"}"""

        snak("""{"snaktype":"value","property":"P1","datatype":"external-id","datavalue":$value}""") shouldBe
            Snak.Value(EntityId("P1"), DataValue.Text("x"), dataType = "external-id")
        snak("""{"snaktype":"value","property":"P1","datavalue":$value}""") shouldBe
            Snak.Value(EntityId("P1"), DataValue.Text("x"), dataType = null)
    }

    @Test
    fun `a value without a type, or not in the shape its type takes, is kept as it came`() {
        val shapes =
            mapOf(
                "{}" to "",
                """{"type":"time"}""" to "time",
                """{"type":"time","value":"+1952"}""" to "time",
                """{"type":"string","value":{}}""" to "string",
            )

        shapes.keys.map { EntityDecoder.decodeValue(json(it)) } shouldBe
            shapes.map { (text, type) -> DataValue.Unknown(type, json(text)) }
    }

    @Test
    fun `a time stated without precision is read at the precision of a day, with no offsets`() {
        EntityDecoder.decodeValue(
            json("""{"type":"time","value":{"time":"+1952-03-11T00:00:00Z"}}""")
        ) shouldBe
            DataValue.Time(
                time = "+1952-03-11T00:00:00Z",
                precision = DataValue.Time.DAY,
                calendarModel = "",
                before = 0,
                after = 0,
                timezone = 0,
            )
        EntityDecoder.decodeValue(json("""{"type":"time","value":{"precision":9}}""")) shouldBe
            DataValue.Time(time = "", precision = DataValue.Time.YEAR, calendarModel = "")
    }

    @Test
    fun `a time keeps the precision and offsets it was stated with`() {
        EntityDecoder.decodeValue(
            json(
                """{"type":"time","value":{"time":"+1952-00-00T00:00:00Z","precision":9,"before":1,"after":2,
                   "timezone":60,"calendarmodel":"http://www.wikidata.org/entity/Q1985727"}}"""
            )
        ) shouldBe
            DataValue.Time(
                time = "+1952-00-00T00:00:00Z",
                precision = DataValue.Time.YEAR,
                calendarModel = "http://www.wikidata.org/entity/Q1985727",
                before = 1,
                after = 2,
                timezone = 60,
            )
    }

    @Test
    fun `a quantity keeps the bounds of its tolerance, and is unitless when it names no unit`() {
        EntityDecoder.decodeValue(
            json("""{"type":"quantity","value":{"amount":"+10","upperBound":"+11","lowerBound":"+9"}}""")
        ) shouldBe
            DataValue.Quantity("+10", DataValue.Quantity.UNITLESS, upperBound = "+11", lowerBound = "+9")
        EntityDecoder.decodeValue(json("""{"type":"quantity","value":{}}""")) shouldBe DataValue.Quantity("")
    }

    @Test
    fun `a globe coordinate stated without its parts reads as the origin with no precision`() {
        EntityDecoder.decodeValue(json("""{"type":"globecoordinate","value":{}}""")) shouldBe
            DataValue.GlobeCoordinate(latitude = 0.0, longitude = 0.0, precision = null, globe = "")
    }

    @Test
    fun `monolingual text keeps its language`() {
        EntityDecoder.decodeValue(
            json("""{"type":"monolingualtext","value":{"text":"Vulkan","language":"de"}}""")
        ) shouldBe DataValue.Monolingual("Vulkan", "de")
        EntityDecoder.decodeValue(json("""{"type":"monolingualtext","value":{}}""")) shouldBe
            DataValue.Monolingual("", "")
    }

    @Test
    fun `an entity that is not a redirect, or names no target, redirects nowhere`() {
        EntityDecoder.redirectTarget(json("""{"type":"item","id":"Q42"}""")).shouldBeNull()
        EntityDecoder.redirectTarget(json("""{"id":"Q42","redirects":{"from":"Q42"}}""")).shouldBeNull()
    }

    @Test
    fun `a response without entities decodes to none`() {
        EntityDecoder.decodeAll(json("""{"success":1}""")) shouldBe emptyMap()
    }
}
