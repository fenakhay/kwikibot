package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Token
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BuilderTest {

    @Test
    fun `constructs nested ten thousand deep are built`() {
        val streams =
            mapOf(
                "{{a|" to
                    "}}" to
                    (listOf(Token.TemplateOpen, Token.Text("a"), Token.ParameterSeparator) to
                        listOf(Token.TemplateClose)),
                "{{a|k=" to
                    "}}" to
                    (listOf(
                        Token.TemplateOpen,
                        Token.Text("a"),
                        Token.ParameterSeparator,
                        Token.Text("k"),
                        Token.ParameterEquals,
                    ) to listOf(Token.TemplateClose)),
                "{{{a|" to
                    "}}}" to
                    (listOf(Token.ArgumentOpen, Token.Text("a"), Token.ArgumentSeparator) to
                        listOf(Token.ArgumentClose)),
                "[[a|" to
                    "]]" to
                    (listOf(Token.WikiLinkOpen, Token.Text("a"), Token.WikiLinkSeparator) to
                        listOf(Token.WikiLinkClose)),
                "[https://e.org " to
                    "]" to
                    (listOf(
                        Token.ExternalLinkOpen(brackets = true),
                        Token.Text("https://e.org"),
                        Token.ExternalLinkSeparator(" "),
                    ) to listOf(Token.ExternalLinkClose)),
                "==" to "==" to (listOf(Token.HeadingStart(2)) to listOf(Token.HeadingEnd)),
                "<div>" to
                    "</div>" to
                    (listOf(Token.OpeningTagStart(), Token.Text("div"), Token.OpeningTagEnd()) to
                        listOf(Token.ClosingTagStart, Token.Text("div"), Token.ClosingTagEnd)),
                "<span title=\"" to
                    "\"/>" to
                    (listOf(
                        Token.OpeningTagStart(),
                        Token.Text("span"),
                        Token.AttributeStart(" ", "", ""),
                        Token.Text("title"),
                        Token.AttributeEquals,
                        Token.AttributeQuote("\""),
                    ) to listOf(Token.SelfClosingTagEnd())),
            )

        for ((written, tokens) in streams) {
            val (open, close) = written
            val (opening, closing) = tokens
            val stream =
                List(DEPTH) { opening }.flatten() + Token.Text("x") + List(DEPTH) { closing }.flatten()

            Markup(Builder(stream).build()).serialize() shouldBe
                open.repeat(DEPTH) + "x" + close.repeat(DEPTH)
        }
    }

    @Test
    fun `a bare attribute and an unquoted value are built as written`() {
        val stream =
            listOf(
                Token.OpeningTagStart(),
                Token.Text("td"),
                Token.AttributeStart(" ", "", ""),
                Token.Text("nowrap"),
                Token.AttributeStart(" ", " ", " "),
                Token.Text("a"),
                Token.AttributeEquals,
                Token.Text("1"),
                Token.OpeningTagEnd(" "),
                Token.Text("x"),
                Token.ClosingTagStart,
                Token.Text("TD"),
                Token.ClosingTagEnd,
            )

        Markup(Builder(stream).build()).serialize() shouldBe "<td nowrap a = 1 >x</TD>"
    }

    @Test
    fun `a stream that is not the shape the tokenizer makes is refused`() {
        val malformed =
            mapOf(
                listOf(Token.TemplateOpen, Token.Text("a")) to "stream ended early",
                listOf(Token.TemplateClose) to "unexpected TemplateClose",
                listOf(Token.TemplateOpen, Token.ArgumentClose) to "unexpected ArgumentClose",
                listOf(Token.OpeningTagStart(), Token.TemplateOpen) to "tag without a name",
                listOf(Token.OpeningTagStart(), Token.Text("b"), Token.Text("c")) to "tag was never closed",
                listOf(Token.CommentStart, Token.Text("a"), Token.Text("b")) to "comment was never closed",
                listOf(Token.EntityStart, Token.EntityEnd) to "entity without a body",
                listOf(Token.EntityStart, Token.Text("amp"), Token.Text("x")) to "expected EntityEnd",
                listOf(
                    Token.OpeningTagStart(),
                    Token.Text("b"),
                    Token.OpeningTagEnd(),
                    Token.ClosingTagStart,
                    Token.Text("b"),
                    Token.Text("c"),
                ) to "expected ClosingTagEnd",
            )

        for ((stream, reason) in malformed) {
            assertFailsWith<IllegalStateException> { Builder(stream).build() }.message.orEmpty() shouldContain
                reason
        }
    }

    private companion object {
        const val DEPTH = 10_000
    }
}
