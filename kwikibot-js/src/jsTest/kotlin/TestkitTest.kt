import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class TestkitTest {

    private fun service(vararg more: Pair<String, Any?>): FakePageService =
        FakePageService(
            optionsOf(
                "pages" to optionsOf<Record<String, String>>("Volcano" to "ash", "Template:Foo" to "bar"),
                *more,
            )
        )

    private fun options(pages: FakePageService, vararg more: Pair<String, Any?>): FakeWikiOptions =
        optionsOf("pages" to pages, *more)

    private val tephra: (Page) -> Any? = { page ->
        optionsOf<PageEdit>("text" to page.text + "!", "summary" to "why", "tags" to arrayOf("t"))
    }

    @Test
    fun `a bot runs on a fake wiki, and what it saved reads back`() = runTest {
        val pages = service()
        val wiki = createFakeWiki(options(pages))

        wiki.id shouldBe "testwiki"
        wiki.user.name shouldBe "TestBot"
        wiki.page("Volcano").await()?.text shouldBe "ash"
        wiki.page("Absent").await() shouldBe null
        wiki.page("Template:Foo").await()?.text shouldBe "bar"

        val dry =
            wiki
                .botRun(optionsOf("source" to pageSourceOfTitles(arrayOf("Volcano")), "transform" to tephra))
                .await()
        dry.pending shouldBe 1
        pages.edits.size shouldBe 0

        val report =
            wiki
                .botRun(
                    optionsOf(
                        "source" to pageSourceOfTitles(arrayOf("Volcano", "Absent")),
                        "dryRun" to false,
                        "stopPolicy" to STOP_POLICY_NONE,
                        "transform" to tephra,
                    )
                )
                .await()
        report.saved shouldBe 1
        report.skipped shouldBe 1

        pages.text("Volcano") shouldBe "ash!"
        pages.text("Absent") shouldBe null
        pages.revision("Volcano") shouldBe FAKE_INITIAL_REVISION + 1
        pages.revision("Absent") shouldBe null

        val edit = pages.edits.single()
        listOf(edit.title, edit.text, edit.summary, edit.baseRevision) shouldBe
            listOf("Volcano", "ash!", "why", FAKE_INITIAL_REVISION)
        edit.tags.toList() shouldBe listOf("t")
        listOf(edit.minor, edit.createOnly, edit.noCreate) shouldBe listOf(false, false, true)
        listOf(edit.section, edit.sectionTitle, edit.prependText, edit.appendText) shouldBe
            listOf(null, null, null, null)
        edit.watchlist shouldBe "preferences"
        edit.watchlistExpiry shouldBe null
        edit.bot shouldBe true
        wiki.close()
    }

    @Test
    fun `the page service answers on a fake wiki`() = runTest {
        val pages = service("expand" to { text: String, title: String? -> "$text in $title" })
        val wiki = createFakeWiki(options(pages))

        wiki.pagesService.watch(arrayOf("Volcano")).await()
        pages.isWatched("Volcano") shouldBe true
        pages.isWatched("Template:Foo") shouldBe false
        wiki.pagesService.expandText("{{x}}", "Volcano").await() shouldBe "{{x}} in Volcano"
        wiki.pagesService.delete("Volcano", optionsOf("reason" to "gone")).await()
        pages.text("Volcano") shouldBe null
        wiki.close()
    }

    @Test
    fun `a refusal is what the fake was told to say`() = runTest {
        fun case(status: String, field: String, value: Any?, read: (EditResult) -> Any?) =
            Triple(optionsOf<FakeRefusal>("status" to status, field to value), value, read)

        val cases =
            listOf(
                case("conflict", "currentRevision", 7.0) { it.currentRevision },
                case("protected", "protectionLevel", "sysop") { it.protectionLevel },
                case("filtered", "filter", "f") { it.filter },
                case("rate-limited", "retryAfter", 5.0) { it.retryAfter },
                case("captcha", "captchaId", "c") { it.captchaId },
                case("page-changed", "wasDeleted", true) { it.wasDeleted },
                case("rejected", "code", "x") { it.code },
                case("permission-denied", "detail", "no") { it.detail },
            )

        cases.forEach { (refusal, value, read) ->
            val pages = service("refuse" to { title: String -> if (title == "Volcano") refusal else null })
            val wiki = createFakeWiki(options(pages))
            val result = wiki.edit("Volcano", "new", "why").await()
            result.status shouldBe refusal.status
            read(result) shouldBe value
            wiki.edit("Template:Foo", "new", "why").await().status shouldBe "saved"
            wiki.close()
        }
    }

    @Test
    fun `a status nobody has heard of says which there are`() = runTest {
        val pages = service("refuse" to { _: String -> optionsOf<FakeRefusal>("status" to "nope") })
        val wiki = createFakeWiki(options(pages))
        shouldThrow<IllegalArgumentException> { wiki.edit("Volcano", "new", "why").await() }
            .message
            .orEmpty() shouldContain "conflict"
        wiki.close()
    }

    @Test
    fun `a failure is an error the wiki would give, and stops a run`() = runTest {
        val pages =
            service(
                "failWith" to { optionsOf<FakeFailure>("code" to "readonly", "info" to "down for a while") }
            )
        val wiki = createFakeWiki(options(pages))

        val report =
            wiki
                .botRun(
                    optionsOf(
                        "source" to pageSourceOfTitles(arrayOf("Volcano")),
                        "dryRun" to false,
                        "transform" to tephra,
                    )
                )
                .await()

        report.failed shouldBe 1
        report.stopReason shouldBe "read-only"
        describeError(report.problems.single().error)?.kind shouldBe "read-only"
        wiki.close()
    }

    @Test
    fun `what the fake leaves unimplemented rejects with its own message`() = runTest {
        val wiki = createFakeWiki()

        suspend fun rejects(what: String, action: suspend () -> Any?) {
            shouldThrow<NotImplementedError> { action() }.message.orEmpty() shouldContain
                "does not implement $what"
        }

        rejects("lists") { wiki.lists.backlinks("Volcano").take() }
        rejects("lists") { wiki.categoryMembers("Lava").take() }
        rejects("lists") { wiki.search("x").take() }
        rejects("revisions") { wiki.history("Volcano").take() }
        rejects("revisions") { wiki.revisions.deletedHistory("Volcano").take() }
        rejects("users") { wiki.users.contributions("Alice").take() }
        rejects("logs") { wiki.logs.events().take() }
        rejects("files") { wiki.files.usage("Fire.jpg").take() }
        rejects("files") { wiki.files.latest("Fire.jpg").await() }
        rejects("extensions") { wiki.extensions.lintErrors().take() }
        rejects("meta") { wiki.meta.statistics().await() }
        rejects("renderer") { wiki.render.render("Volcano").await() }
        rejects("proofread") { wiki.proofread.quality(arrayOf("Page:X")).await() }
        rejects("lists") { parsePageSource("cat:Lava").pages(wiki).take() }
        rejects("transport.call(query)") { wiki.call(optionsOf("action" to "query")).await() }
        wiki.close()
    }

    @Test
    fun `a run over a listing the fake has not rejects with its message`() = runTest {
        val wiki = createFakeWiki()
        shouldThrow<NotImplementedError> {
                wiki.botRun(optionsOf("source" to parsePageSource("cat:Lava"), "transform" to tephra)).await()
            }
            .message
            .orEmpty() shouldContain "does not implement lists"
        wiki.close()
    }

    @Test
    fun `an edit of a numbered section rejects with the fake's message`() = runTest {
        val wiki = createFakeWiki(options(service()))
        shouldThrow<NotImplementedError> {
                wiki.pagesService
                    .edit("Volcano", optionsOf("text" to "x", "summary" to "y", "section" to "1"))
                    .await()
            }
            .message
            .orEmpty() shouldContain "numbered sections"
        wiki.close()
    }

    @Test
    fun `a mock transport answers calls and keeps what was sent`() = runTest {
        val transport =
            MockTransport({ params: Record<String, String> ->
                when (params.toMap()["action"]) {
                    "fail" -> throw IllegalStateException("no")
                    "slow" -> js("Promise.resolve({slow: true})")
                    else -> JSON.parse<Any?>("""{"query":{"a":1}}""")
                }
            })
        val wiki = createFakeWiki(optionsOf("transport" to transport))
        val query: Record<String, ApiValue> = optionsOf("action" to "query", "meta" to "x")

        (wiki.call(query).await().asDynamic().query.a as Int) shouldBe 1
        (wiki.call(optionsOf("action" to "slow")).await().asDynamic().slow as Boolean) shouldBe true
        wiki.callPaged(query).take().size shouldBe 1
        shouldThrow<Throwable> { wiki.call(optionsOf("action" to "fail")).await() }
        wiki.call(query, optionsOf("write" to true, "cacheable" to false)).await()

        val first = transport.requests.first()
        first.action shouldBe "query"
        first.params.toMap()["meta"] shouldBe "x"
        first.isWrite shouldBe false
        first.cacheable shouldBe true
        transport.requests.last().isWrite shouldBe true
        transport.requests.last().cacheable shouldBe false
        wiki.endpoint.server shouldBe "test.example.org"
        wiki.close()

        val elsewhere = MockTransport({ JSON.parse("{}") }, endpointOf("other.example.org"))
        createFakeWiki(optionsOf("transport" to elsewhere)).endpoint.server shouldBe "other.example.org"
    }

    @Test
    fun `a fake wiki says who it is told to`() = runTest {
        val namespaces =
            createNamespaces(
                arrayOf(optionsOf("id" to 0, "name" to ""), optionsOf("id" to 2, "name" to "Benutzer"))
            )
        val wiki =
            createFakeWiki(
                optionsOf(
                    "id" to "mywiki",
                    "namespaces" to namespaces,
                    "identity" to
                        optionsOf<FakeIdentity>(
                            "name" to "Me",
                            "id" to 5.0,
                            "groups" to arrayOf("user"),
                            "rights" to arrayOf("edit"),
                        ),
                )
            )

        wiki.id shouldBe "mywiki"
        wiki.user.name shouldBe "Me"
        wiki.user.groups.toList() shouldBe listOf("user")
        wiki.user.rights.toList() shouldBe listOf("edit")
        wiki.identity.id shouldBe 5.0
        wiki.ref("Benutzer:Me") shouldBe "Benutzer:Me"
        wiki.close()
    }

    @Test
    fun `services from another wiki answer on a fake one`() = runTest {
        val mock = MockWiki(members = listOf("Etna"))
        val real = mock.open()
        val pages = service()
        val wiki = createFakeWiki(options(pages, "services" to real))

        wiki.categoryMembers("Volcanoes").take() shouldBe listOf("Etna")
        wiki.page("Volcano").await()?.text shouldBe "ash"
        shouldThrow<NotImplementedError> { wiki.files.latest("Fire.jpg").await() }
        wiki.close()
        real.close()
    }

    @Test
    fun `a bot's recording context works on a fake wiki`() = runTest {
        val pages = service()
        val wiki = createFakeWiki(options(pages))
        val recording = RecordingContext(wiki)

        recording.context.wiki.edit("Volcano", "new", "why").await().status shouldBe "saved"
        recording.context.say("done")

        recording.said.toList() shouldBe listOf("done")
        pages.text("Volcano") shouldBe "new"
        wiki.close()
    }

    @Test
    fun `globalThis fetch stubbed answers everything a real wiki asks`() = runTest {
        val global: dynamic = js("globalThis")
        val real = global.fetch
        val sent = mutableListOf<String>()
        global.fetch = { url: dynamic, _: dynamic ->
            val address: String = url.toString()
            sent += address
            val text =
                when {
                    "siteinfo" in address ->
                        """{"query":{"general":{"wikiid":"stub","sitename":"Stub","lang":"en","server":"//s",
                           "articlepath":"/wiki/${'$'}1","mainpage":"Main","generator":"MediaWiki 1.47.0"},
                           "namespaces":{"0":{"id":0,"name":"","case":"first-letter"}},"interwikimap":[]}}"""
                    "userinfo" in address ->
                        """{"query":{"userinfo":{"id":0,"name":"1.2.3.4","groups":["*"]}}}"""
                    else ->
                        """{"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano","revisions":[{"revid":1,
                           "timestamp":"2026-01-01T00:00:00Z","slots":{"main":{"content":"ash"}}}]}]}}"""
                }
            val headers: dynamic = js("({})")
            headers["content-type"] = "application/json"
            val init: dynamic = js("({})")
            init.status = 200
            init.headers = headers
            js("Promise.resolve(new Response(text, init))")
        }
        try {
            val wiki = connect("stub.example.org", "https://example.org/me").await()
            wiki.siteName shouldBe "Stub"
            wiki.page("Volcano").await()?.text shouldBe "ash"
            sent.isNotEmpty() shouldBe true
            wiki.close()
        } finally {
            global.fetch = real
        }
    }
}
