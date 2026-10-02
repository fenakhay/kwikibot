package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.BLOCK
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.FORMATTING
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.HEADING
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.ITEM
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.LIST_SCOPE
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.MARKER
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.SCOPE
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.SPECIAL
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.STOPS_ITEM
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.TABLE_PART
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.TABLE_SCOPE
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.TRANSPARENT
import com.fenakhay.kwikibot.wikitext.internal.ElementTable.Companion.VOID

/**
 * The elements open while a segment's tags are paired, closed by HTML5's tree-building rules as MediaWiki's
 * tidying applies them.
 *
 * An element is a tag the page wrote (an opener), one MediaWiki writes for wikitext markup (a paragraph, a
 * list item, a table cell: synthetic), or a self-closing tag such as `<span/>`, which MediaWiki opens but
 * which has no body here (a phantom). Only a written opener can pair, and only with a closing tag that
 * MediaWiki would end it at; everything else that ends an element leaves its opener unpaired.
 *
 * A formatting element such as `<b>` is not ended by a paragraph break, a list item or a heading: MediaWiki
 * ends it there and opens it again after, which reads as one element from its opening tag to its closing tag.
 * So it outlives the synthetic elements closed around it and can pair with its closer past them.
 *
 * Every query looks at the top of one of a few stacks of entries, and every entry is removed once, so a
 * segment is paired in time linear in its tags. Entries are numbered as they are opened and never reused, and
 * a removed entry is skipped when it reaches the top of a stack.
 */
internal class OpenElements(
    private val table: ElementTable,
    private val onPair: (opener: Int, closerEnd: Int) -> Unit,
) {

    private var opener = IntArray(INITIAL)
    private var element = IntArray(INITIAL)
    private var gone = BooleanArray(INITIAL)
    private var sameBelow = IntArray(INITIAL)
    private var count = 0
    private val topOf = IntArray(table.size) { NONE }

    // The kinds of entry each query needs the topmost of.
    private val plain = Stack()
    private val synthetic = Stack()
    private val formatting = Stack()
    private val transparent = Stack()
    private val specials = Stack()
    private val writtenSpecials = Stack()
    private val scopes = Stack()
    private val listScopes = Stack()
    private val tableScopes = Stack()
    private val markers = Stack()
    private val itemStops = Stack()
    private val headings = Stack()
    private val all =
        arrayOf(plain, synthetic, formatting, transparent, specials, writtenSpecials) +
            arrayOf(scopes, listScopes, tableScopes, markers, itemStops, headings)

    private val paragraph = table.id("p")
    private val rule = table.idOf("hr")
    private val row = table.id("tr")
    private val listItem = table.id("li")
    private val caption = table.id("caption")

    /** Forgets everything, for a new segment. */
    fun reset() {
        // Only the names opened since the last reset can be anything but NONE.
        for (entry in 0 until count) topOf[element[entry]] = NONE
        count = 0
        for (stack in all) stack.clear()
    }

    // ------------------------------------------------------------------ tags the page wrote

    /** An opening tag the page wrote at [at], or a self-closing one, which opens a phantom. */
    fun start(id: Int, at: Int, selfClosing: Boolean) {
        when {
            table.has(id, VOID) -> if (id == rule) closeParagraph()
            table.has(id, TRANSPARENT) -> if (!selfClosing) push(id, at)
            selfClosing && table.has(id, ITEM) -> startItem(id)
            else -> open(id, if (selfClosing) PHANTOM else at)
        }
    }

    /** A closing tag the page wrote, whose `>` is at [closerEnd]. */
    fun end(id: Int, closerEnd: Int) {
        when {
            table.has(id, VOID) -> Unit
            table.has(id, TRANSPARENT) -> endTransparent(id, closerEnd)
            table.has(id, FORMATTING) -> endFormatting(id, closerEnd)
            table.has(id, HEADING) -> endHeading(id, closerEnd)
            table.has(id, IN_SCOPE) || id == paragraph -> endInScope(id, closerEnd)
            else -> endOther(id, closerEnd)
        }
    }

    // ------------------------------------------------------------------ what wikitext markup makes

    /** Opens an element MediaWiki writes for wikitext markup, such as a paragraph or a list item. */
    fun openSynthetic(id: Int) = open(id, SYNTHETIC)

    /**
     * Closes the topmost open element named [id], as a closing tag MediaWiki writes for wikitext markup
     * would: whatever is open inside it ends too, except formatting elements, which MediaWiki opens again
     * after it.
     */
    fun closeSynthetic(id: Int) {
        val target = live(topOf[id])
        if (target == NONE || !reachable(target, id)) return
        if (table.has(id, MARKER)) endAllAbove(target) else endImplied(target)
        remove(target)
    }

    /** Closes an open paragraph in button scope, as a block that starts here would. */
    fun closeParagraph() {
        val target = live(topOf[paragraph])
        if (target != NONE && target >= scopes.top()) {
            endImplied(target)
            remove(target)
        }
    }

    /** A wikitext heading: it closes an open paragraph, and a heading open where it starts. */
    fun heading() {
        closeParagraph()
        closeCurrentHeading()
    }

    /** Closes everything a newline ends in a body read a line at a time, such as a gallery's. */
    fun endLine() {
        endImplied(NONE)
        while (formatting.top() != NONE) remove(formatting.top())
    }

    // ------------------------------------------------------------------ opening

    private fun open(id: Int, at: Int) {
        when {
            table.has(id, ITEM) -> startItem(id)
            table.has(id, HEADING) -> {
                closeParagraph()
                closeCurrentHeading()
            }
            table.has(id, TABLE_PART) -> startTablePart(id)
            table.has(id, SPECIAL) -> closeParagraph()
        }
        if (!table.has(id, ITEM) || at != PHANTOM) push(id, at)
    }

    /** A new `li`, `dd` or `dt` closes the open item of its kind, unless a special element is in the way. */
    private fun startItem(id: Int) {
        val stop = itemStops.top()
        if (stop != NONE && sameItemKind(element[stop], id)) {
            endImplied(stop)
            remove(stop)
        }
        closeParagraph()
    }

    private fun sameItemKind(open: Int, starting: Int): Boolean =
        table.has(open, ITEM) && (open == listItem) == (starting == listItem)

    /** A `td`, `th` or `tr` closes the open cell, and a `tr` the open row, of the table it is in. */
    private fun startTablePart(id: Int) {
        val cell = topCell()
        if (cell != NONE) {
            endAllAbove(cell)
            remove(cell)
        }
        if (id == row || id == caption) {
            val open = live(topOf[row])
            if (open != NONE && open >= tableScopes.top()) {
                endAllAbove(open)
                remove(open)
            }
        }
    }

    private fun topCell(): Int {
        val marker = markers.top()
        val boundary = tableScopes.top()
        return if (marker != NONE && marker > boundary && !table.has(element[marker], TRANSPARENT)) marker
        else NONE
    }

    private fun closeCurrentHeading() {
        val heading = headings.top()
        if (heading != NONE && heading == current()) {
            endImplied(heading)
            remove(heading)
        }
    }

    // ------------------------------------------------------------------ closing

    /** `</div>`, `</p>`, `</li>`, `</td>` and their kind: the element is closed if it is in its scope. */
    private fun endInScope(id: Int, closerEnd: Int) {
        val target = live(topOf[id])
        if (target == NONE || !reachable(target, id)) return
        endAllAbove(target)
        pair(target, closerEnd)
    }

    /** `</h2>` closes whichever heading is open, but pairs only with its own name. */
    private fun endHeading(id: Int, closerEnd: Int) {
        val target = headings.top()
        if (target == NONE || target < scopes.top() || target < transparent.top()) return
        endAllAbove(target)
        if (element[target] == id) pair(target, closerEnd) else remove(target)
    }

    /** Any other end tag: the nearest element of its name, unless a special element is open inside it. */
    private fun endOther(id: Int, closerEnd: Int) {
        val target = live(topOf[id])
        if (target == NONE || specials.top() > target || transparent.top() > target) return
        endAllAbove(target)
        pair(target, closerEnd)
    }

    /**
     * A formatting element's end tag, a simplified adoption agency: it pairs past the paragraphs and list
     * items of wikitext markup, but not past a cell or a special element the page wrote. Past those MediaWiki
     * ends the element at this closer all the same, which the page's tree cannot show, so the opener is left
     * unpaired and the closer is text.
     */
    private fun endFormatting(id: Int, closerEnd: Int) {
        val target = live(topOf[id])
        if (target == NONE || markers.top() > target || transparent.top() > target) return
        if (writtenSpecials.top() > target) {
            remove(target)
            return
        }
        while (plain.top() > target) remove(plain.top())
        while (formatting.top() > target) remove(formatting.top())
        pair(target, closerEnd)
    }

    private fun endTransparent(id: Int, closerEnd: Int) {
        val target = live(topOf[id])
        if (target == NONE) return
        endAllAbove(target)
        pair(target, closerEnd)
    }

    /** Whether the open element [target] named [id] is in the scope its end tag looks in. */
    private fun reachable(target: Int, id: Int): Boolean {
        val boundary =
            when {
                table.has(id, TABLE_PART or TABLE_SCOPE) -> tableScopes.top()
                id == listItem -> listScopes.top()
                else -> scopes.top()
            }
        return target >= boundary && target >= transparent.top()
    }

    /** Ends everything open above [target]: each is left unpaired. */
    private fun endAllAbove(target: Int) {
        endImplied(target)
        while (formatting.top() > target) remove(formatting.top())
    }

    /** Ends everything open above [target] but the formatting elements MediaWiki opens again after it. */
    private fun endImplied(target: Int) {
        while (plain.top() > target) remove(plain.top())
        while (synthetic.top() > target) remove(synthetic.top())
    }

    private fun pair(entry: Int, closerEnd: Int) {
        if (opener[entry] >= 0) onPair(opener[entry], closerEnd)
        remove(entry)
    }

    // ------------------------------------------------------------------ the stacks

    private fun push(id: Int, at: Int) {
        grow()
        val entry = count++
        opener[entry] = at
        element[entry] = id
        gone[entry] = false
        sameBelow[entry] = topOf[id]
        topOf[id] = entry

        when {
            table.has(id, FORMATTING) -> formatting.push(entry)
            table.has(id, TRANSPARENT) -> transparent.push(entry)
            at == SYNTHETIC -> synthetic.push(entry)
            else -> plain.push(entry)
        }
        if (at >= 0 && table.has(id, SPECIAL)) writtenSpecials.push(entry)
        index(id, entry)
    }

    /** Puts [entry], an element [id], on each stack its kind is looked for on. */
    private fun index(id: Int, entry: Int) {
        if (table.has(id, SPECIAL)) specials.push(entry)
        if (table.has(id, SCOPE or TRANSPARENT)) scopes.push(entry)
        if (table.has(id, SCOPE or LIST_SCOPE or TRANSPARENT)) listScopes.push(entry)
        if (table.has(id, TABLE_SCOPE or TRANSPARENT)) tableScopes.push(entry)
        if (table.has(id, MARKER or TRANSPARENT)) markers.push(entry)
        if (table.has(id, STOPS_ITEM)) itemStops.push(entry)
        if (table.has(id, HEADING)) headings.push(entry)
    }

    private fun remove(entry: Int) {
        gone[entry] = true
        val id = element[entry]
        if (topOf[id] == entry) topOf[id] = sameBelow[entry]
    }

    /**
     * [entry], the topmost element of its name, or the nearest open one below it. What is found is kept as
     * the topmost, so each removed element is stepped over once.
     */
    private fun live(entry: Int): Int {
        if (entry == NONE) return NONE
        val id = element[entry]
        var at = entry
        while (at != NONE && gone[at]) at = sameBelow[at]
        topOf[id] = at
        return at
    }

    /** The topmost open element of any kind. */
    private fun current(): Int =
        maxOf(maxOf(plain.top(), synthetic.top()), maxOf(formatting.top(), transparent.top()))

    private fun grow() {
        if (count < opener.size) return
        val size = opener.size * 2
        opener = opener.copyOf(size)
        element = element.copyOf(size)
        gone = gone.copyOf(size)
        sameBelow = sameBelow.copyOf(size)
    }

    /** Entries of one kind, topmost last; a removed one is dropped when it reaches the top. */
    private inner class Stack {
        private var entries = IntArray(STACK_INITIAL)
        private var size = 0

        fun push(entry: Int) {
            if (size == entries.size) entries = entries.copyOf(size * 2)
            entries[size++] = entry
        }

        fun top(): Int {
            while (size > 0 && gone[entries[size - 1]]) size--
            return if (size == 0) NONE else entries[size - 1]
        }

        fun clear() {
            size = 0
        }
    }

    private companion object {
        const val INITIAL = 16

        // Most stacks hold a handful of entries, so each starts small.
        const val STACK_INITIAL = 4
        const val NONE = -1

        /** The elements whose end tag closes them only when they are in scope. */
        const val IN_SCOPE = BLOCK or ITEM or TABLE_PART or TABLE_SCOPE

        /** The opener of an element MediaWiki writes for wikitext markup. */
        const val SYNTHETIC = -1

        /** The opener of a self-closing tag, which opens an element but has no body to pair. */
        const val PHANTOM = -2
    }
}
