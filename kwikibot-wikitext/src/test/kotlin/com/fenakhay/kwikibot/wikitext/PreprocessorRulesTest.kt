package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink
import com.fenakhay.kwikibot.wikitext.ops.replaceText
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * The rules of MediaWiki's preprocessor, stated as what each means for a bot reading the tree.
 *
 * [MediaWikiAgreementTest] checks the same rules against MediaWiki itself.
 */
class PreprocessorRulesTest {

    private fun parse(text: String): Markup = Wikitext.parse(text).also { it.serialize() shouldBe text }

    // ------------------------------------------------------------------ brackets

    @Test
    fun `a single bracket in a parameter does not hide the parameters after it`() {
        val template = parse("{{t|vn:كتابة[rare]|b}}").templates().single()

        template.parameters.map { it.rawValue } shouldBe listOf("vn:كتابة[rare]", "b")
    }

    @Test
    fun `three opening brackets are no link, and hide nothing after them`() {
        val code = parse("[[[Dinar]]] {{quote-journal|en}}")

        code.wikilinks().shouldBeEmpty()
        code.templates().single().title shouldBe "quote-journal"
    }

    @Test
    fun `a link left open hides the braces after it, as MediaWiki reads it`() {
        parse("{{t|[[x}}").templates().shouldBeEmpty()
    }

    @Test
    fun `a link is a link only by MediaWiki's rules`() {
        parse("[[a\nb]]").wikilinks().shouldBeEmpty()
        parse("[[a|]]").wikilinks().shouldBeEmpty()
        parse("[[ ]]").wikilinks().shouldBeEmpty()
        parse("[[a<b]]").wikilinks().shouldBeEmpty()
        parse("[[https://example.org]]").wikilinks().shouldBeEmpty()
        parse("[[//example.org/x]]").wikilinks().shouldBeEmpty()
        parse("[[a|b\nc]]").wikilinks().single().title shouldBe "a"
        parse("[[a|b]c]]").wikilinks().single().text?.serialize() shouldBe "b]c"
    }

    @Test
    fun `a link with a link in its text is not one, unless it is a file`() {
        parse("[[a|x [[b]] y]]").wikilinks().map { it.title } shouldBe listOf("b")
        parse("[[File:a.png|thumb|x [[b]] y]]").wikilinks().map { it.title } shouldBe
            listOf("File:a.png", "b")
    }

    @Test
    fun `an external link in a link's text takes the third bracket`() {
        val link = parse("[[a|[http://example.org x]]]").nodes.single().shouldBeInstanceOf<WikiLink>()

        link.text!!.nodes.single().shouldBeInstanceOf<ExternalLink>().url.serialize() shouldBe
            "http://example.org"
    }

    // ------------------------------------------------------------------ separators

    @Test
    fun `an equals sign in an external link names the parameter, as MediaWiki reads it`() {
        val template = parse("{{etyl|[https://example.org/?view=theater x]}}").templates().single()

        template.parameters.single().showKey shouldBe true
        template.parameters.single().key shouldBe "[https://example.org/?view"
    }

    @Test
    fun `a pipe in an HTML tag or in bold splits the template`() {
        parse("{{t|<span>a|b</span>}}").templates().single().parameters shouldHaveSize 2
        parse("{{t|'''a|b'''}}").templates().single().parameters shouldHaveSize 2
    }

    @Test
    fun `a pipe in a link, a language conversion, a comment or an extension tag does not`() {
        parse("{{t|[[a|b]]}}").templates().single().parameters shouldHaveSize 1
        parse("{{t|-{a|b}-}}").templates().single().parameters shouldHaveSize 1
        parse("{{t|<!-- a|b -->}}").templates().single().parameters shouldHaveSize 1
        parse("{{t|<nowiki>|</nowiki>}}").templates().single().parameters shouldHaveSize 1
    }

    @Test
    fun `without language conversion a pipe in -{ splits the template`() {
        val code = Wikitext.parse("{{t|-{a|b}-}}", ParseOptions(languageConversion = false))

        code.templates().single().parameters shouldHaveSize 2
    }

    // ------------------------------------------------------------------ headings

    @Test
    fun `a heading's text runs to its last run of equals signs`() {
        parse("== a = b ==").headings().single().title.serialize() shouldBe " a = b "
    }

    @Test
    fun `surplus equals signs on either side are text`() {
        val heading = parse("===a==").headings().single()

        heading.level shouldBe 2
        heading.title.serialize() shouldBe "=a"
    }

    @Test
    fun `spaces and comments after a heading leave it a heading, outside it`() {
        val code = parse("== a == <!-- c -->\nb")

        code.headings().single().title.serialize() shouldBe " a "
        code.nodes[1] shouldBe TextNode(" ")
        code.nodes[2] shouldBe Comment(" c ")
    }

    @Test
    fun `text after a heading's closing run makes it no heading`() {
        parse("== a == b").headings().shouldBeEmpty()
    }

    @Test
    fun `a heading inside a template is a heading but not a section`() {
        val code = parse("{{t|\n== a ==\n}}")

        code.headings().shouldBeEmpty()
        code.allNodes().filterIsInstance<Heading>().single().level shouldBe 2
    }

    @Test
    fun `a heading inside a div is a section heading`() {
        parse("<div>\n== a ==\n</div>").headings() shouldHaveSize 1
    }

    // ------------------------------------------------------------------ tags

    @Test
    fun `a closing tag is kept as written`() {
        val tag = parse("<i>x</i >").tags().single()

        tag.closing shouldBe "</i >"
        tag.serialize() shouldBe "<i>x</i >"
        tag.copy(name = "b").serialize() shouldBe "<b>x</b>"
    }

    @Test
    fun `only the tags a wiki allows are tags`() {
        val code = parse("<mcRmJpmif='''{{PAGENAME}}'''")

        code.tags("mcRmJpmif").shouldBeEmpty()
        code.templates().single().title shouldBe "PAGENAME"
        parse("<img src=x>").tags().shouldBeEmpty()
    }

    @Test
    fun `an HTML tag nothing closes stands alone`() {
        val tag = parse("<b>a").tags().single()

        tag.selfClosing shouldBe true
        tag.implicitClose shouldBe true
    }

    @Test
    fun `a closing tag nothing opened is text`() {
        parse("a</b>").nodes shouldBe listOf(TextNode("a</b>"))
    }

    @Test
    fun `tags pair as a browser pairs them`() {
        val div = parse("<div>a<span>b</div>c</span>").nodes.first().shouldBeInstanceOf<Tag>()

        div.name shouldBe "div"
        div.contents!!.nodes[1].shouldBeInstanceOf<Tag>().implicitClose shouldBe true
    }

    @Test
    fun `a template among a tag's attributes is a template`() {
        val tag = parse("<span title=\"{{t}}\">x</span>").tags().single()

        tag.attributes.single().value!!.nodes.single().shouldBeInstanceOf<Template>()
        parse("<span title=\"{{t}}\">x</span>").templates() shouldHaveSize 1
    }

    @Test
    fun `an extension tag's attributes are raw and end at the first angle bracket`() {
        val ref = parse("<ref name=\"a>b\">x</ref>").tags().single()

        ref.attributes.single().serialize() shouldBe " name=\"a"
        parse("<ref name=\"{{t}}\">x</ref>").templates().shouldBeEmpty()
    }

    @Test
    fun `a bare attribute keeps the space after it`() {
        parse("<div nowrap >x</div>").tags().single().padding shouldBe " "
    }

    @Test
    fun `an extension tag's body is parsed only when it is wikitext`() {
        parse("<ref>{{t}}</ref>").templates() shouldHaveSize 1
        parse("<math>{{t}}</math>").templates().shouldBeEmpty()
        parse("<math>{{t}}</math>").tags().single().verbatim shouldBe true
        parse("<templatedata>{\"a\":\"{{t}}\"}</templatedata>").templates().shouldBeEmpty()
    }

    @Test
    fun `a pre written with format wikitext is read as wikitext, as MediaWiki reads it`() {
        val pre = parse("<pre format=\"wikitext\">{{t}} [[a]]</pre>")
        pre.templates() shouldHaveSize 1
        pre.wikilinks() shouldHaveSize 1
        pre.tags().single().verbatim shouldBe false
        parse("<pre FORMAT=\" wikitext \">{{t}}</pre>").templates() shouldHaveSize 1
        parse("<pre format=\"wiki&#116;ext\">{{t}}</pre>").templates() shouldHaveSize 1
        parse("<pre format=wikitext>{{t}}</pre>").templates() shouldHaveSize 1
        parse("<pre format=\"WikiText\">{{t}}</pre>").templates().shouldBeEmpty()
        parse("<pre>{{t}}</pre>").templates().shouldBeEmpty()
        parse("<pre format=\"html\" format=\"wikitext\">{{t}}</pre>").templates() shouldHaveSize 1
        parse("<pre format=\"wikitext\" format>{{t}}</pre>").templates().shouldBeEmpty()

        parse("<pre format=\"wikitext\">x</pre>").replaceText(Regex("x"), "y").serialize() shouldBe
            "<pre format=\"wikitext\">y</pre>"
        parse("<pre>x</pre>").replaceText(Regex("x"), "y").serialize() shouldBe "<pre>x</pre>"
    }

    @Test
    fun `an inputbox expands templates in its settings and nothing else`() {
        val box = parse("<inputbox>type=search\ndefault={{t}} [[a]] '''b'''</inputbox>")

        box.templates() shouldHaveSize 1
        box.wikilinks().shouldBeEmpty()
        box.tags().single().verbatim shouldBe false
        box.replaceText(Regex("search"), "x").serialize() shouldBe box.serialize()
    }

    @Test
    fun `a DynamicPageList expands only its category and caption values`() {
        val list =
            parse(
                "<DynamicPageList>\ncategory = {{a}}\ncount={{b}}\nnotcategory={{c}}\n" +
                    "Category={{d}}\n</DynamicPageList>"
            )

        list.templates().map { it.title } shouldBe listOf("a", "c")
        parse("<DynamicPageList>gallerycaption={{a}}</DynamicPageList>").templates() shouldHaveSize 1
        parse("<DynamicPageList>{{a}}</DynamicPageList>").templates().shouldBeEmpty()
    }

    @Test
    fun `an extension tag closes at the first closing tag of its name`() {
        parse("<pre>a</prex>b</pre>").tags().single().contents!!.serialize() shouldBe "a</prex>b"
        parse("<nowiki>a</nowiki>b</nowiki>").nodes.last() shouldBe TextNode("b</nowiki>")
    }

    @Test
    fun `a wiki's own extension tags are its own`() {
        val options = ParseOptions(extensionTags = setOf("score"), parsedTags = emptySet())

        Wikitext.parse("<ref>{{t}}</ref>", options).templates() shouldHaveSize 1
        Wikitext.parse("<ref>{{t}}</ref>", options).tags().shouldBeEmpty()
    }

    @Test
    fun `transclusion tags wrap what is inside them and split nothing`() {
        parse("<noinclude>{{doc}}</noinclude>").templates().single().title shouldBe "doc"
        parse("<includeonly>{{a}}</includeonly>").templates().single().title shouldBe "a"
        val template = parse("{{a<noinclude>|b</noinclude>}}").templates().single()
        template.title shouldBe "a"
        template.parameters shouldHaveSize 1
    }

    // ------------------------------------------------------------------ comments and links

    @Test
    fun `an unclosed comment runs to the end and hides what is in it`() {
        val code = parse("a <!-- b {{t}}")

        code.templates().shouldBeEmpty()
        code.comments().single().closed shouldBe false
    }

    @Test
    fun `an external link keeps the space it was written with`() {
        val link = parse("[https://example.org/x　label]").nodes.single().shouldBeInstanceOf<ExternalLink>()

        link.separator shouldBe "　"
        link.title!!.serialize() shouldBe "label"
        parse("[https://example.org/x\"q\"]")
            .nodes
            .single()
            .shouldBeInstanceOf<ExternalLink>()
            .separator shouldBe ""
    }

    @Test
    fun `a bare URL stops where MediaWiki stops it`() {
        fun url(text: String) =
            parse(text).allNodes().filterIsInstance<ExternalLink>().single().url.serialize()

        url("==https://example.org/x==") shouldBe "https://example.org/x"
        url("''https://example.org/x'' z") shouldBe "https://example.org/x"
        url("see https://example.org/x&lt;y") shouldBe "https://example.org/x"
        url("(https://example.org/x)") shouldBe "https://example.org/x"
        url("https://example.org/x(y)") shouldBe "https://example.org/x(y)"
        url("https://example.org/x&amp;") shouldBe "https://example.org/x&amp;"
        url("https://example.org/x&#x7B;") shouldBe "https://example.org/x&#x7B;"
        url("https://example.org/x&frac12; z") shouldBe "https://example.org/x&frac12"
        url("join matrix:#room:example.org now") shouldBe "matrix:#room:example.org"
        parse("see //example.org/x").allNodes().filterIsInstance<ExternalLink>().toList().shouldBeEmpty()
        parse("xhttps://example.org").allNodes().filterIsInstance<ExternalLink>().toList().shouldBeEmpty()
    }

    @Test
    fun `italics close at the same level, past a template that holds apostrophes`() {
        val italic = parse("''a {{t|''}} b''").nodes.single().shouldBeInstanceOf<Tag>()

        italic.wikiMarkup shouldBe "''"
        italic.contents!!.templates() shouldHaveSize 1
    }
}
