package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiPageService
import com.fenakhay.kwikibot.client.internal.ApiRenderService
import com.fenakhay.kwikibot.client.templateNames
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import com.fenakhay.kwikibot.testkit.MockTransport
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Doing many things in few requests: expanding texts, listing redirects, parsing with a sandbox. */
class BulkServiceTest {

    private val wiki = WikiId("enwiktionary")

    private fun ref(text: String, namespace: Namespace = Namespace.MAIN) =
        PageRef(wiki, Title.Local(namespace, text))

    private fun pages(transport: MockTransport) =
        ApiPageService(
            transport = transport,
            tokens = TokenStore(transport),
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
        )

    /** An expansion that upper-cases the text it was given, as a wiki would expand `{{uc:…}}`. */
    private fun upperCasing(swallow: Boolean = false) = MockTransport { request ->
        val text = request.params.getValue("text")
        val expanded = if (swallow) text.substringBefore("\n<nowiki>") else upperCaseOutsideNowiki(text)
        buildJsonObject {
            putJsonObject("expandtemplates") {
                put("wikitext", expanded)
                if ("categories" in request.params.getValue("prop")) {
                    put(
                        "categories",
                        buildJsonArray { add(buildJsonObject { put("category", "English_nouns") }) },
                    )
                }
            }
        }
    }

    /** What a wiki does to `{{uc:…}}`, leaving what is in `<nowiki>` as it was. */
    private fun upperCaseOutsideNowiki(text: String): String {
        val out = StringBuilder()
        var last = 0
        for (kept in Regex("<nowiki>.*?</nowiki>").findAll(text)) {
            out.append(text.substring(last, kept.range.first).uppercase()).append(kept.value)
            last = kept.range.last + 1
        }
        return out.append(text.substring(last).uppercase()).toString()
    }

    @Test
    fun `many texts are expanded in one request and come back apart, in order`() = runTest {
        val transport = upperCasing()
        val texts = listOf("a", "* b", "c\nd")

        val expanded = pages(transport).expandTexts(texts)

        expanded.map { it.text } shouldBe listOf("A", "* B", "C\nD")
        transport.requests.size shouldBe 1
    }

    @Test
    fun `texts beyond a batch go in further requests`() = runTest {
        val transport = upperCasing()

        val expanded = pages(transport).expandTexts(List(120) { "t$it" })

        expanded.size shouldBe 120
        expanded.last().text shouldBe "T119"
        transport.requests.size shouldBe 3
    }

    @Test
    fun `an expansion that loses a marker is done again a text at a time`() = runTest {
        val transport = upperCasing(swallow = true)

        val expanded = pages(transport).expandTexts(listOf("<!-- a", "b"))

        expanded.map { it.text } shouldBe listOf("<!-- a", "b")
        transport.requests.size shouldBe 3
    }

    @Test
    fun `categories are asked for a text at a time, since they belong to a whole expansion`() = runTest {
        val transport = upperCasing()

        val expanded = pages(transport).expandTexts(listOf("a", "b"), ref("volcano"), categories = true)

        expanded.map { it.categories } shouldBe listOf(listOf("English nouns"), listOf("English nouns"))
        transport.requests.size shouldBe 2
        transport.requests.first().params["title"] shouldBe "volcano"
    }

    @Test
    fun `texts can be expanded with a sandbox and as a saved revision, in every request`() = runTest {
        val transport = upperCasing()
        val sandbox =
            TemplateSandbox(title = ref("l", Namespace.TEMPLATE), text = "{{{2}}}", contentModel = "wikitext")

        val batched =
            pages(transport)
                .expandTexts(listOf("a", "b"), ref("x"), sandbox = sandbox, revision = RevisionId(7))
        pages(transport).expandTexts(listOf("c"), ref("x"), categories = true, sandbox = sandbox)

        batched.map { it.text } shouldBe listOf("A", "B")
        transport.requests.size shouldBe 2
        transport.requests.forEach { request ->
            request.params["templatesandboxtitle"] shouldBe "Template:l"
            request.params["templatesandboxtext"] shouldBe "{{{2}}}"
            request.params["templatesandboxcontentmodel"] shouldBe "wikitext"
        }
        transport.requests.first().params["revid"] shouldBe "7"
        transport.requests.last().params.containsKey("revid") shouldBe false
        transport.requests.last().params.containsKey("templatesandboxprefix") shouldBe false
    }

    @Test
    fun `a page service that cannot batch expands each text on its own, and refuses a sandbox`() = runTest {
        val fake = FakePageService(expander = { text, _ -> text.reversed() })

        (fake as PageService).expandTexts(listOf("ab", "cd")).map { it.text } shouldBe listOf("ba", "dc")
        assertFailsWith<IllegalStateException> {
            fake.expandTexts(listOf("ab"), sandbox = TemplateSandbox(prefixes = listOf("User:Me/sandbox")))
        }
        assertFailsWith<IllegalStateException> { fake.expandTexts(listOf("ab"), revision = RevisionId(1)) }
    }

    @Test
    fun `redirects split across continued answers are all kept`() = runTest {
        val transport = MockTransport { request ->
            val second = "rdcontinue" in request.params
            MockTransport.json(
                if (!second) {
                    """{"continue":{"rdcontinue":"10|2","continue":"||"},"query":{"pages":[
                       {"ns":10,"title":"Template:l","redirects":[{"ns":10,"title":"Template:link"}]}]}}"""
                } else {
                    """{"query":{"pages":[
                       {"ns":10,"title":"Template:l","redirects":[{"ns":10,"title":"Template:L"}]}]}}"""
                }
            )
        }

        val redirects =
            pages(transport).redirectsTo(listOf(ref("l", Namespace.TEMPLATE)), setOf(Namespace.TEMPLATE))

        redirects.getValue(ref("l", Namespace.TEMPLATE)).map { it.title.text } shouldBe listOf("link", "L")
        transport.requests.first().params["rdnamespace"] shouldBe "10"
    }

    @Test
    fun `a template is known by its own name and every redirect to it`() = runTest {
        val fake =
            FakePageService(
                "Template:l" to "{{{1}}}",
                "Template:link" to "#REDIRECT [[Template:l]]",
                "Template:Lien" to "#WEITERLEITUNG [[Template:L]]",
                "lemma" to "#REDIRECT [[Template:l]]",
            )

        FakeWiki(fake).templateNames("l") shouldBe setOf("L", "Link", "Lien")
        FakeWiki(fake).templateNames("Template:l") shouldBe setOf("L", "Link", "Lien")
    }

    @Test
    fun `a template name that would name a page in another namespace keeps its prefix`() = runTest {
        val fake =
            FakePageService(
                "Template:Help:box" to "{{{1}}}",
                "Template:Box help" to "#REDIRECT [[Template:Help:box]]",
                "Template:Note: box" to "#REDIRECT [[Template:Help:box]]",
            )

        FakeWiki(fake).templateNames("Template:Help:box") shouldBe
            setOf("Template:Help:box", "Box help", "Note: box")
    }

    @Test
    fun `a parse can use a sandbox, and report its text and categories in one request`() = runTest {
        var sent: ApiRequest? = null
        val transport = MockTransport { request ->
            sent = request
            MockTransport.json(
                """{"parse":{"title":"volcano","text":"<p>x</p>",""" +
                    """"categories":[{"category":"English_nouns"}]}}"""
            )
        }
        val renderer =
            ApiRenderService(transport, PageDecoder(wiki, NamespaceMap.CANONICAL), NamespaceMap.CANONICAL)
        val request =
            RenderRequest.text(
                    "{{l|en|x}}",
                    ref("volcano"),
                    setOf(ParseProperty.TEXT, ParseProperty.CATEGORIES),
                )
                .copy(
                    sandbox =
                        TemplateSandbox(
                            prefixes = listOf("User:Me/sandbox"),
                            title = ref("l", Namespace.TEMPLATE),
                            text = "{{{2}}}",
                        )
                )

        val parsed = renderer.parse(request)

        parsed.html shouldBe "<p>x</p>"
        parsed.categories.single().title.text shouldBe "English nouns"
        transport.requests.size shouldBe 1
        val params = sent!!.params
        params["prop"] shouldBe "text|categories"
        params["templatesandboxprefix"] shouldBe "User:Me/sandbox"
        params["templatesandboxtitle"] shouldBe "Template:l"
        params["templatesandboxtext"] shouldBe "{{{2}}}"
    }

    @Test
    fun `a parse can name its revision and its parser, and report the limits it used`() = runTest {
        val transport = MockTransport {
            MockTransport.json(
                """{"parse":{"title":"volcano","categories":[],"limitreportdata":[
                   {"name":"limitreport-cputime","0":"0.312"},
                   {"name":"limitreport-expensivefunctioncount","0":2,"1":500},
                   {"name":"scribunto-limitreport-timeusage","0":"0.250","1":"10.000"},
                   {"name":"scribunto-limitreport-memusage","0":1048576,"1":52428800}]}}"""
            )
        }
        val renderer =
            ApiRenderService(transport, PageDecoder(wiki, NamespaceMap.CANONICAL), NamespaceMap.CANONICAL)

        val parsed =
            renderer.parse(
                RenderRequest.text(
                        "{{sa-decl}}",
                        ref("volcano"),
                        setOf(ParseProperty.CATEGORIES, ParseProperty.LIMIT_REPORT),
                    )
                    .copy(revision = RevisionId(93431376), parser = Parser.PARSOID)
            )

        val params = transport.requests.single().params
        params["prop"] shouldBe "categories|limitreportdata"
        params["revid"] shouldBe "93431376"
        params["parser"] shouldBe "parsoid"
        val report = parsed.limitReport!!
        report.luaTime shouldBe 250.milliseconds
        report.luaMemory shouldBe 1_048_576L
        report.expensiveFunctions shouldBe 2
        report.entries["scribunto-limitreport-timeusage"] shouldBe listOf("0.250", "10.000")
        report.entries["limitreport-cputime"] shouldBe listOf("0.312")
    }

    @Test
    fun `a parse that did not ask for the limits has no report, and one without Lua has no Lua figures`() =
        runTest {
            val transport = MockTransport { request ->
                if ("limitreportdata" in request.params.getValue("prop")) {
                    MockTransport.json(
                        """{"parse":{"limitreportdata":[{"name":"limitreport-cputime","0":"0.01"}]}}"""
                    )
                } else {
                    MockTransport.json("""{"parse":{"text":"<p>x</p>"}}""")
                }
            }
            val renderer =
                ApiRenderService(transport, PageDecoder(wiki, NamespaceMap.CANONICAL), NamespaceMap.CANONICAL)

            renderer.parse(RenderRequest.page(ref("a"), setOf(ParseProperty.TEXT))).limitReport shouldBe null
            val report =
                renderer.parse(RenderRequest.page(ref("a"), setOf(ParseProperty.LIMIT_REPORT))).limitReport!!
            report.luaTime shouldBe null
            report.luaMemory shouldBe null
            report.expensiveFunctions shouldBe null
            transport.requests.first().params.containsKey("parser") shouldBe false
            transport.requests.first().params.containsKey("revid") shouldBe false
        }

    @Test
    fun `a request is a page or a text, and a sandbox needs something to stand in`() {
        assertFailsWith<IllegalArgumentException> { RenderRequest() }
        assertFailsWith<IllegalArgumentException> {
            RenderRequest(page = ref("a"), text = "b", context = ref("a"))
        }
        assertFailsWith<IllegalArgumentException> { RenderRequest(text = "b") }
        assertFailsWith<IllegalArgumentException> { TemplateSandbox() }
        assertFailsWith<IllegalArgumentException> { TemplateSandbox(title = ref("l", Namespace.TEMPLATE)) }
        assertFailsWith<IllegalArgumentException> {
            RenderRequest.page(ref("a")).copy(revision = RevisionId(1))
        }
        RenderRequest.page(ref("a")).page shouldBe ref("a")
    }

    @Test
    fun `a renderer that does not override parse answers through resolve and refuses a sandbox`() = runTest {
        var asked: String? = null
        val plain =
            object : RenderService {
                override suspend fun sections(page: PageRef) = TODO()

                override suspend fun render(page: PageRef) = TODO()

                override suspend fun renderText(wikitext: String, context: PageRef) = TODO()

                override suspend fun resolve(page: PageRef, properties: Set<ParseProperty>) =
                    com.fenakhay.kwikibot.model.render.ParsedPage().also { asked = "page" }

                override suspend fun resolveText(
                    wikitext: String,
                    context: PageRef,
                    properties: Set<ParseProperty>,
                ) = com.fenakhay.kwikibot.model.render.ParsedPage().also { asked = "text" }
            }

        plain.parse(RenderRequest.page(ref("a")))
        asked shouldBe "page"
        plain.parse(RenderRequest.text("x", ref("a")))
        asked shouldBe "text"
        assertFailsWith<IllegalStateException> {
            plain.parse(
                RenderRequest.page(ref("a")).copy(sandbox = TemplateSandbox(prefixes = listOf("U:x")))
            )
        }
        assertFailsWith<IllegalStateException> {
            plain.parse(RenderRequest.text("x", ref("a")).copy(revision = RevisionId(1)))
        }
        assertFailsWith<IllegalStateException> {
            plain.parse(RenderRequest.page(ref("a")).copy(parser = Parser.LEGACY))
        }
    }
}
