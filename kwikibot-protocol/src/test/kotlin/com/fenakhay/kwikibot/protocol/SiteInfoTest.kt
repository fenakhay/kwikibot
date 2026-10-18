package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceInfo
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.model.title.TitleCase
import com.fenakhay.kwikibot.model.user.TempAccountConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SiteInfoTest {

    private val response by lazy {
        val stream =
            checkNotNull(javaClass.getResourceAsStream("/siteinfo-enwiki.json")) {
                "siteinfo-enwiki.json missing from test resources"
            }
        Json.parseToJsonElement(stream.reader().readText()).jsonObject["response"]!!.jsonObject
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
        val title =
            Title.parse("wp:Administrators", siteInfo.namespaces, siteInfo.interwiki)
                .shouldBeInstanceOf<Title.Local>()

        title.namespace shouldBe Namespace.PROJECT
        title.text shouldBe "Administrators"
    }

    @Test
    fun `a partial response decodes without failing`() {
        val general =
            Json.parseToJsonElement(
                    """{"query":{"general":{"wikiid":"testwiki","sitename":"Test","lang":"en"}}}"""
                )
                .jsonObject

        val partial = SiteInfo.decode(general)

        partial.id.dbName shouldBe "testwiki"
        partial.namespaces.all.isEmpty() shouldBe true
    }

    private fun decode(query: String) =
        SiteInfo.decode(Json.parseToJsonElement("""{"query":$query}""").jsonObject)

    @Test
    fun `a response without a query block is refused with a typed error`() {
        val error =
            assertFailsWith<WikiError.Api> {
                SiteInfo.decode(Json.parseToJsonElement("""{"batchcomplete":true}""").jsonObject)
            }

        error.code shouldBe "nositeinfo"
        error.module shouldBe "query+siteinfo"
    }

    @Test
    fun `a response that names no wiki is refused with a typed error`() {
        val shapes =
            listOf(
                """{"namespaces":{"0":{"id":0,"name":""}}}""",
                """{"general":{"lang":"en"}}""",
                """{"general":{"wikiid":"","sitename":""}}""",
            )

        shapes.map { assertFailsWith<WikiError.Api> { decode(it) }.code } shouldBe shapes.map { "nositeinfo" }
    }

    @Test
    fun `a general block naming only the site decodes with every default`() {
        val site = decode("""{"general":{"sitename":"Testwiki"}}""")

        site.id shouldBe WikiId("Testwiki")
        site.siteName shouldBe "Testwiki"
        site.language shouldBe LangCode("en")
        site.server shouldBe ""
        site.articlePath shouldBe ""
        site.mainPage shouldBe ""
        site.generator shouldBe ""
        site.version shouldBe ""
        site.timezone shouldBe "UTC"
        site.timeOffsetMinutes shouldBe 0
        site.extensions shouldBe emptyList()
        site.tempAccounts shouldBe TempAccountConfig.DISABLED
        site.namespaces.all shouldBe emptyList()
        ("w" in site.interwiki) shouldBe false
    }

    @Test
    fun `a wiki that creates temporary accounts reports the shape of their names`() {
        val enabled =
            decode(
                """{"general":{"wikiid":"enwiki"},
                   "autocreatetempuser":{"enabled":true,"actions":["edit"],"matchPatterns":["~2$1"]}}"""
            )
        val disabled = decode("""{"general":{"wikiid":"enwiki"},"autocreatetempuser":{"enabled":false}}""")

        enabled.tempAccounts shouldBe TempAccountConfig(enabled = true, matchPatterns = listOf("~2$1"))
        enabled.tempAccounts.matches("~2026-47315-11") shouldBe true
        disabled.tempAccounts shouldBe TempAccountConfig.DISABLED
    }

    @Test
    fun `a namespace whose titles keep their first letter as written is case-sensitive`() {
        val site =
            decode(
                """{"general":{"wikiid":"enwiktionary"},"namespaces":{
                   "0":{"id":0,"case":"case-sensitive","name":"","subpages":false,"content":true},
                   "4":{"id":4}}}"""
            )

        site.namespaces[Namespace.MAIN]?.case shouldBe TitleCase.CASE_SENSITIVE
        site.namespaces[Namespace.PROJECT] shouldBe
            NamespaceInfo(
                id = Namespace.PROJECT,
                canonicalName = "",
                localName = "",
                aliases = emptyList(),
                case = TitleCase.FIRST_LETTER,
                subpages = false,
            )
    }

    @Test
    fun `an extension without a name is left out`() {
        decode(
                """{"general":{"wikiid":"enwiki"},
                   "extensions":[{"name":"Wikibase Client"},{"name":""},{"type":"parserhook"}]}"""
            )
            .extensions shouldBe listOf("Wikibase Client")
    }

    @Test
    fun `only a prefix whose address is this wiki's own host points back at it`() {
        val rows =
            """"interwikimap":[{"prefix":"wikt","url":"https://en.wiktionary.org/wiki/$1"},
               {"prefix":"w","url":"https://en.wikipedia.org/wiki/$1"},{"prefix":"nourl"}]"""

        val hosted = decode("""{"general":{"wikiid":"enwiktionary","server":"//en.wiktionary.org"},$rows}""")
        val unhosted = decode("""{"general":{"wikiid":"enwiktionary"},$rows}""")

        listOf("wikt", "w", "nourl").map { it in hosted.interwiki } shouldBe listOf(true, true, true)
        listOf("wikt", "w", "nourl").map { hosted.interwiki.isSelf(it) } shouldBe listOf(true, false, false)
        listOf("wikt", "w", "nourl").map { unhosted.interwiki.isSelf(it) } shouldBe
            listOf(false, false, false)
    }
}
