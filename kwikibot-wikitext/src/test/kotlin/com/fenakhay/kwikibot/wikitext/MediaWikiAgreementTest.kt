package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.HtmlEntity
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * The parser against what MediaWiki recorded for each case in `wikitext-cases.json`.
 *
 * The strongest check is the preprocessor's own tree, `parsetree`: the parser follows that preprocessor's
 * rules, so the two must agree on every template, argument, part, heading and extension tag. The others
 * compare what the rendered page linked to, which is evidence for what MediaWiki read after the preprocessor.
 *
 * A known disagreement is listed in [PENDING] with its reason, and tolerated. A listed case that starts
 * agreeing fails the test until it is taken off, so a fix is noticed and the entry cannot later hide a
 * regression.
 */
class MediaWikiAgreementTest {

    private val cases by lazy {
        val stream =
            checkNotNull(javaClass.getResourceAsStream("/wikitext-cases.json")) {
                "wikitext-cases.json missing from test resources"
            }
        Json.parseToJsonElement(stream.reader().readText()).jsonObject["cases"]!!.jsonArray.map {
            it.jsonObject
        }
    }

    @Test
    fun `every case round-trips byte for byte`() {
        val failures = cases.mapNotNull { case ->
            val input = case.input
            val serialized = Wikitext.parse(input).serialize()
            if (serialized == input) {
                null
            } else {
                "  ${case.name}\n      in:  ${input.escaped()}\n      out: ${serialized.escaped()}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of ${cases.size} cases did not round-trip:\n" + failures.joinToString("\n")
            )
        }
    }

    @Test
    fun `the parser reads every case as MediaWiki's preprocessor did`() {
        check("parsetree") { case ->
            val recorded = case.mediawiki["parsetree"]!!.jsonPrimitive.content
            val expected = projectRecorded(recorded)
            val actual = project(Wikitext.parse(case.input).nodes)
            if (expected == actual) null else "found  $actual\n      MediaWiki had $expected"
        }
    }

    @Test
    fun `the parser finds the templates MediaWiki found`() {
        check("templates") { case ->
            val expected = case.recorded("templates").map { it.removePrefix(TEMPLATE_PREFIX) }.sorted()
            val actual = transcluded(Wikitext.parse(case.input).nodes).sorted()
            if (expected == actual) null else "found $actual, MediaWiki had $expected"
        }
    }

    @Test
    fun `the parser finds the links MediaWiki found`() {
        check("links") { case ->
            val templates = case.recorded("templates").toSet()
            val expected = case.recorded("links").filter { it !in templates }.sorted()
            val actual =
                visible<WikiLink>(Wikitext.parse(case.input).nodes)
                    .map { it.title.trim().removePrefix(":").substringBefore('#').capitalizeFirst() }
                    .filter { it.isNotEmpty() && it.substringBefore(':') !in FILE_NAMESPACES }
                    .sorted()
            if (expected == actual) null else "found $actual, MediaWiki had $expected"
        }
    }

    @Test
    fun `the parser finds the external links MediaWiki found`() {
        check("externallinks") { case ->
            // MediaWiki reports a URL with a `|` in it percent-encoded.
            val expected = case.recorded("externallinks").map { it.replace("%7C", "|") }.sorted()
            val actual =
                visible<ExternalLink>(Wikitext.parse(case.input).nodes).map { it.url.text.trim() }.sorted()
            if (expected == actual) null else "found $actual, MediaWiki had $expected"
        }
    }

    @Test
    fun `the parser pairs HTML tags where MediaWiki ends them`() {
        check("pairing") { case ->
            PairingProjection.compare(case.input, case.mediawiki["html"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `the parser finds the sections MediaWiki found`() {
        check("sections") { case ->
            val expected = case.recorded("sections").map { it.substringBefore(':').toInt() }
            val actual = Wikitext.parse(case.input).headings().map { it.level }
            if (expected == actual) null else "found levels $actual, MediaWiki had $expected"
        }
    }

    // ------------------------------------------------------------------ checking

    private fun check(property: String, compare: (JsonObject) -> String?) {
        val pending = PENDING[property].orEmpty()
        val problems = mutableListOf<String>()
        val fixed = mutableListOf<String>()

        for (case in cases) {
            val problem = compare(case)
            when {
                problem != null && case.name !in pending -> problems += "  ${case.name}: $problem"
                problem == null && case.name in pending -> fixed += "  ${case.name}"
            }
        }

        val unknown = pending - cases.map { it.name }.toSet()
        if (problems.isEmpty() && fixed.isEmpty() && unknown.isEmpty()) return

        fail(
            buildString {
                if (problems.isNotEmpty()) {
                    append("${problems.size} of ${cases.size} cases disagree on $property:\n")
                    append(problems.joinToString("\n")).append('\n')
                }
                if (fixed.isNotEmpty()) {
                    append("these now agree on $property; take them off PENDING:\n")
                    append(fixed.joinToString("\n")).append('\n')
                }
                if (unknown.isNotEmpty()) append("PENDING names cases that do not exist: $unknown\n")
            }
        )
    }

    private val JsonObject.name: String
        get() = this["name"]!!.jsonPrimitive.content

    private val JsonObject.input: String
        get() = this["input"]!!.jsonPrimitive.content

    private val JsonObject.mediawiki: JsonObject
        get() = this["mediawiki"]!!.jsonObject

    private fun JsonObject.recorded(property: String): List<String> =
        mediawiki[property]!!.jsonArray.map { it.jsonPrimitive.content }

    // ------------------------------------------------------------------ the preprocessor's tree

    /**
     * MediaWiki's `parsetree` as a string that says only what the preprocessor decided.
     *
     * Text is kept with its whitespace removed, since a comment alone on its line takes the line break with
     * it there and leaves it as text here. Comments and `<noinclude>` markers go, as they produce nothing.
     */
    private fun projectRecorded(xml: String): String {
        val document =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(xml.reader()))
        return buildString { recorded(document.documentElement, this) }
    }

    private fun recorded(element: Element, out: StringBuilder) {
        for (index in 0 until element.childNodes.length) {
            val child = element.childNodes.item(index)
            if (child !is Element) {
                out.append(child.textContent.withoutWhitespace())
                continue
            }
            when (child.tagName) {
                "template",
                "tplarg" -> {
                    out.append(if (child.tagName == "template") "⟨T" else "⟨A")
                    val parts = child.elements()
                    recorded(parts.first { it.tagName == "title" }, out)
                    for (part in parts.filter { it.tagName == "part" }) {
                        out.append('|')
                        val name = part.elements().first { it.tagName == "name" }
                        if (!name.hasAttribute("index")) {
                            recorded(name, out)
                            out.append('=')
                        }
                        recorded(part.elements().first { it.tagName == "value" }, out)
                    }
                    out.append('⟩')
                }
                "ext" -> out.append("⟨E").append(child.elements().first().textContent.lowercase()).append('⟩')
                "h",
                "possible-h" -> {
                    val level = child.getAttribute("level").toInt()
                    val inner = StringBuilder().also { recorded(child, it) }.toString()
                    out.append("⟨H$level:").append(inner.drop(level).dropLast(level)).append('⟩')
                }
                "comment",
                "ignore" -> Unit
                else -> recorded(child, out)
            }
        }
    }

    private fun Element.elements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /** The parser's tree as the same string, everything the preprocessor does not decide written as text. */
    private fun project(nodes: List<Node>): String = buildString { for (node in nodes) project(node, this) }

    private fun project(markup: Markup?): String = markup?.let { project(it.nodes) }.orEmpty()

    @Suppress("CyclomaticComplexMethod") // One branch per node type.
    private fun project(node: Node, out: StringBuilder) {
        when (node) {
            // A transclusion tag nothing paired is text here, and nothing to the preprocessor.
            is TextNode -> out.append(node.text.replace(TRANSCLUSION_MARKER, "").withoutWhitespace())
            is Comment -> Unit
            is Template -> {
                out.append("⟨T").append(project(node.name))
                for (parameter in node.parameters) {
                    out.append('|')
                    if (parameter.showKey) out.append(project(parameter.name)).append('=')
                    out.append(project(parameter.value))
                }
                out.append('⟩')
            }
            is Argument -> {
                out.append("⟨A").append(project(node.name))
                node.default?.let { out.append('|').append(project(it)) }
                out.append('⟩')
            }
            is Heading -> out.append("⟨H${node.level}:").append(project(node.title)).append('⟩')
            is WikiLink -> {
                out.append("[[").append(project(node.target))
                node.text?.let { out.append('|').append(project(it)) }
                out.append("]]")
            }
            is ExternalLink ->
                if (node.brackets) {
                    out.append('[').append(project(node.url)).append(project(node.title)).append(']')
                } else {
                    out.append(project(node.url))
                }
            is HtmlEntity -> out.append(node.serialize())
            is Tag -> projectTag(node, out)
        }
    }

    private fun projectTag(tag: Tag, out: StringBuilder) {
        val markup = tag.wikiMarkup
        when {
            markup != null -> {
                out.append(markup)
                if (!tag.selfClosing) out.append(project(tag.contents)).append(markup)
            }
            tag.name.lowercase() in ParseOptions.DEFAULT.extensionTags ->
                out.append("⟨E").append(tag.name.lowercase()).append('⟩')
            tag.name.lowercase() in TRANSCLUSION_TAGS -> out.append(project(tag.contents))
            else -> {
                out.append('<').append(tag.name)
                for (attribute in tag.attributes) {
                    out.append(project(attribute.name))
                    attribute.value?.let {
                        out.append('=').append(attribute.quote.orEmpty()).append(project(it))
                        out.append(attribute.quote.orEmpty())
                    }
                }
                when {
                    !tag.selfClosing ->
                        out.append('>')
                            .append(project(tag.contents))
                            .append(tag.closingTag.withoutWhitespace())
                    tag.implicitClose -> out.append('>')
                    else -> out.append("/>")
                }
            }
        }
    }

    // ------------------------------------------------------------------ what the page shows

    /**
     * The templates MediaWiki would transclude: the top-level ones, any a template's name is built from, and
     * those in a parser function's arguments. A function such as `#if` expands only the branch it takes, so
     * counting every branch holds only while, as in the corpus, the branch taken is the one with templates. A
     * template's own parameters are not counted, since a template that does not exist expands none of them.
     */
    private fun transcluded(nodes: List<Node>): List<String> = buildList {
        for (node in visibleNodes(nodes)) {
            if (node !is Template) continue
            if (!node.isParserFunction) {
                node.name.text.trim().capitalizeFirst().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            addAll(transcluded(node.name.nodes))
            if (node.isParserFunction) node.parameters.forEach { addAll(transcluded(it.value.nodes)) }
        }
    }

    private inline fun <reified T : Node> visible(nodes: List<Node>): List<T> =
        visibleNodes(nodes).filterIsInstance<T>()

    /** Every node outside a template's parameters, which MediaWiki only reaches by expanding the template. */
    @Suppress("CyclomaticComplexMethod") // One branch per node type.
    private fun visibleNodes(nodes: List<Node>): List<Node> = buildList {
        for (node in nodes) {
            add(node)
            when (node) {
                is Template -> Unit
                is Heading -> addAll(visibleNodes(node.title.nodes))
                is WikiLink -> {
                    addAll(visibleNodes(node.target.nodes))
                    node.text?.let { addAll(visibleNodes(it.nodes)) }
                }
                is ExternalLink -> {
                    addAll(visibleNodes(node.url.nodes))
                    node.title?.let { addAll(visibleNodes(it.nodes)) }
                }
                is Tag -> {
                    node.contents?.let { addAll(visibleNodes(it.nodes)) }
                    node.attributes.forEach { attribute ->
                        addAll(visibleNodes(attribute.name.nodes))
                        attribute.value?.let { addAll(visibleNodes(it.nodes)) }
                    }
                }
                is Argument -> {
                    addAll(visibleNodes(node.name.nodes))
                    node.default?.let { addAll(visibleNodes(it.nodes)) }
                }
                else -> Unit
            }
        }
    }

    private fun String.capitalizeFirst(): String =
        if (isEmpty()) this else this[0].uppercaseChar() + substring(1)

    private fun String.withoutWhitespace(): String = filterNot { it.isWhitespace() }

    private fun String.escaped(): String = replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private companion object {
        const val TEMPLATE_PREFIX = "Template:"

        val FILE_NAMESPACES = setOf("File", "Image")

        val TRANSCLUSION_TAGS = setOf("noinclude", "includeonly", "onlyinclude")

        val TRANSCLUSION_MARKER = Regex("</?(noinclude|onlyinclude)>")

        /**
         * Cases known to disagree, by check, each with its reason. Take a case off when it starts agreeing.
         *
         * Several come from the recording itself rather than from the parser: every template in the corpus is
         * one no wiki has, and MediaWiki expands one of those into a red link, `[[:Template:Zqx …]]`, which
         * is then a link where a real template would have been text.
         */
        val PENDING: Map<String, Set<String>> =
            mapOf(
                "parsetree" to
                    setOf(
                        // `<includeonly>` is read as the source of a template, where it wraps text to keep;
                        // MediaWiki viewing the page hides what is inside it.
                        "tag/includeonly"
                    ),
                "templates" to
                    setOf(
                        // en.wiktionary reports a `<ref>` it cannot place with a template of its own.
                        "tag/ext-nested",
                        "tag/ext-short",
                        // As for the parse tree.
                        "tag/includeonly",
                        // DynamicPageList expands its category with a parser of its own, so the page records
                        // no template there, though one is used.
                        "body/page-list",
                    ),
                "links" to
                    setOf(
                        // The template inside the target expands to a red link, so the target is no title.
                        "bracket/template-in-target"
                    ),
                "externallinks" to
                    setOf(
                        // MediaWiki leaves an external link in a link's text unlinked; the parser reads it.
                        "bracket/extlink-in-link-text"
                    ),
                "pairing" to
                    setOf(
                        // MediaWiki ends a `<b>` at its closer across a `<div>`'s start or end, which a tree
                        // cannot show without overlapping the div, so the `<b>` is left unpaired.
                        "pairing/bold-into-div",
                        "pairing/bold-out-of-div",
                        // `<span/>` opens a span MediaWiki closes at the next `</span>`; a self-closing tag
                        // here has no body to pair.
                        "pairing/self-closing-span",
                    ),
                "sections" to
                    setOf(
                        // tocdata leaves out a heading's text when it is empty, and the recording then skips
                        // the heading.
                        "heading/empty"
                    ),
            )
    }
}
