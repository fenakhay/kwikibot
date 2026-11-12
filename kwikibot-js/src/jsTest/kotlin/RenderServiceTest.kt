import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class RenderServiceTest {

    private val mock = MockWiki()

    private fun runWiki(block: suspend TestScope.(RenderService) -> Unit) = runTest {
        mock.canned["paraminfo"] = """{"paraminfo":{"modules":[]}}"""
        val wiki = mock.open()
        block(wiki.render)
        wiki.close()
    }

    private fun sent(): Map<String, String> = mock.requests.last { it["action"] == "parse" }

    private val parsed =
        """{"parse":{"text":"<p>hi</p>","revid":9,
          "links":[{"ns":0,"title":"Volcano","exists":true},{"ns":0,"title":"Red","exists":false}],
          "templates":[{"ns":10,"title":"Template:Fire","exists":true}],
          "categories":[{"category":"Red_rocks"}],
          "images":["Fire_pit.jpg"],
          "externallinks":["https://example.org"],
          "limitreportdata":[{"name":"limitreport-expensivefunctioncount","0":3,"1":500},
            {"name":"scribunto-limitreport-timeusage","0":"0.25"},
            {"name":"scribunto-limitreport-memusage","0":1024}],
          "sections":[{"index":"1","line":"Top","level":"2","toclevel":1,"number":"1","anchor":"Top",
            "byteoffset":10}]}}"""

    @Test
    fun `sections reads each heading`() = runWiki { render ->
        mock.canned["parse"] = parsed

        val section = render.sections("Volcano").await().single()

        listOf(section.index, section.heading, section.number, section.anchor) shouldBe
            listOf("1", "Top", "1", "Top")
        listOf(section.level, section.tocLevel, section.byteOffset) shouldBe listOf(2, 1, 10)
        sent()["page"] shouldBe "Volcano"
    }

    @Test
    fun `render and renderText answer with HTML`() = runWiki { render ->
        mock.canned["parse"] = parsed

        render.render("Volcano").await() shouldBe "<p>hi</p>"
        sent()["prop"] shouldBe "text"
        render.renderText("hi", "Volcano").await() shouldBe "<p>hi</p>"
        listOf(sent()["text"], sent()["title"]) shouldBe listOf("hi", "Volcano")
    }

    @Test
    fun `resolve reads what a page resolves to`() = runWiki { render ->
        mock.canned["parse"] = parsed

        val page = render.resolve("Volcano", literal(arrayOf("links", "external-links"))).await()

        sent()["prop"] shouldBe "links|externallinks"
        page.html shouldBe "<p>hi</p>"
        page.revision shouldBe 9.0
        page.links.map { it.page to it.exists } shouldBe listOf("Volcano" to true, "Red" to false)
        page.templates.single().page shouldBe "Template:Fire"
        page.categories.toList() shouldBe listOf("Category:Red rocks")
        page.images.toList() shouldBe listOf("File:Fire pit.jpg")
        page.externalLinks.toList() shouldBe listOf("https://example.org")
        page.sections.single().heading shouldBe "Top"
        val report = page.limitReport!!
        report.expensiveFunctions shouldBe 3
        report.luaTime shouldBe 250.0
        report.luaMemory shouldBe 1024.0
        (report.entries.asDynamic()["limitreport-expensivefunctioncount"][1] as String) shouldBe "500"
    }

    @Test
    fun `resolveText asks for what a page points at unless told`() = runWiki { render ->
        mock.canned["parse"] = parsed

        render.resolveText("hi", "Volcano").await()

        sent()["prop"] shouldBe "links|templates|categories|images|externallinks"
        render.contents.toList() shouldBe
            listOf("links", "templates", "categories", "images", "external-links")
        render.all.map { it.toString() }.contains("toc-data") shouldBe true
        render.all.map { it.toString() }.contains("sections") shouldBe false
    }

    @Test
    fun `parse sends a sandbox, a revision and a parser`() = runWiki { render ->
        mock.canned["parse"] = parsed

        render
            .parse(
                optionsOf(
                    "text" to "{{x}}",
                    "context" to "Volcano",
                    "properties" to arrayOf("text", "limit-report"),
                    "sandbox" to
                        optionsOf<TemplateSandboxOptions>(
                            "prefixes" to arrayOf("User:Me/sandbox"),
                            "title" to "Template:Fire",
                            "text" to "new",
                            "contentModel" to "wikitext",
                        ),
                    "revision" to 7.0,
                    "parser" to "parsoid",
                )
            )
            .await()

        val sent = sent()
        listOf(
            sent["prop"],
            sent["templatesandboxprefix"],
            sent["templatesandboxtitle"],
            sent["templatesandboxtext"],
            sent["templatesandboxcontentmodel"],
            sent["revid"],
            sent["parser"],
        ) shouldBe
            listOf(
                "text|limitreportdata",
                "User:Me/sandbox",
                "Template:Fire",
                "new",
                "wikitext",
                "7",
                "parsoid",
            )

        render.parse(optionsOf("page" to "Volcano")).await()
        sent()["page"] shouldBe "Volcano"
    }

    @Test
    fun `parse rejects what cannot be parsed`() = runWiki { render ->
        shouldThrow<Throwable> { render.parse(optionsOf("text" to "x")).await() }.message shouldContain
            "context"
        shouldThrow<Throwable> { render.parse(optionsOf("page" to "x", "parser" to "new")).await() }
            .message shouldContain "'parsoid'"
        shouldThrow<Throwable> { render.resolve("x", literal(arrayOf("everything"))).await() }
            .message shouldContain "'toc-data'"
    }
}
