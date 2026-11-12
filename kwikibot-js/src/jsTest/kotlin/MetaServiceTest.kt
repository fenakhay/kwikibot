import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class MetaServiceTest {

    private val mock = MockWiki()

    private fun runWiki(block: suspend TestScope.(MetaService) -> Unit) = runTest {
        val wiki = mock.open()
        block(wiki.meta)
        wiki.close()
    }

    private fun sent(action: String): Map<String, String> = mock.requests.last { it["action"] == action }

    @Test
    fun `property answers with plain JSON and null when absent`() = runWiki { meta ->
        mock.canned["siteinfo"] = """{"query":{"rightsinfo":{"url":"https://example.org","count":[1,2]}}}"""

        val found: dynamic = meta.property("rightsinfo").await()

        (found.url as String) shouldBe "https://example.org"
        (found.count[1] as Int) shouldBe 2
        meta.property("nothing").await() shouldBe null
    }

    @Test
    fun `messages asks in the language given`() = runWiki { meta ->
        mock.canned["allmessages"] =
            """{"query":{"allmessages":[{"name":"edit","content":"Modifier"},{"name":"gone","missing":true}]}}"""

        val found: dynamic = meta.messages(arrayOf("edit", "gone"), "fr").await()

        (found.edit as String) shouldBe "Modifier"
        (found.gone == undefined) shouldBe true
        mock.requests.last { it["meta"] == "allmessages" }["amlang"] shouldBe "fr"
        meta.message("edit").await() shouldBe "Modifier"
        meta.message("gone").await() shouldBe null
    }

    @Test
    fun `magicWords and statistics are tables`() = runWiki { meta ->
        mock.canned["siteinfo"] =
            """{"query":{"magicwords":[{"name":"redirect","aliases":["#REDIRECT","#REDIRECTION"]}],
                "statistics":{"pages":12,"edits":99}}}"""

        (meta.magicWords().await().asDynamic().redirect[1] as String) shouldBe "#REDIRECTION"
        (meta.statistics().await().asDynamic().edits as Double) shouldBe 99.0
    }

    @Test
    fun `languages reads each by code`() = runWiki { meta ->
        mock.canned["languageinfo"] =
            """{"query":{"languageinfo":{"ar":{"name":"Arabic","autonym":"العربية","dir":"rtl",
               "fallbacks":["en"],"bcp47":"ar"}}}}"""

        val language = meta.languages(arrayOf("ar")).await().asDynamic().ar.unsafeCast<LanguageInfo>()

        listOf(language.code, language.name, language.direction, language.bcp47) shouldBe
            listOf("ar", "Arabic", "right-to-left", "ar")
        language.autonym shouldBe "العربية"
        language.fallbacks.toList() shouldBe listOf("en")
        mock.requests.last { it["meta"] == "languageinfo" }["licode"] shouldBe "ar"
    }

    @Test
    fun `applyTags sends everything it is told`() = runWiki { meta ->
        mock.canned["tag"] = """{"tag":[]}"""

        meta
            .applyTags(
                optionsOf(
                    "add" to arrayOf("a", "b"),
                    "remove" to arrayOf("c"),
                    "revisions" to arrayOf(1.0),
                    "recentChanges" to arrayOf(2.0),
                    "logEntries" to arrayOf(3.0),
                    "reason" to "why",
                )
            )
            .await()

        val sent = sent("tag")
        listOf(
            sent["add"],
            sent["remove"],
            sent["revid"],
            sent["rcid"],
            sent["logid"],
            sent["reason"],
        ) shouldBe listOf("a|b", "c", "1", "2", "3", "why")
    }

    @Test
    fun `manageTag sends the operation and refuses an unknown one`() = runWiki { meta ->
        mock.canned["managetags"] = """{"managetags":{}}"""

        meta.manageTag("t", literal("deactivate"), "why").await()

        listOf(sent("managetags")["tag"], sent("managetags")["operation"]) shouldBe listOf("t", "deactivate")
        shouldThrow<Throwable> { meta.manageTag("t", literal("burn")).await() }.message shouldContain
            "'activate'"
    }
}
