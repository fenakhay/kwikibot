package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.Token
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.ARGUMENT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.COMMENT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.COMMENT_OPEN_ENDED
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.EXT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.EXT_SHORT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.HEADING
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.HTML_PAIRED
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.TEMPLATE
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.APOSTROPHE
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.ASCII
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.LINK_BRACKETS
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.isLink
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.isWordChar

/**
 * Turns wikitext into a flat stream of tokens.
 *
 * Two passes. The [Preprocessor] decides what MediaWiki's preprocessor decides (templates, arguments, the `|`
 * and `=` between their parts, headings, comments and extension tags), and this walks the text with those
 * answers in hand, so it never tries a construct to see whether it parses and never undoes one.
 *
 * What is left here is what MediaWiki reads after the preprocessor, in the text the templates expand to:
 * links, HTML tags, bold and italic, external links and bare URLs, entities and list markers. None of them
 * can move a template's boundaries, which is why they are decided second. Each is decided inside one
 * *segment* (the page, a template's name or one of its parameters, a link's target or text, a heading), and a
 * construct that would reach out of its segment is not one. The [Scanner] finds where each is; this emits it.
 *
 * Every character of the input lands in exactly one token, which is what makes the tree serialize back to the
 * input byte for byte.
 */
internal class Tokenizer(private val options: ParseOptions = ParseOptions.DEFAULT) {

    private var text: String = ""
    private var pp: Preprocessor = Preprocessor("", options)
    private var scan: Scanner = Scanner("", pp, options)
    private var kinds: ByteArray = ByteArray(0)
    private var head: Int = 0

    private val tokens = ArrayList<Token>()
    private val buffer = StringBuilder()

    /** Tokenizes [wikitext]. */
    fun tokenize(wikitext: String): List<Token> {
        text = wikitext
        pp = Preprocessor(wikitext, options)
        pp.run(0, wikitext.length)
        scan = Scanner(wikitext, pp, options)
        kinds = pp.kinds
        head = 0
        segmentDepth = 0
        tokens.clear()
        buffer.setLength(0)

        segment(wikitext.length)
        flush()
        return tokens.toList()
    }

    // ------------------------------------------------------------------ emitting

    /** Moves buffered literal text into the token list, keeping token order. */
    private fun flush() {
        if (buffer.isNotEmpty()) {
            tokens += Token.Text(buffer.toString())
            buffer.setLength(0)
        }
    }

    private fun emit(token: Token) {
        flush()
        tokens += token
    }

    private fun emitText(value: String) {
        buffer.append(value)
    }

    /** Buffers the source between two offsets and leaves the cursor after it. */
    private fun emitSpan(from: Int, to: Int) {
        if (to > from) buffer.append(text, from, to)
        head = maxOf(head, to)
    }

    private fun emitChar() {
        buffer.append(text[head])
        head++
    }

    private fun atLineStart(): Boolean = head == scan.documentStart || text[head - 1] == '\n'

    // ------------------------------------------------------------------ segments and content

    // The segments being read, innermost last: where each starts and ends, and whether its tags are paired.
    private var segmentStarts = IntArray(INITIAL_DEPTH)
    private var segmentLimits = IntArray(INITIAL_DEPTH)
    private var segmentPaired = BooleanArray(INITIAL_DEPTH)
    private var segmentModes = arrayOfNulls<BodyMode>(INITIAL_DEPTH)
    private var segmentDepth = 0

    /**
     * Reads one segment up to [limit]: the page, or a part of a template, link or heading.
     *
     * HTML tags pair inside a segment and never across one. They are paired when the first opening tag in the
     * segment is reached, once for the whole segment, so the many segments with no tag in them (most template
     * parameters) cost nothing extra.
     */
    private fun segment(limit: Int, mode: BodyMode = BodyMode.PAGE) {
        if (segmentDepth == segmentStarts.size) {
            segmentStarts = segmentStarts.copyOf(segmentDepth * 2)
            segmentLimits = segmentLimits.copyOf(segmentDepth * 2)
            segmentPaired = segmentPaired.copyOf(segmentDepth * 2)
            segmentModes = segmentModes.copyOf(segmentDepth * 2)
        }
        segmentStarts[segmentDepth] = head
        segmentLimits[segmentDepth] = limit
        segmentPaired[segmentDepth] = false
        segmentModes[segmentDepth] = mode
        segmentDepth++

        content(limit)
        segmentDepth--
    }

    /** Pairs the tags of the segment being read, if nothing has yet, and says whether it did. */
    private fun pairSegment(): Boolean {
        val top = segmentDepth - 1
        if (top < 0 || segmentPaired[top]) return false
        segmentPaired[top] = true
        scan.pairTags(segmentStarts[top], segmentLimits[top], segmentModes[top] ?: BodyMode.PAGE)
        return true
    }

    /**
     * Consumes content up to [limit], which is always where the enclosing construct's closing markup starts.
     *
     * Every construct found here is known to close by [limit] before it is parsed, so nested constructs are
     * read once each and the cursor ends at [limit].
     */
    @Suppress("CyclomaticComplexMethod") // A tokenizer's dispatch is a table; splitting it hides it.
    private fun content(limit: Int) {
        while (head < limit) {
            skipPlainText(limit)
            if (head >= limit) return

            val char = text[head]
            when {
                char == '{' -> braces(limit)
                char == '[' -> brackets(limit)
                char == '<' -> angle(limit)
                char == '&' -> entity(limit)
                char == APOSTROPHE && head + 1 < limit && text[head + 1] == APOSTROPHE -> style(limit)
                char == '=' && Preprocessor.isHeading(kinds[head]) && pp.end(head) <= limit -> heading()
                char in LIST_MARKERS && atLineStart() && listAllowed() -> listMarker(char)
                else -> if (!freeLink(limit)) emitChar()
            }
        }
    }

    /**
     * Content with only the preprocessor's constructs in it, for attributes and URLs.
     *
     * MediaWiki escapes everything else it finds in an attribute value, and a URL is read whole, so a
     * template or a comment is all that can be structure there.
     */
    private fun preprocessedOnly(limit: Int) {
        while (head < limit) {
            val kind = kinds[head]
            when {
                pp.end(head) > limit -> emitChar()
                kind == TEMPLATE || kind == ARGUMENT -> braces(limit)
                kind == COMMENT || kind == COMMENT_OPEN_ENDED -> comment()
                kind == EXT || kind == EXT_SHORT -> extensionTag()
                else -> emitChar()
            }
        }
    }

    /**
     * Runs the cursor forward over text that cannot start anything, buffering it in one go.
     *
     * Most of a page is prose. Here each character costs one array read, and the run is copied in one block
     * when it ends. A word starting with a URL scheme's first letter is settled here too, by whether its
     * letters run into a `:`; sending every such word to the dispatch would stop prose at every other word.
     */
    private fun skipPlainText(limit: Int) {
        val start = head
        val schemes = options.schemesByFirst
        while (head < limit && !startsSomething(head, limit, schemes)) {
            head = if (startsWord(head, schemes)) wordEnd(head, limit) else head + 1
        }
        if (head > start) buffer.append(text, start, head)
    }

    private fun startsSomething(at: Int, limit: Int, schemes: Array<List<String>?>): Boolean {
        val code = text[at].code
        return code < ASCII && (MARKUP[code] || (startsWord(at, schemes) && scan.couldStartUrl(at, limit)))
    }

    /** Whether a word starts at [at] with a letter some URL scheme starts with. */
    private fun startsWord(at: Int, schemes: Array<List<String>?>): Boolean {
        val code = text[at].code
        return code < ASCII && schemes[code] != null && (at == 0 || !isWordChar(text[at - 1]))
    }

    /** The end of the letters of the word starting at [at], which are prose. */
    private fun wordEnd(at: Int, limit: Int): Int {
        var end = at + 1
        while (end < limit && Scanner.isSchemeChar(text[end])) end++
        return end
    }

    // ------------------------------------------------------------------ templates and arguments

    /** Parses whatever a `{` opens, or emits it as the text it is. */
    private fun braces(limit: Int) {
        val kind = kinds[head]
        val end = pp.end(head)
        when {
            (kind != TEMPLATE && kind != ARGUMENT) || end > limit -> emitChar()
            kind == ARGUMENT -> argument(end)
            else -> template(end)
        }
    }

    /** Parses `{{name|params}}`, splitting it where the preprocessor did. */
    private fun template(end: Int) {
        val start = head
        val inner = end - TEMPLATE_BRACES
        val separators = pp.separatorsOf(start)
        val parts = if (separators < 0) 0 else pp.partCount(separators)

        emit(Token.TemplateOpen)
        head = start + TEMPLATE_BRACES
        segment(if (parts > 0) pp.pipe(separators, 0) else inner)

        for (part in 0 until parts) {
            val partEnd = if (part + 1 < parts) pp.pipe(separators, part + 1) else inner
            emit(Token.ParameterSeparator)
            head = pp.pipe(separators, part) + 1

            val equals = pp.equalsSign(separators, part)
            if (equals >= 0) {
                segment(equals)
                emit(Token.ParameterEquals)
                head = equals + 1
            }
            segment(partEnd)
        }

        head = end
        emit(Token.TemplateClose)
    }

    /**
     * Parses `{{{name|default}}}`.
     *
     * An argument takes only one default. Anything after a second `|` is still part of the default as far as
     * the page's text goes, so it is kept there as text.
     */
    private fun argument(end: Int) {
        val start = head
        val inner = end - ARGUMENT_BRACES
        val separators = pp.separatorsOf(start)

        emit(Token.ArgumentOpen)
        head = start + ARGUMENT_BRACES
        if (separators < 0) {
            segment(inner)
        } else {
            segment(pp.pipe(separators, 0))
            emit(Token.ArgumentSeparator)
            head = pp.pipe(separators, 0) + 1
            segment(inner)
        }

        head = end
        emit(Token.ArgumentClose)
    }

    // ------------------------------------------------------------------ links

    /** Parses whatever a `[` opens, or emits it as the text it is. */
    private fun brackets(limit: Int) {
        val link = head + 1 < limit && text[head + 1] == '[' && isLink(kinds[head])
        val end = if (link) scan.linkEnd(head) else -1
        when {
            end in 0..limit -> wikilink(end)
            scan.bracketedLinkAt(head, limit) -> bracketedLink()
            else -> emitChar()
        }
    }

    /** Parses `[[target|text]]`, whose closing brackets end at [end]. */
    private fun wikilink(end: Int) {
        val start = head
        val pipe = scan.linkPipe(start, end - LINK_BRACKETS)

        emit(Token.WikiLinkOpen)
        head = start + LINK_BRACKETS
        if (pipe < 0) {
            segment(end - LINK_BRACKETS)
        } else {
            segment(pipe)
            emit(Token.WikiLinkSeparator)
            head = pipe + 1
            segment(end - LINK_BRACKETS)
        }

        head = end
        emit(Token.WikiLinkClose)
    }

    /** Parses the `[url text]` [Scanner.bracketedLinkAt] just found. */
    private fun bracketedLink() {
        val urlEnd = scan.urlEnd
        val labelStart = scan.labelStart
        val close = scan.labelEnd

        emit(Token.ExternalLinkOpen(brackets = true))
        head++
        preprocessedOnly(urlEnd)
        if (close > urlEnd) {
            emit(Token.ExternalLinkSeparator(text.substring(urlEnd, labelStart)))
            head = labelStart
            content(close)
        }
        head = close + 1
        emit(Token.ExternalLinkClose)
    }

    /**
     * Parses a URL appearing bare in running text, such as `https://example.org`, or reports there is none.
     */
    private fun freeLink(limit: Int): Boolean {
        val end = scan.freeLinkEnd(head, limit)
        if (end < 0) return false

        emit(Token.ExternalLinkOpen(brackets = false))
        preprocessedOnly(end)
        emit(Token.ExternalLinkClose)
        return true
    }

    // ------------------------------------------------------------------ comments and tags

    /** Parses whatever a `<` opens, or emits it as the text it is. */
    private fun angle(limit: Int) {
        val kind = kinds[head]
        val fits = pp.end(head) <= limit
        when {
            fits && (kind == COMMENT || kind == COMMENT_OPEN_ENDED) -> comment()
            fits && (kind == EXT || kind == EXT_SHORT) -> extensionTag()
            else -> htmlTag(limit)
        }
    }

    /** Parses `<!-- … -->`, or an unclosed `<!--` that runs to the end. */
    private fun comment() {
        val start = head
        val end = pp.end(start)
        val closed = kinds[start] == COMMENT

        emit(Token.CommentStart)
        emitSpan(start + COMMENT_OPEN.length, if (closed) end - COMMENT_CLOSE.length else end)
        emit(Token.CommentEnd(closed))
        head = end
    }

    /**
     * Parses an extension tag the preprocessor found.
     *
     * Its attributes are raw: the preprocessor does not look inside them, so nothing there is a template. Its
     * body is wikitext only for the tags [ParseOptions.parsedTags] names, and is then read as a document of
     * its own; any other body is kept as written.
     */
    private fun extensionTag() {
        val start = head
        val end = pp.end(start)
        var nameEnd = start + 1
        while (!Preprocessor.isSpace(text[nameEnd]) && text[nameEnd] != '/' && text[nameEnd] != '>') nameEnd++
        val name = text.substring(start + 1, nameEnd)
        val tagEnd = text.indexOf('>', nameEnd)
        val short = kinds[start] == EXT_SHORT

        emit(Token.OpeningTagStart())
        emitText(name)
        head = nameEnd
        val padding = attributes(if (short) tagEnd - 1 else tagEnd, raw = true)

        if (short) {
            emit(Token.SelfClosingTagEnd(padding))
            head = end
            return
        }

        val closerStart = text.lastIndexOf('<', end - 1)
        val body = bodyOf(name, nameEnd, tagEnd)
        emit(Token.OpeningTagEnd(padding, verbatim = body == Body.VERBATIM))
        head = tagEnd + 1

        when (body) {
            Body.VERBATIM -> emitSpan(head, closerStart)
            Body.WIKITEXT -> {
                pp.run(head, closerStart)
                val outer = scan.documentStart
                scan.documentStart = head
                segment(closerStart, modeOf(name))
                scan.documentStart = outer
            }
            Body.PREPROCESSED -> {
                pp.run(head, closerStart)
                preprocessedOnly(closerStart)
            }
            Body.PAGE_LIST -> pageListBody(closerStart)
        }

        closingTag(closerStart, end)
    }

    /** How the body of the extension tag [name], whose attributes end at [tagEnd], is read. */
    private fun bodyOf(name: String, nameEnd: Int, tagEnd: Int): Body =
        when {
            name in options.parsedTagNames -> Body.WIKITEXT
            name.equals(TagNames.PRE, ignoreCase = true) &&
                TagAttributes.isWikitextPre(text, nameEnd, tagEnd) -> Body.WIKITEXT
            name in options.preprocessedTagNames -> Body.PREPROCESSED
            name.equals(TagNames.PAGE_LIST, ignoreCase = true) -> Body.PAGE_LIST
            else -> Body.VERBATIM
        }

    /**
     * A `<DynamicPageList>` body up to [limit], read as the extension reads it: line by line, each split at
     * its first `=`, with only the values of [TagNames.PAGE_LIST_EXPANDED] keys expanded, each on its own.
     */
    private fun pageListBody(limit: Int) {
        while (head < limit) {
            val lineEnd = text.indexOf('\n', head).let { if (it < 0 || it > limit) limit else it }
            val equals = text.indexOf('=', head).takeIf { it in head until lineEnd }
            val key = equals?.let { text.substring(head, it).trim() }
            if (equals == null || key !in TagNames.PAGE_LIST_EXPANDED) {
                emitSpan(head, minOf(lineEnd + 1, limit))
                continue
            }
            var valueStart = equals + 1
            while (valueStart < lineEnd && text[valueStart].isWhitespace()) valueStart++
            var valueEnd = lineEnd
            while (valueEnd > valueStart && text[valueEnd - 1].isWhitespace()) valueEnd--
            emitSpan(head, valueStart)
            pp.run(valueStart, valueEnd)
            preprocessedOnly(valueEnd)
            emitSpan(head, minOf(lineEnd + 1, limit))
        }
    }

    /** How MediaWiki's paragraph pass reads the wikitext body of the extension tag [name]. */
    private fun modeOf(name: String): BodyMode =
        when (name.lowercase()) {
            "ref",
            "references" -> BodyMode.REFERENCE
            "poem" -> BodyMode.POEM
            TagNames.PRE -> BodyMode.PREFORMATTED
            "gallery",
            "imagemap" -> BodyMode.PER_LINE
            else -> BodyMode.PAGE
        }

    /**
     * Whether a list can start here: not inside a `<pre format="wikitext">`, and not on a reference's first
     * line, which MediaWiki places after the list item it opens for the reference.
     */
    private fun listAllowed(): Boolean {
        val top = segmentDepth - 1
        return when (if (top >= 0) segmentModes[top] else null) {
            BodyMode.PREFORMATTED -> false
            BodyMode.REFERENCE -> head != segmentStarts[top]
            else -> true
        }
    }

    /** How an extension tag's body is read. */
    private enum class Body {
        /** Kept as written. */
        VERBATIM,

        /** Read as wikitext, a document of its own. */
        WIKITEXT,

        /** Templates, arguments, comments and extension tags found; everything else text. */
        PREPROCESSED,

        /** `<DynamicPageList>`'s lines. */
        PAGE_LIST,
    }

    /** Emits the closing tag between [start] and [end] as written. */
    private fun closingTag(start: Int, end: Int) {
        emit(Token.ClosingTagStart)
        emitText(text.substring(start + 2, end - 1))
        emit(Token.ClosingTagEnd)
        head = end
    }

    /**
     * Parses an HTML tag, or emits its `<` as text.
     *
     * A tag paired with its closing tag in this segment wraps what is between them. One that is not stands
     * alone, written as it was: `<br>`, an `<li>` left open, or a `<div>` whose `</div>` is in another
     * segment. A closing tag with no opening is text.
     */
    private fun htmlTag(limit: Int) {
        val start = head
        if (!scan.htmlTagAt(start, limit) || scan.tagClosing) {
            emitChar()
            return
        }
        // The first opening tag in a segment that can pair pairs the segment's tags. Pairing reads tags too,
        // so this one is read again after it.
        if (!scan.tagVoid && !scan.tagSelfClosing && pairSegment()) scan.htmlTagAt(start, limit)

        val nameEnd = scan.tagNameEnd
        val end = scan.tagEnd
        val selfClosing = scan.tagSelfClosing
        val single = scan.tagVoid || selfClosing
        val paired = !single && kinds[start] == HTML_PAIRED && pp.end(start) <= limit

        emit(Token.OpeningTagStart())
        emitText(text.substring(start + 1, nameEnd))
        head = nameEnd
        val padding = attributes(if (selfClosing) end - 1 else end, raw = false)

        if (!paired) {
            emit(Token.SelfClosingTagEnd(padding, implicit = !selfClosing))
            head = end + 1
            return
        }

        emit(Token.OpeningTagEnd(padding))
        head = end + 1
        val close = pp.end(start)
        val closerStart = text.lastIndexOf('<', close - 1)
        content(closerStart)
        closingTag(closerStart, close)
    }

    /**
     * Parses a tag's attributes up to [to], keeping the whitespace around each one, and returns the
     * whitespace left before the closing `>`.
     *
     * Any text at all has a reading here, so a tag rebuilds byte for byte whatever its attributes look like:
     * a name is anything up to whitespace or `=`, a quoted value runs to its closing quote if it has one, and
     * an unquoted value to the next whitespace. [raw] is for an extension tag, whose attributes the
     * preprocessor never looked inside.
     */
    private fun attributes(to: Int, raw: Boolean): String {
        var padStart = head
        head = whitespaceEnd(head, to)
        while (head < to) {
            val padFirst = text.substring(padStart, head)
            val nameStart = head
            val nameEnd = attributeRun(nameStart, to, raw) { it.isWhitespace() || it == '=' }
            val equals = whitespaceEnd(nameEnd, to)

            if (equals < to && text[equals] == '=') {
                val valueStart = whitespaceEnd(equals + 1, to)
                emit(
                    Token.AttributeStart(
                        padFirst,
                        text.substring(nameEnd, equals),
                        text.substring(equals + 1, valueStart),
                    )
                )
                attributeText(nameStart, nameEnd, raw)
                emit(Token.AttributeEquals)
                attributeValue(valueStart, to, raw)
            } else {
                // A bare attribute: the whitespace after it belongs to whatever comes next.
                emit(Token.AttributeStart(padFirst, "", ""))
                attributeText(nameStart, nameEnd, raw)
                head = nameEnd
            }

            padStart = head
            head = whitespaceEnd(head, to)
        }
        return text.substring(padStart, head)
    }

    /** Parses a value starting at [from]: quoted if a closing quote follows, unquoted otherwise. */
    private fun attributeValue(from: Int, to: Int, raw: Boolean) {
        val quote = if (from < to) text[from] else ' '
        val closingQuote =
            if (quote == '"' || quote == APOSTROPHE) attributeRun(from + 1, to, raw) { it == quote } else to
        if (closingQuote < to) {
            emit(Token.AttributeQuote(quote.toString()))
            attributeText(from + 1, closingQuote, raw)
            head = closingQuote + 1
        } else {
            val valueEnd = attributeRun(from, to, raw) { it.isWhitespace() }
            attributeText(from, valueEnd, raw)
            head = valueEnd
        }
    }

    private fun whitespaceEnd(from: Int, to: Int): Int {
        var at = from
        while (at < to && text[at].isWhitespace()) at++
        return at
    }

    /** Where a run of attribute text from [from] ends: at [stop], skipping templates unless [raw]. */
    private inline fun attributeRun(from: Int, to: Int, raw: Boolean, stop: (Char) -> Boolean): Int {
        var at = from
        while (at < to) {
            val skip = if (raw || kinds[at] == 0.toByte()) -1 else scan.opaqueEnd(at, to, links = false)
            if (skip < 0 && stop(text[at])) return at
            at = if (skip >= 0) skip else at + 1
        }
        return at
    }

    private fun attributeText(from: Int, to: Int, raw: Boolean) {
        head = from
        if (raw) emitSpan(from, to) else preprocessedOnly(to)
        head = to
    }

    // ------------------------------------------------------------------ styles and lists

    /**
     * Parses `''italic''` and `'''bold'''`, or emits the apostrophes as text.
     *
     * The partner is looked for on the same line and at the same level, by [Scanner.styleCloser].
     */
    private fun style(limit: Int) {
        val markup = if (head + 2 < limit && text[head + 2] == APOSTROPHE) BOLD else ITALIC
        val closing = scan.styleCloser(head + markup.length, limit, markup)

        if (closing < 0) {
            emitText(markup)
            head += markup.length
            return
        }

        val name = if (markup == BOLD) "b" else "i"
        head += markup.length

        emit(Token.OpeningTagStart(wikiMarkup = markup))
        emitText(name)
        emit(Token.OpeningTagEnd())

        content(closing)

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
     * Parses a heading the preprocessor found.
     *
     * Its level is the shorter of its two runs of `=`, capped at six, and any surplus on either side is part
     * of its text, which is how `===a==` is a level-two heading reading `=a`. Whatever follows the closing
     * run on the line, spaces or a comment, is outside the heading.
     */
    private fun heading() {
        val start = head
        val level = kinds[start] - HEADING
        val end = pp.end(start)

        emit(Token.HeadingStart(level))
        head = start + level
        segment(end - level)
        head = end
        emit(Token.HeadingEnd)
    }

    // ------------------------------------------------------------------ entities

    /**
     * Parses `&amp;`, `&#65;` or `&#x41;`.
     *
     * An `&` that does not begin a well-formed entity is ordinary text, so `a & b` survives untouched.
     */
    private fun entity(limit: Int) {
        val start = head
        val numeric = start + 1 < limit && text[start + 1] == '#'
        val hexadecimal = numeric && start + 2 < limit && (text[start + 2] == 'x' || text[start + 2] == 'X')
        val bodyStart = start + 1 + (if (numeric) 1 else 0) + (if (hexadecimal) 1 else 0)

        var semicolon = bodyStart
        while (semicolon < limit && text[semicolon].isLetterOrDigit()) semicolon++
        val closed = semicolon < limit && text[semicolon] == ';'
        if (!closed || !isValidEntity(bodyStart, semicolon, numeric, hexadecimal)) {
            emitChar()
            return
        }

        emit(Token.EntityStart)
        if (numeric) emit(Token.EntityNumeric)
        if (hexadecimal) emit(Token.EntityHex(text[start + 2].toString()))

        emitText(text.substring(bodyStart, semicolon))
        emit(Token.EntityEnd)
        head = semicolon + 1
    }

    private fun isValidEntity(from: Int, to: Int, numeric: Boolean, hexadecimal: Boolean): Boolean =
        when {
            to == from -> false
            hexadecimal -> (from until to).all { text[it].isDigit() || text[it].lowercaseChar() in 'a'..'f' }
            numeric -> (from until to).all { text[it] in '0'..'9' }
            else -> text.substring(from, to) in HtmlEntities.NAMES
        }

    private companion object {
        const val TEMPLATE_BRACES = 2
        const val ARGUMENT_BRACES = 3
        const val INITIAL_DEPTH = 32
        const val COMMENT_OPEN = "<!--"
        const val COMMENT_CLOSE = "-->"
        const val BOLD = "'''"
        const val ITALIC = "''"

        /** Line-start markers MediaWiki turns into list tags. */
        val LIST_MARKERS = mapOf('*' to "li", '#' to "li", ';' to "dt", ':' to "dd")

        /** Every character that begins a construct here, wherever it appears. */
        val MARKUP =
            BooleanArray(ASCII).apply {
                for (char in "{[<&'=") this[char.code] = true
                for (char in "*#;:") this[char.code] = true
            }
    }
}
