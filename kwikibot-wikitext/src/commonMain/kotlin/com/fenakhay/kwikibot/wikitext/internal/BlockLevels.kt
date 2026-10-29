package com.fenakhay.kwikibot.wikitext.internal

/** How a segment's lines are read by MediaWiki's paragraph pass. */
internal enum class BodyMode {
    /** A page, or anything read as one: a template's parameter, a link's text, a heading's title. */
    PAGE,

    /** A `<ref>` or `<references>` body, which MediaWiki places after a list item's opening tag. */
    REFERENCE,

    /** A `<poem>`, whose every line break MediaWiki writes as `<br />`, so it never has a paragraph break. */
    POEM,

    /** A `<pre format="wikitext">`, inside which MediaWiki makes no paragraphs or lists. */
    PREFORMATTED,

    /** A gallery or an image map, whose lines MediaWiki reads one at a time. */
    PER_LINE,
}

/**
 * What MediaWiki's paragraph pass sees on one line, after its templates are expanded and its comments gone.
 *
 * Filled by [TagPairing] for each line it reads and handed to [BlockLevels].
 */
internal class LineFacts {
    /** Where the list prefix (`*`, `#`, `:` and `;`) starts and ends; equal when there is none. */
    var prefixStart = 0
    var prefixEnd = 0

    /**
     * Whether the line has a tag that starts a block the paragraph pass leaves alone (`<table>`, `<ul>` …).
     */
    var openMatch = false

    /** Whether it has a tag that ends one, or a `<div>` or `<center>` (`</table>`, `<td>`, `<div>` …). */
    var closeMatch = false
    var preOpen = false
    var preClose = false

    /** Whether the line's last blockquote tag opens one (1) or closes one (-1); 0 when it has none. */
    var blockquote = 0

    /** The first character after the prefix, or 0 at the end of the line. */
    var firstChar: Char = NO_CHAR

    /** Whether nothing but whitespace is left on the line. */
    var blank = true

    /** Whether the line holds nothing but style sheets, which open and close no paragraph (T186965). */
    var styleOnly = false

    fun reset(start: Int) {
        prefixStart = start
        prefixEnd = start
        openMatch = false
        closeMatch = false
        preOpen = false
        preClose = false
        blockquote = 0
        firstChar = NO_CHAR
        blank = true
        styleOnly = false
    }

    companion object {
        const val NO_CHAR = '\u0000'
    }
}

/**
 * Follows MediaWiki's paragraph pass (`BlockLevelPass`) a line at a time: where it opens and closes the
 * paragraphs, preformatted blocks and lists that wikitext makes, which end any inline element open in them.
 *
 * It keeps the pass's state between lines (the open list, whether a paragraph or a `<pre>` is open, a
 * paragraph break waiting for the next line) and tells [elements] what it opens and closes.
 */
internal class BlockLevels(
    private val text: CharSequence,
    private val elements: OpenElements,
    table: ElementTable,
) {

    private val paragraphElement = table.id("p")
    private val pre = table.id("pre")
    private val unordered = table.id("ul")
    private val ordered = table.id("ol")
    private val definitions = table.id("dl")
    private val item = table.id("li")
    private val term = table.id("dt")
    private val description = table.id("dd")

    // The list prefix of the last line, with `;` read as `:`.
    private var lastPrefix = CharArray(INITIAL_PREFIX)
    private var lastPrefixLength = 0
    private var termOpen = false

    private var inPre = false
    private var inBlock = false
    private var inBlockquote = false
    private var lastParagraph = NONE
    private var pending = NONE
    private var glueFirstLine = false
    private var blankIsText = false
    private var noIndentPre = false

    /** Starts a segment read in [mode]. */
    fun reset(mode: BodyMode) {
        lastPrefixLength = 0
        termOpen = false
        inPre = mode == BodyMode.PREFORMATTED
        inBlock = mode == BodyMode.REFERENCE || mode == BodyMode.POEM
        inBlockquote = false
        lastParagraph = NONE
        pending = NONE
        glueFirstLine = mode == BodyMode.REFERENCE
        blankIsText = mode == BodyMode.POEM
        noIndentPre = mode == BodyMode.POEM
    }

    /** Reads one line, before anything on it is opened or closed. */
    fun line(facts: LineFacts) {
        if (glueFirstLine) {
            // The first line follows the list item MediaWiki opens for a reference, on the same line.
            glueFirstLine = false
            facts.prefixEnd = facts.prefixStart
            facts.openMatch = true
        }

        val prefixLength = if (inPre) 0 else facts.prefixEnd - facts.prefixStart
        if (!inPre) inPre = facts.preOpen
        lists(facts.prefixStart, prefixLength)
        if (prefixLength == 0) paragraph(facts)
        if (facts.preClose && inPre) inPre = false
    }

    /** Closes what is still open at the end of the segment. */
    fun finish() {
        while (lastPrefixLength > 0) {
            closeList(lastPrefix[lastPrefixLength - 1])
            lastPrefixLength--
        }
        closeParagraph()
    }

    // ------------------------------------------------------------------ lists

    private fun lists(start: Int, length: Int) {
        if (length == 0 && lastPrefixLength == 0) return
        pending = NONE
        if (length > 0 && length == lastPrefixLength && samePrefix(start, length)) {
            nextItem(text[start + length - 1])
            return
        }

        var common = commonPrefix(start, length)
        while (common < lastPrefixLength) {
            closeList(lastPrefix[lastPrefixLength - 1])
            lastPrefixLength--
        }
        if (length <= common && common > 0) nextItem(text[start + common - 1])
        if (termOpen && common > 0 && text[start + common - 1] == ':') nextItem(':')
        while (length > common) {
            openList(text[start + common])
            common++
        }
        remember(start, length)
    }

    /** Whether the prefix at [start], read with `;` as `:`, is the last line's. */
    private fun samePrefix(start: Int, length: Int): Boolean =
        (0 until length).all { definitionAsDescription(text[start + it]) == lastPrefix[it] }

    /** How much of the prefix at [start], as written, the last line's shares from its start. */
    private fun commonPrefix(start: Int, length: Int): Int {
        val shorter = minOf(length, lastPrefixLength)
        var common = 0
        while (common < shorter && text[start + common] == lastPrefix[common]) common++
        return common
    }

    private fun remember(start: Int, length: Int) {
        if (length > lastPrefix.size) lastPrefix = lastPrefix.copyOf(length * 2)
        for (index in 0 until length) lastPrefix[index] = definitionAsDescription(text[start + index])
        lastPrefixLength = length
    }

    private fun openList(char: Char) {
        closeParagraph()
        when (char) {
            '*' -> open(unordered, item)
            '#' -> open(ordered, item)
            ':' -> open(definitions, description)
            else -> {
                open(definitions, term)
                termOpen = true
            }
        }
    }

    private fun open(list: Int, entry: Int) {
        elements.openSynthetic(list)
        elements.openSynthetic(entry)
    }

    private fun nextItem(char: Char) {
        if (char == '*' || char == '#') {
            elements.closeSynthetic(item)
            elements.openSynthetic(item)
            return
        }
        elements.closeSynthetic(if (termOpen) term else description)
        termOpen = char == ';'
        elements.openSynthetic(if (termOpen) term else description)
    }

    private fun closeList(char: Char) {
        when (char) {
            '*' -> close(item, unordered)
            '#' -> close(item, ordered)
            else -> {
                close(if (termOpen) term else description, definitions)
                termOpen = false
            }
        }
    }

    private fun close(entry: Int, list: Int) {
        elements.closeSynthetic(entry)
        elements.closeSynthetic(list)
    }

    // ------------------------------------------------------------------ paragraphs

    private fun paragraph(facts: LineFacts) {
        when {
            facts.openMatch || facts.closeMatch -> blockLine(facts)
            inBlock || inPre -> Unit
            startsIndentPre(facts) ->
                if (lastParagraph != PRE) {
                    pending = NONE
                    closeParagraph()
                    elements.openSynthetic(pre)
                    lastParagraph = PRE
                }
            facts.styleOnly ->
                if (pending != NONE) {
                    closeParagraph()
                    pending = NONE
                }
            else -> textLine(facts.blank && !blankIsText)
        }
    }

    /** Whether the line is indented to start or go on with a preformatted block. */
    private fun startsIndentPre(facts: LineFacts): Boolean =
        facts.firstChar == ' ' && (lastParagraph == PRE || !facts.blank) && !(inBlockquote || noIndentPre)

    private fun blockLine(facts: LineFacts) {
        pending = NONE
        if (!inPre || facts.preOpen) closeParagraph()
        if (facts.preOpen && !facts.preClose) inPre = true
        if (facts.blockquote != 0) inBlockquote = facts.blockquote > 0
        inBlock = !facts.closeMatch
    }

    private fun textLine(blank: Boolean) {
        when {
            blank && pending != NONE -> applyPending()
            blank && lastParagraph != PARAGRAPH -> {
                closeParagraph()
                pending = OPEN
            }
            blank -> pending = CLOSE_AND_OPEN
            pending != NONE -> applyPending()
            lastParagraph != PARAGRAPH -> {
                closeParagraph()
                elements.openSynthetic(paragraphElement)
                lastParagraph = PARAGRAPH
            }
        }
    }

    /** Writes the paragraph a blank line left waiting: a new one, or the old one closed and a new one. */
    private fun applyPending() {
        if (pending == CLOSE_AND_OPEN) elements.closeSynthetic(paragraphElement)
        elements.openSynthetic(paragraphElement)
        pending = NONE
        lastParagraph = PARAGRAPH
    }

    private fun closeParagraph() {
        when (lastParagraph) {
            PARAGRAPH -> elements.closeSynthetic(paragraphElement)
            PRE -> elements.closeSynthetic(pre)
        }
        inPre = false
        lastParagraph = NONE
    }

    private companion object {
        const val INITIAL_PREFIX = 8
        const val NONE = 0
        const val PARAGRAPH = 1
        const val PRE = 2
        const val OPEN = 1
        const val CLOSE_AND_OPEN = 2

        fun definitionAsDescription(char: Char): Char = if (char == ';') ':' else char
    }
}
