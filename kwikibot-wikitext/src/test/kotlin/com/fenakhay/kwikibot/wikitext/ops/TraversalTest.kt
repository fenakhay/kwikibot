package com.fenakhay.kwikibot.wikitext.ops

import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.TextNode
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TraversalTest {

    private val shout: (Node) -> Node = { node ->
        if (node is TextNode) TextNode(node.text.uppercase()) else node
    }

    @Test
    fun `every node is rewritten, however deeply it is nested`() {
        val code =
            Wikitext.parse(
                "==a==\n{{t|b|c=d}} [[e|f]] [[g]] [https://h.example i] [https://j.example] " +
                    "{{{k|l}}} {{{m}}} <span title=\"n\" hidden>o</span><br/>"
            )

        code.mapNodes(shout).serialize() shouldBe
            "==A==\n{{T|B|C=D}} [[E|F]] [[G]] [HTTPS://H.EXAMPLE I] [HTTPS://J.EXAMPLE] " +
                "{{{K|L}}} {{{M}}} <span TITLE=\"N\" HIDDEN>O</span><br/>"
    }

    @Test
    fun `what MediaWiki shows as written is left alone, and so is code`() {
        val code = Wikitext.parse("<nowiki>a</nowiki> <code>b</code> <CODE>c</CODE> <pre>d</pre> e")

        code.mapNodes(shout).serialize() shouldBe
            "<nowiki>a</nowiki> <code>b</code> <CODE>c</CODE> <pre>d</pre> E"
    }
}
