package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.ParseOptions

/**
 * Decides the structure of a page: which braces are a template, which `|` and `=` separate its parts, which
 * lines are headings, and where comments and extension tags begin and end.
 *
 * MediaWiki decides these before anything else and reads HTML tags, links, bold and external links later, in
 * the expanded text. That is why a `|` inside `<span>` or an external link splits a template parameter while
 * one inside `[[ ]]` does not, and why `{{t|[[x}}` is no template at all. The rules here follow
 * `Preprocessor_Hash::buildDomTreeArrayFromText` in the same order, including the cache that stops
 * `<foo><foo>…` from being quadratic.
 *
 * No tree is built: at the offset where each construct opens, this records what it is and where it ends, and
 * the tokenizer reads the page with those answers in hand.
 *
 * Two departures. `<includeonly>`, `<noinclude>` and `<onlyinclude>` are not preprocessor tags here: a bot
 * reads a template's source, where all three are wrappers to keep, not instructions to obey. And the body of
 * an extension tag is preprocessed only when the tokenizer asks, by calling [run] on it, since MediaWiki
 * leaves it to the extension and most extensions' bodies are not wikitext.
 */
internal class Preprocessor(private val text: String, private val options: ParseOptions) {

    /**
     * Where the construct opening at each offset ends, stored one higher so that zero means "nothing".
     *
     * At a template or argument's offset plus one, which is always one of its own opening braces, it holds
     * the index of its separators instead.
     */
    val ends = IntArray(text.length)

    /** What opens at each offset, one of the constants in the companion. */
    val kinds = ByteArray(text.length)

    /** Each template's separators: how many parts follow the name, then each part's `|` and its `=` or -1. */
    private var separators = IntArray(INITIAL_SEPARATORS)
    private var separatorsUsed = 0

    /**
     * The separators of every open piece, on one stack.
     *
     * Only the piece on top of the preprocessor's stack ever gains a part, and everything above a piece is
     * gone by the time it is on top again, so each piece's parts sit in one run above those of the pieces
     * under it. Sharing one array keeps a page of unclosed `{{a|` from allocating per opening.
     */
    private class PartStack {
        var values = IntArray(INITIAL_PARTS)
        var top = 0

        fun push(value: Int) {
            if (top == values.size) values = values.copyOf(top * 2)
            values[top++] = value
        }
    }

    /** One frame of the preprocessor's stack: an opening that has not closed yet. */
    private class Piece(
        var open: Int,
        var count: Int,
        var start: Int,
        var savedPrefix: Boolean,
        private val partStack: PartStack,
    ) {
        /** Where this piece's parts start on [partStack]: a `|` and an `=` or -1 for each after the name. */
        private val base = partStack.top

        var parts = 1

        /** Where the last comment in the current part ended, for a heading's closing run. */
        var commentEnd = -1

        /** Where the text before that comment ended. */
        var visualEnd = -1

        val findPipe: Boolean
            get() = open != HEADING_PIECE && open != BRACKET

        val findEquals: Boolean
            get() = findPipe && parts > 1 && equalsSign(parts - 1) == -1

        /** The `|` that starts part [part], counting the name as part 0. */
        fun pipe(part: Int): Int = partStack.values[base + 2 * (part - 1)]

        /** The `=` of part [part], or -1. */
        fun equalsSign(part: Int): Int = partStack.values[base + 2 * (part - 1) + 1]

        fun setEquals(at: Int) {
            partStack.values[base + 2 * (parts - 2) + 1] = at
        }

        fun addPart(pipe: Int) {
            partStack.push(pipe)
            partStack.push(-1)
            parts++
            commentEnd = -1
            visualEnd = -1
        }

        fun resetParts() {
            release()
            parts = 1
            commentEnd = -1
            visualEnd = -1
        }

        /** Gives this piece's parts back, when it leaves the top of the stack. */
        fun release() {
            partStack.top = base
        }
    }

    /** Where the construct opening at [offset] ends, given that one does. */
    fun end(offset: Int): Int = ends[offset] - 1

    /** The template or argument at [offset]'s separator record, or -1 if it has no parts. */
    fun separatorsOf(offset: Int): Int = ends[offset + 1] - 1

    /** How many parts follow the name in separator record [index]. */
    fun partCount(index: Int): Int = separators[index]

    /** The offset of part [part]'s `|`, counting from 0 for the first part after the name. */
    fun pipe(index: Int, part: Int): Int = separators[index + 1 + 2 * part]

    /** The offset of part [part]'s `=`, or -1 if it is positional. */
    fun equalsSign(index: Int, part: Int): Int = separators[index + 2 + 2 * part]

    /**
     * Preprocesses the text between [from] and [to] as a document of its own.
     *
     * Called once for the page, and again for the body of each extension tag whose body is wikitext, which is
     * how MediaWiki's extensions treat it: a fresh parse that starts at a line start.
     */
    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "NestedBlockDepth",
        "LoopWithTooManyJumpStatements",
    ) // Kept in the shape of buildDomTreeArrayFromText, whose rules it follows.
    fun run(from: Int, to: Int) {
        val stack = ArrayList<Piece>()
        val partStack = PartStack()
        var i = from
        var noMoreGT = false
        var noMoreClosingTag: ArrayList<String>? = null
        var fakeLineStart = true

        while (true) {
            val top = stack.lastOrNull()
            var found: Int
            var width = 1
            var rule = 0

            if (fakeLineStart) {
                found = LINE_START
            } else {
                while (i < to && !significant(text[i], top)) i++

                if (i >= to) {
                    if (top != null && top.open == HEADING_PIECE) found = LINE_END else break
                } else {
                    val char = text[i]
                    val next = if (i + 1 < to) text[i + 1] else NUL
                    found =
                        when {
                            char == '|' -> PIPE
                            char == '=' -> EQUALS
                            char == '<' -> ANGLE
                            char == '\n' -> if (top?.open == HEADING_PIECE) LINE_END else LINE_START
                            top?.open == CONVERSION && char == '}' && next == '-' -> {
                                width = 2
                                CLOSE
                            }
                            top != null && char == closerOf(top.open) -> CLOSE
                            char == '-' && next == '{' -> {
                                width = 2
                                rule = CONVERSION
                                OPEN
                            }
                            char == '{' -> {
                                rule = BRACE
                                OPEN
                            }
                            char == '[' -> {
                                rule = BRACKET
                                OPEN
                            }
                            else -> {
                                i++
                                continue
                            }
                        }
                }
            }

            when (found) {
                ANGLE -> {
                    if (i + COMMENT_OPEN.length <= to && text.startsWith(COMMENT_OPEN, i)) {
                        val result = comment(i, from, to, top)
                        i = result
                        if (fakeLineAfterComment) {
                            fakeLineAfterComment = false
                            fakeLineStart = true
                        }
                        continue
                    }

                    val nameEnd = extensionNameEnd(i + 1, to)
                    if (nameEnd < 0) {
                        i++
                        continue
                    }

                    val tagEnd = if (noMoreGT) -1 else indexOf('>', nameEnd, to)
                    if (tagEnd < 0) {
                        // MediaWiki stops looking for `>` once one search has failed, which keeps a page
                        // of `<ref` with no `>` linear.
                        noMoreGT = true
                        i++
                        continue
                    }

                    if (text[tagEnd - 1] == '/') {
                        record(i, tagEnd + 1, EXT_SHORT)
                        i = tagEnd + 1
                        continue
                    }

                    val known = noMoreClosingTag
                    val unclosed = known != null && known.any { failed -> sameName(failed, i + 1, nameEnd) }
                    val closed = if (unclosed) -1 else closingTagEnd(i + 1, nameEnd, tagEnd + 1, to)
                    if (closed < 0) {
                        // The opening tag is text, and is skipped whole: whatever is inside it is not
                        // looked at again. Remembered by name, as MediaWiki does. MediaWiki lower-cases
                        // the name; keeping it as written gives the same answers, since the closing tag
                        // is looked for ignoring case.
                        if (!unclosed) {
                            val name = text.substring(i + 1, nameEnd)
                            (known ?: ArrayList<String>().also { noMoreClosingTag = it }).add(name)
                        }
                        i = tagEnd + 1
                        continue
                    }

                    record(i, closed, EXT)
                    i = closed
                }

                LINE_START -> {
                    if (fakeLineStart) fakeLineStart = false else i++

                    val count = run('=', i, minOf(to, i + MAX_HEADING))
                    if (count == 1 && top != null && top.findEquals) {
                        // MediaWiki's own heuristic: a lone `=` at the start of a line, in a template part
                        // with no `=` yet, is left to name the parameter rather than open a heading.
                    } else if (count > 0) {
                        stack += Piece(HEADING_PIECE, count, i, savedPrefix = false, partStack)
                        i += count
                    }
                }

                LINE_END -> {
                    val piece = top!!
                    var searchStart = i - runBack(' ', '\t', i, from)
                    if (piece.commentEnd != -1 && searchStart - 1 == piece.commentEnd) {
                        // A comment at the end of the line: the closing run is the one before it.
                        searchStart = piece.visualEnd
                        searchStart -= runBack(' ', '\t', searchStart, from)
                    }

                    val equalsLength = runBack('=', '=', searchStart, from)
                    if (equalsLength > 0) {
                        val level =
                            if (searchStart - equalsLength == piece.start) {
                                // A line of nothing but equals signs, read as `handleHeadings` reads it.
                                if (equalsLength < MIN_EQUALS_LINE) 0
                                else minOf(MAX_HEADING, (equalsLength - 1) / 2)
                            } else {
                                minOf(equalsLength, piece.count)
                            }
                        if (level > 0) record(piece.start, searchStart, (HEADING + level).toByte())
                    }

                    stack.removeAt(stack.lastIndex).release()
                    // The line break is not consumed: it may open the next heading.
                }

                OPEN -> {
                    var count = if (width > 1) run('{', i + 1, to) + 1 else run(text[i], i, to)
                    var savedPrefix = false
                    if (rule == CONVERSION && count > width) {
                        // `-{{` is a template after a hyphen, because the rightmost opening wins.
                        savedPrefix = true
                        i++
                        rule = BRACE
                        count--
                    }

                    if (count >= MIN_OPENING) stack += Piece(rule, count, i, savedPrefix, partStack)
                    i += count
                }

                CLOSE -> {
                    val piece = top!!
                    val count = if (width > 1) width else run(text[i], i, minOf(to, i + piece.count))
                    val max = if (piece.open == BRACE) ARGUMENT_BRACES else MIN_OPENING

                    var matching = if (count > max) max else count
                    while (matching > 0 && !names(piece.open, matching)) matching--
                    if (matching <= 0) {
                        i += count
                        continue
                    }

                    val opensAt = piece.start + piece.count - matching
                    when (piece.open) {
                        BRACE -> recordTemplate(piece, opensAt, i + matching, matching)
                        BRACKET -> record(opensAt, i + matching, LINK)
                    }
                    i += matching

                    stack.removeAt(stack.lastIndex).release()
                    if (matching < piece.count) {
                        piece.resetParts()
                        piece.count -= matching
                        if (piece.count >= MIN_OPENING) {
                            stack += piece
                        } else if (piece.count == 1 && piece.open == BRACE && piece.savedPrefix) {
                            // In `-{{{x}}` the template takes two braces and leaves `-{`, which opens a
                            // language conversion after all.
                            piece.savedPrefix = false
                            piece.open = CONVERSION
                            piece.count = 2
                            piece.start--
                            stack += piece
                        }
                    }
                }

                PIPE -> {
                    top!!.addPart(i)
                    i++
                }

                EQUALS -> {
                    top!!.setEquals(i)
                    i++
                }
            }
        }
        // Whatever is still open never closed, and is text: nothing was recorded for it.
    }

    /** Whether [char] can change anything given what is open, which is MediaWiki's search set. */
    private fun significant(char: Char, top: Piece?): Boolean {
        if (char.code >= ASCII || !CANDIDATES[char.code]) return false
        return when (char) {
            '[',
            '{',
            '<',
            '\n' -> true
            '-' -> options.languageConversion || top?.open == CONVERSION
            '}',
            ']' -> closesOn(char, top)
            '|' -> top?.findPipe == true
            '=' -> top?.findEquals == true
            else -> false
        }
    }

    /** Whether [char] can close what is open: `}` a template or a conversion, `]` a link. */
    private fun closesOn(char: Char, top: Piece?): Boolean =
        when (top?.open) {
            BRACE,
            CONVERSION -> char == '}'
            BRACKET -> char == ']'
            else -> false
        }

    /** Set by [comment] when it swallowed a whole line, so a heading may start right after it. */
    private var fakeLineAfterComment = false

    /**
     * Records a comment starting at [start] and returns where to carry on.
     *
     * A comment alone on its line takes the line break with it, so the line after it is a line start; and a
     * run of comments separated by spaces or tabs is taken together. Both matter only for headings.
     */
    private fun comment(start: Int, from: Int, to: Int, top: Piece?): Int {
        val close = indexOf(COMMENT_CLOSE, start + COMMENT_OPEN.length, to)
        if (close < 0) {
            // An unclosed comment runs to the end of the text.
            record(start, to, COMMENT_OPEN_ENDED)
            return to
        }

        val wsStart = start - runBack(' ', '\t', start, from)
        val firstClose = close + COMMENT_CLOSE.length
        var wsEnd = close + 2 + run(' ', '\t', firstClose, to)
        record(start, firstClose, COMMENT)

        // The comments that follow, separated by spaces, are recorded as they are found. If they turn out
        // not to fill the line, each is found again on its own later and recorded the same way.
        while (wsEnd + 1 + COMMENT_OPEN.length <= to && text.startsWith(COMMENT_OPEN, wsEnd + 1)) {
            val next = indexOf(COMMENT_CLOSE, wsEnd + 1 + COMMENT_OPEN.length, to)
            if (next < 0) break
            record(wsEnd + 1, next + COMMENT_CLOSE.length, COMMENT)
            wsEnd = next + 2 + run(' ', '\t', next + COMMENT_CLOSE.length, to)
        }

        val endPos: Int
        if (fillsLine(wsStart, wsEnd, from, to)) {
            // The comments fill their line, which disappears with them.
            fakeLineAfterComment = true
            endPos = wsEnd + 1
        } else {
            endPos = firstClose - 1
        }

        if (top != null) {
            if (top.commentEnd != wsStart - 1) top.visualEnd = wsStart
            top.commentEnd = endPos
        }
        return endPos + 1
    }

    /** Whether comments from [wsStart] to [wsEnd], spaces included, are all their line holds. */
    private fun fillsLine(wsStart: Int, wsEnd: Int, from: Int, to: Int): Boolean {
        val newlineBefore = wsStart > from && text[wsStart - 1] == '\n'
        return newlineBefore && wsEnd + 1 < to && text[wsEnd + 1] == '\n'
    }

    /**
     * The end of an extension tag's name starting at [from], or -1 if no extension tag starts there.
     *
     * The name has to be one the wiki has, followed by whitespace, `/>` or `>`, as MediaWiki's regex says.
     */
    private fun extensionNameEnd(from: Int, to: Int): Int {
        var i = from
        while (i < to && !endsTagName(text[i])) i++
        if (i == from || i - from > MAX_TAG_NAME || i >= to) return -1
        if (text[i] == '/' && (i + 1 >= to || text[i + 1] != '>')) return -1
        if (!text[from].isLetter()) return -1

        return if (options.extensionTagNames.contains(text, from, i)) i else -1
    }

    /**
     * The end of the first `</name\s*>` after [from], or -1, where the name is the text between [nameStart]
     * and [nameEnd], compared ignoring case.
     */
    private fun closingTagEnd(nameStart: Int, nameEnd: Int, from: Int, to: Int): Int {
        val length = nameEnd - nameStart
        var at = indexOf("</", from, to)
        while (at >= 0) {
            val afterName = at + 2 + length
            if (afterName <= to && text.regionMatches(at + 2, text, nameStart, length, ignoreCase = true)) {
                var i = afterName
                while (i < to && isSpace(text[i])) i++
                if (i < to && text[i] == '>') return i + 1
            }
            at = indexOf("</", at + 1, to)
        }
        return -1
    }

    /** Whether [name] is the text between [from] and [to], case and all. */
    private fun sameName(name: String, from: Int, to: Int): Boolean =
        name.length == to - from && text.regionMatches(from, name, 0, name.length)

    private fun recordTemplate(piece: Piece, opensAt: Int, end: Int, braces: Int) {
        record(opensAt, end, if (braces == ARGUMENT_BRACES) ARGUMENT else TEMPLATE)
        if (piece.parts == 1) return

        val needed = 1 + 2 * (piece.parts - 1)
        if (separatorsUsed + needed > separators.size) {
            separators = separators.copyOf(maxOf(separators.size * 2, separatorsUsed + needed))
        }
        val index = separatorsUsed
        separators[index] = piece.parts - 1
        for (part in 1 until piece.parts) {
            separators[index + 2 * part - 1] = piece.pipe(part)
            separators[index + 2 * part] = piece.equalsSign(part)
        }
        separatorsUsed += needed
        ends[opensAt + 1] = index + 1
    }

    private fun record(start: Int, end: Int, kind: Byte) {
        ends[start] = end + 1
        kinds[start] = kind
    }

    private fun run(char: Char, from: Int, to: Int): Int {
        var i = from
        while (i < to && text[i] == char) i++
        return i - from
    }

    private fun run(first: Char, second: Char, from: Int, to: Int): Int {
        var i = from
        while (i < to && (text[i] == first || text[i] == second)) i++
        return i - from
    }

    /** How many of [first] or [second] end just before [before], looking no further back than [floor]. */
    private fun runBack(first: Char, second: Char, before: Int, floor: Int): Int {
        var i = before
        while (i > floor && (text[i - 1] == first || text[i - 1] == second)) i--
        return before - i
    }

    private fun indexOf(char: Char, from: Int, to: Int): Int {
        val at = text.indexOf(char, from)
        return if (at in 0 until to) at else -1
    }

    private fun indexOf(string: String, from: Int, to: Int): Int {
        val at = text.indexOf(string, from)
        return if (at >= 0 && at + string.length <= to) at else -1
    }

    internal companion object {
        // What the preprocessor found at the offset it is looking at.
        private const val PIPE = 1
        private const val EQUALS = 2
        private const val ANGLE = 3
        private const val LINE_START = 4
        private const val LINE_END = 5
        private const val OPEN = 6
        private const val CLOSE = 7

        // The kinds of opening the stack holds.
        private const val BRACE = 1
        private const val BRACKET = 2
        private const val CONVERSION = 3
        private const val HEADING_PIECE = 4

        // What [kinds] records at an offset.
        const val TEMPLATE: Byte = 1
        const val ARGUMENT: Byte = 2

        /** A `[[` the preprocessor closed, not yet checked against MediaWiki's link rules. */
        const val LINK: Byte = 3
        const val LINK_VALID: Byte = 4
        const val LINK_INVALID: Byte = 5
        const val COMMENT: Byte = 6

        /** A comment with no `-->`, which runs to the end of its document. */
        const val COMMENT_OPEN_ENDED: Byte = 7
        const val EXT: Byte = 8

        /** An extension tag written `<ref … />`. */
        const val EXT_SHORT: Byte = 9

        /** An HTML tag paired with its closing tag, recorded by the tokenizer rather than here. */
        const val HTML_PAIRED: Byte = 10

        /** A heading of level n is recorded as HEADING + n. */
        const val HEADING: Int = 10

        private const val MIN_OPENING = 2
        private const val ARGUMENT_BRACES = 3
        private const val MAX_HEADING = 6
        private const val MIN_EQUALS_LINE = 3
        private const val MAX_TAG_NAME = 32
        private const val INITIAL_PARTS = 32
        private const val INITIAL_SEPARATORS = 64
        private const val ASCII = 128
        private const val NUL = '\u0000'

        private const val COMMENT_OPEN = "<!--"
        private const val COMMENT_CLOSE = "-->"

        /** The characters MediaWiki's search set can ever hold. */
        private val CANDIDATES =
            BooleanArray(ASCII).apply { for (char in "[{<\n-}]|=") this[char.code] = true }

        /** Whether [kind] is a heading, and so carries its level. */
        fun isHeading(kind: Byte): Boolean = kind > HEADING && kind <= HEADING + MAX_HEADING

        /**
         * Whether an opening of [open] closes on [count] characters: two or three braces, two of anything
         * else.
         */
        private fun names(open: Int, count: Int): Boolean =
            count == MIN_OPENING || (open == BRACE && count == ARGUMENT_BRACES)

        private fun closerOf(open: Int): Char =
            when (open) {
                BRACE -> '}'
                BRACKET -> ']'
                else -> NUL
            }

        /** What ends a tag name for MediaWiki: whitespace, `/` or `>`. */
        private fun endsTagName(char: Char): Boolean = isSpace(char) || char == '/' || char == '>'

        /** PCRE's `\s`. */
        fun isSpace(char: Char): Boolean =
            char == ' ' ||
                char == '\t' ||
                char == '\n' ||
                char == '\r' ||
                char == '\u000B' ||
                char == '\u000C'
    }
}
