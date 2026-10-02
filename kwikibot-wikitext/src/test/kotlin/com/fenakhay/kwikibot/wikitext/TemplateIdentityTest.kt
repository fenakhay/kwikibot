package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Which template a `{{…}}` is, what it was written as, and where it sits. */
class TemplateIdentityTest {

    private fun template(text: String): Template = Wikitext.parse(text).templates().first()

    @Test
    fun `a name keeps a nested template and drops comments`() {
        val template = template("{{ {{lang}}-noun <!-- x --> |a}}")

        template.title shouldBe "{{lang}}-noun"
        template.rawName shouldBe " {{lang}}-noun <!-- x --> "
    }

    @Test
    fun `a parameter has its key and its value as written`() {
        val template = template("{{t| <!--c--> a = {{b}} |c}}")
        val named = template.parameters[0]

        named.key shouldBe "a"
        named.rawKey shouldBe " <!--c--> a "
        named.rawValue shouldBe " {{b}} "
        template.rawValue("a") shouldBe " {{b}} "
        template.value("a") shouldBe ""
        template.parameters[1].rawKey shouldBe null
        template.rawValue("1") shouldBe "c"
        template.rawValue("missing") shouldBe null
    }

    @Test
    fun `the key is the page transcluded, normalised as MediaWiki normalises it`() {
        template("{{foo bar}}").key shouldBe "Foo bar"
        template("{{Template:foo_bar}}").key shouldBe "Foo bar"
        template("{{ template : foo   bar }}").key shouldBe "Foo bar"
        template("{{subst:foo}}").key shouldBe "Foo"
        template("{{safesubst:msgnw:foo}}").key shouldBe "Foo"
        template("{{:foo}}").key shouldBe ":Foo"
        template("{{User:x/y}}").key shouldBe "User:X/y"
        template("{{Image:x}}").key shouldBe "File:X"
        template("{{foo#bar}}").key shouldBe "Foo"
    }

    @Test
    fun `parser functions and variables are no page`() {
        template("{{#if:a|b}}").isParserFunction shouldBe true
        template("{{#if:a|b}}").key shouldBe null
        template("{{lc:ABC}}").isParserFunction shouldBe true
        template("{{PAGENAME}}").key shouldBe null
        template("{{PAGENAME:x}}").key shouldBe null
        template("{{!}}").key shouldBe null
        template("{{subst:#if:a|b}}").key shouldBe null
        template("{{pagename}}").isParserFunction shouldBe false
        template("{{ {{a}} }}").key shouldBe null
        template("{{<!-- -->}}").key shouldBe null
    }

    @Test
    fun `each magic word keeps or ignores case as MediaWiki's does`() {
        template("{{DEFAULTSORT:x}}").isParserFunction shouldBe true
        template("{{DEFAULTSORTKEY:x}}").isParserFunction shouldBe true
        template("{{defaultsort:x}}").key shouldBe "Defaultsort:x"
        template("{{FormatNum:1}}").isParserFunction shouldBe true
        template("{{server}}").isParserFunction shouldBe true
        template("{{USERLANGUAGE}}").isParserFunction shouldBe true
        template("{{#language:en}}").isParserFunction shouldBe true
        template("{{formal:x}}").key shouldBe "Formal:x"
        template("{{CURRENTYEAR:x}}").key shouldBe "CURRENTYEAR:x"
        template("{{lc :x}}").key shouldBe "Lc :x"
    }

    @Test
    fun `a variable's name with arguments transcludes a page, as MediaWiki reads it`() {
        template("{{PAGENAME|x}}").key shouldBe "PAGENAME"
        template("{{PAGENAME|}}").key shouldBe "PAGENAME"
        template("{{PAGENAME}}").key shouldBe null
        template("{{subst:PAGENAME}}").key shouldBe null
        template("{{msg:PAGENAME}}").key shouldBe "PAGENAME"
        template("{{msg:lc:x}}").key shouldBe null
        template("{{subst :x}}").key shouldBe "Subst :x"
        template("{{lc\uFF1Ax}}").isParserFunction shouldBe true
        Wikitext.parse("{{PAGENAME}}{{PAGENAME|x}}").templates(listOf("PAGENAME")) shouldHaveSize 1
    }

    @Test
    fun `every kind of space MediaWiki folds in a title is folded`() {
        template("{{foo\u00A0\u3000bar}}").key shouldBe "Foo bar"
    }

    @Test
    fun `a wiki whose templates keep case keeps it in the key`() {
        val wiktionary = TitleRules(caseSensitive = setOf(10))

        template("{{l|en|x}}").key(wiktionary) shouldBe "l"
        template("{{L|en|x}}").key(wiktionary) shouldBe "L"
        template("{{Template:l}}").key(wiktionary) shouldBe "l"
    }

    @Test
    fun `a wiki's own namespace names resolve`() {
        val spanish = TitleRules(namespaces = TitleRules.CANONICAL_NAMESPACES + ("Plantilla" to 10))

        template("{{Plantilla:Ficha}}").key(spanish) shouldBe "Ficha"
        template("{{Plantilla:Ficha}}").key shouldBe "Plantilla:Ficha"
        TitleRules(canonicalNames = emptyMap()).key("User:x") shouldBe "2:X"
    }

    @Test
    fun `templates are found by any name that resolves to them`() {
        val code = Wikitext.parse("{{l|a}} {{Template:L|b}} {{subst:l|c}} {{link|d}} {{L|e}}")

        code.templates(listOf("l", "link")) shouldHaveSize 5
        code.templates(listOf("l"), TitleRules(caseSensitive = setOf(10))) shouldHaveSize 2
        val renamed = code.mapTemplates(listOf("Template:link")) { it.copy(name = Markup.of("l")) }
        renamed.serialize() shouldBe "{{l|a}} {{Template:L|b}} {{subst:l|c}} {{l|d}} {{L|e}}"
    }

    @Test
    fun `ranges say where each piece of a page sits`() {
        val text = "a {{t|x=1}} <b class=\"c\">d</b> [[e]]"
        val code = Wikitext.parse(text)
        val ranges = code.ranges()

        val template = code.templates().single()
        text.substring(ranges[template]!!) shouldBe "{{t|x=1}}"
        text.substring(ranges[template.parameters.single()]!!) shouldBe "x=1"
        text.substring(ranges[template.parameters.single().value]!!) shouldBe "1"

        val tag = code.tags().single()
        text.substring(ranges[tag.attributes.single()]!!) shouldBe " class=\"c\""
        text.substring(ranges[code.wikilinks().single()]!!) shouldBe "[[e]]"
        text.substring(ranges[code]!!) shouldBe text

        ranges[TextNode("a ")] shouldBe null
    }

    @Test
    fun `an exact parse returns the tree of a page that writes back as it was`() {
        val text = "{{t|[[a|b]]}} <i>x</i > <!-- open"

        Wikitext.parseExact(text).serialize() shouldBe text
        Wikitext.roundTrips(text) shouldBe true
        Wikitext.parseExact(text, ParseOptions.DEFAULT).comments().single() shouldBe
            Comment(" open", closed = false)
    }

    @Test
    fun `the round-trip failure names the offset that would change`() {
        val error = assertFailsWith<WikitextRoundTripException> { throw WikitextRoundTripException(3, "x") }

        error.offset shouldBe 3
    }

    @Test
    fun `nodes built by hand write themselves as written`() {
        Template(Markup.of("t")).serialize() shouldBe "{{t}}"
        Comment("x", closed = false).serialize() shouldBe "<!--x"
        Wikitext.parse("[http://example.org  x]").nodes.single().serialize() shouldBe
            "[http://example.org  x]"
        Wikitext.parse("<ref name=a/>").tags().single().serialize() shouldBe "<ref name=a/>"
        Wikitext.parse("{{{1|a}}}").nodes.single().serialize() shouldBe "{{{1|a}}}"
        Wikitext.parse("== a ==").headings().single().serialize() shouldBe "== a =="
        Wikitext.parse("&#x41;").nodes.single().serialize() shouldBe "&#x41;"
        Wikitext.parse("{{t|a=b}}").templates().single().parameters.single().serialize() shouldBe "a=b"
        Wikitext.parse("<b x=y>z</b>").tags().single().attributes.single().serialize() shouldBe " x=y"
        Wikitext.parse("[[a|b]]")
            .nodes
            .single()
            .shouldBeInstanceOf<com.fenakhay.kwikibot.wikitext.node.WikiLink>()
            .serialize() shouldBe "[[a|b]]"
    }
}
