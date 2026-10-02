package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.ARGUMENT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.COMMENT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.EXT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.EXT_SHORT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.HTML_PAIRED
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.TEMPLATE

/**
 * Pairs the HTML tags of one segment where MediaWiki ends the elements they open.
 *
 * MediaWiki does not pair tags as written. Its paragraph pass wraps lines in paragraphs, lists and
 * preformatted blocks, and the HTML5 tree built over the result ends a `<span>` at a paragraph break but
 * keeps a `<div>` open across one. So the segment is read a line at a time, as the paragraph pass reads it
 * ([BlockLevels]), and each line's tags are replayed into the elements open at that point ([OpenElements]),
 * along with the paragraphs, lists, headings and wikitext tables between them.
 *
 * Templates and arguments count as inline text, since what they expand to is not known here. Comments are
 * ignored, and a line holding only comments is dropped, as MediaWiki drops it. An extension tag counts as
 * what it renders: a block (a gallery, the references), nothing (an indicator), a style sheet, or inline
 * text.
 */
internal class TagPairing(
    private val text: String,
    private val pp: Preprocessor,
    private val scan: Scanner,
    options: ParseOptions,
) {
    private val kinds = pp.kinds
    private val table = options.elements
    private val elements =
        OpenElements(table) { opener, closerEnd ->
            pp.ends[opener] = closerEnd + 2
            kinds[opener] = HTML_PAIRED
        }
    private val blocks = BlockLevels(text, elements, table)
    private val facts = LineFacts()

    private val blockquote = table.idOf("blockquote")
    private val preElement = table.id("pre")
    private val tableElement = table.id("table")
    private val rowElement = table.id("tr")
    private val cellElement = table.id("td")
    private val headerElement = table.id("th")
    private val captionElement = table.id("caption")
    private val definitions = table.id("dl")
    private val description = table.id("dd")
    private val linkElement = table.idOf("link")

    private val events = Events()
    private val tables = WikiTables()

    // What the line being read is to the table pass.
    private var lineKind = PLAIN
    private var colons = 0

    /**
     * Pairs the tags between [from] and [limit], a segment read in [mode] in a document starting at
     * [documentStart].
     */
    fun pair(from: Int, limit: Int, mode: BodyMode, documentStart: Int) {
        elements.reset()
        blocks.reset(if (mode == BodyMode.PER_LINE) BodyMode.PAGE else mode)
        tables.reset()

        var start = from
        while (true) {
            val end = readLine(start, limit)
            if (!removed(start, end, documentStart)) {
                if (mode == BodyMode.PER_LINE) elements.endLine() else startLine()
                replay()
            }
            if (end >= limit) break
            start = end + 1
        }
        if (mode != BodyMode.PER_LINE) blocks.finish()
    }

    // ------------------------------------------------------------------ reading a line

    /** Reads the line starting at [start] into [facts] and [events], and returns where it ends. */
    private fun readLine(start: Int, limit: Int): Int {
        facts.reset(start)
        events.clear()
        lineKind = PLAIN
        colons = 0
        commentsOnly = true

        val first = skipComments(start, limit)
        val opening = if (first < limit) text[first] else LineFacts.NO_CHAR
        var at =
            if (opening.code < MARKUP_START.size && MARKUP_START[opening.code]) tableLine(first, limit)
            else first
        if (lineKind == CELL || lineKind == CAPTION) events.startCell()
        if (lineKind == PLAIN) {
            at = prefix(first, limit)
            facts.firstChar = skipComments(at, limit).let { if (it < limit) text[it] else LineFacts.NO_CHAR }
            if (opening == '-' && text.startsWith(RULE, first)) {
                facts.closeMatch = true
                events.add(BLOCK, first, -1)
                at = first + RULE.length
            }
        }
        styleCandidate = at < limit && text[at] == '<'
        val end = readContent(at, limit)
        facts.styleOnly = styleCandidate && sawStyle && !sawOther
        return end
    }

    private var commentsOnly = true
    private var styleCandidate = false
    private var sawStyle = false
    private var sawOther = false

    /** Reads the rest of the line from [from], and returns where it ends. */
    private fun readContent(from: Int, limit: Int): Int {
        sawStyle = false
        sawOther = false
        var at = from
        while (at < limit && text[at] != '\n') {
            at =
                when {
                    kinds[at] != NO_KIND && kinds[at] != HTML_PAIRED -> construct(at, limit)
                    text[at] == '<' && scan.htmlTagAt(at, limit) -> tag(at)
                    (lineKind == CELL || lineKind == CAPTION) && cellSeparator(at, limit) -> at + 2
                    else -> plain(at, limit)
                }
        }
        return minOf(at, limit)
    }

    /**
     * Text at [at]: a behaviour switch, or a run of characters that start nothing. Each character is looked
     * at only while the line is still blank.
     */
    private fun plain(at: Int, limit: Int): Int {
        if (text[at] == '_') {
            val switchEnd = if (text.startsWith(SWITCH, at)) behaviourSwitch(at) else -1
            return if (switchEnd >= 0) switchEnd else character(at)
        }
        var end = at
        while (facts.blank && end < limit && !stops(text[end])) character(end++)
        end = skipText(end, limit)
        return if (end == at) character(at) else end
    }

    /** The first position from [from] where a construct, tag, cell, switch or line end may start. */
    private fun skipText(from: Int, limit: Int): Int {
        val text = text
        val stops = STOPS
        var at = from
        while (at < limit) {
            val code = text[at].code
            if (code < stops.size && stops[code]) break
            at++
        }
        return at
    }

    private fun stops(char: Char): Boolean = char.code < STOPS.size && STOPS[char.code]

    private fun character(at: Int): Int {
        if (!isTrimmed(text[at])) {
            commentsOnly = false
            sawOther = true
            facts.blank = false
        }
        return at + 1
    }

    /** A template, comment, link, heading or extension tag at [at]: reads past it, and returns where. */
    private fun construct(at: Int, limit: Int): Int {
        val kind = kinds[at]
        if (Preprocessor.isHeading(kind)) {
            nonBlank()
            facts.openMatch = true
            facts.closeMatch = true
            events.add(HEADING, at, -1)
            return maxOf(at + 1, minOf(pp.end(at), limit))
        }
        val end = scan.opaqueEnd(at, limit)
        if (end < 0) return character(at)
        when (kind) {
            COMMENT,
            Preprocessor.COMMENT_OPEN_ENDED -> Unit
            EXT,
            EXT_SHORT -> extension(at)
            TEMPLATE,
            ARGUMENT -> nonBlank()
            else -> link(at)
        }
        return end
    }

    /** A link: inline text, unless it shows a file as a figure, which is a block and ends the paragraph. */
    private fun link(at: Int) {
        nonBlank()
        tables.linkInCell()
        if (scan.isFigure(at)) {
            facts.closeMatch = true
            events.add(BLOCK, at, -1)
        }
    }

    /**
     * A behaviour switch such as `__NOTOC__` at [at], which MediaWiki removes before it reads the line, so a
     * line holding only switches is blank. `__TOC__` leaves a placeholder the paragraph pass reads as a block
     * line, which ends the paragraph before it. Returns where it ends, or -1 if there is none.
     */
    private fun behaviourSwitch(at: Int): Int {
        val end = text.indexOf(SWITCH, at + SWITCH.length)
        if (end < 0 || end - at > LONGEST_SWITCH) return -1
        val word = text.substring(at + SWITCH.length, end)
        val known = word in SWITCHES || word.uppercase() in SWITCHES_IGNORING_CASE
        if (!known) return -1
        commentsOnly = false
        if (word.uppercase() == TOC) facts.closeMatch = true
        return end + SWITCH.length
    }

    private fun extension(at: Int) {
        var nameEnd = at + 1
        while (nameEnd < text.length && !endsExtensionName(text[nameEnd])) nameEnd++
        when {
            TagNames.INVISIBLE_EXTENSIONS.contains(text, at + 1, nameEnd) -> commentsOnly = false
            TagNames.STYLE_EXTENSIONS.contains(text, at + 1, nameEnd) -> {
                commentsOnly = false
                facts.blank = false
                sawStyle = true
            }
            TagNames.BLOCK_EXTENSIONS.contains(text, at + 1, nameEnd) -> {
                nonBlank()
                facts.openMatch = true
                facts.closeMatch = true
                if (nameEnd - at - 1 == PRE.length && text.regionMatches(at + 1, PRE, 0, PRE.length, true)) {
                    facts.preOpen = true
                    facts.preClose = true
                }
                events.add(BLOCK, at, -1)
            }
            else -> nonBlank()
        }
    }

    /**
     * An HTML tag [Scanner.htmlTagAt] just read at [at]: notes what it means to the line, and returns its
     * end.
     */
    private fun tag(at: Int): Int {
        val closing = scan.tagClosing
        val nameStart = if (closing) at + 2 else at + 1
        val id = table.idOf(text, nameStart, scan.tagNameEnd)
        val end = scan.tagEnd + 1
        if (id < 0) return end

        if (table.has(id, ElementTable.TRANSPARENT)) {
            commentsOnly = false
        } else {
            nonBlank()
            if (id == linkElement) sawStyle = true else sawOther = true
        }
        if (table.onLine(id, if (closing) ElementTable.OPENS_ON_END else ElementTable.OPENS_ON_START)) {
            facts.openMatch = true
        }
        if (table.onLine(id, if (closing) ElementTable.CLOSES_ON_END else ElementTable.CLOSES_ON_START)) {
            facts.closeMatch = true
        }
        if (id == preElement) if (closing) facts.preClose = true else facts.preOpen = true
        if (id == blockquote) facts.blockquote = if (closing) -1 else 1

        if (closing) events.add(END, scan.tagEnd, id) else events.add(START, at, id, scan.tagSelfClosing)
        return end
    }

    private fun endsExtensionName(char: Char): Boolean =
        Preprocessor.isSpace(char) || char == '/' || char == '>'

    private fun nonBlank() {
        commentsOnly = false
        sawOther = true
        facts.blank = false
    }

    /** Past the comments starting at [from], which are gone by the time MediaWiki reads the line. */
    private fun skipComments(from: Int, limit: Int): Int {
        var at = from
        while (at < limit && kinds[at] == COMMENT) at = pp.end(at)
        return at
    }

    /** The list prefix at [from], noted in [facts]; returns where the line's content starts. */
    private fun prefix(from: Int, limit: Int): Int {
        var at = from
        while (at < limit && isListMarker(text[at])) at++
        facts.prefixStart = from
        facts.prefixEnd = at
        return at
    }

    /**
     * Whether the line [start] until [end] is one the preprocessor removes: nothing but comments and spaces,
     * with a line break on either side.
     */
    private fun removed(start: Int, end: Int, documentStart: Int): Boolean =
        commentsOnly &&
            start > documentStart &&
            text[start - 1] == '\n' &&
            end < text.length &&
            text[end] == '\n' &&
            hasComment(start, end)

    private fun hasComment(start: Int, end: Int): Boolean = (start until end).any { kinds[it] == COMMENT }

    // ------------------------------------------------------------------ wikitext tables

    /**
     * Whether the line starting at [from] is a wikitext table's markup, noted in [lineKind]; returns where
     * its content starts.
     */
    private fun tableLine(from: Int, limit: Int): Int {
        val at = skipBlanks(from, limit)
        val colonEnd = at + run(':', at, limit)
        val brace = skipBlanks(colonEnd, limit)
        if (text.startsWith("{|", brace)) {
            lineKind = TABLE_START
            colons = colonEnd - at
            return lineEnd(brace, limit)
        }
        return if (tables.open && at < limit) tablePart(from, at, limit) else from
    }

    /** A line inside a table that starts with its markup at [at]: a row, a cell, a caption, or the end. */
    private fun tablePart(from: Int, at: Int, limit: Int): Int {
        val next = if (at + 1 < limit) text[at + 1] else LineFacts.NO_CHAR
        return when {
            text[at] == '|' && next == '}' -> kind(TABLE_END, at + 2)
            text[at] == '|' && next == '-' -> kind(ROW, lineEnd(at, limit))
            text[at] == '|' && next == '+' -> kind(CAPTION, at + 2)
            text[at] == '|' -> kind(CELL, at + 1).also { cellHeader = false }
            text[at] == '!' -> kind(CELL, at + 1).also { cellHeader = true }
            else -> from
        }
    }

    private var cellHeader = false

    private fun skipBlanks(from: Int, limit: Int): Int {
        var at = from
        while (at < limit && (text[at] == ' ' || text[at] == '\t')) at++
        return at
    }

    private fun run(char: Char, from: Int, limit: Int): Int {
        var at = from
        while (at < limit && text[at] == char) at++
        return at - from
    }

    private fun kind(kind: Int, contentStart: Int): Int {
        lineKind = kind
        return contentStart
    }

    /**
     * Where the line holding [from] ends, past constructs that span lines. For a table's or a row's line,
     * whose rest is attributes rather than content.
     */
    private fun lineEnd(from: Int, limit: Int): Int {
        var at = from
        while (at < limit && text[at] != '\n') {
            val end = if (kinds[at] == NO_KIND) -1 else scan.opaqueEnd(at, limit)
            at = if (end > at) end else at + 1
        }
        return at
    }

    /** A `||`, or `!!` on a header line, that starts the next cell. */
    private fun cellSeparator(at: Int, limit: Int): Boolean {
        val char = text[at]
        val separates = (char == '|' || (char == '!' && cellHeader)) && at + 1 < limit && text[at + 1] == char
        if (separates) events.add(CELL_SEPARATOR, at, -1)
        if (!separates && char == '|') events.attributesEnd()
        return separates
    }

    // ------------------------------------------------------------------ replaying a line

    private fun startLine() {
        when (lineKind) {
            TABLE_START -> {
                facts.openMatch = true
                blocks.line(facts)
                repeat(colons) {
                    elements.openSynthetic(definitions)
                    elements.openSynthetic(description)
                }
                elements.openSynthetic(tableElement)
                tables.start(colons)
            }
            TABLE_END -> {
                facts.openMatch = true
                facts.closeMatch = true
                blocks.line(facts)
                closeTable()
            }
            ROW -> {
                if (tables.cellOpen || tables.rowOpen) facts.openMatch = true
                blocks.line(facts)
                closeCell()
                closeRow()
            }
            CELL,
            CAPTION -> {
                facts.closeMatch = true
                blocks.line(facts)
                cell()
            }
            else -> blocks.line(facts)
        }
    }

    private fun replay() {
        for (index in 0 until events.size) {
            when (events.kind(index)) {
                START -> elements.start(events.id(index), events.at(index), events.selfClosing(index))
                END -> elements.end(events.id(index), events.at(index))
                HEADING -> elements.heading()
                BLOCK -> elements.closeParagraph()
                CELL_SEPARATOR -> cell()
            }
        }
    }

    /**
     * A new cell, as the table pass writes it: the open one closed, a row opened if none is, the cell opened.
     */
    private fun cell() {
        closeCell()
        val kind =
            when {
                lineKind == CAPTION -> captionElement
                cellHeader -> headerElement
                else -> cellElement
            }
        if (kind != captionElement && !tables.rowOpen) {
            elements.openSynthetic(rowElement)
            tables.rowOpen = true
        }
        elements.openSynthetic(kind)
        tables.cellOpen = true
        tables.cellKind = kind
    }

    private fun closeCell() {
        if (!tables.cellOpen) return
        elements.closeSynthetic(tables.cellKind)
        tables.cellOpen = false
    }

    private fun closeRow() {
        if (!tables.rowOpen) return
        elements.closeSynthetic(rowElement)
        tables.rowOpen = false
    }

    private fun closeTable() {
        if (!tables.open) return
        closeCell()
        closeRow()
        elements.closeSynthetic(tableElement)
        repeat(tables.end()) {
            elements.closeSynthetic(description)
            elements.closeSynthetic(definitions)
        }
    }

    /** The open wikitext tables: whether each has a row and a cell open, and how many colons indent it. */
    private inner class WikiTables {
        private var depth = 0
        private var rows = BooleanArray(INITIAL)
        private var cells = BooleanArray(INITIAL)
        private var indents = IntArray(INITIAL)
        private var cellHasLink = false

        /** The element the open cell is: a `td`, a `th` or a `caption`. */
        var cellKind = -1

        val open: Boolean
            get() = depth > 0

        var rowOpen: Boolean
            get() = depth > 0 && rows[depth - 1]
            set(value) {
                if (depth > 0) rows[depth - 1] = value
            }

        var cellOpen: Boolean
            get() = depth > 0 && cells[depth - 1]
            set(value) {
                if (depth > 0) cells[depth - 1] = value
                cellHasLink = false
            }

        fun reset() {
            depth = 0
        }

        fun start(colons: Int) {
            if (depth == rows.size) {
                rows = rows.copyOf(depth * 2)
                cells = cells.copyOf(depth * 2)
                indents = indents.copyOf(depth * 2)
            }
            rows[depth] = false
            cells[depth] = false
            indents[depth] = colons
            depth++
        }

        /** Closes the innermost table and returns how many colons indented it. */
        fun end(): Int = indents[--depth]

        fun linkInCell() {
            cellHasLink = true
        }

        val attributesAllowed: Boolean
            get() = !cellHasLink
    }

    /** The events of the line being read, in order, kept in arrays so reading a line allocates nothing. */
    private inner class Events {
        private var kinds = IntArray(INITIAL)
        private var positions = IntArray(INITIAL)
        private var ids = IntArray(INITIAL)
        private var flags = BooleanArray(INITIAL)
        var size = 0
            private set

        // Where the current cell's events start, while its attributes may still be ahead.
        private var cellStart = -1

        fun clear() {
            size = 0
            cellStart = -1
        }

        /** The line is a table cell's, whose attributes may come first. */
        fun startCell() {
            cellStart = 0
        }

        fun add(kind: Int, at: Int, id: Int, selfClosing: Boolean = false) {
            if (size == kinds.size) {
                kinds = kinds.copyOf(size * 2)
                positions = positions.copyOf(size * 2)
                ids = ids.copyOf(size * 2)
                flags = flags.copyOf(size * 2)
            }
            kinds[size] = kind
            positions[size] = at
            ids[size] = id
            flags[size] = selfClosing
            size++
            if (kind == CELL_SEPARATOR) cellStart = size
        }

        /** A single `|` in a cell: what came before it was the cell's attributes, unless a link was. */
        fun attributesEnd() {
            if (cellStart >= 0 && tables.attributesAllowed) size = cellStart
            cellStart = -1
        }

        fun kind(index: Int): Int = kinds[index]

        fun at(index: Int): Int = positions[index]

        fun id(index: Int): Int = ids[index]

        fun selfClosing(index: Int): Boolean = flags[index]
    }

    private companion object {
        const val INITIAL = 8
        const val NO_KIND: Byte = 0

        const val START = 1
        const val END = 2
        const val HEADING = 3
        const val BLOCK = 4
        const val CELL_SEPARATOR = 5

        // What a line is to the table pass.
        const val PLAIN = 0
        const val TABLE_START = 6
        const val TABLE_END = 7
        const val CELL = 8
        const val CAPTION = 9
        const val ROW = 10

        const val RULE = "----"
        const val PRE = "pre"

        fun isListMarker(char: Char): Boolean = char == '*' || char == '#' || char == ':' || char == ';'

        /** The characters a line of table, rule or list markup can start with, after any spaces. */
        private val MARKUP_START = BooleanArray(128).apply { for (char in " \t:{|!-") this[char.code] = true }

        /**
         * The characters a line's text is read up to: where a construct, a tag, a cell, a switch or the line
         * may start or end.
         */
        private val STOPS = BooleanArray(128).apply { for (char in "<{[=|!_\n") this[char.code] = true }

        // MediaWiki's behaviour switches (`MagicWordFactory`'s double-underscore words), by their case rule.
        const val SWITCH = "__"
        const val TOC = "TOC"
        const val LONGEST_SWITCH = 30
        val SWITCHES =
            setOf("EXPECTSHORTPAGE", "EXPECTUNUSEDCATEGORY", "EXPECTUNUSEDTEMPLATE", "HIDDENCAT", "INDEX") +
                setOf("NEWSECTIONLINK", "NOINDEX", "NONEWSECTIONLINK", "STATICREDIRECT")
        val SWITCHES_IGNORING_CASE =
            setOf("FORCETOC", "NOCONTENTCONVERT", "NOCC", "NOEDITSECTION", "NOGALLERY", "NOTITLECONVERT") +
                setOf("NOTC", "NOTOC", "TOC")

        /** What PHP's `trim` removes, which is what the paragraph pass means by a blank line. */
        fun isTrimmed(char: Char): Boolean =
            char == ' ' ||
                char == '\t' ||
                char == '\n' ||
                char == '\r' ||
                char.code == VERTICAL_TAB ||
                char.code == 0

        const val VERTICAL_TAB = 0x0B
    }
}
