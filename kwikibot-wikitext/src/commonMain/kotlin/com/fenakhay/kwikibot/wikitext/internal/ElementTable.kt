package com.fenakhay.kwikibot.wikitext.internal

/**
 * What each HTML element a wiki allows is to HTML5's tree builder, which MediaWiki's tidying runs on every
 * page: whether it is a formatting element, a special one, a scope boundary, and so on.
 *
 * The categories are HTML5's own, as the Remex library MediaWiki uses implements them, cut down to the
 * elements MediaWiki's sanitizer lets through. The transclusion tags are added as a category of their own: a
 * page's source keeps them as wrappers, and nothing pairs across one.
 */
internal class ElementTable(htmlTags: Set<String>) {

    /** The names, by element id. */
    val names: NameSet = NameSet(STRUCTURAL + htmlTags + TagNames.TRANSCLUSION.names)

    /** Each element's flags, by element id. */
    private val flags: IntArray = IntArray(names.names.size) { flagsOf(names.names[it]) }

    /** What each element's opening and closing tags mean to the paragraph pass, by element id. */
    private val lineFlags: IntArray = IntArray(names.names.size) { lineFlagsOf(names.names[it]) }

    /** Whether element [id]'s tag, closing or not, carries [flag] for the paragraph pass. */
    fun onLine(id: Int, flag: Int): Boolean = lineFlags[id] and flag != 0

    /** The element named by the text between [from] and [to], or -1. */
    fun idOf(text: CharSequence, from: Int, to: Int): Int = names.indexOf(text, from, to)

    /** The element named [name], or -1 if the wiki does not allow it. */
    fun idOf(name: String): Int = idOf(name, 0, name.length)

    /** The element named [name], which must be one of the structural ones. */
    fun id(name: String): Int = idOf(name).also { check(it >= 0) { name } }

    fun has(id: Int, flag: Int): Boolean = flags[id] and flag != 0

    val size: Int
        get() = flags.size

    companion object {
        const val FORMATTING = 1
        const val SPECIAL = 2

        /** Ends the default scope: an end tag looking for its element stops here. */
        const val SCOPE = 4

        /** Ends list-item scope as well: `ul` and `ol`. */
        const val LIST_SCOPE = 8

        /** Ends table scope: `table`. */
        const val TABLE_SCOPE = 16

        /**
         * A marker in the active formatting elements: a formatting element outside one is not closed in it.
         */
        const val MARKER = 32

        /** `noinclude` and the other transclusion wrappers, which nothing pairs across. */
        const val TRANSPARENT = 64
        const val VOID = 128
        const val HEADING = 256

        /** An element whose end tag closes it only when it is in scope, and otherwise does nothing. */
        const val BLOCK = 512

        /** `li`, `dd` and `dt`, which close an open item of their kind when a new one starts. */
        const val ITEM = 1024

        /** A table part: `tr`, `td`, `th` and `caption`. */
        const val TABLE_PART = 2048

        /** A special element that a new `li` does not look past for an open one. */
        const val STOPS_ITEM = 4096

        // What a tag means to the paragraph pass, by `BlockLevelPass`'s two lists: its line holds a block
        // (an open match) or ends one (a close match).
        const val OPENS_ON_START = 1
        const val OPENS_ON_END = 2
        const val CLOSES_ON_START = 4
        const val CLOSES_ON_END = 8

        private val OPEN_MATCH_START =
            setOf("table", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "p", "ul", "ol", "dl") +
                setOf("tr", "caption", "dt", "dd", "li")
        private val OPEN_MATCH_END = setOf("td", "th", "tr", "caption", "dt", "dd", "li")
        private val CLOSE_MATCH_START = setOf("td", "th", "center", "blockquote", "div", "hr")
        private val CLOSE_MATCH_END =
            setOf("table", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "p", "ul", "ol", "dl", "center") +
                setOf("blockquote", "div")

        private fun lineFlagsOf(name: String): Int =
            (if (name in OPEN_MATCH_START) OPENS_ON_START else 0) or
                (if (name in OPEN_MATCH_END) OPENS_ON_END else 0) or
                (if (name in CLOSE_MATCH_START) CLOSES_ON_START else 0) or
                (if (name in CLOSE_MATCH_END) CLOSES_ON_END else 0)

        /** The elements wikitext markup makes, which are known whatever the wiki allows. */
        private val STRUCTURAL =
            listOf("p", "pre", "ul", "ol", "dl", "li", "dd", "dt", "table", "tr", "td", "th", "caption") +
                (1..6).map { "h$it" }

        private val FORMATTING_NAMES =
            setOf("b", "big", "code", "em", "font", "i", "s", "small", "strike", "strong", "tt", "u")
        private val BLOCK_NAMES = setOf("div", "center", "blockquote", "ol", "ul", "dl", "pre")
        private val VOID_NAMES = setOf("br", "wbr", "hr", "meta", "link")

        private fun flagsOf(name: String): Int {
            val flags = kindOf(name)
            // A new item looks past an open `div` or `p` for the item it closes, and stops at any other
            // special element, an item of another kind included.
            val stops = flags and SPECIAL != 0 && name != "div" && name != "p"
            return if (stops) flags or STOPS_ITEM else flags
        }

        private fun kindOf(name: String): Int =
            KINDS[name] ?: if (name in TagNames.TRANSCLUSION) TRANSPARENT else 0

        /** Each element's category, by name; an element not listed is an ordinary one, such as `span`. */
        private val KINDS: Map<String, Int> = buildMap {
            for (name in FORMATTING_NAMES) put(name, FORMATTING)
            for (name in VOID_NAMES) put(name, VOID)
            for (name in BLOCK_NAMES) put(name, SPECIAL or BLOCK)
            for (name in listOf("ul", "ol")) put(name, SPECIAL or BLOCK or LIST_SCOPE)
            for (level in 1..6) put("h$level", SPECIAL or HEADING)
            for (name in listOf("li", "dd", "dt")) put(name, SPECIAL or ITEM)
            for (name in listOf("td", "th", "caption")) put(name, SPECIAL or SCOPE or MARKER or TABLE_PART)
            put("p", SPECIAL)
            put("table", SPECIAL or SCOPE or TABLE_SCOPE)
            put("tr", SPECIAL or TABLE_PART)
        }
    }
}
