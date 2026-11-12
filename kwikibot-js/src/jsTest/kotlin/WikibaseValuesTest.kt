import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WikibaseValuesTest {

    private val calendar = "http://www.wikidata.org/entity/Q1985727"

    @Test
    fun `each kind of value carries its own fields and writes itself back`() {
        val text = textValue("ISBN")
        listOf(text.kind, text.value) shouldBe listOf("text", "ISBN")
        (text.toJSON().asDynamic().type.unsafeCast<Any?>()) shouldBe "string"

        val ref = entityValue("Q5")
        listOf(ref.kind, ref.id) shouldBe listOf("entity-ref", "Q5")

        val time = timeValue("+1952-00-00T00:00:00Z", TIME_PRECISION_YEAR, calendar, 1, 2, 60)
        listOf(time.kind, time.time, time.precision, time.calendarModel) shouldBe
            listOf("time", "+1952-00-00T00:00:00Z", 9.0, calendar)
        listOf(time.before, time.after, time.timezone) shouldBe listOf(1, 2, 60)

        val quantity = quantityValue("+3", "Q11573", "+4", "+2")
        listOf(
            quantity.kind,
            quantity.amount,
            quantity.unit,
            quantity.upperBound,
            quantity.lowerBound,
        ) shouldBe listOf("quantity", "+3", "Q11573", "+4", "+2")
        quantityValue("+3").unit shouldBe UNITLESS

        val point = coordinateValue(1.5, -2.5, 0.1, "http://www.wikidata.org/entity/Q2")
        listOf(point.kind, point.latitude, point.longitude, point.precision, point.globe) shouldBe
            listOf("globe-coordinate", 1.5, -2.5, 0.1, "http://www.wikidata.org/entity/Q2")

        val motto = monolingualValue("Fiat lux", "la")
        listOf(motto.kind, motto.text, motto.language) shouldBe listOf("monolingual", "Fiat lux", "la")

        val unknown = unknownValue("novel", JSON.parse("""{"a":1}"""))
        listOf(unknown.kind, unknown.type) shouldBe listOf("unknown", "novel")
        (unknown.json.asDynamic().a.unsafeCast<Any?>()) shouldBe 1
    }

    @Test
    fun `the numbers Wikibase stores are the numbers named`() {
        listOf(
            TIME_PRECISION_BILLION_YEARS,
            TIME_PRECISION_CENTURY,
            TIME_PRECISION_DECADE,
            TIME_PRECISION_MONTH,
            TIME_PRECISION_DAY,
        ) shouldBe listOf(0, 7, 8, 10, 11)
        MULTIPLE_LANGUAGES shouldBe "mul"
    }

    @Test
    fun `a statement is built with qualifiers and references and copied`() {
        val main = valueSnak("P31", entityValue("Q5"), "wikibase-item")
        listOf(main.kind, main.property, main.dataType, main.value?.id) shouldBe
            listOf("value", "P31", "wikibase-item", "Q5")
        someValueSnak("P1").kind shouldBe "somevalue"
        noValueSnak("P1").kind shouldBe "novalue"
        (main.toJSON().asDynamic().snaktype.unsafeCast<Any?>()) shouldBe "value"

        val source = Reference(arrayOf(valueSnak("P854", textValue("https://example.org"))))
        val built =
            claim(
                main,
                optionsOf(
                    "rank" to "preferred",
                    "qualifiers" to arrayOf(noValueSnak("P580"), noValueSnak("P582")),
                    "references" to arrayOf(source),
                ),
            )

        built.rank shouldBe "preferred"
        built.mainSnak.property shouldBe "P31"
        built.dataValue?.id shouldBe "Q5"
        built.qualifiers.map { it.property } shouldBe listOf("P580", "P582")
        built.qualifiersFor("P582").single().kind shouldBe "novalue"
        built.references.single().get("P854").single().value?.value shouldBe "https://example.org"
        built.references.single().snaks.size shouldBe 1

        val copied = built.copy(optionsOf("rank" to "deprecated", "id" to "Q1$1"))
        listOf(copied.rank, copied.id, copied.qualifiers.size) shouldBe listOf("deprecated", "Q1$1", 2)
        built.withMainSnak(noValueSnak("P9")).snakType shouldBe "novalue"
        claim(main).rank shouldBe "normal"
    }

    @Test
    fun `a rank nobody has heard of is refused`() {
        val refused = runCatching { claim(noValueSnak("P1"), optionsOf("rank" to "best")) }.exceptionOrNull()
        (refused is IllegalArgumentException) shouldBe true
    }

    @Test
    fun `what Wikibase serialises reads back and writes out`() {
        val statement =
            JSON.parse<Record<String, JsonValue>>(
                """{"mainsnak":{"snaktype":"value","property":"P31","datatype":"wikibase-item",
                "datavalue":{"value":{"entity-type":"item","numeric-id":5,"id":"Q5"},"type":"wikibase-entityid"}},
                "type":"statement","id":"Q1${'$'}1","rank":"normal"}"""
            )
        val read = decodeClaim(statement)
        read.dataValue?.id shouldBe "Q5"
        (read.toJSON().asDynamic().rank.unsafeCast<Any?>()) shouldBe "normal"
        decodeSnak(read.mainSnak.toJSON()).property shouldBe "P31"
        decodeDataValue(checkNotNull(read.dataValue).toJSON()).id shouldBe "Q5"

        decodeEntity(JSON.parse("""{"id":"Q1","type":"item"}""")).id shouldBe "Q1"
        decodeEntities(JSON.parse("""{"entities":{"Q1":{"id":"Q1","type":"item"}}}""")).toMap().keys shouldBe
            setOf("Q1")
        entityRedirectTarget(JSON.parse("""{"redirects":{"from":"Q1","to":"Q2"}}""")) shouldBe "Q2"
        entityRedirectTarget(JSON.parse("""{"id":"Q1"}""")) shouldBe null

        (encodeClaims(recordOf("P31" to arrayOf(read))).asDynamic().P31[0].id.unsafeCast<Any?>()) shouldBe
            "Q1$1"
        (encodeLanguageValues(recordOf("en" to LanguageValue("en", "a")))
            .asDynamic()
            .en
            .value
            .unsafeCast<Any?>()) shouldBe "a"
        (encodeAliases(recordOf("en" to arrayOf(LanguageValue("en", "a"))))
            .asDynamic()
            .en[0]
            .value
            .unsafeCast<Any?>()) shouldBe "a"
        (encodeSiteLinks(recordOf("enwiki" to EntitySiteLink("enwiki", "A")))
            .asDynamic()
            .enwiki
            .title
            .unsafeCast<Any?>()) shouldBe "A"
    }

    @Test
    fun `an item's labels and statements are tables`() {
        val item =
            decodeEntity(
                JSON.parse(
                    """{"id":"Q1","type":"item","labels":{"mul":{"language":"mul","value":"Everywhere"}},
                       "descriptions":{"en":{"language":"en","value":"d"}},
                       "aliases":{"en":[{"language":"en","value":"a"}]},
                       "claims":{"P1":[{"mainsnak":{"snaktype":"novalue","property":"P1"},"type":"statement"}]}}"""
                )
            )

        item.label("en") shouldBe "Everywhere"
        item.labelIn("en") shouldBe null
        item.labels.toMap().getValue("mul").value shouldBe "Everywhere"
        item.descriptions.toMap().getValue("en").language shouldBe "en"
        item.allAliases.toMap().getValue("en").single().value shouldBe "a"
        item.statements.toMap().getValue("P1").single().snakType shouldBe "novalue"
        listOf(item.dataType, item.lemmas, item.forms, item.senses, item.caption("en")) shouldBe
            listOf(null, null, null, null, null)
    }

    @Test
    fun `a property has a data type, a file captions, and a lexeme its forms and senses`() {
        decodeEntity(JSON.parse("""{"id":"P1","type":"property","datatype":"url"}""")).dataType shouldBe "url"

        val file =
            decodeEntity(
                JSON.parse(
                    """{"id":"M1","type":"mediainfo","labels":{"en":{"language":"en","value":"Lava flow"}}}"""
                )
            )
        file.caption("en") shouldBe "Lava flow"

        val lexeme =
            decodeEntity(
                JSON.parse(
                    """{"id":"L1","type":"lexeme","language":"Q1860","lexicalCategory":"Q1084",
                       "lemmas":{"en":{"language":"en","value":"lava"}},
                       "forms":[{"id":"L1-F1","representations":{"en":{"language":"en","value":"lavas"}},
                         "grammaticalFeatures":["Q146786"],
                         "claims":{"P1":[{"mainsnak":{"snaktype":"novalue","property":"P1"},"type":"statement"}]}}],
                       "senses":[{"id":"L1-S1","glosses":{"en":{"language":"en","value":"molten rock"}},
                         "claims":{"P2":[{"mainsnak":{"snaktype":"novalue","property":"P2"},"type":"statement"}]}}]}"""
                )
            )
        lexeme.lemma("en") shouldBe "lava"
        lexeme.lemmas?.toMap()?.getValue("en")?.value shouldBe "lava"
        listOf(lexeme.language, lexeme.lexicalCategory) shouldBe listOf("Q1860", "Q1084")
        val form = lexeme.forms!!.single()
        form.id shouldBe "L1-F1"
        form.representations.toMap().getValue("en").value shouldBe "lavas"
        form.grammaticalFeatures.toList() shouldBe listOf("Q146786")
        form.statements.toMap().keys shouldBe setOf("P1")
        val sense = lexeme.senses!!.single()
        sense.id shouldBe "L1-S1"
        sense.glosses.toMap().getValue("en").value shouldBe "molten rock"
        sense.statements.toMap().keys shouldBe setOf("P2")
    }
}
