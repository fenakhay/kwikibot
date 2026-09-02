package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.Token

/**
 * Turns wikitext into a flat stream of tokens.
 *
 * Two passes. [Scan] works out what closes, and this walks the text with that answer already in hand — so it
 * never tries a construct to see whether it parses, and never has to undo one. That is the whole design, and
 * it is worth saying why rather than only what.
 *
 * A parser that finds out by trying re-reads everything an abandoned attempt covered, and if abandoned
 * attempts can contain each other the cost doubles per level. `{{#ifeq:{{pagename}}|x| '''}}` down a
 * quotation template is ordinary Wiktionary markup, and fifty of them on one page did not finish parsing.
 * Knowing the answer first is not an optimisation of that; it removes the question.
 *
 * The tokenizer decides what each piece of syntax *is*. [Builder] assembles the tree afterwards, which keeps
 * this a plain left-to-right walk.
 */
internal class Tokenizer {

    private var text: String = ""
    private var scan: Scan = Scan.of("")
    private var head: Int = 0

    private val tokens = mutableListOf<Token>()
    private val buffer = StringBuilder()

    /** Tokenizes [wikitext]. */
    fun tokenize(wikitext: String): List<Token> {
        text = wikitext
        scan = Scan.of(wikitext)
        head = 0
        tokens.clear()
        buffer.setLength(0)

        content(Scope.TOP, wikitext.length)
        flush()
        return tokens.toList()
    }

    // ------------------------------------------------------------------ emitting

    /** Moves buffered literal text into the token list, keeping token order. */
    private fun flush() {
        if (buffer.isNotEmpty()) {
            tokens += Token.Text(buffer.toString())
            buffer.clear()
        }
    }

    private fun emit(token: Token) {
        flush()
        tokens += token
    }

    /**
     * Buffers literal text.
     *
     * Buffered rather than emitted so that a run of ordinary characters becomes one [Token.Text] instead of
     * one per character.
     */
    private fun emitText(value: String) {
        buffer.append(value)
    }

    private fun emitText(value: Char) {
        buffer.append(value)
    }

    /** Buffers the source between two offsets and leaves the cursor after it. */
    private fun emitSpan(from: Int, to: Int) {
        buffer.append(text, from, to)
        head = to
    }

    /**
     * Everything needed to pretend a construct was never started.
     *
     * [Scan] settles whether a construct *closes*, which is the question that used to cost the parser its
     * running time. It does not settle whether the inside is well formed, and two things can still turn out
     * not to be what their opening claimed: a tag whose attributes run into something that is not a `>`, and
     * a bracket pair around something that is not a URL.
     *
     * Backing out of those is safe in a way the old parser's backtracking was not. Neither can contain
     * another of its own kind in the part being retried, so what is re-read is bounded by one construct
     * rather than by the nesting depth of the page.
     *
     * The pending text is kept whole rather than as a length. Emitting a token flushes the buffer into one,
     * so a length taken before that points into the *next* run of text, and restoring it would keep a few
     * characters of whatever came after.
     */
    private class Mark(val head: Int, val tokens: Int, val pending: String)

    private fun mark() = Mark(head, tokens.size, buffer.toString())

    /** Undoes everything emitted since [mark], then emits [literal] and steps over it. */
    private fun rollBackTo(mark: Mark, literal: Char) {
        while (tokens.size > mark.tokens) tokens.removeLast()
        buffer.setLength(0)
        buffer.append(mark.pending)

        head = mark.head
        emitText(literal)
        head++
    }

    // ------------------------------------------------------------------ reading

    private fun at(offset: Int = 0): Char = text[head + offset]

    private fun has(offset: Int = 0): Boolean = head + offset < text.length

    private fun startsWith(prefix: String): Boolean = text.startsWith(prefix, head)

    private fun atLineStart(): Boolean = head == 0 || text[head - 1] == '\n'

    // ------------------------------------------------------------------ content

    /**
     * What is being parsed, and which characters end it.
     *
     * Each scope carries its own terminators, so the content loop stays a dispatch on markup rather than a
     * second switch on scope.
     */
    private enum class Scope {
        TOP,
        TEMPLATE_NAME,
        TEMPLATE_KEY,
        TEMPLATE_VALUE,
        ARGUMENT_NAME,
        ARGUMENT_DEFAULT,
        WIKILINK_TITLE,
        WIKILINK_TEXT,
        EXTERNAL_LINK_TEXT,
        TAG_BODY,
        STYLE,
        HEADING,
    }

    /** Whether [char] ends [scope]. */
    private fun ends(scope: Scope, char: Char): Boolean =
        when (scope) {
            Scope.TEMPLATE_NAME,
            Scope.TEMPLATE_VALUE,
            Scope.ARGUMENT_NAME -> char == '|'
            Scope.TEMPLATE_KEY -> char == '|' || char == '='
            Scope.WIKILINK_TITLE -> char == '|'
            Scope.EXTERNAL_LINK_TEXT -> char == ']'
            Scope.HEADING -> char == '\n' || char == '='
            else -> false
        }

    /**
     * Consumes content up to [limit], or until whatever ends [scope], whichever comes first.
     *
     * [limit] is where the enclosing construct's closing markup begins, and it is known before this is called
     * — which is what lets nested constructs be parsed once each.
     */
    @Suppress("CyclomaticComplexMethod") // A tokenizer's dispatch is a table; splitting it hides it.
    private fun content(scope: Scope, limit: Int) {
        while (head < limit) {
            skipPlainText(limit)
            if (head >= limit) return

            val char = text[head]
            if (ends(scope, char)) return

            when {
                char == '{' -> braces(limit)
                char == '[' -> brackets(limit)
                char == '<' -> angle(limit)
                char == '&' -> entity()
                char == APOSTROPHE && has(1) && at(1) == APOSTROPHE -> style(limit)
                char == '=' && scope == Scope.TOP && atLineStart() -> heading(limit)
                char in LIST_MARKERS && atLineStart() -> listMarker(char)
                char == ':' || char.isLetter() -> freeLink(char)
                else -> {
                    emitText(char)
                    head++
                }
            }
        }
    }

    /**
     * Runs the cursor forward over text that cannot start anything, buffering it in one go.
     *
     * Almost every character of almost every page is prose, and prose used to cost a map lookup that boxed
     * the character, a call, and an append of one character to a builder. Here it costs one array read, and
     * the run is copied in a block when it ends.
     *
     * The table is deliberately generous. It says "this character might begin something", not "this character
     * does", so the dispatch above still decides — a `;` that is not at the start of a line, or an `h` that
     * is not the start of `https://`, comes back here having emitted itself and cost only the detour.
     */
    private fun skipPlainText(limit: Int) {
        val start = head
        while (head < limit && !startsSomething(head)) head++
        if (head > start) buffer.append(text, start, head)
    }

    /**
     * Whether the character at [offset] could begin a construct or end a scope.
     *
     * Nothing above ASCII is markup, so non-Latin text answers no on a bounds check and runs through at
     * memory speed. A scheme letter answers yes only at the start of a word, since the `s` inside one cannot
     * begin `sftp://`.
     */
    private fun startsSomething(offset: Int): Boolean {
        val code = text[offset].code
        if (code >= MARKUP.size) return false
        if (MARKUP[code]) return true

        return SCHEME_START[code] && (offset == 0 || !text[offset - 1].isLetterOrDigit())
    }

    // ------------------------------------------------------------------ templates and arguments

    /**
     * Parses whatever a `{` opens, or emits it as the text it is.
     *
     * A run of braces is several openings, not one, and only some of them may close: in `{{{{x}}}}` the outer
     * brace on each side is text and the three inside are an argument. So each brace is looked at on its own
     * and the run sorts itself out.
     */
    private fun braces(limit: Int) {
        val end = scan.closerOf(head)
        val width = scan.braceWidth(head)

        if (end == Scan.UNMATCHED || end > limit || width == 0) {
            emitText('{')
            head++
            return
        }

        if (width == ARGUMENT_BRACES) argument(end) else template(end)
    }

    /** Parses `{{name|params}}`, whose closing braces begin at [end] minus two. */
    private fun template(end: Int) {
        val inner = end - TEMPLATE_BRACES
        emit(Token.TemplateOpen)
        head += TEMPLATE_BRACES

        content(Scope.TEMPLATE_NAME, inner)
        while (head < inner && at() == '|') {
            head++
            emit(Token.ParameterSeparator)
            parameter(inner)
        }

        head = end
        emit(Token.TemplateClose)
    }

    /**
     * Parses one template parameter.
     *
     * A parameter is positional until an `=` turns up in its key, which is why the key is read first and the
     * equals sign decides whether a value follows.
     */
    private fun parameter(limit: Int) {
        content(Scope.TEMPLATE_KEY, limit)
        if (head < limit && at() == '=') {
            head++
            emit(Token.ParameterEquals)
            content(Scope.TEMPLATE_VALUE, limit)
        }
    }

    /** Parses `{{{name|default}}}`, whose closing braces begin at [end] minus three. */
    private fun argument(end: Int) {
        val inner = end - ARGUMENT_BRACES
        emit(Token.ArgumentOpen)
        head += ARGUMENT_BRACES

        content(Scope.ARGUMENT_NAME, inner)
        if (head < inner && at() == '|') {
            head++
            emit(Token.ArgumentSeparator)
            content(Scope.ARGUMENT_DEFAULT, inner)
        }

        head = end
        emit(Token.ArgumentClose)
    }

    // ------------------------------------------------------------------ links

    /** Parses whatever a `[` opens, or emits it as the text it is. */
    private fun brackets(limit: Int) {
        val end = scan.closerOf(head)
        if (end == Scan.UNMATCHED || end > limit) {
            emitText('[')
            head++
            return
        }

        if (startsWith("[[")) wikilink(end) else bracketedLink(end)
    }

    /** Parses `[[target|text]]`. */
    private fun wikilink(end: Int) {
        val inner = end - WIKILINK_BRACKETS
        emit(Token.WikiLinkOpen)
        head += WIKILINK_BRACKETS

        content(Scope.WIKILINK_TITLE, inner)
        if (head < inner && at() == '|') {
            head++
            emit(Token.WikiLinkSeparator)
            content(Scope.WIKILINK_TEXT, inner)
        }

        head = end
        emit(Token.WikiLinkClose)
    }

    /**
     * Parses `[url text]`.
     *
     * The brackets close — [Scan] said so — but a bracket pair around something that is not a URL is not a
     * link, and `[not a url]` has to come back as the text it is.
     */
    private fun bracketedLink(end: Int) {
        val inner = end - 1
        val mark = mark()

        head++
        if (schemeAt(head) == null) {
            rollBackTo(mark, '[')
            return
        }

        val url = readUrl(inner)
        if (url.isEmpty()) {
            rollBackTo(mark, '[')
            return
        }

        emit(Token.ExternalLinkOpen(brackets = true))
        emitText(url)

        // Whatever follows the first space is the link's display text.
        if (head < inner && at() == ' ') {
            head++
            emit(Token.ExternalLinkSeparator)
            content(Scope.EXTERNAL_LINK_TEXT, inner)
        }

        // The URL has to run right up to the bracket, give or take the display text. Anything
        // else between the two - a full-width space, say, which is whitespace but is not the
        // space that introduces a label - means this was never a link.
        if (head != inner) {
            rollBackTo(mark, '[')
            return
        }

        head = end
        emit(Token.ExternalLinkClose)
    }

    /**
     * Parses a URL appearing bare in running text, such as `https://example.org`.
     *
     * Reached from every letter of every page, so the cheap checks come first: a character that cannot start
     * a scheme costs one array lookup.
     */
    private fun freeLink(char: Char) {
        if (!couldStartScheme(char) || schemeAt(head) == null) {
            emitText(char)
            head++
            return
        }

        val url = readUrl(text.length)
        if (url.isEmpty()) {
            emitText(char)
            head++
            return
        }

        emit(Token.ExternalLinkOpen(brackets = false))
        emitText(url)
        emit(Token.ExternalLinkClose)
    }

    /** Whether [char] is the first character of any scheme, which most letters are not. */
    private fun couldStartScheme(char: Char): Boolean =
        char.code < SCHEMES_BY_FIRST.size && SCHEMES_BY_FIRST[char.code] != null

    /**
     * The URL scheme at [position], or `null` if what is there is not one MediaWiki links.
     *
     * Matched against the text in place rather than against a substring: this is consulted at the start of
     * every word of every page, and copying the remainder each time would make tokenizing quadratic in the
     * length of the page.
     *
     * Only the schemes beginning with this character are tried. That sounds like a detail and is not: the
     * schemes start with s, t, i, h, m, w, n, f, b, g and u, which is most of the consonants English words
     * start with, so testing all twenty-one against every such word was the single most expensive thing the
     * tokenizer did.
     */
    private fun schemeAt(position: Int): String? {
        // A scheme only starts a link at a word boundary, so "shttps://x" is not one.
        if (position > 0 && text[position - 1].isLetterOrDigit()) return null

        val char = text[position]
        val candidates = if (char.code < SCHEMES_BY_FIRST.size) SCHEMES_BY_FIRST[char.code] else null

        return candidates?.firstOrNull { scheme ->
            text.startsWith(scheme, position, ignoreCase = true)
        }
    }

    /**
     * Consumes a URL, stopping where MediaWiki stops.
     *
     * Trailing punctuation is left behind: a sentence ending "see https://example.org." links to the site,
     * not to the site plus a full stop.
     */
    private fun readUrl(limit: Int): String {
        val start = head
        var end = head
        while (end < limit && !text[end].isWhitespace() && text[end] !in URL_STOP) end++
        while (end > start && text[end - 1] in URL_TRAILING_PUNCTUATION) end--

        head = end
        return text.substring(start, end)
    }

    // ------------------------------------------------------------------ comments and tags

    /** Parses whatever a `<` opens, or emits it as the text it is. */
    private fun angle(limit: Int) {
        val end = scan.closerOf(head)
        if (end == Scan.UNMATCHED || end > limit) {
            emitText('<')
            head++
            return
        }

        if (startsWith(COMMENT_OPEN)) comment(end) else tag(end)
    }

    /** Parses `<!-- … -->`. */
    private fun comment(end: Int) {
        emit(Token.CommentStart)
        head += COMMENT_OPEN.length

        emitSpan(head, end - COMMENT_CLOSE.length)
        emit(Token.CommentEnd)
        head = end
    }

    /**
     * Parses an HTML-style tag.
     *
     * Tags whose contents MediaWiki does not parse — `nowiki`, `pre` and friends — have their bodies taken
     * verbatim, which is the whole point of writing `<nowiki>{{x}}</nowiki>`.
     */
    private fun tag(end: Int) {
        val mark = mark()

        head++
        val name = readWhile { it.isLetterOrDigit() }

        emit(Token.OpeningTagStart())
        emitText(name)
        attributes()

        val padding = readSpaces()
        if (startsWith("/>")) {
            head = end
            emit(Token.SelfClosingTagEnd(padding))
            return
        }

        // The attributes have to end at the `>`. `<span class{{=}}"x">` does not: whatever it is,
        // it is not a tag, and MediaWiki shows it as the text it is.
        if (!has() || at() != '>') {
            rollBackTo(mark, '<')
            return
        }

        head++
        if (name.lowercase() in VOID_TAGS) {
            emit(Token.SelfClosingTagEnd(padding, implicit = true))
            return
        }
        emit(Token.OpeningTagEnd(padding))

        // The closing tag contains no `<` of its own, so the last one before the end opens it.
        val closerStart = text.lastIndexOf('<', end - 1)

        if (name.lowercase() in RAW_CONTENT_TAGS) {
            emitSpan(head, closerStart)
        } else {
            content(Scope.TAG_BODY, closerStart)
        }

        head = end
        emit(Token.ClosingTagStart)
        emitText(name)
        emit(Token.ClosingTagEnd)
    }

    /**
     * Parses a tag's attributes, keeping the whitespace around each one.
     *
     * The padding is not cosmetic detail to be normalised away: a bot that rewrites one attribute must leave
     * the rest of the tag exactly as the last editor typed it.
     */
    private fun attributes() {
        while (true) {
            val padFirst = readSpaces()
            if (padFirst.isEmpty()) return
            if (!has() || at() == '>' || startsWith("/>")) {
                // The whitespace belongs to the tag's closing padding, not to an attribute.
                head -= padFirst.length
                return
            }

            val name = readWhile { it.isLetterOrDigit() || it in ATTRIBUTE_NAME_PUNCTUATION }
            if (name.isEmpty()) {
                head -= padFirst.length
                return
            }

            val padBeforeEq = readSpaces()
            if (!has() || at() != '=') {
                emit(Token.AttributeStart(padFirst, padBeforeEq, ""))
                emitText(name)
                continue
            }

            head++
            val padAfterEq = readSpaces()
            emit(Token.AttributeStart(padFirst, padBeforeEq, padAfterEq))
            emitText(name)
            emit(Token.AttributeEquals)
            attributeValue()
        }
    }

    private fun attributeValue() {
        val quote = if (has()) at() else return
        val value =
            if (quote == '"' || quote == APOSTROPHE) {
                val closing = text.indexOf(quote, head + 1)
                // An unterminated quote is not a quote; the rest of the tag is read unquoted.
                if (closing < 0) {
                    readWhile { !it.isWhitespace() && it != '>' && it != '/' }
                } else {
                    head++
                    emit(Token.AttributeQuote(quote.toString()))
                    val quoted = text.substring(head, closing)
                    head = closing + 1
                    quoted
                }
            } else {
                readWhile { !it.isWhitespace() && it != '>' && it != '/' }
            }

        if (value.isNotEmpty()) emitText(value)
    }

    private fun readSpaces(): String = readWhile { it == ' ' || it == '\t' }

    private inline fun readWhile(predicate: (Char) -> Boolean): String {
        val start = head
        while (has() && predicate(at())) head++
        return text.substring(start, head)
    }

    // ------------------------------------------------------------------ styles and lists

    /**
     * Parses `''italic''` and `'''bold'''`, or emits the apostrophes as text.
     *
     * The partner is looked for once, with a plain search, rather than by parsing ahead and giving up. That
     * is the difference between this being linear and it being the reason a real page never finished.
     *
     * The search stops at the end of the line, because MediaWiki applies apostrophe markup a line at a time
     * and closes an open one where the line ends. Without that bound an unmatched `'''` pairs with the next
     * one anywhere on the page, and everything between — headings included — ends up inside the tag. On `1`,
     * a `'''` inside a `<gallery>` paired with one in the Swedish section three languages later, and the
     * entry appeared to have no English, Chinese or German at all.
     */
    private fun style(limit: Int) {
        val markup = if (startsWith(BOLD)) BOLD else ITALIC
        val closing = text.indexOf(markup, head + markup.length)
        val endOfLine = text.indexOf('\n', head).takeIf { it >= 0 } ?: text.length

        if (closing < 0 || closing + markup.length > minOf(limit, endOfLine)) {
            emitText(markup)
            head += markup.length
            return
        }

        val name = if (markup == BOLD) "b" else "i"
        head += markup.length

        emit(Token.OpeningTagStart(wikiMarkup = markup))
        emitText(name)
        emit(Token.OpeningTagEnd())

        content(Scope.STYLE, closing)

        head = closing + markup.length
        emit(Token.ClosingTagStart)
        emitText(name)
        emit(Token.ClosingTagEnd)
    }

    /**
     * Parses a list marker at the start of a line, which MediaWiki treats as a self-closing tag.
     *
     * `*` is a bullet, `#` numbered, `;` a definition term and `:` its definition.
     */
    private fun listMarker(char: Char) {
        emit(Token.OpeningTagStart(wikiMarkup = char.toString()))
        emitText(LIST_MARKERS.getValue(char))
        emit(Token.SelfClosingTagEnd())
        head++
    }

    // ------------------------------------------------------------------ headings

    /**
     * Parses a heading, or emits its `=` run as text.
     *
     * Whether the line is a heading is decided by looking at the line, before anything in it is parsed — so a
     * line that turns out not to be one is not parsed twice.
     */
    private fun heading(limit: Int) {
        val opening = countEquals(head)
        val bodyStart = head + opening

        // The body ends at the first `=` after it, which is why `== a = b ==` is not a heading:
        // what follows its closing run is not blank.
        var bodyEnd = bodyStart
        while (bodyEnd < limit && text[bodyEnd] != '=' && text[bodyEnd] != '\n') bodyEnd++

        val closing = countEquals(bodyEnd)
        if (opening == 0 || closing == 0 || !restOfLineIsBlank(bodyEnd + closing)) {
            emitSpan(head, bodyStart)
            return
        }

        val level = minOf(opening, closing, MAX_HEADING_LEVEL)
        emit(Token.HeadingStart(level))
        head = bodyStart

        content(Scope.HEADING, bodyEnd)

        // MediaWiki takes the shorter side and leaves the surplus as heading text, which is why
        // `==foo===` is a level-two heading whose text ends in `=`.
        appendText("=".repeat(closing - level))

        head = bodyEnd + closing
        emit(Token.HeadingEnd)
    }

    /** Appends text to the stream, merging into a trailing [Token.Text] if there is one. */
    private fun appendText(suffix: String) {
        if (suffix.isEmpty()) return
        if (buffer.isNotEmpty()) {
            buffer.append(suffix)
            return
        }

        val last = tokens.lastOrNull()
        if (last is Token.Text) {
            tokens[tokens.lastIndex] = Token.Text(last.text + suffix)
        } else {
            tokens += Token.Text(suffix)
        }
    }

    private fun countEquals(from: Int): Int {
        var probe = from
        while (probe < text.length && text[probe] == '=') probe++
        return probe - from
    }

    private fun restOfLineIsBlank(from: Int): Boolean {
        var probe = from
        while (probe < text.length && text[probe] == ' ') probe++
        return probe >= text.length || text[probe] == '\n'
    }

    // ------------------------------------------------------------------ entities

    /**
     * Parses `&amp;`, `&#65;` or `&#x41;`.
     *
     * An `&` that does not begin a well-formed entity is ordinary text — `a & b` must survive untouched — so
     * an unrecognised name is emitted as what it is.
     */
    private fun entity() {
        val start = head
        var probe = head + 1

        var numeric = false
        var hexadecimal = false

        if (probe < text.length && text[probe] == '#') {
            numeric = true
            probe++
            if (probe < text.length && (text[probe] == 'x' || text[probe] == 'X')) {
                hexadecimal = true
                probe++
            }
        }

        val semicolon = text.indexOf(';', probe)
        val body = if (semicolon < 0) "" else text.substring(probe, semicolon)

        if (semicolon < 0 || !isValidEntity(body, numeric, hexadecimal)) {
            emitText('&')
            head++
            return
        }

        emit(Token.EntityStart)
        if (numeric) emit(Token.EntityNumeric)
        if (hexadecimal) emit(Token.EntityHex(text[start + 2].toString()))

        emitText(body)
        emit(Token.EntityEnd)
        head = semicolon + 1
    }

    private fun isValidEntity(body: String, numeric: Boolean, hexadecimal: Boolean): Boolean =
        when {
            body.isEmpty() -> false
            hexadecimal -> body.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            numeric -> body.all { it.isDigit() }
            else -> body in HtmlEntities.NAMES
        }

    private companion object {
        const val MAX_HEADING_LEVEL = 6
        const val TEMPLATE_BRACES = 2
        const val ARGUMENT_BRACES = 3
        const val WIKILINK_BRACKETS = 2
        const val COMMENT_OPEN = "<!--"
        const val COMMENT_CLOSE = "-->"

        /** The character MediaWiki doubles and triples for italic and bold. */
        const val APOSTROPHE = '\''

        private const val BOLD = "'''"
        private const val ITALIC = "''"

        private const val ATTRIBUTE_NAME_PUNCTUATION = "-_:."

        /** Tags that never have a body, so `<br>` needs no `</br>` to be complete. */
        val VOID_TAGS = setOf("br", "wbr", "hr", "meta", "link", "img")

        /** Tags whose contents MediaWiki does not parse; their bodies are taken verbatim. */
        val RAW_CONTENT_TAGS = setOf("nowiki", "pre", "syntaxhighlight", "source", "math", "score")

        /** Line-start markers MediaWiki turns into list tags. */
        val LIST_MARKERS = mapOf('*' to "li", '#' to "li", ';' to "dt", ':' to "dd")

        /** The schemes MediaWiki turns into links without brackets. */
        val URL_SCHEMES =
            listOf(
                "https://",
                "http://",
                "ftps://",
                "ftp://",
                "sftp://",
                "irc://",
                "ircs://",
                "gopher://",
                "telnet://",
                "nntp://",
                "worldwind://",
                "mailto:",
                "news:",
                "svn://",
                "git://",
                "mms://",
                "bitcoin:",
                "magnet:",
                "urn:",
                "geo:",
                "//",
            )

        /**
         * The schemes each character can begin, in both cases, or `null` for the characters that begin none.
         *
         * Consulted at the start of every word of every page, which is why it is a lookup into a handful of
         * candidates rather than a search through all of them.
         */
        val SCHEMES_BY_FIRST: Array<List<String>?> =
            arrayOfNulls<List<String>>(128).also { table ->
                for (scheme in URL_SCHEMES) {
                    val first = scheme[0]
                    if (first.code >= table.size) continue
                    for (code in setOf(first.lowercaseChar().code, first.uppercaseChar().code)) {
                        table[code] = (table[code] ?: emptyList()) + scheme
                    }
                }
            }

        /**
         * Every character that begins a construct or ends a scope, wherever it appears.
         *
         * Anything not in here and not in [SCHEME_START] is prose, and is copied without being looked at
         * again.
         */
        val MARKUP =
            BooleanArray(128).apply {
                // Openings.
                for (char in "{[<&'") this[char.code] = true
                // Scope terminators: a parameter's pipe, a named parameter's equals, a link's
                // bracket, and the newline that ends a heading.
                for (char in "}]|=>\n") this[char.code] = true
                // Line-start markers, which are only markers at the start of a line - but they are
                // punctuation, so stopping wherever they appear costs almost nothing.
                for (char in "*#;:") this[char.code] = true
            }

        /**
         * Characters that begin a construct only at the start of a word.
         *
         * Kept apart from [MARKUP] because these are letters, and letters are what pages are made of. Between
         * them the schemes begin with s, t, i, h, m, w, n, f, b, g and u, so breaking at every one of those
         * rather than every word-initial one meant breaking on most of the consonants in the language.
         */
        val SCHEME_START =
            BooleanArray(128).apply {
                SCHEMES_BY_FIRST.forEachIndexed { code, schemes -> if (schemes != null) this[code] = true }
            }

        /** Characters that end a URL because they cannot appear inside one unescaped. */
        val URL_STOP = charArrayOf('|', '}', ']', '[', '<', '>', '"', '{')

        /** Punctuation a URL never ends with, so a sentence's full stop stays out of the link. */
        val URL_TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')')
    }
}
