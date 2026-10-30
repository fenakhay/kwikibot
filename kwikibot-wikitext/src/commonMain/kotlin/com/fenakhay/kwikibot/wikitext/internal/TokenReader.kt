package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.Token
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.ATTRIBUTE_NAME_READ
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.ATTRIBUTE_NEXT
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.ATTRIBUTE_READ
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.B
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.D
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.E
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.LIMIT
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.MODE
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.NONE
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.PREPROCESSED
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.SEGMENT
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.START
import com.fenakhay.kwikibot.wikitext.internal.Frames.Companion.STEP
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.APOSTROPHE
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.ASCII
import com.fenakhay.kwikibot.wikitext.internal.Scanner.Companion.isWordChar

/**
 * What every construct the [Tokenizer] reads shares: the cursor over the text and the tokens written so far,
 * the frames of the constructs being read and the segments they are read in, reading one part of a construct,
 * reading a tag's attributes, and running over plain text.
 *
 * The loops that run over the text character by character live here, beside the cursor they move.
 */
internal abstract class TokenReader(protected val options: ParseOptions) {

    protected var text: String = ""

    protected var pp: Preprocessor = Preprocessor("", options)

    protected var scan: Scanner = Scanner("", pp, options)

    protected var kinds: ByteArray = ByteArray(0)

    protected var head: Int = 0

    protected val tokens = ArrayList<Token>()

    protected val buffer = StringBuilder()

    protected val frames = Frames()

    /**
     * Reads a part of [frame]'s construct up to [limit] in [mode], and says whether it got there; when it did
     * not, a construct inside the part is on top, and the part is read on once that is done.
     */
    protected fun read(frame: Int, mode: Int, limit: Int): Boolean {
        startPart(frame, mode, limit)
        val done = if (mode == PREPROCESSED) preprocessedOnly(limit) else content(limit)
        if (done) {
            if (mode == SEGMENT) segmentDepth--
            frames[frame, MODE] = NONE
        }
        return done
    }

    /**
     * Starts on a part of [frame]'s construct up to [limit] in [mode], read as [body] when it is a segment of
     * its own, without reading any of it: it is read once [frame] is on top.
     */
    protected fun startPart(frame: Int, mode: Int, limit: Int, body: BodyMode = BodyMode.PAGE) {
        frames[frame, MODE] = mode
        frames[frame, LIMIT] = limit
        if (mode == SEGMENT) enterSegment(limit, body)
    }

    protected fun step(frame: Int): Int = frames[frame, STEP]

    protected fun next(frame: Int, step: Int) {
        frames[frame, STEP] = step
    }

    /** Moves buffered literal text into the token list, keeping token order. */
    protected fun flush() {
        if (buffer.isNotEmpty()) {
            tokens += Token.Text(buffer.toString())
            buffer.setLength(0)
        }
    }

    protected fun emit(token: Token) {
        flush()
        tokens += token
    }

    protected fun emitText(value: String) {
        buffer.append(value)
    }

    /** Buffers the source between two offsets and leaves the cursor after it. */
    protected fun emitSpan(from: Int, to: Int) {
        if (to > from) buffer.append(text, from, to)
        head = maxOf(head, to)
    }

    protected fun emitChar() {
        buffer.append(text[head])
        head++
    }

    protected fun atLineStart(): Boolean = head == scan.documentStart || text[head - 1] == '\n'

    // The segments being read, innermost last: where each starts and ends, and whether its tags are paired.
    private var segmentStarts = IntArray(INITIAL_DEPTH)
    private var segmentLimits = IntArray(INITIAL_DEPTH)
    private var segmentPaired = BooleanArray(INITIAL_DEPTH)
    private var segmentModes = arrayOfNulls<BodyMode>(INITIAL_DEPTH)
    protected var segmentDepth = 0

    /**
     * Starts reading one segment up to [limit]: the page, or a part of a template, link or heading.
     *
     * HTML tags pair inside a segment and never across one. They are paired when the first opening tag in the
     * segment is reached, once for the whole segment, so the many segments with no tag in them (most template
     * parameters) cost nothing extra.
     */
    private fun enterSegment(limit: Int, mode: BodyMode) {
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
    }

    /** Pairs the tags of the segment being read, if nothing has yet, and says whether it did. */
    protected fun pairSegment(): Boolean {
        val top = segmentDepth - 1
        if (top < 0 || segmentPaired[top]) return false
        segmentPaired[top] = true
        scan.pairTags(segmentStarts[top], segmentLimits[top], segmentModes[top] ?: BodyMode.PAGE)
        return true
    }

    /**
     * Whether a list can start here: not inside a `<pre format="wikitext">`, and not on a reference's first
     * line, which MediaWiki places after the list item it opens for the reference.
     */
    protected fun listAllowed(): Boolean {
        val top = segmentDepth - 1
        return when (if (top >= 0) segmentModes[top] else null) {
            BodyMode.PREFORMATTED -> false
            BodyMode.REFERENCE -> head != segmentStarts[top]
            else -> true
        }
    }

    /** Emits the closing tag between [start] and [end] as written. */
    protected fun closingTag(start: Int, end: Int) {
        emit(Token.ClosingTagStart)
        emitText(text.substring(start + 2, end - 1))
        emit(Token.ClosingTagEnd)
        head = end
    }

    /**
     * Reads the attributes of the tag in [frame] up to [B], keeping the whitespace around each one, and says
     * whether it got to the end; the whitespace left before the closing `>` then starts at [D].
     *
     * Any text at all has a reading here, so a tag rebuilds byte for byte whatever its attributes look like:
     * a name is anything up to whitespace or `=`, a quoted value runs to its closing quote if it has one, and
     * an unquoted value to the next whitespace. [raw] is for an extension tag, whose attributes the
     * preprocessor never looked inside, and which so never stops before the end. The steps are where it
     * stopped: between attributes, or after the name, the bare name or the value whose end is at [E].
     */
    protected fun attributes(frame: Int, raw: Boolean): Boolean {
        val to = frames[frame, B]
        while (true) {
            when (step(frame)) {
                START -> {
                    frames[frame, D] = head
                    head = whitespaceEnd(head, to)
                    next(frame, ATTRIBUTE_NEXT)
                }
                ATTRIBUTE_NEXT -> {
                    if (head >= to) return true
                    if (!attributeStart(frame, to, raw)) return false
                }
                ATTRIBUTE_NAME_READ -> if (!attributeValue(frame, to, raw)) return false
                else -> {
                    head = frames[frame, E]
                    frames[frame, D] = head
                    head = whitespaceEnd(head, to)
                    next(frame, ATTRIBUTE_NEXT)
                }
            }
        }
    }

    /** Emits the start of the attribute at the cursor and reads its name. */
    private fun attributeStart(frame: Int, to: Int, raw: Boolean): Boolean {
        val padFirst = text.substring(frames[frame, D], head)
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
            frames[frame, E] = valueStart
            next(frame, ATTRIBUTE_NAME_READ)
        } else {
            // A bare attribute: the whitespace after it belongs to whatever comes next.
            emit(Token.AttributeStart(padFirst, "", ""))
            frames[frame, E] = nameEnd
            next(frame, ATTRIBUTE_READ)
        }
        return attributeText(frame, nameStart, nameEnd, raw)
    }

    /** Reads a value starting at [E]: quoted if a closing quote follows, unquoted otherwise. */
    private fun attributeValue(frame: Int, to: Int, raw: Boolean): Boolean {
        emit(Token.AttributeEquals)
        val from = frames[frame, E]
        val quote = if (from < to) text[from] else ' '
        val closingQuote =
            if (quote == '"' || quote == APOSTROPHE) attributeRun(from + 1, to, raw) { it == quote } else to
        next(frame, ATTRIBUTE_READ)
        if (closingQuote < to) {
            emit(Token.AttributeQuote(quote.toString()))
            frames[frame, E] = closingQuote + 1
            return attributeText(frame, from + 1, closingQuote, raw)
        }
        val valueEnd = attributeRun(from, to, raw) { it.isWhitespace() }
        frames[frame, E] = valueEnd
        return attributeText(frame, from, valueEnd, raw)
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

    private fun attributeText(frame: Int, from: Int, to: Int, raw: Boolean): Boolean {
        head = from
        if (!raw) return read(frame, PREPROCESSED, to)
        emitSpan(from, to)
        head = to
        return true
    }

    /**
     * Runs the cursor forward over text that cannot start anything, buffering it in one go.
     *
     * Most of a page is prose. Here each character costs one array read, and the run is copied in one block
     * when it ends. A word starting with a URL scheme's first letter is settled here too, by whether its
     * letters run into a `:`; sending every such word to the dispatch would stop prose at every other word.
     */
    protected fun skipPlainText(limit: Int) {
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

    /**
     * Consumes content up to [limit] and says whether it got there; it stops before, with the construct's
     * frame on top, at a construct that holds content of its own.
     */
    protected abstract fun content(limit: Int): Boolean

    /** Content with only the preprocessor's constructs in it, read as [content] is. */
    protected abstract fun preprocessedOnly(limit: Int): Boolean

    private companion object {
        const val INITIAL_DEPTH = 32

        /** Every character that begins a construct here, wherever it appears. */
        val MARKUP =
            BooleanArray(ASCII).apply {
                for (char in "{[<&'=") this[char.code] = true
                for (char in "*#;:") this[char.code] = true
            }
    }
}
