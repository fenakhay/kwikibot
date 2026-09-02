package com.fenakhay.kwikibot.client.model

import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.page.WikiId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SiteMatrixTest {

    private val response =
        Json.parseToJsonElement(
                """
                {"sitematrix":{
                  "count":2,
                  "0":{"code":"en","name":"English","site":[
                    {"url":"https://en.wikipedia.org","dbname":"enwiki","code":"wiki","sitename":"Wikipedia"},
                    {"url":"https://en.wiktionary.org","dbname":"enwiktionary","code":"wiktionary",
                     "sitename":"Wiktionary"}]},
                  "1":{"code":"fr","name":"français","site":[
                    {"url":"https://fr.wiktionary.org","dbname":"frwiktionary","code":"wiktionary"},
                    {"url":"https://fr.wikinews.org","dbname":"frwikinews","code":"wikinews",
                     "closed":""}]},
                  "specials":[
                    {"url":"https://commons.wikimedia.org","dbname":"commonswiki","code":"commons"},
                    {"url":"https://foundation.wikimedia.org","dbname":"foundationwiki","code":"foundation",
                     "private":""}]}}
                """
                    .trimIndent()
            )
            .jsonObject

    private val matrix = SiteMatrix.decode(response)

    @Test
    fun `every wiki is read, language wikis and specials alike`() {
        matrix.wikis.map { it.id.dbName }.sorted() shouldBe
            listOf(
                "commonswiki",
                "enwiki",
                "enwiktionary",
                "foundationwiki",
                "frwikinews",
                "frwiktionary",
            )
    }

    @Test
    fun `a language wiki knows its language and a special one does not`() {
        matrix[WikiId("frwiktionary")]?.language shouldBe LangCode("fr")
        matrix[WikiId("commonswiki")]?.language.shouldBeNull()
    }

    @Test
    fun `the wikis of a project are listed across languages`() {
        matrix.languagesOf("wiktionary").map { it.code } shouldBe listOf("en", "fr")
    }

    @Test
    fun `a wiki is found by language and project`() {
        matrix[LangCode("fr"), "wiktionary"]?.id shouldBe WikiId("frwiktionary")
        matrix[LangCode("de"), "wiktionary"].shouldBeNull()
    }

    @Test
    fun `closed and private wikis are flagged and left out of the editable ones`() {
        matrix[WikiId("frwikinews")]?.isClosed shouldBe true
        matrix[WikiId("foundationwiki")]?.isPrivate shouldBe true

        matrix.open().map { it.id.dbName } shouldBe
            listOf("enwiki", "enwiktionary", "frwiktionary", "commonswiki")
    }

    @Test
    fun `a matrix entry becomes an endpoint and a family`() {
        val wiki = checkNotNull(matrix[WikiId("frwiktionary")])

        wiki.endpoint.apiUrl shouldBe "https://fr.wiktionary.org/w/api.php"
        Family.of(wiki).endpoint(LangCode("fr")).server shouldBe "fr.wiktionary.org"
    }

    @Test
    fun `an empty response is an empty matrix rather than a failure`() {
        SiteMatrix.decode(Json.parseToJsonElement("{}").jsonObject).wikis shouldBe emptyList()
    }
}
