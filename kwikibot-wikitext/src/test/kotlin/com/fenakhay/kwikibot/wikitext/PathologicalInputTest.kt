package com.fenakhay.kwikibot.wikitext

import kotlin.test.Test
import kotlin.test.fail

class PathologicalInputTest {

    @Test
    fun `unclosed style markup inside templates does not blow up`() {
        parseWithin("{{outer|" + "{{#ifeq:{{pagename}}|a|'''}}".repeat(400) + "}}")
    }

    @Test
    fun `runs of unclosed constructs do not blow up`() {
        parseWithin("{{a|".repeat(400))
        parseWithin("[[a|".repeat(400))
        parseWithin("<ref>".repeat(400))
        parseWithin("{{{a|".repeat(400))
        parseWithin("[http://example.org ".repeat(400))
    }

    @Test
    fun `deeply nested constructs do not blow up`() {
        parseWithin("{{a|".repeat(200) + "x" + "}}".repeat(200))
        parseWithin("[[a|".repeat(200) + "x" + "]]".repeat(200))
    }

    /**
     * Shapes where each opening searches ahead for its partner, long enough that repeating a failed search
     * per opening, instead of remembering it, is quadratic and misses the deadline.
     */
    @Test
    fun `searches for a partner stay linear on one long line`() {
        parseWithin("[http://example.org a ".repeat(LONG))
        parseWithin("''a ".repeat(LONG))
        parseWithin("'''a ".repeat(LONG))
        parseWithin("<b>a".repeat(LONG))
        parseWithin("</b>a".repeat(LONG))
        parseWithin("<span title=\"a\" ".repeat(LONG))
        parseWithin("<ref>a".repeat(LONG))
        parseWithin("<ref a".repeat(LONG))
        parseWithin("<!--a".repeat(LONG))
        parseWithin("-{a|".repeat(LONG))
        parseWithin("https://example.org/a.".repeat(LONG))
    }

    @Test
    fun `runs that close stay linear`() {
        parseWithin("[[a]] {{b|c=d}} <b>e</b> ''f'' [http://example.org g] ".repeat(LONG))
        parseWithin("==a==\n".repeat(LONG))
        parseWithin("[[File:a.png|" + "[[b]] ".repeat(LONG) + "]]")
        parseWithin("[[a|" + "[[b]] ".repeat(LONG) + "]]")
        parseWithin("<div>" + "<span>".repeat(LONG) + "</div>")
    }

    /**
     * Shapes that make pairing tags across paragraphs, lists and tables look back over everything still open,
     * which is quadratic unless each element is visited once.
     */
    @Test
    fun `pairing across paragraphs, lists and tables stays linear`() {
        parseWithin("<span>a\n\n".repeat(LONG))
        parseWithin("* <b>a\n".repeat(LONG))
        parseWithin("*".repeat(LONG) + "a\n" + "*".repeat(LONG))
        parseWithin("<b>x" + "\n\na".repeat(LONG))
        parseWithin(("<b>".repeat(NESTED) + "\n\n" + "a</b>".repeat(NESTED) + "\n").repeat(LONG / NESTED))
        parseWithin("<span><div>" + "<b>".repeat(LONG) + "</span>".repeat(LONG))
        parseWithin("<ul>" + "<b>".repeat(LONG) + "<li></li>".repeat(LONG))
        parseWithin("{|\n" + "|a||b\n".repeat(LONG) + "|}")
        parseWithin("<h2><span>".repeat(LONG) + "</h3>".repeat(LONG))
        parseWithin("<span>".repeat(LONG) + "</div>".repeat(LONG))
        parseWithin("<div>" + "\n\n<b>a".repeat(LONG))
        parseWithin("<span>a\n<!-- c -->\n".repeat(LONG))
    }

    private fun parseWithin(wikitext: String) {
        var thrown: Throwable? = null

        val worker = Thread { runCatching { Wikitext.parse(wikitext).serialize() }.onFailure { thrown = it } }
        worker.isDaemon = true
        worker.start()
        worker.join(DEADLINE_MILLIS)

        if (worker.isAlive) {
            fail("parsing ${wikitext.length} characters did not finish within ${DEADLINE_MILLIS}ms")
        }
        thrown?.let { throw it }
    }

    private companion object {
        const val DEADLINE_MILLIS = 20_000L

        /** Long enough that a quadratic search takes minutes; a linear one takes milliseconds. */
        const val LONG = 20_000

        /** As deep as the tree is made to nest: deep enough to matter, shallow enough for its recursion. */
        const val NESTED = 100
    }
}
