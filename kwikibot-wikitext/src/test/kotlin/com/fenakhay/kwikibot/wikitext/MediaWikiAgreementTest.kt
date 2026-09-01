package com.fenakhay.kwikibot.wikitext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.fail

class MediaWikiAgreementTest {

    private val cases by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/wikitext-cases.json")) {
            "wikitext-cases.json missing from test resources"
        }
        Json.parseToJsonElement(stream.reader().readText())
            .jsonObject["cases"]!!
            .jsonArray
            .map { it.jsonObject }
    }

    @Test
    fun `every case round-trips byte for byte`() {
        val failures = cases.mapNotNull { case ->
            val input = case["input"]!!.jsonPrimitive.content
            val serialized = Wikitext.parse(input).serialize()
            if (serialized == input) {
                null
            } else {
                "  ${case["name"]!!.jsonPrimitive.content}\n" +
                    "      in:  ${input.escaped()}\n" +
                    "      out: ${serialized.escaped()}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of ${cases.size} cases did not round-trip:\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    @Test
    fun `the parser finds the templates MediaWiki found`() {
        check("templates") { code ->
            code.topLevel<Template>()
                .map { it.title.trim().capitalizeFirst() }
                .filter { it.isNotEmpty() }
        }
    }

    @Test
    fun `the parser finds the links MediaWiki found`() {
        check("links") { code ->
            code.topLevel<WikiLink>()
                .map { it.title.trim().removePrefix(":").substringBefore('#').capitalizeFirst() }
                .filter { it.isNotEmpty() }
        }
    }

    @Test
    fun `the parser finds the headings MediaWiki found`() {
        val problems = cases.mapNotNull { case ->
            val name = case["name"]!!.jsonPrimitive.content
            val headings = Wikitext.parse(case["input"]!!.jsonPrimitive.content).topLevel<Heading>()
            val recorded = case["mediawiki"]!!.jsonObject["sections"]!!.jsonArray
                .map { it.jsonPrimitive.content }

            val plain = headings.all { heading -> heading.title.nodes.all { it is TextNode } }

            val actual = headings.map {
                if (plain) "${it.level}:${it.title.text.trim()}" else "${it.level}:"
            }
            val expected =
                if (plain) recorded else recorded.map { it.substringBefore(':') + ":" }

            if (expected.sorted() == actual.sorted()) {
                null
            } else {
                "  $name: found $actual, MediaWiki had $expected"
            }
        }

        if (problems.isNotEmpty()) {
            fail(
                "${problems.size} of ${cases.size} cases disagree on sections:\n" +
                    problems.joinToString("\n"),
            )
        }
    }

    @Test
    fun `the parser finds the external links MediaWiki found`() {
        check("externallinks") { code -> code.topLevel<ExternalLink>().map { it.url.text.trim() } }
    }

    private inline fun <reified T : Node> Markup.topLevel(): List<T> =
        outsideTemplates(nodes).filterIsInstance<T>()

    private fun outsideTemplates(nodes: List<Node>): List<Node> = buildList {
        for (node in nodes) {
            add(node)
            when (node) {
                is Template -> Unit
                is Heading -> addAll(outsideTemplates(node.title.nodes))
                is WikiLink -> {
                    addAll(outsideTemplates(node.target.nodes))
                    node.text?.let { addAll(outsideTemplates(it.nodes)) }
                }
                is Tag -> node.contents?.let { addAll(outsideTemplates(it.nodes)) }
                is Argument -> {
                    addAll(outsideTemplates(node.name.nodes))
                    node.default?.let { addAll(outsideTemplates(it.nodes)) }
                }
                else -> Unit
            }
        }
    }

    private fun check(property: String, found: (Markup) -> List<String>) {
        val problems = cases.mapNotNull { case ->
            val name = case["name"]!!.jsonPrimitive.content
            val input = case["input"]!!.jsonPrimitive.content

            val expected = case["mediawiki"]!!.jsonObject[property]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .map { it.expectedFor(property, case) }
                .filterNotNull()
                .sorted()
            val actual = found(Wikitext.parse(input)).sorted()

            if (expected == actual) null else "  $name: found $actual, MediaWiki had $expected"
        }

        if (problems.isNotEmpty()) {
            fail(
                "${problems.size} of ${cases.size} cases disagree on $property:\n" +
                    problems.joinToString("\n"),
            )
        }
    }

    private fun String.expectedFor(property: String, case: kotlinx.serialization.json.JsonObject): String? {
        val templates = case["mediawiki"]!!.jsonObject["templates"]!!.jsonArray
            .map { it.jsonPrimitive.content }

        return when (property) {
            "templates" -> removePrefix(TEMPLATE_PREFIX)
            "links" -> if (this in templates) null else this
            else -> this
        }
    }

    private fun String.capitalizeFirst(): String =
        if (isEmpty()) this else this[0].uppercaseChar() + substring(1)

    private fun String.escaped(): String =
        replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private companion object {
        const val TEMPLATE_PREFIX = "Template:"
    }
}
