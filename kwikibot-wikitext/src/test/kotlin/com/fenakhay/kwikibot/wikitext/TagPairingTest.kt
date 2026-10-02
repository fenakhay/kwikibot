package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.ops.outline
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * HTML tags paired where MediaWiki ends the elements they open, each case as MediaWiki renders it.
 *
 * MediaWiki's paragraph pass ends a `<span>` at a paragraph break, a list, a heading or a table, while a
 * `<div>` stays open across all of them and a `<b>` is opened again after each, which reads as one element.
 * `MediaWikiAgreementTest` checks the recorded cases against MediaWiki's own HTML; these say what each rule
 * means for the tree.
 */
class TagPairingTest {

    private fun parse(text: String): Markup = Wikitext.parse(text).also { it.serialize() shouldBe text }

    /** Whether the first tag named [name] in [text] was paired with a closer. */
    private fun paired(text: String, name: String = "span"): Boolean =
        parse(text).tags(name).first().contents != null

    /** Whether some text node of [text] holds the closing tag `</name>`, left as text. */
    private fun closerIsText(text: String, name: String = "span"): Boolean =
        parse(text).allNodes().any { it is TextNode && "</$name>" in it.text }

    // ------------------------------------------------------------------ headings

    @Test
    fun `a div stays open around a heading, and the heading is still a section`() {
        val code = parse("<div>a\n== h ==\nb</div>")

        code.tags("div").single().contents?.serialize() shouldBe "a\n== h ==\nb"
        code.headings() shouldHaveSize 1
        code.outline().serialize() shouldBe code.serialize()
    }

    @Test
    fun `a span ends at a heading, and its closer is text`() {
        paired("<span>a\n== h ==\nb</span>") shouldBe false
        closerIsText("<span>a\n== h ==\nb</span>") shouldBe true
    }

    @Test
    fun `bold and small run on across a heading, as MediaWiki opens them again after it`() {
        paired("<b>a\n== h ==\nb</b>", "b") shouldBe true
        paired("<small>a\n== h ==\nb</small>", "small") shouldBe true
        parse("<b>a\n== h ==\nb</b>").headings() shouldHaveSize 1
    }

    @Test
    fun `center and blockquote stay open around a heading`() {
        paired("<center>a\n== h ==\nb</center>", "center") shouldBe true
        paired("<blockquote>a\n== h ==\nb</blockquote>", "blockquote") shouldBe true
    }

    @Test
    fun `a span inside a div holds a heading, since no paragraph is open there`() {
        paired("<div><span>a\n== h ==\nb</span>c</div>") shouldBe true
    }

    // ------------------------------------------------------------------ paragraphs and blocks

    @Test
    fun `a span ends at a paragraph break, a div and bold do not`() {
        paired("<span>a\n\nb</span>") shouldBe false
        paired("<div>a\n\nb</div>", "div") shouldBe true
        paired("<b>a\n\nb</b>", "b") shouldBe true
        paired("<span>a\nb</span>") shouldBe true
    }

    @Test
    fun `a span inside a div runs past a blank line, which opens no paragraph there`() {
        paired("<div><span>a\n\nb</span>c</div>") shouldBe true
    }

    @Test
    fun `a list, an indented line, a rule or a table ends a span`() {
        paired("<span>a\n* b</span>") shouldBe false
        paired("<span>a\n b</span>") shouldBe false
        paired("<span>a\n----\nb</span>") shouldBe false
        paired("<span>a\n{|\n|b</span>\n|}") shouldBe false
        paired("<span>a\n<references />\nb</span>") shouldBe false
    }

    @Test
    fun `a line that disappears or holds only a style sheet ends nothing, one left empty does`() {
        paired("<span>a\n<!-- c -->\nb</span>") shouldBe true
        paired("<span>a\n<templatestyles src=\"x.css\" />\nb</span>") shouldBe true
        paired("<span>a\n\n<templatestyles src=\"x.css\" />\nb</span>") shouldBe false
        paired("<span>a\n<indicator name=\"x\">y</indicator>\nb</span>") shouldBe false
    }

    @Test
    fun `a behaviour switch leaves its line blank, and the table of contents ends a paragraph`() {
        paired("<span>a\n__NOTOC__\nb</span>") shouldBe false
        paired("<span>a __NOTOC__ b</span>") shouldBe true
        paired("<span>a __TOC__ b</span>") shouldBe true
        paired("<span>a\nb __TOC__ c</span>") shouldBe false
        paired("<span>a __NOTASWITCH__ b</span>") shouldBe true
    }

    @Test
    fun `a file shown as a figure ends a span, one shown inline does not`() {
        paired("<span>a\n[[File:X.png|thumb|c]]\nb</span>") shouldBe false
        paired("<span>a\n[[File:X.png|left]]\nb</span>") shouldBe false
        paired("<span>a [[File:X.png|c]] b</span>") shouldBe true
        paired("<span>a [[X|thumb]] b</span>") shouldBe true
    }

    // ------------------------------------------------------------------ closers MediaWiki ignores

    @Test
    fun `a span's closer inside a div or a paragraph is ignored, as MediaWiki ignores it`() {
        paired("<span>a<div>b</span>c</div>") shouldBe false
        paired("<span>a<div>b</span>c</div>", "div") shouldBe true
        paired("<span>a<p>b</span>c</p>") shouldBe false
    }

    @Test
    fun `a bold closer inside a cell is ignored, and the bold goes on after the table`() {
        val code = parse("<b>a\n{|\n|b</b> c\n|}\nd</b> e")

        code.tags("b").single().contents?.serialize() shouldBe "a\n{|\n|b</b> c\n|}\nd"
    }

    @Test
    fun `bold crossing into or out of a div is left unpaired`() {
        paired("<b>a\n\n<div>b</b>c</div>", "b") shouldBe false
        paired("<div><b>a</div>b</b>", "b") shouldBe false
    }

    // ------------------------------------------------------------------ lists and tables

    @Test
    fun `a list item ends the span before it, and bold runs on through a list`() {
        paired("* <span>a\n* b</span>") shouldBe false
        paired("<ul><li><span>a<li>b</span></ul>") shouldBe false
        paired("<b>a\n* b\nc</b>", "b") shouldBe true
        paired("; <span>a : b</span> c\n: d") shouldBe true
    }

    @Test
    fun `a new cell ends a span from the cell before`() {
        paired("{|\n|<span>a\n|b</span>\n|}") shouldBe false
        paired("{|\n|<span>a||b</span>\n|}") shouldBe false
        paired("{|\n|<span>a</span>||b\n|}") shouldBe true
        paired("{|\n! <span>a!!b</span>\n|}") shouldBe false
        paired("<table><tr><td><span>a</td></tr></table>") shouldBe false
    }

    @Test
    fun `a cell's attributes are not its content`() {
        parse("{|\n| <span>x | <span>a</span>\n|}").tags("span").last().contents shouldBe Markup.of("a")
        paired("{|\n|-\n| a || b\n|}\n<span>c</span>") shouldBe true
        paired(":{|\n|<span>a</span>\n|}") shouldBe true
    }

    @Test
    fun `an item started on its own closes the item before it, and no other`() {
        paired("<ul><li>a<li/>b</ul>", "ul") shouldBe true
        paired("<dl><dt>a<dd>b</dl>", "dl") shouldBe true
        paired("<dl><dt><span>a<dd>b</span></dl>") shouldBe false
    }

    // ------------------------------------------------------------------ bodies and parameters

    @Test
    fun `a reference, a poem and a wikitext pre keep a span across a blank line`() {
        val ref = parse("x<ref><span>a\n\nb</span></ref>").tags("span").first()
        ref.contents shouldBe Markup.of("a\n\nb")
        paired("<poem><span>a\n\nb</span></poem>") shouldBe true
        paired("<pre format=\"wikitext\"><span>a\n\nb</span></pre>") shouldBe true
    }

    @Test
    fun `inside a reference, a block line lets paragraphs start again`() {
        paired("x<ref><div>a</div>\n<span>b\n\nc</span></ref>") shouldBe false
    }

    @Test
    fun `a gallery's lines are read one at a time`() {
        paired("<gallery>\nX.png|<span>a\nY.png|b</span>\n</gallery>") shouldBe false
        paired("<gallery>\nX.png|<span>a</span>\n</gallery>") shouldBe true
    }

    @Test
    fun `a template parameter is read as a page of its own`() {
        paired("{{#if:x|<span>a\n\nb</span>}}") shouldBe false
        paired("{{#if:x|<span>a\nb</span>}}") shouldBe true
    }

    @Test
    fun `a reference's first line and a wikitext pre start no list`() {
        parse("x<ref>* a</ref>").allNodes().none { it is Tag && it.wikiMarkup == "*" } shouldBe true
        parse("<pre format=\"wikitext\">\n* a</pre>").allNodes().none {
            it is Tag && it.wikiMarkup == "*"
        } shouldBe true
        parse("x<ref>a\n* b</ref>").allNodes().any { it is Tag && it.wikiMarkup == "*" } shouldBe true
    }

    // ------------------------------------------------------------------ odd tags

    @Test
    fun `a self-closing span opens nothing that pairs, and the closer after it is text`() {
        closerIsText("<span/>a</span>b") shouldBe true
    }

    @Test
    fun `a heading tag closes at any heading's closer, but pairs only with its own`() {
        paired("<h2>a</h2>", "h2") shouldBe true
        paired("<h2>a</h3>b", "h2") shouldBe false
        paired("<h2>a<h3>b</h3>", "h3") shouldBe true
    }

    @Test
    fun `nothing pairs across a transclusion wrapper`() {
        paired("<noinclude><span>a</noinclude>b</span>") shouldBe false
        paired("<noinclude>a</noinclude>", "noinclude") shouldBe true
        paired("<span>a<noinclude>b</span></noinclude>") shouldBe false
    }

    @Test
    fun `a void tag and a self-closing tag start no pairing pass of their own`() {
        parse("a<br>b</br>c").tags("br") shouldHaveSize 1
        parse("a<hr>b").tags("hr") shouldHaveSize 1
    }

    @Test
    fun `a wiki that allows fewer elements still pairs the ones it allows`() {
        val text = "<span>a</span>\n<blockquote>b</blockquote><hr><link>"
        val code = Wikitext.parse(text, ParseOptions(htmlTags = setOf("span")))

        code.serialize() shouldBe text
        code.tags("span").single().contents shouldBe Markup.of("a")
        code.tags("blockquote").shouldBeEmpty()
    }
}
