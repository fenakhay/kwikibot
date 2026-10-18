package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.model.WikiError
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class WikitextCorpusTest {

    private val directory = Files.createTempDirectory("kwikibot-corpus")
    private val target = directory.resolve("wikitext-cases.json")
    private val cases = WikitextCases.ALL

    @Test
    fun `each case is recorded with what MediaWiki made of it`() = runTest {
        val wikis = wiki()

        recordCorpus(arrayOf(target.toString()), wikis.environment)

        val document = Json.parseToJsonElement(target.readText()).jsonObject
        document.getValue("wiki").jsonPrimitive.content shouldBe "en.wiktionary.org"
        document.getValue("about").jsonPrimitive.content shouldContain "replayed offline"
        val recorded = document.getValue("cases").jsonArray.map { it.jsonObject }
        recorded.map { it.text("name") to it.text("input") } shouldBe cases.map { it.name to it.input }

        recorded.single { it.text("name") == "heading/level2" }.getValue("mediawiki") shouldBe
            buildJsonObject {
                // The whitespace MediaWiki varies between releases is taken out.
                put("html", "<h2>== Heading ==</h2>")
                putJsonArray("templates") { add("Template:Zqx template") }
                putJsonArray("links") { add("Zqx page") }
                // A heading the wiki gives no level or no text for is left out.
                putJsonArray("sections") { add("2:Heading") }
                putJsonArray("externallinks") { add("https://example.org/zqx") }
                put("parsetree", "<root>== Heading ==</root>")
            }
        wikis.output.toString() shouldBe "wrote $target: ${cases.size} cases, ${cases.size} recorded now\n"
        wikis.log.lines().first() shouldBe "  20/${cases.size}"
    }

    @Test
    fun `each case is asked for as a fragment, parsed on its own, without decoration`() = runTest {
        val wikis = wiki()

        recordCorpus(arrayOf(target.toString()), wikis.environment)

        wikis.sent.map { it["text"] } shouldBe cases.map { it.input }
        val request = wikis.sent.first { it["text"] == "== Heading ==" }
        request.host shouldBe "en.wiktionary.org"
        request["action"] shouldBe "parse"
        request["title"] shouldBe "Sandbox"
        request["contentmodel"] shouldBe "wikitext"
        request["prop"] shouldBe "text|templates|links|tocdata|externallinks|parsetree"
        request["disablelimitreport"] shouldBe "1"
        request["disableeditsection"] shouldBe "1"
        request["wrapoutputclass"] shouldBe ""
        request["maxlag"] shouldBe null
    }

    @Test
    fun `an answer that says nothing is recorded as nothing`() = runTest {
        val wikis = FakeWikis(this) { respondJson("{}") }

        recordCorpus(arrayOf(target.toString()), wikis.environment)

        val first = Json.parseToJsonElement(target.readText()).jsonObject.getValue("cases").jsonArray.first()
        first.jsonObject.getValue("mediawiki") shouldBe
            buildJsonObject {
                put("html", "")
                putJsonArray("templates") {}
                putJsonArray("links") {}
                putJsonArray("sections") {}
                putJsonArray("externallinks") {}
                put("parsetree", "")
            }
    }

    @Test
    fun `a case already recorded with the same input is kept, and only the rest are asked for`() = runTest {
        val changed = cases[0]
        val added = cases[1]
        target.writeText(
            corpus(
                listOf(changed.name to "what it used to say") +
                    cases.drop(2).map { it.name to it.input } +
                    ("retired/case" to "gone")
            )
        )
        val wikis = wiki()

        recordCorpus(arrayOf(target.toString()), wikis.environment)

        wikis.sent.map { it["text"] } shouldBe listOf(changed.input, added.input)
        val recorded =
            Json.parseToJsonElement(target.readText()).jsonObject.getValue("cases").jsonArray.map {
                it.jsonObject
            }
        recorded.map { it.text("name") } shouldBe cases.map { it.name }
        recorded.drop(2).map { it.getValue("mediawiki").jsonObject.text("html") }.toSet() shouldBe
            setOf("as recorded")
        recorded[0].getValue("mediawiki").jsonObject.text("html") shouldBe "<p>${changed.input}</p>"
        wikis.output.toString() shouldBe "wrote $target: ${cases.size} cases, 2 recorded now\n"
    }

    @Test
    fun `asked for all, every case is recorded again`() = runTest {
        target.writeText(corpus(cases.map { it.name to it.input }))
        val wikis = wiki()

        recordCorpus(arrayOf(target.toString(), "--all"), wikis.environment)

        wikis.sent.size shouldBe cases.size
        wikis.output.toString() shouldBe "wrote $target: ${cases.size} cases, ${cases.size} recorded now\n"
    }

    @Test
    fun `without a file named, the corpus is written under its own name in the working directory`() =
        runTest {
            val wikis = wiki()

            recordCorpus(emptyArray(), wikis.environment)

            val written = wikis.workingDirectory.resolve("wikitext-cases.json")
            Json.parseToJsonElement(written.readText()).jsonObject.getValue("cases").jsonArray.size shouldBe
                cases.size
            wikis.output.toString() shouldBe
                "wrote $written: ${cases.size} cases, ${cases.size} recorded now\n"
        }

    @Test
    fun `asked for all without a file named, the corpus is still written under its own name`() = runTest {
        val wikis = wiki()
        val written = wikis.workingDirectory.resolve("wikitext-cases.json")
        written.writeText(corpus(cases.map { it.name to it.input }))

        recordCorpus(arrayOf("--all"), wikis.environment)

        wikis.sent.size shouldBe cases.size
        wikis.output.toString() shouldBe "wrote $written: ${cases.size} cases, ${cases.size} recorded now\n"
        wikis.workingDirectory.resolve("--all").exists() shouldBe false
    }

    @Test
    fun `a recorded case missing its name or its input is recorded again`() = runTest {
        target.writeText(
            buildJsonObject {
                putJsonArray("cases") {
                    addJsonObject { put("input", cases[0].input) }
                    addJsonObject { put("name", cases[1].name) }
                    cases.drop(2).forEach { case ->
                        addJsonObject {
                            put("name", case.name)
                            put("input", case.input)
                        }
                    }
                }
            }
                .toString()
        )
        val wikis = wiki()

        recordCorpus(arrayOf(target.toString()), wikis.environment)

        wikis.sent.map { it["text"] } shouldBe listOf(cases[0].input, cases[1].input)
    }

    @Test
    fun `a file without a list of cases is recorded afresh`() = runTest {
        target.writeText("""{"wiki":"en.wiktionary.org"}""")
        val wikis = wiki()

        recordCorpus(arrayOf(target.toString()), wikis.environment)

        wikis.sent.size shouldBe cases.size
    }

    @Test
    fun `a wiki that refuses stops the recording before anything is written`() = runTest {
        val wikis =
            FakeWikis(this) {
                respondJson("""{"errors":[{"code":"badvalue","text":"Bad prop.","module":"parse"}]}""")
            }

        val error =
            assertFailsWith<WikiError.Api> { recordCorpus(arrayOf(target.toString()), wikis.environment) }

        error.code shouldBe "badvalue"
        target.exists() shouldBe false
    }

    /** A wiki that answers each fragment with markup, links and headings of every shape it can take. */
    private fun TestScope.wiki() =
        FakeWikis(this) { sent ->
            val input = sent["text"].orEmpty()
            respondJson(
                buildJsonObject {
                    putJsonObject("parse") {
                        put(
                            "text",
                            if (input.startsWith("==")) "\n<h2>==  Heading ==</h2>\n\n" else "<p>$input</p>",
                        )
                        putJsonArray("templates") {
                            addJsonObject {
                                put("ns", 10)
                                put("title", "Template:Zqx template")
                                put("exists", false)
                            }
                            addJsonObject { put("ns", 10) }
                        }
                        putJsonArray("links") { addJsonObject { put("title", "Zqx page") } }
                        putJsonObject("tocdata") {
                            putJsonArray("sections") {
                                addJsonObject {
                                    put("hLevel", 2)
                                    put("line", "Heading")
                                }
                                addJsonObject { put("line", "No level") }
                                addJsonObject { put("hLevel", 3) }
                            }
                        }
                        putJsonArray("externallinks") { add("https://example.org/zqx") }
                        put("parsetree", "<root>$input</root>")
                    }
                }
                    .toString()
            )
        }

    /** A recorded corpus holding [cases] by name and input, each marked as recorded before. */
    private fun corpus(cases: List<Pair<String, String>>): String = buildJsonObject {
        put("wiki", "en.wiktionary.org")
        put(
            "cases",
            buildJsonArray {
                cases.forEach { (name, input) ->
                    addJsonObject {
                        put("name", name)
                        put("input", input)
                        putJsonObject("mediawiki") { put("html", "as recorded") }
                    }
                }
            },
        )
    }
        .toString()

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
