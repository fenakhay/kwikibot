package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.TitleCase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

class SiteInfoTest {

    private val response by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/siteinfo-enwiki.json")) {
            "siteinfo-enwiki.json missing from test resources"
        }
        Json.parseToJsonElement(stream.reader().readText())
            .jsonObject["response"]!!
            .jsonObject
    }

    private val siteInfo by lazy { SiteInfo.decode(response) }

    @Test
    fun `general information is read`() {
        siteInfo.id.dbName shouldBe "enwiki"
        siteInfo.siteName shouldBe "Wikipedia"
        siteInfo.language.code shouldBe "en"
        siteInfo.mainPage shouldBe "Main Page"
        siteInfo.articlePath shouldBe "/wiki/\$1"
    }

    @Test
    fun `the protocol-relative server is normalised to a host`() {
        siteInfo.server shouldBe "en.wikipedia.org"
    }

    @Test
    fun `the MediaWiki version is separated from the generator string`() {
        siteInfo.generator.startsWith("MediaWiki ") shouldBe true
        siteInfo.version shouldBe siteInfo.generator.removePrefix("MediaWiki ")
    }

    @Test
    fun `namespaces are decoded with their names and casing`() {
        val category = checkNotNull(siteInfo.namespaces[Namespace.CATEGORY])

        category.localName shouldBe "Category"
        category.canonicalName shouldBe "Category"
        category.case shouldBe TitleCase.FIRST_LETTER
    }

    @Test
    fun `the project namespace carries the local wiki name`() {
        val project = checkNotNull(siteInfo.namespaces[Namespace.PROJECT])

        project.localName shouldBe "Wikipedia"
        project.canonicalName shouldBe "Project"
    }

    @Test
    fun `namespace aliases resolve`() {
        checkNotNull(siteInfo.namespaces.byPrefix("WP")).id shouldBe Namespace.PROJECT
        checkNotNull(siteInfo.namespaces.byPrefix("WT")).id shouldBe Namespace.PROJECT_TALK
    }

    @Test
    fun `the decoded namespaces drive title parsing`() {
        val title = Title.parse("wp:Administrators", siteInfo.namespaces, siteInfo.interwiki)
            .shouldBeInstanceOf<Title.Local>()

        title.namespace shouldBe Namespace.PROJECT
        title.text shouldBe "Administrators"
    }

    @Test
    fun `a partial response decodes without failing`() {
        val general = Json.parseToJsonElement(
            """{"query":{"general":{"wikiid":"testwiki","sitename":"Test","lang":"en"}}}""",
        ).jsonObject

        val partial = SiteInfo.decode(general)

        partial.id.dbName shouldBe "testwiki"
        partial.namespaces.all.isEmpty() shouldBe true
    }
}
