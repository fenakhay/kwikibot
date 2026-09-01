package com.fenakhay.kwikibot.wikitext

/**
 * Assembles a token stream into nodes.
 *
 * Kept apart from the tokenizer on purpose: the tokenizer decides what each piece of syntax is,
 * backtracking freely over a flat list, and the builder — which never backtracks — turns the
 * result into the tree callers work with.
 */
internal class Builder(private val tokens: List<Token>) {

    private var index = 0

    /** Builds every node in the stream. */
    fun build(): List<Node> = buildNodes { false }

    private fun peek(): Token? = tokens.getOrNull(index)

    private fun next(): Token = tokens.getOrElse(index++) { malformed("stream ended early") }

    /** Builds nodes until [stop] matches the next token, which is left unconsumed. */
    private inline fun buildNodes(stop: (Token) -> Boolean): List<Node> = buildList {
        while (true) {
            val token = peek() ?: return@buildList
            if (stop(token)) return@buildList
            add(buildNode())
        }
    }

    private fun buildNode(): Node = when (val token = next()) {
        is Token.Text -> TextNode(token.text)
        Token.TemplateOpen -> buildTemplate()
        Token.ArgumentOpen -> buildArgument()
        Token.WikiLinkOpen -> buildWikilink()
        is Token.ExternalLinkOpen -> buildExternalLink(token)
        is Token.HeadingStart -> buildHeading(token)
        Token.CommentStart -> buildComment()
        Token.EntityStart -> buildEntity()
        is Token.OpeningTagStart -> buildTag(token)
        else -> malformed("unexpected $token")
    }

    private fun buildTemplate(): Template {
        val name = code { it == Token.ParameterSeparator || it == Token.TemplateClose }
        val parameters = mutableListOf<Parameter>()
        var positional = 0

        while (peek() == Token.ParameterSeparator) {
            next()
            parameters += buildParameter(++positional).also { if (it.showKey) positional-- }
        }

        expect(Token.TemplateClose)
        return Template(name, parameters)
    }

    /**
     * Builds one parameter.
     *
     * A positional parameter is numbered by its position among the other positional ones, which
     * is why a named parameter in the middle does not shift the numbering of those after it.
     */
    private fun buildParameter(position: Int): Parameter {
        val first = code {
            it == Token.ParameterEquals ||
                it == Token.ParameterSeparator ||
                it == Token.TemplateClose
        }

        if (peek() != Token.ParameterEquals) {
            return Parameter(Markup.of(position.toString()), first, showKey = false)
        }

        next()
        val value = code { it == Token.ParameterSeparator || it == Token.TemplateClose }
        return Parameter(first, value, showKey = true)
    }

    private fun buildArgument(): Argument {
        val name = code { it == Token.ArgumentSeparator || it == Token.ArgumentClose }
        var default: Markup? = null

        if (peek() == Token.ArgumentSeparator) {
            next()
            default = code { it == Token.ArgumentClose }
        }

        expect(Token.ArgumentClose)
        return Argument(name, default)
    }

    private fun buildWikilink(): WikiLink {
        val target = code { it == Token.WikiLinkSeparator || it == Token.WikiLinkClose }
        var text: Markup? = null

        if (peek() == Token.WikiLinkSeparator) {
            next()
            text = code { it == Token.WikiLinkClose }
        }

        expect(Token.WikiLinkClose)
        return WikiLink(target, text)
    }

    private fun buildExternalLink(open: Token.ExternalLinkOpen): ExternalLink {
        val url = code { it == Token.ExternalLinkSeparator || it == Token.ExternalLinkClose }
        var title: Markup? = null

        if (peek() == Token.ExternalLinkSeparator) {
            next()
            title = code { it == Token.ExternalLinkClose }
        }

        expect(Token.ExternalLinkClose)
        return ExternalLink(url, title, open.brackets)
    }

    private fun buildHeading(start: Token.HeadingStart): Heading {
        val title = code { it == Token.HeadingEnd }
        expect(Token.HeadingEnd)
        return Heading(title, start.level)
    }

    private fun buildComment(): Comment {
        val contents = (peek() as? Token.Text)?.also { next() }?.text.orEmpty()
        expect(Token.CommentEnd)
        return Comment(contents)
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

    private fun buildTag(open: Token.OpeningTagStart): Tag {
        val name = (next() as? Token.Text)?.text ?: malformed("tag without a name")
        val attributes = buildAttributes()

        (peek() as? Token.SelfClosingTagEnd)?.let { close ->
            next()
            return Tag(
                name = name,
                attributes = attributes,
                selfClosing = true,
                wikiMarkup = open.wikiMarkup,
                padding = close.padding.orEmpty(),
                implicitClose = close.implicit,
            )
        }

        val closeOpen = next() as? Token.OpeningTagEnd ?: malformed("tag was never closed")
        val contents = code { it == Token.ClosingTagStart }
        expect(Token.ClosingTagStart)
        // The closing tag repeats the name, which serialization regenerates.
        if (peek() is Token.Text) next()
        expect(Token.ClosingTagEnd)

        return Tag(
            name = name,
            contents = contents,
            attributes = attributes,
            wikiMarkup = open.wikiMarkup,
            padding = closeOpen.padding.orEmpty(),
        )
    }

    private fun buildAttributes(): List<Attribute> = buildList {
        while (true) {
            val start = peek() as? Token.AttributeStart ?: return@buildList
            next()

            val name = code {
                it == Token.AttributeEquals ||
                    it is Token.AttributeStart ||
                    it is Token.OpeningTagEnd ||
                    it is Token.SelfClosingTagEnd
            }

            if (peek() != Token.AttributeEquals) {
                add(Attribute(name, padFirst = start.padFirst, padBeforeEq = start.padBeforeEq))
                continue
            }
            next()

            val quote = (peek() as? Token.AttributeQuote)?.also { next() }?.char
            val value = code {
                it is Token.AttributeStart ||
                    it is Token.OpeningTagEnd ||
                    it is Token.SelfClosingTagEnd
            }

            add(
                Attribute(
                    name = name,
                    value = value,
                    quote = quote,
                    padFirst = start.padFirst,
                    padBeforeEq = start.padBeforeEq,
                    padAfterEq = start.padAfterEq,
                ),
            )
        }
    }

    private inline fun code(stop: (Token) -> Boolean): Markup = Markup(buildNodes(stop))

    private fun expect(token: Token) {
        val actual = next()
        if (actual != token) malformed("expected $token but found $actual")
    }

    /**
     * The token stream did not have the shape the tokenizer promises.
     *
     * Not a user-input problem — no wikitext can cause it — so it is a programming error in the
     * tokenizer or the builder, and it fails loudly rather than producing a half-built tree.
     */
    private fun malformed(reason: String): Nothing =
        error("malformed token stream at $index: $reason")
}
