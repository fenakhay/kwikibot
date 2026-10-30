package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.Parameter
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MarkupTest {

    @Test
    fun `templates are found including nested ones`() {
        val code = Wikitext.parse("{{col|en|{{l|en|vog}}|[[volcano]]}}")

        code.templates().map { it.title } shouldBe listOf("col", "l")
    }

    @Test
    fun `every node is walked depth first through the wikitext each kind of node holds`() {
        val code =
            Wikitext.parse(
                "{{t|{{a}}|k={{b}}}}{{{p|{{c}}}}}[[d|{{e}}]][http://x.org {{f}}]\n== {{g}} ==\n" +
                    "<div title=\"{{h}}\">{{i}}</div>"
            )

        code.templates().map { it.title } shouldBe listOf("t", "a", "b", "c", "e", "f", "g", "i", "h")
        code.allNodes().filterIsInstance<TextNode>().joinToString("|") { it.text } shouldBe
            "t|1|a|k|b|p|c|d|e|http://x.org|f|\n| |g| |\n|i|title|h"
        code.allNodes().count() shouldBe 33
    }

    @Test
    fun `a walk reaches every parameter of a template with many of them`() {
        val code = Wikitext.parse((1..40).joinToString("|", "{{t|", "}}") { "{{p$it}}" })

        code.templates().map { it.title } shouldBe listOf("t") + (1..40).map { "p$it" }
    }

    @Test
    fun `a walk reaches the bottom of deeply nested templates`() {
        val depth = 1000
        var code = Markup.of("x")
        repeat(depth) {
            code =
                Markup(
                    listOf(Template(Markup.of("a"), listOf(Parameter(Markup.of("1"), code, showKey = false))))
                )
        }

        code.templates().size shouldBe depth
    }

    @Test
    fun `a walk can be repeated and ends where the tree does`() {
        val nodes = Wikitext.parse("{{a|[[b]]}} c").allNodes()

        nodes.count() shouldBe nodes.count()
        val walk = Markup.EMPTY.allNodes().iterator()
        walk.hasNext() shouldBe false
        assertFailsWith<NoSuchElementException> { walk.next() }
    }

    @Test
    fun `templates can be found by name`() {
        val code = Wikitext.parse("{{col|en}} {{l|en|x}} {{col|fr}}")

        code.templates("col").size shouldBe 2
    }

    @Test
    fun `a blank name finds no template`() {
        Wikitext.parse("{{col|en}}").templates(" ") shouldBe emptyList()
    }

    @Test
    fun `template names match the way MediaWiki matches them`() {
        Wikitext.parse("{{Col|en}}").templates("col").size shouldBe 1
        Wikitext.parse("{{col|en}}").templates("Col").size shouldBe 1
        Wikitext.parse("{{coL|en}}").templates("col").size shouldBe 0

        Wikitext.parse("{{col_top|en}}").templates("col top").size shouldBe 1
    }

    @Test
    fun `parameters are readable by name and by position`() {
        val template = Wikitext.parse("{{col|en|hypervolcano|title=Terms}}").templates().single()

        template.value("1") shouldBe "en"
        template.value("2") shouldBe "hypervolcano"
        template.value("title") shouldBe "Terms"
        template.value("nope").shouldBeNull()
        ("title" in template) shouldBe true
    }

    @Test
    fun `a named parameter does not shift the numbering of the positional ones`() {
        val template = Wikitext.parse("{{col|en|title=Terms|vog}}").templates().single()

        template.value("1") shouldBe "en"
        template.value("2") shouldBe "vog"
    }

    @Test
    fun `the last of a repeated parameter wins as MediaWiki does`() {
        val template = Wikitext.parse("{{col|title=First|title=Second}}").templates().single()

        template.value("title") shouldBe "Second"
    }

    @Test
    fun `removing a parameter leaves the others alone`() {
        val template = Wikitext.parse("{{col|en|title=Terms|vog}}").templates().single()

        template.withoutParameter("title").serialize() shouldBe "{{col|en|vog}}"
    }

    @Test
    fun `wikilinks report their target and display text`() {
        val links = Wikitext.parse("[[volcano]] and [[vog|volcanic smog]]").wikilinks()

        links.map { it.title } shouldBe listOf("volcano", "vog")
        links[1].text?.text shouldBe "volcanic smog"
    }

    @Test
    fun `headings report their level`() {
        val headings = Wikitext.parse("==English==\n===Noun===\n====Derived terms====").headings()

        headings.map { it.level } shouldBe listOf(2, 3, 4)
        headings.map { it.title.text } shouldBe listOf("English", "Noun", "Derived terms")
    }

    @Test
    fun `visible text drops the markup a reader never sees`() {
        val code = Wikitext.parse("A [[volcano|mountain]] {{qualifier|rare}}<!-- note --> erupts.")

        code.text shouldBe "A mountain  erupts."
    }

    @Test
    fun `visible text keeps what each kind of node shows a reader`() {
        Wikitext.parse("==Lava==").text shouldBe "Lava"
        Wikitext.parse("[[vog]] and [[vog|smog]]").text shouldBe "vog and smog"
        Wikitext.parse("[https://example.org the site]").text shouldBe "the site"
        Wikitext.parse("[https://example.org]").text shouldBe ""
        Wikitext.parse("<ref>a note</ref> and<br/> more").text shouldBe "a note and more"
        Wikitext.parse("{{{1|default}}}").text shouldBe ""
        Wikitext.parse("caf&eacute;").text shouldBe "caf&eacute;"
    }

    @Test
    fun `a bare URL in running text is text a reader sees`() {
        Wikitext.parse("see https://example.org now").text shouldBe "see https://example.org now"
        Wikitext.parse("{{cite|url=https://example.org/a}}").templates().single().value("url") shouldBe
            "https://example.org/a"
    }

    @Test
    fun `a node can be replaced anywhere in the tree`() {
        val code = Wikitext.parse("see [[volcano]] and [[vog]]")
        val target = code.wikilinks().first { it.title == "vog" }

        val updated = code.replace(target, WikiLink(Markup.of("volcanic smog")))

        updated.serialize() shouldBe "see [[volcano]] and [[volcanic smog]]"
    }

    @Test
    fun `a node can be removed from inside every kind of node that holds wikitext`() {
        val code =
            Wikitext.parse(
                "=={{x}} h==\n{{{a}}} {{{a|{{x}}}}} [[b]] [[{{x}}|c]] [https://example.org] " +
                    "[https://example.org {{x}}] <br/><span title=\"{{x}}\" hidden>{{x}}</span> {{t|{{x}}}}"
            )

        code.remove(code.templates("x").first()).serialize() shouldBe
            "== h==\n{{{a}}} {{{a|}}} [[b]] [[|c]] [https://example.org] " +
                "[https://example.org ] <br/><span title=\"\" hidden></span> {{t|}}"
    }

    @Test
    fun `removing a node that is not there changes nothing`() {
        val code = Wikitext.parse("[[a]] {{t|b}}")

        code.remove(TextNode("absent")) shouldBe code
        code.replace(TextNode("absent"), TextNode("x")).serialize() shouldBe "[[a]] {{t|b}}"
    }

    @Test
    fun `a top-level node is removed outright and the nodes around it stay as they were`() {
        val code = Wikitext.parse("a {{x}} <!-- c --> &amp;")

        code.remove(code.templates().single()).serialize() shouldBe "a  <!-- c --> &amp;"
    }

    @Test
    fun `every template can be passed through one transform`() {
        val code = Wikitext.parse("{{a|1}} [[{{b|2}}]]")

        code.mapTemplates { it.withParameter("1", "x") }.serialize() shouldBe "{{a|x}} [[{{b|x}}]]"
        code.mapTemplates { it } shouldBe code
    }

    @Test
    fun `markup is a value equal to markup with equal nodes and prints as its wikitext`() {
        val code = Wikitext.parse("a [[b]]")

        code shouldBe Markup(code.nodes)
        code.hashCode() shouldBe Markup(code.nodes).hashCode()
        code shouldNotBe Markup.of("a")
        code.equals("a [[b]]") shouldBe false
        code.toString() shouldBe "a [[b]]"
        (code + Wikitext.parse(" {{c}}")).serialize() shouldBe "a [[b]] {{c}}"
        Markup.EMPTY.serialize() shouldBe ""
    }

    @Test
    fun `a template name is read without the transclusion markers around part of it`() {
        Wikitext.parse("{{a<includeonly>b</includeonly>|x}}").templates().single().title shouldBe "ab"
        Wikitext.parse("{{a<noinclude/>|x}}").templates().single().title shouldBe "a"
        Wikitext.parse("{{a<span>b</span>|x}}").templates().single().title shouldBe "a<span>b</span>"
        Wikitext.parse("{{a'''b'''|x}}").templates().single().title shouldBe "a'''b'''"
    }

    @Test
    fun `a template nested inside a link can be edited`() {
        val code = Wikitext.parse("[[{{l|en|volcano}}]]")

        val updated = code.mapTemplates("l") { it.withParameter("2", "vog") }

        updated.serialize() shouldBe "[[{{l|en|vog}}]]"
    }

    @Test
    fun `an unclosed construct stays literal text as MediaWiki renders it`() {
        val code = Wikitext.parse("{{col|en and [[volcano")

        code.templates().isEmpty() shouldBe true
        code.serialize() shouldBe "{{col|en and [[volcano"
    }

    @Test
    fun `comments are readable and survive serialization`() {
        val code = Wikitext.parse("{{col|en<!-- keep sorted -->}}")

        code.comments().single().contents shouldBe " keep sorted "
        code.serialize() shouldBe "{{col|en<!-- keep sorted -->}}"
    }

    @Test
    fun `a value set on a template stays one parameter when the wiki reads it back`() {
        val template = Wikitext.parse("{{t|a|key=old}}").templates().single()

        val piped = template.withParameter("key", "[https://example.org/?a=b label|more]")
        val equals = template.withParameter("1", "x=y")
        val linked = template.withParameter("1", "[[page|text]] and {{tpl|z}}")

        piped.serialize() shouldBe "{{t|a|key=[https://example.org/?a=b label{{!}}more]}}"
        equals.serialize() shouldBe "{{t|1=x=y|key=old}}"
        linked.serialize() shouldBe "{{t|[[page|text]] and {{tpl|z}}|key=old}}"
        Wikitext.parse(linked.serialize()).templates().first().parameters.size shouldBe 2
    }

    @Test
    fun `a parameter the template lacks is added at the end`() {
        val template = Wikitext.parse("{{t|a|key=old}}").templates().single()

        template.withParameter("new", "v").serialize() shouldBe "{{t|a|key=old|new=v}}"
        template.withParameter("2", "v").serialize() shouldBe "{{t|a|key=old|v}}"
        ("new" in template) shouldBe false
    }

    @Test
    fun `a number past the next position is added by name so that it keeps its number`() {
        val template = Wikitext.parse("{{t|a|key=old}}").templates().single()

        val added = template.withParameter("3", "v")

        added.serialize() shouldBe "{{t|a|key=old|3=v}}"
        Wikitext.parse(added.serialize()).templates().single().value("3") shouldBe "v"
    }

    @Test
    fun `a value that was only whitespace is replaced whole`() {
        val template = Wikitext.parse("{{t|key= }}").templates().single()

        template.withParameter("key", "x").serialize() shouldBe "{{t|key=x}}"
    }

    @Test
    fun `a pipe inside an HTML tag is escaped and an attribute makes the value a named one`() {
        val template = Wikitext.parse("{{t|a}}").templates().single()

        template.withParameter("1", "<span class=\"x\">a|b</span>").serialize() shouldBe
            "{{t|1=<span class=\"x\">a{{!}}b</span>}}"
        template
            .withParameter("2", "[https://example.org] a|b <br/> <span hidden>c</span>")
            .serialize() shouldBe "{{t|a|[https://example.org] a{{!}}b <br/> <span hidden>c</span>}}"
    }

    @Test
    fun `an extension tag holds its pipes and equals signs itself`() {
        val template = Wikitext.parse("{{t|a}}").templates().single()

        template.withParameter("2", "<ref name=x>a|b=c</ref>").serialize() shouldBe
            "{{t|a|<ref name=x>a|b=c</ref>}}"
    }

    @Test
    fun `a closing tag is written as it was only while it still names the tag`() {
        val tag = Tag("ref", contents = Markup.of("a"), closing = "</REF >")

        tag.serialize() shouldBe "<ref>a</REF >"
        tag.copy(name = "note").serialize() shouldBe "<note>a</note>"
        tag.copy(closing = "</refs>").serialize() shouldBe "<ref>a</ref>"
        tag.copy(closing = "</r>").serialize() shouldBe "<ref>a</ref>"
        tag.copy(closing = "<ref >").serialize() shouldBe "<ref>a</ref>"
    }

    @Test
    fun `a value that would close the template it goes into is refused`() {
        val template = Wikitext.parse("{{t|a}}").templates().single()

        listOf("x}}y", "{{open", "[[unclosed").forEach { value ->
            assertFailsWith<IllegalArgumentException> { template.withParameter("1", value) }
        }
    }

    @Test
    fun `tags report their name and contents`() {
        val code = Wikitext.parse("""<ref name="a">see [[volcano]]</ref>""")

        val tag = code.tags("ref").single()
        tag.attributes.single().name.text shouldBe "name"
        tag.contents?.wikilinks()?.single()?.title shouldBe "volcano"
    }

    @Test
    fun `bold markup is a tag that writes itself back as markup`() {
        val code = Wikitext.parse("'''bold''' text")

        code.tags("b").single().contents?.text shouldBe "bold"
        code.serialize() shouldBe "'''bold''' text"
    }
}
