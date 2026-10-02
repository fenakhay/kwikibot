package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.ops.outline
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

/**
 * Random markup, parsed and written back.
 *
 * The corpus covers the inputs someone thought to write. This runs together, in random order, the pieces that
 * open, close and separate things, to catch a combination the parser loses or duplicates a character on.
 * Seeded, so a failure reproduces.
 */
class RoundTripFuzzTest {

    private val pieces =
        listOf(
            "{",
            "}",
            "{{",
            "}}",
            "{{{",
            "}}}",
            "[",
            "]",
            "[[",
            "]]",
            "|",
            "=",
            "==",
            "===",
            "\n",
            " ",
            "\t",
            "<",
            ">",
            "</",
            "/>",
            "<!--",
            "-->",
            "''",
            "'''",
            "-{",
            "}-",
            "-",
            "&",
            ";",
            "&amp;",
            "&#x41;",
            "#",
            "*",
            ":",
            "a",
            "b",
            "x",
            "é",
            "(",
            ")",
            "?",
            ".",
            ",",
            "_",
            "\"",
            "'",
            "\u3000",
            "http://",
            "https://e.org/",
            "//e.org",
            "mailto:",
            "File:",
            "<ref>",
            "</ref>",
            "<ref name=a/>",
            "<ref ",
            "</ref >",
            "<nowiki>",
            "</nowiki>",
            "<pre>",
            "</pre>",
            "<b>",
            "</b>",
            "</B>",
            "<span ",
            "<div>",
            "</div >",
            "title=\"",
            "nowrap",
            "<br>",
            "<br/>",
            "<noinclude>",
            "</noinclude>",
            "<math>",
            "</math>",
            "<gallery>",
            "</gallery>",
            "\n\n",
            "\n* ",
            "\n ",
            "\n{|",
            "\n|}",
            "\n|-",
            "\n|",
            "||",
            "\n!",
            "!!",
            "\n|+",
            "\n----",
            "<span>",
            "</span>",
            "<span/>",
            "<p>",
            "</p>",
            "<li>",
            "<li/>",
            "</li>",
            "<td>",
            "<table>",
            "</table>",
            "<h2>",
            "</h3>",
            "<center>",
            "<blockquote>",
            "</blockquote>",
            "<poem>",
            "</poem>",
            "<templatestyles src=a />",
            "<references />",
            "<pre format=\"wikitext\">",
            "<inputbox>",
            "</inputbox>",
            "__NOTOC__",
            "__TOC__",
            "[[File:a|thumb]]",
        )

    @Test
    fun `random markup always writes back exactly as it was`() {
        checkRoundTrips(ParseOptions.DEFAULT, seed = 20_261_002)
    }

    @Test
    fun `random markup writes back as it was without language conversion too`() {
        checkRoundTrips(ParseOptions(languageConversion = false), seed = 7)
    }

    private fun checkRoundTrips(options: ParseOptions, seed: Int) {
        val random = Random(seed)
        val failures = mutableListOf<String>()

        repeat(CASES) {
            val text = buildString { repeat(random.nextInt(1, MAX_PIECES)) { append(pieces.random(random)) } }
            val written = runCatching {
                val parsed = Wikitext.parse(text, options)
                val outline = parsed.outline().serialize()
                check(outline == text) { "the outline wrote ${outline.escaped()}" }
                parsed.serialize()
            }
                .getOrElse { "<threw $it>" }
            if (written != text && failures.size < SHOWN) {
                failures += "in:  ${text.escaped()}\nout: ${written.escaped()}"
            }
        }

        failures.joinToString("\n\n") shouldBe ""
    }

    private fun String.escaped(): String = replace("\n", "\\n").replace("\t", "\\t")

    private companion object {
        const val CASES = 20_000
        const val MAX_PIECES = 30
        const val SHOWN = 10
    }
}
