package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.Wikitext
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ScanTest {

    private fun String.construct(at: Int): String? {
        val end = Scan.of(this).closerOf(at)
        return if (end == Scan.UNMATCHED) null else substring(at, end)
    }

    @Test
    fun `a template closes at its own braces`() {
        "{{a}}".construct(0) shouldBe "{{a}}"
        "x {{a|b}} y".construct(2) shouldBe "{{a|b}}"
        "{{a}}{{b}}".construct(0) shouldBe "{{a}}"
        "{{a}}{{b}}".construct(5) shouldBe "{{b}}"
    }

    @Test
    fun `a nested template closes before the one containing it`() {
        val text = "{{out|{{in}}}}"
        text.construct(0) shouldBe "{{out|{{in}}}}"
        text.construct(6) shouldBe "{{in}}"
    }

    @Test
    fun `three braces are an argument and take precedence over two`() {
        "{{{1}}}".construct(0) shouldBe "{{{1}}}"
        "{{{1}}}".let { Scan.of(it).braceWidth(0) } shouldBe 3
        "{{a}}".let { Scan.of(it).braceWidth(0) } shouldBe 2
    }

    @Test
    fun `a brace run is spent from the right`() {
        val text = "{{{{{|safesubst:}}}x}}"
        text.construct(0) shouldBe text
        text.construct(2) shouldBe "{{{|safesubst:}}}"
        Scan.of(text).braceWidth(0) shouldBe 2
        Scan.of(text).braceWidth(2) shouldBe 3
    }

    @Test
    fun `four braces either side are an argument with a brace on each side of it`() {
        val text = "{{{{Zqx template}}}}"
        text.construct(0) shouldBe null
        text.construct(1) shouldBe "{{{Zqx template}}}"
    }

    @Test
    fun `an opening that never closes matches nothing`() {
        "{{a".construct(0) shouldBe null
        "[[a".construct(0) shouldBe null
        "{{{1".construct(0) shouldBe null
        "a <ref>never ends".construct(2) shouldBe null
        "a <!-- never ends".construct(2) shouldBe null
    }

    @Test
    fun `a wikilink closes at its own brackets`() {
        "[[a]]".construct(0) shouldBe "[[a]]"
        "[[a|b]] c".construct(0) shouldBe "[[a|b]]"
        "[[a]][[b]]".construct(5) shouldBe "[[b]]"
    }

    @Test
    fun `a closing bracket does not reach past a construct opened after it`() {
        val text = "[[a{{b]]"
        text.construct(0) shouldBe null
    }

    @Test
    fun `a comment closes at its marker and hides what is inside`() {
        "a <!-- x --> b".construct(2) shouldBe "<!-- x -->"
        "a <!-- {{b}} --> c".construct(7) shouldBe null
    }

    @Test
    fun `a raw tag hides what is inside it`() {
        val text = "<nowiki>{{a}}</nowiki>"
        text.construct(0) shouldBe text
        text.construct(8) shouldBe null
    }

    @Test
    fun `a tag closes at its own closing tag`() {
        "x<ref>a</ref>".construct(1) shouldBe "<ref>a</ref>"
        "<div><span>a</span></div>".construct(0) shouldBe "<div><span>a</span></div>"
        "<div><span>a</span></div>".construct(5) shouldBe "<span>a</span>"
    }

    @Test
    fun `a tag that closes itself is matched where it stands`() {
        "a<br>b".construct(1) shouldBe "<br>"
        "a<br />b".construct(1) shouldBe "<br />"
        "x<ref name=a />y".construct(1) shouldBe "<ref name=a />"
    }

    @Test
    fun `a quoted attribute may contain the character that would end the tag`() {
        "<span title=\"a > b\">x</span>".construct(0) shouldBe "<span title=\"a > b\">x</span>"
    }

    @Test
    fun `a tag opening that never reaches its bracket is not a tag`() {
        "a <ref name=".construct(2) shouldBe null
    }

    @Test
    fun `an inner tag left open does not steal the outer one's closer`() {
        val text = "<div><span>a</div>"
        text.construct(0) shouldBe text
        text.construct(5) shouldBe null
    }

    @Test
    fun `text with no markup matches nothing anywhere`() {
        val text = "just some words, café and 日本語"
        val scan = Scan.of(text)
        text.indices.forEach { scan.closes(it) shouldBe false }
    }

    @Test
    fun `a page far larger than any in the corpus still round-trips`() {
        val text = buildString { while (length < 300_000) append(MIXED) }
        Wikitext.parse(text).serialize() shouldBe text
    }

    private companion object {
        val MIXED =
            """
            == Heading ==
            Some prose with [[a link]] and {{a template|k=v}} in it.
            <!-- a comment with {{ braces }} inside -->
            <nowiki>{{not a template}}</nowiki> and <t:unclosed> and {{{{{|x}}}y}}
            * '''bold''' and https://example.org/x and &amp; and <ref>note</ref>
            """
                .trimIndent() + "\n"
    }
}
