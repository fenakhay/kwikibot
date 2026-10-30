package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Token
import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Attribute
import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.HtmlEntity
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Parameter
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink

/**
 * Assembles a token stream into nodes.
 *
 * Kept apart from the tokenizer on purpose: the tokenizer decides what each piece of syntax is, backtracking
 * freely over a flat list, and the builder — which never backtracks — turns the result into the tree callers
 * work with.
 *
 * The constructs still open are kept on a stack of their own rather than on the thread's: an opening token
 * starts one, the tokens after it fill its parts in turn, and the token that ends its last part finishes it
 * into a node of the construct around it. How deep a tree nests is then a matter of memory alone.
 */
internal class Builder(private val tokens: List<Token>) {

    private var index = 0
    private val top = ArrayList<Node>()
    private val open = ArrayList<Construct>()
    private var depth = 0

    /** Builds every node in the stream. */
    fun build(): List<Node> {
        while (true) {
            val token = peek()
            if (depth == 0) {
                if (token == null) return top
                shift(top)
            } else {
                val construct = open[depth - 1]
                if (token == null || construct.endsPart(token)) end(construct) else shift(construct.part())
            }
        }
    }

    private fun peek(): Token? = tokens.getOrNull(index)

    private fun next(): Token = tokens.getOrElse(index++) { malformed("stream ended early") }

    /** Takes the next token into [into], as text, a comment or an entity, or as the start of a construct. */
    private fun shift(into: MutableList<Node>) {
        when (val token = next()) {
            is Token.Text -> into += TextNode(token.text)
            Token.CommentStart -> into += buildComment()
            Token.EntityStart -> into += buildEntity()
            Token.TemplateOpen -> push(TEMPLATE_NAME).parameters = ArrayList()
            Token.ArgumentOpen -> push(ARGUMENT_NAME)
            Token.WikiLinkOpen -> push(LINK_TARGET)
            is Token.ExternalLinkOpen -> push(URL).brackets = token.brackets
            is Token.HeadingStart -> push(HEADING_TITLE).level = token.level
            is Token.OpeningTagStart -> {
                val name = (next() as? Token.Text)?.text ?: malformed("tag without a name")
                push(TAG_ATTRIBUTES).apply {
                    tagName = name
                    wikiMarkup = token.wikiMarkup
                }
            }
            else -> malformed("unexpected $token")
        }
    }

    private fun push(step: Int): Construct {
        if (depth == open.size) open += Construct()
        return open[depth++].also { it.reset(step) }
    }

    /** Finishes the construct on top as [node], which goes into the part of the one around it. */
    private fun finish(node: Node) {
        depth--
        if (depth == 0) top += node else open[depth - 1].part() += node
    }

    /**
     * Ends the part [construct] is in, at the token that ends it, and starts its next part or finishes it.
     */
    private fun end(construct: Construct) {
        when (construct.step) {
            TEMPLATE_NAME,
            PARAMETER_FIRST,
            PARAMETER_VALUE -> endTemplatePart(construct)
            ARGUMENT_NAME,
            ARGUMENT_DEFAULT -> endArgumentPart(construct)
            LINK_TARGET,
            LINK_TEXT -> endLinkPart(construct)
            URL,
            URL_TITLE -> endExternalLinkPart(construct)
            HEADING_TITLE -> {
                val title = construct.markup()
                expect(Token.HeadingEnd)
                finish(Heading(title, construct.level))
            }
            else -> endTagPart(construct)
        }
    }

    /**
     * A template's name or one of its parameters has ended.
     *
     * A positional parameter is numbered by its position among the other positional ones, which is why a
     * named parameter in the middle does not shift the numbering of those after it.
     */
    private fun endTemplatePart(template: Construct) {
        val parameters = checkNotNull(template.parameters)
        when (template.step) {
            TEMPLATE_NAME -> template.name = template.markup()
            PARAMETER_FIRST -> {
                val first = template.markup()
                if (peek() == Token.ParameterEquals) {
                    next()
                    template.first = first
                    template.step = PARAMETER_VALUE
                    return
                }
                parameters += Parameter(Markup.of(template.position.toString()), first, showKey = false)
            }
            else -> {
                parameters += Parameter(checkNotNull(template.first), template.markup(), showKey = true)
                template.positional--
            }
        }
        if (peek() == Token.ParameterSeparator) {
            next()
            template.position = ++template.positional
            template.step = PARAMETER_FIRST
        } else {
            expect(Token.TemplateClose)
            finish(Template(checkNotNull(template.name), parameters))
        }
    }

    private fun endArgumentPart(argument: Construct) {
        if (argument.step == ARGUMENT_NAME) {
            argument.name = argument.markup()
            if (peek() == Token.ArgumentSeparator) {
                next()
                argument.step = ARGUMENT_DEFAULT
                return
            }
            expect(Token.ArgumentClose)
            finish(Argument(checkNotNull(argument.name)))
        } else {
            val default = argument.markup()
            expect(Token.ArgumentClose)
            finish(Argument(checkNotNull(argument.name), default))
        }
    }

    private fun endLinkPart(link: Construct) {
        if (link.step == LINK_TARGET) {
            link.name = link.markup()
            if (peek() == Token.WikiLinkSeparator) {
                next()
                link.step = LINK_TEXT
                return
            }
            expect(Token.WikiLinkClose)
            finish(WikiLink(checkNotNull(link.name)))
        } else {
            val text = link.markup()
            expect(Token.WikiLinkClose)
            finish(WikiLink(checkNotNull(link.name), text))
        }
    }

    private fun endExternalLinkPart(link: Construct) {
        if (link.step == URL) {
            link.name = link.markup()
            val separator = peek() as? Token.ExternalLinkSeparator
            if (separator != null) {
                next()
                link.text = separator.separator
                link.step = URL_TITLE
                return
            }
            expect(Token.ExternalLinkClose)
            finish(ExternalLink(checkNotNull(link.name), brackets = link.brackets))
        } else {
            val title = link.markup()
            expect(Token.ExternalLinkClose)
            finish(ExternalLink(checkNotNull(link.name), title, link.brackets, checkNotNull(link.text)))
        }
    }

    /** A tag's attributes are read one after another, then it either closes itself or has contents. */
    private fun endTagPart(tag: Construct) {
        when (tag.step) {
            TAG_ATTRIBUTES -> nextAttributeOrEnd(tag)
            ATTRIBUTE_NAME -> {
                val name = tag.markup()
                val start = checkNotNull(tag.attributeStart)
                if (peek() != Token.AttributeEquals) {
                    tag.attributes() +=
                        Attribute(name, padFirst = start.padFirst, padBeforeEq = start.padBeforeEq)
                    tag.step = TAG_ATTRIBUTES
                    return
                }
                next()
                tag.quote = (peek() as? Token.AttributeQuote)?.also { next() }?.char
                tag.name = name
                tag.step = ATTRIBUTE_VALUE
            }
            ATTRIBUTE_VALUE -> {
                val start = checkNotNull(tag.attributeStart)
                tag.attributes() +=
                    Attribute(
                        name = checkNotNull(tag.name),
                        value = tag.markup(),
                        quote = tag.quote,
                        padFirst = start.padFirst,
                        padBeforeEq = start.padBeforeEq,
                        padAfterEq = start.padAfterEq,
                    )
                tag.step = TAG_ATTRIBUTES
            }
            else -> closeTag(tag)
        }
    }

    private fun nextAttributeOrEnd(tag: Construct) {
        when (val token = peek()) {
            is Token.AttributeStart -> {
                next()
                tag.attributeStart = token
                tag.step = ATTRIBUTE_NAME
            }
            is Token.SelfClosingTagEnd -> {
                next()
                finish(
                    Tag(
                        name = checkNotNull(tag.tagName),
                        attributes = tag.finishedAttributes(),
                        selfClosing = true,
                        wikiMarkup = tag.wikiMarkup,
                        padding = token.padding.orEmpty(),
                        implicitClose = token.implicit,
                    )
                )
            }
            else -> {
                tag.opening = next() as? Token.OpeningTagEnd ?: malformed("tag was never closed")
                tag.step = TAG_CONTENTS
            }
        }
    }

    /** The closing tag as written is kept only where it is not `</name>`. */
    private fun closeTag(tag: Construct) {
        val contents = tag.markup()
        expect(Token.ClosingTagStart)
        val closer = (peek() as? Token.Text)?.also { next() }?.text.orEmpty()
        expect(Token.ClosingTagEnd)
        val name = checkNotNull(tag.tagName)
        val opening = checkNotNull(tag.opening)
        finish(
            Tag(
                name = name,
                contents = contents,
                attributes = tag.finishedAttributes(),
                wikiMarkup = tag.wikiMarkup,
                padding = opening.padding.orEmpty(),
                closing = "</$closer>".takeIf { tag.wikiMarkup == null && closer != name },
                verbatim = opening.verbatim,
            )
        )
    }

    private fun buildComment(): Comment {
        val contents = (peek() as? Token.Text)?.also { next() }?.text.orEmpty()
        val end = next() as? Token.CommentEnd ?: malformed("comment was never closed")
        return Comment(contents, end.closed)
    }

    private fun buildEntity(): HtmlEntity {
        var numeric = false
        var hexChar: String? = null

        if (peek() == Token.EntityNumeric) {
            next()
            numeric = true
            (peek() as? Token.EntityHex)?.let {
                next()
                hexChar = it.char
            }
        }

        val value = (next() as? Token.Text)?.text ?: malformed("entity without a body")
        expect(Token.EntityEnd)
        return HtmlEntity(value, numeric, hexChar)
    }

    private fun expect(token: Token) {
        val actual = next()
        if (actual != token) malformed("expected $token but found $actual")
    }

    /**
     * The token stream did not have the shape the tokenizer promises.
     *
     * Not a user-input problem — no wikitext can cause it — so it is a programming error in the tokenizer or
     * the builder, and it fails loudly rather than producing a half-built tree.
     */
    private fun malformed(reason: String): Nothing = error("malformed token stream at $index: $reason")

    /**
     * A construct being built: which part it is in, the nodes of that part so far, and what its earlier parts
     * came to. Kept and reused for the next construct at the same depth.
     */
    private class Construct {
        var step = 0
        private var nodes: ArrayList<Node>? = null

        /**
         * A template's name, an argument's, a link's target, a URL, or the name of the attribute being read.
         */
        var name: Markup? = null
        var first: Markup? = null
        var parameters: ArrayList<Parameter>? = null
        var positional = 0
        var position = 0
        var brackets = false
        var level = 0

        /** A tag's name, or what separates a link's URL from its title. */
        var tagName: String? = null
        var text: String? = null
        var wikiMarkup: String? = null
        var attributeStart: Token.AttributeStart? = null
        var quote: String? = null
        var opening: Token.OpeningTagEnd? = null
        private var attributes: ArrayList<Attribute>? = null

        fun reset(step: Int) {
            this.step = step
            nodes = null
            name = null
            first = null
            parameters = null
            positional = 0
            position = 0
            tagName = null
            text = null
            wikiMarkup = null
            attributeStart = null
            quote = null
            opening = null
            attributes = null
        }

        /** The nodes of the part being read. */
        fun part(): ArrayList<Node> = nodes ?: ArrayList<Node>().also { nodes = it }

        /** The part just read, as markup; the next part starts empty. */
        fun markup(): Markup {
            val read = nodes
            nodes = null
            return Markup.owning(read ?: emptyList())
        }

        fun attributes(): ArrayList<Attribute> = attributes ?: ArrayList<Attribute>().also { attributes = it }

        fun finishedAttributes(): List<Attribute> = attributes ?: emptyList()

        /** Whether [token] ends the part being read, which it then leaves unconsumed. */
        fun endsPart(token: Token): Boolean =
            when (step) {
                TEMPLATE_NAME,
                PARAMETER_FIRST,
                PARAMETER_VALUE -> endsTemplatePart(token)
                ARGUMENT_NAME,
                ARGUMENT_DEFAULT -> endsArgumentPart(token)
                LINK_TARGET,
                LINK_TEXT -> endsLinkPart(token)
                URL,
                URL_TITLE -> endsUrlPart(token)
                HEADING_TITLE -> token == Token.HeadingEnd
                else -> endsTagPart(token)
            }

        private fun endsTemplatePart(token: Token): Boolean =
            token == Token.ParameterSeparator ||
                token == Token.TemplateClose ||
                (step == PARAMETER_FIRST && token == Token.ParameterEquals)

        private fun endsArgumentPart(token: Token): Boolean =
            token == Token.ArgumentClose || (step == ARGUMENT_NAME && token == Token.ArgumentSeparator)

        private fun endsLinkPart(token: Token): Boolean =
            token == Token.WikiLinkClose || (step == LINK_TARGET && token == Token.WikiLinkSeparator)

        private fun endsUrlPart(token: Token): Boolean =
            token == Token.ExternalLinkClose || (step == URL && token is Token.ExternalLinkSeparator)

        private fun endsTagPart(token: Token): Boolean =
            when (step) {
                ATTRIBUTE_NAME -> token == Token.AttributeEquals || endsAttribute(token)
                ATTRIBUTE_VALUE -> endsAttribute(token)
                TAG_CONTENTS -> token == Token.ClosingTagStart
                else -> true
            }

        private fun endsAttribute(token: Token): Boolean =
            token is Token.AttributeStart || token is Token.OpeningTagEnd || token is Token.SelfClosingTagEnd
    }

    private companion object {
        const val TEMPLATE_NAME = 1
        const val PARAMETER_FIRST = 2
        const val PARAMETER_VALUE = 3
        const val ARGUMENT_NAME = 4
        const val ARGUMENT_DEFAULT = 5
        const val LINK_TARGET = 6
        const val LINK_TEXT = 7
        const val URL = 8
        const val URL_TITLE = 9
        const val HEADING_TITLE = 10

        /** Between a tag's attributes, where every token decides what comes next. */
        const val TAG_ATTRIBUTES = 11
        const val ATTRIBUTE_NAME = 12
        const val ATTRIBUTE_VALUE = 13
        const val TAG_CONTENTS = 14
    }
}
