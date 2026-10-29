package com.fenakhay.kwikibot.bot.source

import com.fenakhay.kwikibot.bot.temporaryDirectory
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

class PageSourceSpecTest {

    @Test
    fun `every advertised kind parses`() {
        PageSourceSpec.kinds.forEach { kind ->
            PageSourceSpec.parse("$kind:argument")
        }
    }

    @Test
    fun `a spec with no kind says what one looks like`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PageSourceSpec.parse("English lemmas")
            }

        failure.message.orEmpty() shouldContain "kind:argument"
    }

    @Test
    fun `an unknown kind lists the ones that exist`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PageSourceSpec.parse("categories:English lemmas")
            }

        failure.message.orEmpty() shouldContain "cat"
        failure.message.orEmpty() shouldContain "transcludes"
    }

    @Test
    fun `a kind that needs an argument refuses to run without one`() {
        assertFailsWith<IllegalArgumentException> { PageSourceSpec.parse("cat:") }
        assertFailsWith<IllegalArgumentException> { PageSourceSpec.parse("page:") }
    }

    @Test
    fun `the two kinds that mean something on their own do not need an argument`() {
        PageSourceSpec.parse("allpages:")
        PageSourceSpec.parse("recentchanges:")
    }

    @Test
    fun `only the first colon separates the kind from its argument`() {
        PageSourceSpec.parse("transcludes:Template:col")

        assertFailsWith<IllegalArgumentException> { PageSourceSpec.parse("Template:col:x") }
    }

    @Test
    fun `the kind is read case-insensitively`() {
        PageSourceSpec.parse("CAT:English lemmas")
        PageSourceSpec.parse("Page:volcano")
    }

    @Test
    fun `no sources at all is an empty source rather than an error`() {
        PageSourceSpec.parseAll(emptyList())
    }

    @Test
    fun `several sources are read in the order they were given`() = runTest {
        val pages = PageSourceSpec.parseAll(listOf("page:volcano", "page:Template:col")).pages(FakeWiki())

        pages.toList().map { it.title.namespace to it.title.text } shouldBe
            listOf(Namespace.MAIN to "Volcano", Namespace.TEMPLATE to "Col")
    }

    @Test
    fun `a file of titles is read a title a line past blank lines and comments`() = runTest {
        val file = Path(temporaryDirectory("titles"), "titles.txt")
        try {
            SystemFileSystem.sink(file).buffered().use {
                it.writeString("volcano\n\n# from the last run\n  lava  \n")
            }

            val pages = PageSourceSpec.parse("file:$file").pages(FakeWiki()).toList()

            pages.map { it.title.text } shouldBe listOf("Volcano", "Lava")
        } finally {
            SystemFileSystem.delete(file, mustExist = false)
        }
    }

    @Test
    fun `each kind asks the wiki the question its name says`() = runTest {
        val asked = mutableListOf<Parameters>()
        val specs =
            listOf(
                "cat:English lemmas",
                "subcats:English lemmas",
                "catfiles:English lemmas",
                "backlinks:volcano",
                "transcludes:Template:col",
                "prefix:Volc",
                "search:lava flow",
                "allpages:",
                "allpages:M",
                "usercontribs:FenaBot",
                "recentchanges:",
                "recentchanges:5",
            )

        val found =
            client(asked).use { client ->
                val wiki = client.wiki(ApiEndpoint("test.example.org"))
                specs.map { PageSourceSpec.parse(it).pages(wiki).toList().single().title.text }
            }

        found shouldBe specs.map { "volcano" }
        val sent = specs.zip(asked).toMap()
        sent.getValue("cat:English lemmas")["cmtitle"] shouldBe "Category:English lemmas"
        sent.getValue("cat:English lemmas")["cmtype"] shouldBe "page"
        sent.getValue("subcats:English lemmas")["cmtype"] shouldBe "subcat"
        sent.getValue("catfiles:English lemmas")["cmtype"] shouldBe "file"
        sent.getValue("backlinks:volcano")["bltitle"] shouldBe "Volcano"
        sent.getValue("transcludes:Template:col")["eititle"] shouldBe "Template:Col"
        sent.getValue("prefix:Volc")["apprefix"] shouldBe "Volc"
        sent.getValue("search:lava flow")["srsearch"] shouldBe "lava flow"
        sent.getValue("search:lava flow")["srwhat"] shouldBe "text"
        sent.getValue("allpages:")["list"] shouldBe "allpages"
        sent.getValue("allpages:").contains("apfrom") shouldBe false
        sent.getValue("allpages:M")["apfrom"] shouldBe "M"
        sent.getValue("usercontribs:FenaBot")["ucuser"] shouldBe "FenaBot"
        sent.getValue("recentchanges:")["list"] shouldBe "recentchanges"
        sent.getValue("recentchanges:")["rclimit"] shouldBe "100"
        sent.getValue("recentchanges:5")["rclimit"] shouldBe "5"
    }

    /** A logged-out client of a wiki whose every list holds one page, keeping what each list was asked. */
    private fun TestScope.client(asked: MutableList<Parameters>): WikiClient {
        val engine = MockEngine { request ->
            val parameters = request.url.parameters
            val answer =
                when {
                    parameters["meta"] == "userinfo" ->
                        """{"query":{"userinfo":{"id":0,"name":"192.0.2.1","anon":true}}}"""
                    parameters["meta"] == "siteinfo" -> SITE_INFO
                    else -> {
                        asked += parameters
                        """{"query":{"${parameters["list"]}":[{"ns":0,"title":"volcano","type":"edit",
                           "user":"FenaBot","revid":10,"timestamp":"2026-08-01T00:00:00Z"}]}}"""
                    }
                }
            respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return WikiClient(
            WikiConfig(
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO, testScheduler.timeSource),
                retry = RetryPolicy.NONE,
            ),
            engine = engine,
        )
    }

    private companion object {
        val SITE_INFO =
            """
            {"query":{
              "general":{"wikiid":"testwiki","sitename":"Test Wiki","lang":"en",
                         "server":"//test.example.org","articlepath":"/wiki/${'$'}1",
                         "mainpage":"Main Page","generator":"MediaWiki 1.47.0"},
              "namespaces":{
                "0":{"id":0,"name":"","case":"first-letter"},
                "2":{"id":2,"name":"User","canonical":"User","case":"first-letter"},
                "10":{"id":10,"name":"Template","canonical":"Template","case":"first-letter"},
                "14":{"id":14,"name":"Category","canonical":"Category","case":"first-letter"}}}}
            """
                .trimIndent()
    }
}
