package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.Token
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.A
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.ARGUMENT_FRAME
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.B
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.BRACKETED_FRAME
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.C
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.CONTENT
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.D
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.DONE
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.E
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.EXTENSION_BODY
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.EXTENSION_DOCUMENT
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.EXTENSION_FRAME
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.EXTENSION_LINES
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.EXTENSION_LINE_READ
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.FREE_FRAME
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.HEADING_FRAME
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.KEY_READ
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.KIND
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.LIMIT
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.MODE
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.NONE
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.PAGE
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.PART_READ
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.PREPROCESSED
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.SEGMENT
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.STYLE_FRAME
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.TAG_CONTENTS
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.TAG_FRAME
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.TEMPLATE_FRAME
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.WIKILINK_FRAME
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.ARGUMENT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.COMMENT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.COMMENT_OPEN_ENDED
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.EXT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.EXT_SHORT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.HEADING
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.HTML_PAIRED
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.TEMPLATE
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.APOSTROPHE
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.LINK_BRACKETS
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.isLink

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
 *
 * The constructs being read are kept in [Frames] rather than on the thread's stack. Opening a construct emits
 * its opening tokens and puts its frame on top, set to read its first part, and the content around it stops
 * there. The frame then reads its parts in turn, emits its closing tokens and comes off, and the content
 * around it goes on where it stopped. The tokens come out in the order recursion would give them, and a page
 * nested a hundred thousand deep needs no more stack than a flat one.
 */
internal class Tokenizer(options: ParseOptions = ParseOptions.DEFAULT) : TokenReader(options) {

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
        frames.clear()

        val page = frames.push(PAGE)
        next(page, DONE)
        startPart(page, SEGMENT, wikitext.length)
        run()
        flush()
        return tokens.toList()
    }

    /** Takes the steps of the innermost construct until none is left. */
    private fun run() {
        while (frames.depth > 0) step()
    }

    /** Reads on in the innermost construct, and takes its next steps once the part it is reading is done. */
    private fun step() {
        val frame = frames.top
        val mode = frames[frame, MODE]
        val limit = frames[frame, LIMIT]
        val done =
            when (mode) {
                NONE -> true
                PREPROCESSED -> preprocessedOnly(limit)
                else -> content(limit)
            }
        if (done) {
            if (mode == SEGMENT) segmentDepth--
            frames[frame, MODE] = NONE
            resume(frame)
        }
    }

    /** Takes the next steps of the construct in [frame], whose last part has been read. */
    private fun resume(frame: Int) {
        when (frames[frame, KIND]) {
            PAGE -> page()
            TEMPLATE_FRAME -> template(frame)
            ARGUMENT_FRAME -> argument(frame)
            WIKILINK_FRAME -> wikilink(frame)
            BRACKETED_FRAME -> bracketedLink(frame)
            FREE_FRAME -> freeLink()
            EXTENSION_FRAME -> extensionTag(frame)
            TAG_FRAME -> tag(frame)
            STYLE_FRAME -> style(frame)
            else -> heading(frame)
        }
    }

    /** The page has been read. */
    private fun page() {
        frames.pop()
    }

    // ------------------------------------------------------------------ content

    /**
     * Consumes content up to [limit], which is always where the enclosing construct's closing markup starts,
     * and says whether it got there: it stops before, with the construct's frame on top, at a construct that
     * holds content of its own.
     *
     * Every construct found here is known to close by [limit] before it is parsed, so nested constructs are
     * read once each and the cursor ends at [limit].
     */
    @Suppress("CyclomaticComplexMethod") // A tokenizer's dispatch is a table; splitting it hides it.
    override fun content(limit: Int): Boolean {
        val depth = frames.depth
        while (head < limit) {
            skipPlainText(limit)
            if (head >= limit) return true

            val char = text[head]
            when {
                char == '{' -> braces(limit)
                char == '[' -> brackets(limit)
                char == '<' -> angle(limit)
                char == '&' -> entity(limit)
                char == APOSTROPHE && head + 1 < limit && text[head + 1] == APOSTROPHE -> openStyle(limit)
                char == '=' && Preprocessor.isHeading(kinds[head]) && pp.end(head) <= limit -> openHeading()
                char in LIST_MARKERS && atLineStart() && listAllowed() -> listMarker(char)
                else -> if (!openFreeLink(limit)) emitChar()
            }
            if (frames.depth > depth) return false
        }
        return true
    }

    /**
     * Content with only the preprocessor's constructs in it, for attributes and URLs, read as [content] is.
     *
     * MediaWiki escapes everything else it finds in an attribute value, and a URL is read whole, so a
     * template or a comment is all that can be structure there.
     */
    override fun preprocessedOnly(limit: Int): Boolean {
        val depth = frames.depth
        while (head < limit) {
            val kind = kinds[head]
            when {
                pp.end(head) > limit -> emitChar()
                kind == TEMPLATE || kind == ARGUMENT -> braces(limit)
                kind == COMMENT || kind == COMMENT_OPEN_ENDED -> comment()
                kind == EXT || kind == EXT_SHORT -> openExtensionTag()
                else -> emitChar()
            }
            if (frames.depth > depth) return false
        }
        return true
    }

    // ------------------------------------------------------------------ templates and arguments

    /** Opens whatever a `{` opens, or emits it as the text it is. */
    private fun braces(limit: Int) {
        val kind = kinds[head]
        val end = pp.end(head)
        when {
            (kind != TEMPLATE && kind != ARGUMENT) || end > limit -> emitChar()
            kind == ARGUMENT -> openArgument(end)
            else -> openTemplate(end)
        }
    }

    /** Opens `{{name|params}}`, which ends at [end], and starts on its name. */
    private fun openTemplate(end: Int) {
        val start = head
        val separators = pp.separatorsOf(start)
        emit(Token.TemplateOpen)
        head = start + TEMPLATE_BRACES
        val frame = frames.push(TEMPLATE_FRAME, a = end, b = separators, c = -1)
        next(frame, PART_READ)
        val parts = if (separators < 0) 0 else pp.partCount(separators)
        startPart(frame, SEGMENT, if (parts > 0) pp.pipe(separators, 0) else end - TEMPLATE_BRACES)
    }

    /** Reads the rest of `{{name|params}}`, splitting it where the preprocessor did. */
    private fun template(frame: Int) {
        val end = frames[frame, A]
        val separators = frames[frame, B]
        val inner = end - TEMPLATE_BRACES
        val parts = if (separators < 0) 0 else pp.partCount(separators)
        while (true) {
            when (step(frame)) {
                PART_READ -> {
                    val part = frames[frame, C] + 1
                    if (part == parts) {
                        head = end
                        emit(Token.TemplateClose)
                        frames.pop()
                        return
                    }
                    frames[frame, C] = part
                    emit(Token.ParameterSeparator)
                    head = pp.pipe(separators, part) + 1
                    val equals = pp.equalsSign(separators, part)
                    next(frame, if (equals >= 0) KEY_READ else PART_READ)
                    val partEnd = if (equals >= 0) equals else partEnd(separators, part, parts, inner)
                    if (!read(frame, SEGMENT, partEnd)) return
                }
                else -> {
                    val part = frames[frame, C]
                    emit(Token.ParameterEquals)
                    head = pp.equalsSign(separators, part) + 1
                    next(frame, PART_READ)
                    if (!read(frame, SEGMENT, partEnd(separators, part, parts, inner))) return
                }
            }
        }
    }

    /** Where part [part] of a template ends: at the next `|`, or at the closing braces. */
    private fun partEnd(separators: Int, part: Int, parts: Int, inner: Int): Int =
        if (part + 1 < parts) pp.pipe(separators, part + 1) else inner

    /**
     * Opens `{{{name|default}}}`, which ends at [end], and starts on its name.
     *
     * An argument takes only one default. Anything after a second `|` is still part of the default as far as
     * the page's text goes, so it is kept there as text.
     */
    private fun openArgument(end: Int) {
        val start = head
        val separators = pp.separatorsOf(start)
        emit(Token.ArgumentOpen)
        head = start + ARGUMENT_BRACES
        val frame = frames.push(ARGUMENT_FRAME, a = end, b = separators)
        next(frame, if (separators < 0) DONE else PART_READ)
        startPart(frame, SEGMENT, if (separators < 0) end - ARGUMENT_BRACES else pp.pipe(separators, 0))
    }

    /** Reads the rest of the argument in [frame]. */
    private fun argument(frame: Int) {
        val end = frames[frame, A]
        val separators = frames[frame, B]
        val inner = end - ARGUMENT_BRACES
        if (step(frame) == PART_READ) {
            emit(Token.ArgumentSeparator)
            head = pp.pipe(separators, 0) + 1
            next(frame, DONE)
            if (!read(frame, SEGMENT, inner)) return
        }
        head = end
        emit(Token.ArgumentClose)
        frames.pop()
    }

    // ------------------------------------------------------------------ links

    /** Opens whatever a `[` opens, or emits it as the text it is. */
    private fun brackets(limit: Int) {
        val link = head + 1 < limit && text[head + 1] == '[' && isLink(kinds[head])
        val end = if (link) scan.linkEnd(head) else -1
        when {
            end in 0..limit -> openWikilink(end)
            scan.bracketedLinkAt(head, limit) -> openBracketedLink()
            else -> emitChar()
        }
    }

    /** Opens `[[target|text]]`, whose closing brackets end at [end], and starts on its target. */
    private fun openWikilink(end: Int) {
        val start = head
        val pipe = scan.linkPipe(start, end - LINK_BRACKETS)
        emit(Token.WikiLinkOpen)
        head = start + LINK_BRACKETS
        val frame = frames.push(WIKILINK_FRAME, a = end, b = pipe)
        next(frame, if (pipe < 0) DONE else PART_READ)
        startPart(frame, SEGMENT, if (pipe < 0) end - LINK_BRACKETS else pipe)
    }

    /** Reads the rest of the link in [frame], whose closing brackets end at [A], with its `|` at [B]. */
    private fun wikilink(frame: Int) {
        val end = frames[frame, A]
        val pipe = frames[frame, B]
        if (step(frame) == PART_READ) {
            emit(Token.WikiLinkSeparator)
            head = pipe + 1
            next(frame, DONE)
            if (!read(frame, SEGMENT, end - LINK_BRACKETS)) return
        }
        head = end
        emit(Token.WikiLinkClose)
        frames.pop()
    }

    /** Opens the `[url text]` [Scanner.bracketedLinkAt] just found, and starts on its URL. */
    private fun openBracketedLink() {
        val urlEnd = scan.urlEnd
        val frame = frames.push(BRACKETED_FRAME, a = urlEnd, b = scan.labelStart, c = scan.labelEnd)
        emit(Token.ExternalLinkOpen(brackets = true))
        head++
        next(frame, PART_READ)
        startPart(frame, PREPROCESSED, urlEnd)
    }

    /** Reads the rest of the bracketed link in [frame], its URL ending at [A] and its text at [C]. */
    private fun bracketedLink(frame: Int) {
        val urlEnd = frames[frame, A]
        val close = frames[frame, C]
        if (step(frame) == PART_READ && close > urlEnd) {
            val labelStart = frames[frame, B]
            emit(Token.ExternalLinkSeparator(text.substring(urlEnd, labelStart)))
            head = labelStart
            next(frame, DONE)
            if (!read(frame, CONTENT, close)) return
        }
        head = close + 1
        emit(Token.ExternalLinkClose)
        frames.pop()
    }

    /**
     * Opens a URL appearing bare in running text, such as `https://example.org`, or reports there is none.
     */
    private fun openFreeLink(limit: Int): Boolean {
        val end = scan.freeLinkEnd(head, limit)
        if (end < 0) return false
        emit(Token.ExternalLinkOpen(brackets = false))
        val frame = frames.push(FREE_FRAME)
        next(frame, DONE)
        startPart(frame, PREPROCESSED, end)
        return true
    }

    /** The bare URL has been read. */
    private fun freeLink() {
        emit(Token.ExternalLinkClose)
        frames.pop()
    }

    // ------------------------------------------------------------------ comments and tags

    /** Opens whatever a `<` opens, or emits it as the text it is. */
    private fun angle(limit: Int) {
        val kind = kinds[head]
        val fits = pp.end(head) <= limit
        when {
            fits && (kind == COMMENT || kind == COMMENT_OPEN_ENDED) -> comment()
            fits && (kind == EXT || kind == EXT_SHORT) -> openExtensionTag()
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

    /** Emits the start of the extension tag the preprocessor found at the cursor, and opens it. */
    private fun openExtensionTag() {
        val start = head
        val nameEnd = extensionNameEnd(start)
        val tagEnd = text.indexOf('>', nameEnd)
        val short = kinds[start] == EXT_SHORT

        emit(Token.OpeningTagStart())
        emitText(text.substring(start + 1, nameEnd))
        head = nameEnd
        val frame = frames.push(EXTENSION_FRAME, a = start, b = if (short) tagEnd - 1 else tagEnd)
        openExtensionBody(frame, start, pp.end(start))
    }

    private fun extensionNameEnd(start: Int): Int {
        var nameEnd = start + 1
        while (!Preprocessor.isSpace(text[nameEnd]) && text[nameEnd] != '/' && text[nameEnd] != '>') nameEnd++
        return nameEnd
    }

    /**
     * Reads the rest of the extension tag in [frame], starting at [A], once its body has been read.
     *
     * Its attributes are raw: the preprocessor does not look inside them, so nothing there is a template. Its
     * body is wikitext only for the tags [ParseOptions.parsedTags] names, and is then read as a document of
     * its own; any other body is kept as written.
     */
    private fun extensionTag(frame: Int) {
        val end = pp.end(frames[frame, A])
        when (step(frame)) {
            EXTENSION_DOCUMENT -> scan.documentStart = frames[frame, D]
            EXTENSION_BODY -> Unit
            else -> if (!pageListBody(frame)) return
        }
        closingTag(frames[frame, E], end)
        frames.pop()
    }

    /**
     * Reads the attributes of the extension tag in [frame], whose attributes end at [B], and starts on its
     * body, or finishes the tag when it has none to read.
     */
    private fun openExtensionBody(frame: Int, start: Int, end: Int) {
        attributes(frame, raw = true)
        val padding = text.substring(frames[frame, D], head)
        val tagEnd = frames[frame, B]
        if (kinds[start] == EXT_SHORT) {
            emit(Token.SelfClosingTagEnd(padding))
            head = end
            frames.pop()
            return
        }

        val nameEnd = extensionNameEnd(start)
        val name = text.substring(start + 1, nameEnd)
        val closerStart = text.lastIndexOf('<', end - 1)
        val body = bodyOf(name, nameEnd, tagEnd)
        emit(Token.OpeningTagEnd(padding, verbatim = body == Body.VERBATIM))
        head = tagEnd + 1
        frames[frame, E] = closerStart

        when (body) {
            Body.VERBATIM -> {
                emitSpan(head, closerStart)
                closingTag(closerStart, end)
                frames.pop()
            }
            Body.WIKITEXT -> {
                pp.run(head, closerStart)
                frames[frame, D] = scan.documentStart
                scan.documentStart = head
                next(frame, EXTENSION_DOCUMENT)
                startPart(frame, SEGMENT, closerStart, modeOf(name))
            }
            Body.PREPROCESSED -> {
                pp.run(head, closerStart)
                next(frame, EXTENSION_BODY)
                startPart(frame, PREPROCESSED, closerStart)
            }
            Body.PAGE_LIST -> next(frame, EXTENSION_LINES)
        }
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
     * A `<DynamicPageList>` body up to the tag's closing tag, read as the extension reads it: line by line,
     * each split at its first `=`, with only the values of [TagNames.PAGE_LIST_EXPANDED] keys expanded, each
     * on its own. Says whether it got to the end; the line being read ends at [D].
     */
    private fun pageListBody(frame: Int): Boolean {
        val limit = frames[frame, E]
        if (step(frame) == EXTENSION_LINE_READ) endLine(frame, limit)
        while (head < limit) {
            val lineEnd = text.indexOf('\n', head).let { if (it < 0 || it > limit) limit else it }
            val equals = text.indexOf('=', head).takeIf { it in head until lineEnd }
            if (equals == null || text.substring(head, equals).trim() !in TagNames.PAGE_LIST_EXPANDED) {
                emitSpan(head, minOf(lineEnd + 1, limit))
            } else {
                frames[frame, D] = lineEnd
                if (!expandValue(frame, equals, lineEnd)) return false
                endLine(frame, limit)
            }
        }
        return true
    }

    /** Reads the value after the `=` at [equals] on a line ending at [lineEnd], on its own. */
    private fun expandValue(frame: Int, equals: Int, lineEnd: Int): Boolean {
        var valueStart = equals + 1
        while (valueStart < lineEnd && text[valueStart].isWhitespace()) valueStart++
        var valueEnd = lineEnd
        while (valueEnd > valueStart && text[valueEnd - 1].isWhitespace()) valueEnd--
        emitSpan(head, valueStart)
        pp.run(valueStart, valueEnd)
        next(frame, EXTENSION_LINE_READ)
        return read(frame, PREPROCESSED, valueEnd)
    }

    /** Takes the rest of the line ending at [D], its line break included, as written. */
    private fun endLine(frame: Int, limit: Int) {
        emitSpan(head, minOf(frames[frame, D] + 1, limit))
        next(frame, EXTENSION_LINES)
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

    /**
     * Opens an HTML tag, or emits its `<` as text.
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
        val flags = (if (selfClosing) SELF_CLOSING else 0) or (if (paired) PAIRED else 0)
        tag(frames.push(TAG_FRAME, a = start, b = if (selfClosing) end - 1 else end, c = flags))
    }

    /**
     * Reads the attributes of the HTML tag in [frame] and starts on its contents when it is paired, or, once
     * they have been read, closes it.
     */
    private fun tag(frame: Int) {
        val start = frames[frame, A]
        if (step(frame) == TAG_CONTENTS) {
            closingTag(frames[frame, E], pp.end(start))
            frames.pop()
            return
        }
        if (!attributes(frame, raw = false)) return
        val padding = text.substring(frames[frame, D], head)
        val flags = frames[frame, C]
        val selfClosing = flags and SELF_CLOSING != 0
        val end = frames[frame, B] + if (selfClosing) 1 else 0
        if (flags and PAIRED == 0) {
            emit(Token.SelfClosingTagEnd(padding, implicit = !selfClosing))
            head = end + 1
            frames.pop()
            return
        }
        emit(Token.OpeningTagEnd(padding))
        head = end + 1
        val closerStart = text.lastIndexOf('<', pp.end(start) - 1)
        frames[frame, E] = closerStart
        next(frame, TAG_CONTENTS)
        startPart(frame, CONTENT, closerStart)
    }

    // ------------------------------------------------------------------ styles and lists

    /**
     * Opens `''italic''` and `'''bold'''`, or emits the apostrophes as text.
     *
     * The partner is looked for on the same line and at the same level, by [Scanner.styleCloser].
     */
    private fun openStyle(limit: Int) {
        val length = if (head + 2 < limit && text[head + 2] == APOSTROPHE) BOLD.length else ITALIC.length
        val markup = if (length == BOLD.length) BOLD else ITALIC
        val closing = scan.styleCloser(head + length, limit, markup)

        if (closing < 0) {
            emitText(markup)
            head += length
            return
        }
        head += length
        emit(Token.OpeningTagStart(wikiMarkup = markup))
        emitText(if (markup == BOLD) "b" else "i")
        emit(Token.OpeningTagEnd())
        val frame = frames.push(STYLE_FRAME, a = closing, b = length)
        next(frame, DONE)
        startPart(frame, CONTENT, closing)
    }

    /** Closes the bold or italic in [frame], as long as [B], whose partner is at [A]. */
    private fun style(frame: Int) {
        val closing = frames[frame, A]
        val markup = if (frames[frame, B] == BOLD.length) BOLD else ITALIC
        val name = if (markup == BOLD) "b" else "i"
        head = closing + markup.length
        emit(Token.ClosingTagStart)
        emitText(name)
        emit(Token.ClosingTagEnd)
        frames.pop()
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
     * Opens the heading the preprocessor found at the cursor, and starts on its text.
     *
     * Its level is the shorter of its two runs of `=`, capped at six, and any surplus on either side is part
     * of its text, which is how `===a==` is a level-two heading reading `=a`. Whatever follows the closing
     * run on the line, spaces or a comment, is outside the heading.
     */
    private fun openHeading() {
        val start = head
        val level = kinds[start] - HEADING
        val end = pp.end(start)
        emit(Token.HeadingStart(level))
        head = start + level
        val frame = frames.push(HEADING_FRAME, a = end)
        next(frame, DONE)
        startPart(frame, SEGMENT, end - level)
    }

    /** Closes the heading in [frame], which ends at [A]. */
    private fun heading(frame: Int) {
        val end = frames[frame, A]
        head = end
        emit(Token.HeadingEnd)
        frames.pop()
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
        const val COMMENT_OPEN = "<!--"
        const val COMMENT_CLOSE = "-->"
        const val BOLD = "'''"
        const val ITALIC = "''"

        /** What an HTML tag's frame keeps about it in [C]. */
        const val SELF_CLOSING = 1
        const val PAIRED = 2

        /** Line-start markers MediaWiki turns into list tags. */
        val LIST_MARKERS = mapOf('*' to "li", '#' to "li", ';' to "dt", ':' to "dd")
    }
}
