package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.ParseOptions
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PreprocessorTest {

    private fun preprocess(text: String, options: ParseOptions = ParseOptions.DEFAULT): Preprocessor =
        Preprocessor(text, options).apply { run(0, text.length) }

    /** What the preprocessor found at [at] in this text, as the text it covers, or `null`. */
    private fun String.found(at: Int, options: ParseOptions = ParseOptions.DEFAULT): String? {
        val pp = preprocess(this, options)
        return if (pp.kinds[at] == 0.toByte()) null else substring(at, pp.end(at))
    }

    private fun String.kind(at: Int): Byte = preprocess(this).kinds[at]

    @Test
    fun `braces close from the inside out`() {
        "{{a|{{b}}}}".found(0) shouldBe "{{a|{{b}}}}"
        "{{a|{{b}}}}".found(4) shouldBe "{{b}}"
        "{{{1}}}".kind(0) shouldBe Preprocessor.ARGUMENT
        "{{a}}".kind(0) shouldBe Preprocessor.TEMPLATE
    }

    @Test
    fun `a long brace run is spent from the right`() {
        val text = "{{{{{|safesubst:}}}x}}"

        text.found(0) shouldBe text
        text.found(2) shouldBe "{{{|safesubst:}}}"
        text.kind(2) shouldBe Preprocessor.ARGUMENT
        "{{{{{x}}}".found(2) shouldBe "{{{x}}}"
        "{{{{{x}}}".found(0) shouldBe null
    }

    @Test
    fun `a template's separators are where the preprocessor split it`() {
        val text = "{{a|b=c|d|[[e|f]]|g}}"
        val pp = preprocess(text)
        val separators = pp.separatorsOf(0)

        pp.partCount(separators) shouldBe 4
        (0 until 4).map { text[pp.pipe(separators, it)] } shouldBe List(4) { '|' }
        pp.equalsSign(separators, 0) shouldBe text.indexOf('=')
        pp.equalsSign(separators, 1) shouldBe -1
        preprocess("{{a}}").separatorsOf(0) shouldBe -1
    }

    @Test
    fun `only the first equals sign of a part counts`() {
        val text = "{{a|b=c=d}}"
        val pp = preprocess(text)

        pp.equalsSign(pp.separatorsOf(0), 0) shouldBe 5
    }

    @Test
    fun `a single bracket is never pushed, a double one is`() {
        "{{a|[b}}".found(0) shouldBe "{{a|[b}}"
        "[[a]]".kind(0) shouldBe Preprocessor.LINK
        "{{a|[[b}}".found(0) shouldBe null
    }

    @Test
    fun `a conversion hides its pipes from the template around it`() {
        val pp = preprocess("{{a|-{b|c}-}}")
        pp.partCount(pp.separatorsOf(0)) shouldBe 1

        val off = preprocess("{{a|-{b|c}-}}", ParseOptions(languageConversion = false))
        off.partCount(off.separatorsOf(0)) shouldBe 2
        "-{{a}}".found(1) shouldBe "{{a}}"
    }

    @Test
    fun `a heading runs to the last run of equals signs on its line`() {
        "== a = b ==".found(0) shouldBe "== a = b =="
        "== a == <!-- c -->".found(0) shouldBe "== a =="
        "== a ==  ".found(0) shouldBe "== a =="
        "== a == x".found(0) shouldBe null
        "x\n== a ==".found(2) shouldBe "== a =="
    }

    @Test
    fun `a heading's level is the shorter run, at most six`() {
        "===a==".kind(0) shouldBe (Preprocessor.HEADING + 2).toByte()
        "=======a=======".kind(0) shouldBe (Preprocessor.HEADING + 6).toByte()
        "=====".kind(0) shouldBe (Preprocessor.HEADING + 2).toByte()
        "===".kind(0) shouldBe (Preprocessor.HEADING + 1).toByte()
        "==".kind(0) shouldBe 0.toByte()
    }

    @Test
    fun `a heading left open on a line swallows the braces that close on it`() {
        "{{a|b\n== c }}".found(0) shouldBe null
    }

    @Test
    fun `a lone equals sign at a line start in a template names a parameter`() {
        val text = "{{a\n|b\n=c}}"
        val pp = preprocess(text)

        pp.equalsSign(pp.separatorsOf(0), 0) shouldBe text.lastIndexOf('=')
    }

    @Test
    fun `a comment on a line of its own lets a heading start after it`() {
        val text = "a\n<!-- c -->\n== b =="
        text.found(text.indexOf("==")) shouldBe "== b =="
        text.found(2) shouldBe "<!-- c -->"
    }

    @Test
    fun `an unclosed comment runs to the end`() {
        "a <!-- b".found(2) shouldBe "<!-- b"
        "a <!-- b".kind(2) shouldBe Preprocessor.COMMENT_OPEN_ENDED
    }

    @Test
    fun `an extension tag ends at the first angle bracket and its first closing tag`() {
        "<ref name=\"a>b\">x</ref>".found(0) shouldBe "<ref name=\"a>b\">x</ref>"
        "<ref>a</ref >b</ref>".found(0) shouldBe "<ref>a</ref >"
        "<REF>a</ref>".found(0) shouldBe "<REF>a</ref>"
        "<ref name=a/>".kind(0) shouldBe Preprocessor.EXT_SHORT
        "<references />".found(0) shouldBe "<references />"
    }

    @Test
    fun `an extension tag with no closing tag is text`() {
        "<ref>a".found(0) shouldBe null
        "<ref a".found(0) shouldBe null
        "<refx>a</refx>".found(0) shouldBe null
        "<ref/x>".found(0) shouldBe null
    }

    @Test
    fun `nothing inside an extension tag is looked at until asked`() {
        val text = "<ref>{{a}}</ref>"
        val pp = preprocess(text)

        pp.kinds[5] shouldBe 0.toByte()
        pp.run(5, 10)
        pp.kinds[5] shouldBe Preprocessor.TEMPLATE
    }

    @Test
    fun `a failed search is not repeated`() {
        // Each of these would search to the end once per opening without the caches.
        val text = "<ref>a".repeat(5) + "<ref b".repeat(5)
        val pp = preprocess(text)

        (text.indices).count { pp.kinds[it] != 0.toByte() } shouldBe 0
    }
}
