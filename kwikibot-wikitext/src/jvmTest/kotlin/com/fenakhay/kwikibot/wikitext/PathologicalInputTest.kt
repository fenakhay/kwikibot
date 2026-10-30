package com.fenakhay.kwikibot.wikitext

import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.ops.mapNodes
import com.fenakhay.kwikibot.wikitext.ops.outline
import com.fenakhay.kwikibot.wikitext.ops.replaceText
import com.fenakhay.kwikibot.wikitext.ops.textIn
import io.kotest.matchers.shouldBe
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
    fun `pairing across paragraphs lists and tables stays linear`() {
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

    /**
     * Every pass over a tree, on nesting a hundred thousand deep, finishes in time on a thread whose stack
     * holds only a few dozen levels of recursion: one that recursed once per level would overflow it, and one
     * that went back over the levels above for each level would take hours.
     */
    @Test
    fun `nesting a hundred thousand deep is parsed and walked in time on a small stack`() {
        val templates = "{{a|".repeat(DEEP) + "x" + "}}".repeat(DEEP)
        val tags = "<div>".repeat(DEEP) + "\n==h==\n" + "</div>".repeat(DEEP)

        within(SMALL_STACK) {
            val code = Wikitext.parse(templates)
            code.serialize() shouldBe templates
            code.ranges()[code] shouldBe templates.indices
            (code == Wikitext.parse(templates)) shouldBe true
            code.templates().toSet().size shouldBe DEEP
            code.text shouldBe ""
            code.textIn(TextScope.EVERYWHERE).size shouldBe 2 * DEEP + 1
            code.mapNodes { if (it is TextNode) TextNode(it.text.uppercase()) else it }.serialize() shouldBe
                templates.uppercase()
            code.replaceText(Regex("x"), "y", TextScope.EVERYWHERE).serialize().last { it != '}' } shouldBe
                'y'
            code.replace(code.templates()[DEEP / 2], TextNode("z")).serialize() shouldBe
                "{{a|".repeat(DEEP / 2) + "z" + "}}".repeat(DEEP / 2)
        }
        within(SMALL_STACK) {
            val code = Wikitext.parse(tags)
            code.serialize() shouldBe tags
            code.headings().size shouldBe 1
            code.outline().serialize() shouldBe tags
            CosmeticChanges.SAFE.apply(code).serialize() shouldBe tags
        }
    }

    private fun parseWithin(wikitext: String) {
        within { Wikitext.parse(wikitext).serialize() }
    }

    /** Runs [work] on a thread of its own, with [stack] bytes of stack, and fails if it takes too long. */
    private fun within(stack: Long = 0, work: () -> Unit) {
        var thrown: Throwable? = null

        val worker = Thread(null, { runCatching(work).onFailure { thrown = it } }, "pathological", stack)
        worker.isDaemon = true
        worker.start()
        worker.join(DEADLINE_MILLIS)

        if (worker.isAlive) fail("the work did not finish within ${DEADLINE_MILLIS}ms")
        thrown?.let { throw it }
    }

    private companion object {
        const val DEADLINE_MILLIS = 20_000L

        /** Long enough that a quadratic search takes minutes; a linear one takes milliseconds. */
        const val LONG = 20_000

        /** As deep as the tree is made to nest where it is the clock that matters, not the depth. */
        const val NESTED = 100

        /** Far deeper than any thread's stack could take if each level cost a frame. */
        const val DEEP = 100_000

        /** A stack a few dozen levels of the library's own recursion fit in, and no more. */
        const val SMALL_STACK = 256L * 1024
    }
}
