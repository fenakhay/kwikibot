import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class WikiInfoTest {

    private val mock = MockWiki().apply { extensions += "Echo" }

    private fun runWiki(login: BotPassword? = null, block: suspend TestScope.(Wiki) -> Unit) = runTest {
        val wiki = mock.open(login)
        block(wiki)
        wiki.close()
    }

    @Test
    fun `info says what the wiki said when opened`() = runWiki { wiki ->
        val info = wiki.info

        listOf(info.id, info.siteName, info.language, info.server, info.mainPage, info.timezone) shouldBe
            listOf("testwiki", "Test Wiki", "fr", "test.example.org", "Main Page", "Europe/Paris")
        listOf(info.articlePath, info.generator, info.version) shouldBe
            listOf("/wiki/$1", "MediaWiki 1.47.0", "1.47.0")
        info.timeOffsetMinutes shouldBe 0
        info.extensions.toList() shouldBe listOf("Echo")
        info.hasExtension("echo") shouldBe true
        info.namespaces.get(14)?.localName shouldBe "Category"
        info.interwiki.has("w") shouldBe true
        info.tempAccounts.enabled shouldBe false
        info.tempAccounts.matchPatterns.toList() shouldBe emptyList()
        info.tempAccounts.matches("~2026-1") shouldBe false
    }

    @Test
    fun `identity says more than user`() =
        runWiki(BotPassword("Bot", "task", "secret")) { wiki ->
            val identity = wiki.identity

            listOf(identity.name, identity.id, identity.isBot, identity.isAnonymous) shouldBe
                listOf("Bot", 7.0, true, false)
            identity.groups.toList() shouldBe listOf("*", "bot", "user")
            identity.rights.toList() shouldBe listOf("bot", "edit")
            identity.batchLimit shouldBe 50
            identity.has("edit") shouldBe true
            identity.has("delete") shouldBe false
        }

    @Test
    fun `namespaces and interwiki answer as the wiki does`() = runWiki { wiki ->
        val category = wiki.namespaces.byPrefix("category")!!
        listOf(category.id, category.canonicalName, category.case, category.subpages) shouldBe
            listOf(14, "Category", "first-letter", false)
        category.aliases.toList() shouldBe emptyList()
        category.prefixes.toList() shouldBe listOf("Category")
        wiki.namespaces.all.first().id shouldBe 0
        wiki.namespaces.get(999) shouldBe null
        wiki.namespaces.byPrefix("nothing") shouldBe null

        wiki.interwiki.has("w") shouldBe true
        wiki.interwiki.has("zz") shouldBe false
        wiki.interwiki.isSelf("w") shouldBe false
    }

    @Test
    fun `endpoint, ref and parseTitleIn resolve against this wiki`() = runWiki { wiki ->
        wiki.endpoint.server shouldBe "test.example.org"
        wiki.endpoint.apiUrl shouldContain "api.php"

        wiki.ref("volcano_ash") shouldBe "Volcano ash"
        wiki.ref("Fire", 14) shouldBe "Category:Fire"
        wiki.ref("Category:Fire", 10) shouldBe "Category:Fire"
        shouldThrow<Throwable> { wiki.ref("w:Fire") }

        wiki.parseTitleIn("Fire", 10).toString() shouldBe "Template:Fire"
    }

    @Test
    fun `call sends each kind of value and answers with plain JSON`() = runWiki { wiki ->
        mock.canned["frobnicate"] = """{"frobnicate":{"ok":[1,2]}}"""

        val answer: dynamic =
            wiki
                .call(
                    optionsOf(
                        "action" to "frobnicate",
                        "name" to "x",
                        "count" to 5,
                        "on" to true,
                        "off" to false,
                        "nothing" to null,
                        "titles" to arrayOf("A", "B"),
                        "when" to Date("2026-01-01T00:00:00Z"),
                    )
                )
                .await()

        (answer.frobnicate.ok[1] as Int) shouldBe 2
        val sent = mock.requests.last { it["action"] == "frobnicate" }
        listOf(sent["name"], sent["count"], sent["on"], sent["titles"]) shouldBe listOf("x", "5", "1", "A|B")
        sent["when"]?.startsWith("2026-01-01") shouldBe true
        sent.containsKey("off") shouldBe false
        sent.containsKey("nothing") shouldBe false
    }

    @Test
    fun `call reports an error from the wiki as an answer, and refuses what cannot be sent`() =
        runWiki { wiki ->
            mock.canned["frobnicate"] = """{"error":{"code":"nope","info":"no"}}"""

            val answer: dynamic =
                wiki
                    .call(
                        optionsOf("action" to "frobnicate"),
                        optionsOf("write" to true, "cacheable" to false),
                    )
                    .await()

            (answer.error.code as String) shouldBe "nope"
            shouldThrow<Throwable> { wiki.call(optionsOf("name" to "x")).await() }.message shouldContain
                "action"
            shouldThrow<Throwable> {
                    wiki.call(optionsOf("action" to "frobnicate", "name" to js("({})"))).await()
                }
                .message shouldContain "'name'"
        }

    @Test
    fun `a write through call carries a token from tokens`() =
        runWiki(BotPassword("Bot", "task", "secret")) { wiki ->
            val tokens = wiki.tokens

            tokens.token().await() shouldBe "c+\\"
            tokens.token(tokens.csrf).await() shouldBe "c+\\"
            tokens.invalidate().await()
            tokens.clear().await()
            listOf(tokens.login, tokens.patrol, tokens.rollback, tokens.userRights, tokens.watch) shouldBe
                listOf("login", "patrol", "rollback", "userrights", "watch")
        }

    @Test
    fun `withFreshToken tries once more when the wiki rejects the token`() =
        runWiki(BotPassword("Bot", "task", "secret")) { wiki ->
            var calls = 0
            val answer =
                wiki.tokens
                    .withFreshToken({ token: String ->
                        calls++
                        if (calls == 1) {
                            untyped(JSON.parse<Any?>("""{"error":{"code":"badtoken"}}"""))
                        } else {
                            answering(Promise.resolve(token))
                        }
                    })
                    .await()

            answer shouldBe "c+\\"
            calls shouldBe 2

            wiki.tokens.withFreshToken({ _: String -> answering(5) }, "csrf").await() shouldBe 5
            wiki.tokens.withFreshToken({ _: String -> answering<Int?>(null) }).await() shouldBe null
            shouldThrow<Throwable> {
                    wiki.tokens
                        .withFreshToken({ _: String ->
                            answering(JSON.parse<Any?>("""{"error":{"code":"badtoken"}}"""))
                        })
                        .await()
                }
                .message shouldContain "csrf"
        }

    @Test
    fun `paramInfo reads what the API accepts`() = runWiki { wiki ->
        mock.canned["paraminfo"] =
            """{"paraminfo":{"modules":[{"name":"usercontribs","path":"query+usercontribs","prefix":"uc",
               "source":"MediaWiki","sourcename":"MW","group":"list","internal":true,"deprecated":true,
               "mustbeposted":true,"parameters":[
                 {"name":"limit","type":"limit","max":500,"highmax":5000,"default":"10","required":true},
                 {"name":"prop","type":["ids","title"],"multi":true,"limit":50,"highlimit":500,
                  "deprecatedvalues":["title"],"internalvalues":["ids"],"deprecated":true,"sensitive":true}]}]}}"""
        val info = wiki.paramInfo

        val module = info.module("query+usercontribs").await()!!
        listOf(
            module.name,
            module.path,
            module.prefix,
            module.source,
            module.sourceName,
            module.group,
        ) shouldBe listOf("usercontribs", "query+usercontribs", "uc", "MediaWiki", "MW", "list")
        listOf(module.isWrite, module.mustBePosted, module.deprecated, module.internal) shouldBe
            listOf(true, true, true, true)
        module.has("limit") shouldBe true
        module.has("nothing") shouldBe false

        val limit = module.get("limit")!!
        listOf(limit.name, limit.type, limit.limit, limit.highLimit, limit.required, limit.default) shouldBe
            listOf("limit", "limit", 500, 5000, true, "10")
        val prop = module.parameters.asDynamic().prop.unsafeCast<ParamDescription>()
        prop.values.toList() shouldBe listOf("ids", "title")
        listOf(
            prop.multiValued,
            prop.deprecated,
            prop.sensitive,
            prop.valueLimit,
            prop.highValueLimit,
        ) shouldBe listOf(true, true, true, 50, 500)
        prop.deprecatedValues.toList() shouldBe listOf("title")
        prop.internalValues.toList() shouldBe listOf("ids")

        info.limit("query+usercontribs", "limit", true).await() shouldBe 5000
        info.supports("query+usercontribs", "prop").await() shouldBe true
        info.values("query+usercontribs", "prop").await()?.toList() shouldBe listOf("ids", "title")
        info.isDeprecated("query+usercontribs", "prop", "title").await() shouldBe true
        info.prefetch(arrayOf("query+usercontribs")).await()
        info.modules(arrayOf("query+usercontribs")).await().single().name shouldBe "usercontribs"
        info.maxModules shouldBe 50
    }

    @Test
    fun `parseOptions parses as the wiki does`() = runWiki { wiki ->
        mock.canned["siteinfo"] =
            """{"query":{"extensiontags":["<custom>"],"protocols":["https://"],
               "general":{"langconversion":false}}}"""

        val options = wiki.parseOptions().await()

        options.extensionTags.toList() shouldBe listOf("custom")
        options.protocols.toList() shouldBe listOf("https://")
        options.languageConversion shouldBe false
        options.parsedTags.isNotEmpty() shouldBe true
        options.preprocessedTags.toList() shouldBe listOf("inputbox")
        options.htmlTags.isNotEmpty() shouldBe true
        options.fileNamespaces.isNotEmpty() shouldBe true
        options.parse("<custom>{{x}}</custom>").templates().size shouldBe 0
        parse("<custom>{{x}}</custom>").templates().size shouldBe 1
    }

    @Test
    fun `titleRules tells templates apart as the wiki does`() = runWiki { wiki ->
        mock.canned["siteinfo"] =
            """{"query":{"namespaces":{"10":{"id":10,"name":"Modèle","canonical":"Template","case":"case-sensitive"}},
               "magicwords":[{"name":"lc","aliases":["lc:"],"case-sensitive":false},
                 {"name":"pagename","aliases":["PAGENAME"],"case-sensitive":true},
                 {"name":"subst","aliases":["subst:"]},{"name":"msg","aliases":["msg:"]}],
               "functionhooks":["lc"],"variables":["pagename"]}}"""

        val rules = wiki.titleRules().await()

        (rules.namespaces.asDynamic()["Modèle"] as Int) shouldBe 10
        (rules.canonicalNames.asDynamic()["10"] as String) shouldBe "Template"
        rules.caseSensitive.toList() shouldBe listOf(10)
        rules.functions.has("LC") shouldBe true
        rules.functions.caseInsensitive.toList() shouldBe listOf("lc")
        rules.variables.has("PAGENAME") shouldBe true
        rules.variables.caseSensitive.toList() shouldBe listOf("PAGENAME")
        rules.substitutions.toList() shouldBe listOf("subst")
        rules.modifiers.toList() shouldBe listOf("msg")

        rules.isParserFunction("PAGENAME") shouldBe true
        rules.isParserFunction("PAGENAME", hasArguments = true) shouldBe false
        rules.key("foo") shouldBe "foo"
        rules.key("PAGENAME") shouldBe null
        rules.key("PAGENAME", hasArguments = true) shouldBe "PAGENAME"

        val text = parse("{{foo}}{{Foo}}{{Modèle:foo}}")
        rules.keyOf(text.templates().first()) shouldBe "foo"
        rules.templatesIn(text, arrayOf("foo")).size shouldBe 2
    }

    @Test
    fun `the facade's old exports are all still there`() = runWiki { wiki ->
        wiki.pages(emptyArray()).await().size shouldBe 0
        wiki.user.isAnonymous shouldBe true
    }
}
