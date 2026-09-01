package com.fenakhay.kwikibot.wikibase

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class EntityAccessTest {

    private val instanceOf = EntityId("P31")
    private val startTime = EntityId("P580")

    private fun statement(
        value: String,
        rank: Rank = Rank.NORMAL,
        property: EntityId = instanceOf,
        qualifiers: List<Snak> = emptyList(),
    ) = Statement(
        mainSnak = Snak.Value(property, DataValue.Text(value)),
        rank = rank,
        qualifiers = qualifiers,
    )

    private val douglas = Entity.Item(
        id = EntityId("Q42"),
        labels = mapOf(
            "fr" to LanguageValue("fr", "Douglas Adams"),
            MULTIPLE_LANGUAGES to LanguageValue(MULTIPLE_LANGUAGES, "Douglas Adams"),
        ),
        descriptions = mapOf("en" to LanguageValue("en", "English writer")),
        statements = mapOf(instanceOf to listOf(statement("human"))),
        siteLinks = mapOf("enwiki" to SiteLink("enwiki", "Douglas Adams")),
    )

    @Test
    fun `a label falls back to mul, which is where a shared name is stored`() {
        douglas.label("en") shouldBe "Douglas Adams"
        douglas.label("fr") shouldBe "Douglas Adams"
    }

    @Test
    fun `labelIn asks for one language only, with no fallback`() {
        douglas.labelIn("en").shouldBeNull()
        douglas.labelIn("fr") shouldBe "Douglas Adams"
    }

    @Test
    fun `a description has no mul fallback, since a shared one would defeat its purpose`() {
        douglas.description("en") shouldBe "English writer"
        douglas.description("fr").shouldBeNull()
    }

    @Test
    fun `reading a property that is not there gives an empty list, not null`() {
        douglas[instanceOf].size shouldBe 1
        douglas[EntityId("P999")] shouldBe emptyList()
        douglas.best(EntityId("P999")).shouldBeNull()
    }

    @Test
    fun `best prefers a preferred statement and never a deprecated one`() {
        val entity = Entity.Item(
            id = EntityId("Q1"),
            statements = mapOf(
                instanceOf to listOf(
                    statement("wrong", Rank.DEPRECATED),
                    statement("ordinary"),
                    statement("current", Rank.PREFERRED),
                ),
            ),
        )

        entity.best(instanceOf)?.value shouldBe DataValue.Text("current")
    }

    @Test
    fun `a deprecated statement is never best, even when it is the only one`() {
        val entity = Entity.Item(
            id = EntityId("Q1"),
            statements = mapOf(instanceOf to listOf(statement("wrong", Rank.DEPRECATED))),
        )

        entity.best(instanceOf).shouldBeNull()
    }

    @Test
    fun `a statement reports the property of its own main snak`() {
        statement("human").property shouldBe instanceOf
    }

    @Test
    fun `qualifiers can be read one property at a time`() {
        val dated = statement(
            "human",
            qualifiers = listOf(
                Snak.Value(startTime, DataValue.Text("1952")),
                Snak.Value(EntityId("P1"), DataValue.Text("other")),
            ),
        )

        dated.qualifiers(startTime).size shouldBe 1
        dated.qualifiers(EntityId("P999")) shouldBe emptyList()
    }

    @Test
    fun `a reference's snaks can be read one property at a time`() {
        val cited = Reference(
            snaks = listOf(
                Snak.Value(EntityId("P854"), DataValue.Text("https://example.org")),
                Snak.Value(EntityId("P813"), DataValue.Text("2026-01-01")),
            ),
        )

        cited[EntityId("P854")].size shouldBe 1
        cited[EntityId("P999")] shouldBe emptyList()
    }

    @Test
    fun `a lexeme reads its lemmas where an item reads labels`() {
        val lexeme = Entity.Lexeme(
            id = EntityId("L1"),
            lemmas = mapOf("en" to LanguageValue("en", "volcano")),
            language = EntityId("Q1860"),
            lexicalCategory = EntityId("Q1084"),
        )

        lexeme.lemma("en") shouldBe "volcano"
        lexeme.label("en") shouldBe "volcano"
        lexeme.descriptions shouldBe emptyMap()
        lexeme.aliases shouldBe emptyMap()
    }

    @Test
    fun `a media info entity reads its captions as labels`() {
        val file = Entity.MediaInfo(
            id = EntityId("M1"),
            labels = mapOf("en" to LanguageValue("en", "A volcano erupting")),
        )

        file.caption("en") shouldBe "A volcano erupting"
        file.aliases shouldBe emptyMap()
        file.descriptions shouldBe emptyMap()
    }

    @Test
    fun `an item reports the page it is about on one wiki`() {
        douglas.siteLink("enwiki")?.title shouldBe "Douglas Adams"
        douglas.siteLink("frwiki").shouldBeNull()
    }

    @Test
    fun `an id prints as the wiki writes it, prefix included`() {
        EntityId("Q42").toString() shouldBe "Q42"
    }

    @Test
    fun `an id reports what kind of entity it names, from its prefix`() {
        EntityId("Q42").kind shouldBe EntityId.Kind.ITEM
        EntityId("P31").kind shouldBe EntityId.Kind.PROPERTY
        EntityId("L1").kind shouldBe EntityId.Kind.LEXEME
        EntityId("L1-F2").kind shouldBe EntityId.Kind.FORM
        EntityId("L1-S1").kind shouldBe EntityId.Kind.SENSE
    }
}
