package com.fenakhay.kwikibot.wikitext


/**
 * Which constructs close, worked out before anything is parsed.
 *
 * This is the half of the parser that makes the other half straightforward. Wikitext is full of
 * openings that turn out not to be openings — `{{`, `[[` and `<ref>` are ordinary text when
 * nothing closes them, which is what MediaWiki renders for `{{Zqx template` and `a <ref>never
 * ends`. A parser that finds that out by trying to parse the construct and giving up has to undo
 * the attempt, and everything it read on the way is then read again by whatever it fell back to.
 * Nest that and the cost doubles per level: a real Wiktionary quotation template with fifty
 * unclosed constructs in it never finished parsing at all.
 *
 * So closure is decided first, in one left-to-right pass with an explicit stack, and the parser
 * that follows never guesses. It asks [closerOf] where a construct ends, is told, and either
 * parses it or emits an opening as the text it is. Nothing is parsed twice, and the exponent
 * is not tamed but absent.
 *
 * This is roughly what MediaWiki's own preprocessor does, which is the other reason to do it:
 * matching by counting brace runs is the behaviour the recorded corpus pins, and it is far
 * easier to match a rule than to match the consequences of one.
 */
internal class Scan private constructor(private val text: String) {

    /**
     * Where each opening construct ends, indexed by the offset it starts at, stored one higher
     * than it is.
     *
     * The offset is kept off by one so that zero can mean "nothing closes here", which is the
     * answer almost everywhere. A sentinel of `-1` would read better and would cost a second pass
     * over the whole array to write it: the JVM has already zeroed this once, and filling it
     * again is one of the few things in the parser that is genuinely proportional to page size
     * without doing any work.
     */
    private val ends = IntArray(text.length)

    /**
     * How many braces open the construct at each offset: three for an argument, two for a
     * template, zero for everything else.
     *
     * A brace run is spent from the right, so the offset alone does not say which of the two
     * opened there — `{{{{{{x}}}}}}` is an argument inside an argument, and both open on the same
     * run. Only braces are ambiguous this way; a `[[` or a `<ref` says what it is.
     */
    private val braceWidths = ByteArray(text.length)

    /**
     * The offset one past the construct opening at [start], or [UNMATCHED].
     *
     * Only meaningful at an offset where a construct actually opens. Anywhere else the answer is
     * [UNMATCHED], which is also the answer for an opening that never closes, and the two do not
     * need telling apart: both mean "there is no construct here".
     */
    fun closerOf(start: Int): Int {
        if (start !in ends.indices) return UNMATCHED
        val stored = ends[start]
        return if (stored == 0) UNMATCHED else stored - 1
    }

    /** Whether a construct opening at [start] closes. */
    fun closes(start: Int): Boolean = closerOf(start) != UNMATCHED

    /** How many braces open the construct at [start]: 3, 2, or 0 if none does. */
    fun braceWidth(start: Int): Int =
        if (start in braceWidths.indices) braceWidths[start].toInt() else 0

    /**
     * One unclosed opening, waiting for its partner.
     *
     * [braces] is how many of a brace run are still unspent — five braces can close as three then
     * two — and is zero for everything else. [name] is a tag's name, and empty otherwise.
     */
    private class Open(val kind: Int, val start: Int, var braces: Int = 0, val name: String = "")

    private fun scan() {
        // Tags are kept off the brace stack, as MediaWiki keeps them out of its preprocessor.
        // They are not alternatives to each other and must not block each other: `<t:smog>` looks
        // like a tag, never closes, and is ordinary text inside `{{col|en|vog<t:smog>}}` - a
        // template that has to close whatever the thing inside it turned out to be.
        val open = ArrayDeque<Open>()
        val openTags = ArrayDeque<Open>()
        var i = 0

        // Dispatch on the character before looking at any string. Five characters out of the
        // whole alphabet open or close anything, so every other one costs a single switch and
        // nothing else - testing startsWith("<!--") at every offset of every page is most of what
        // this pass would otherwise spend its time doing.
        while (i < text.length) {
            i = when (text[i]) {
                in SIGNIFICANT -> step(open, openTags, i)
                else -> i + 1
            }
        }
    }

    /**
     * Acts on one significant character and returns where to look next.
     *
     * Split out because the parallel path needs the same decisions taken in the same order over a
     * list of offsets rather than over the text.
     */
    private fun step(open: ArrayDeque<Open>, openTags: ArrayDeque<Open>, i: Int): Int =
        when (text[i]) {
            '{' -> braceRun(open, i)
            '}' -> closeBraceRun(open, i)
            '[' -> openBracket(open, i)
            ']' -> closeBracket(open, i)
            else -> if (text.startsWith(COMMENT_OPEN, i)) skipComment(i) else tag(openTags, i)
        }

    // ------------------------------------------------------------------ comments and raw tags

    /**
     * Steps over a comment, so nothing inside it is matched.
     *
     * `<!-- {{ -->` contains no template, and a page whose braces balance only because a comment
     * supplied one of them would be parsed quite differently from the way it renders.
     */
    private fun skipComment(start: Int): Int {
        val end = text.indexOf(COMMENT_CLOSE, start + COMMENT_OPEN.length)
        // An unclosed comment is not a comment - MediaWiki renders it literally rather than
        // swallowing the rest of the page - so the `<` is left to be looked at again as text.
        if (end < 0) return start + 1

        ends[start] = end + COMMENT_CLOSE.length + 1
        return end + COMMENT_CLOSE.length
    }

    /**
     * Steps over the body of a tag MediaWiki does not parse, given the offset after its `>`.
     *
     * The whole purpose of `<nowiki>{{x}}</nowiki>` is that the braces inside it are not a
     * template, so they must not be matched as one.
     */
    private fun skipRawTag(start: Int, bodyStart: Int, name: String): Int {
        val closing = "</$name"
        val at = text.indexOf(closing, startIndex = bodyStart, ignoreCase = true)
        if (at < 0) return bodyStart

        val end = text.indexOf('>', at + closing.length)
        if (end < 0) return bodyStart

        ends[start] = end + 2
        return end + 1
    }

    // ------------------------------------------------------------------ braces

    /**
     * Reads a run of `{` and records what each part of it opens.
     *
     * A run is not one opening but several, and the rightmost braces open the innermost
     * construct: `{{{1}}}` is an argument, not a template whose name is `{1`. So the run is
     * pushed whole and spent from the right as closers arrive.
     */
    private fun braceRun(open: ArrayDeque<Open>, start: Int): Int {
        var end = start
        while (end < text.length && text[end] == '{') end++

        val count = end - start
        if (count >= TEMPLATE_BRACES) open.addLast(Open(BRACES, start, braces = count))
        return end
    }

    /**
     * Matches a run of `}` against whatever brace run is still open.
     *
     * Three close an argument and two a template, preferring the argument, which is how
     * `{{{1|x}}}` is an argument rather than a template containing a stray brace. Both runs are
     * spent from the right, so five braces closed by five give an argument inside a template -
     * and `{{{{{|safesubst:}}}x}}` is exactly that on real pages.
     */
    private fun closeBraceRun(open: ArrayDeque<Open>, start: Int): Int {
        var end = start
        while (end < text.length && text[end] == '}') end++
        var available = end - start

        while (available >= TEMPLATE_BRACES) {
            val frame = open.lastOrNull()
            if (frame == null || frame.kind != BRACES || frame.braces < TEMPLATE_BRACES) break

            val spend = if (available >= ARGUMENT_BRACES && frame.braces >= ARGUMENT_BRACES) {
                ARGUMENT_BRACES
            } else {
                TEMPLATE_BRACES
            }

            frame.braces -= spend
            available -= spend
            // The opening this closes is the rightmost unspent part of the run, and the closer is
            // the leftmost unspent part of this one - both are consumed from the inside out.
            val opensAt = frame.start + frame.braces
            ends[opensAt] = end - available + 1
            braceWidths[opensAt] = spend.toByte()
            if (frame.braces < TEMPLATE_BRACES) open.removeLast()
        }

        return end
    }

    // ------------------------------------------------------------------ brackets

    /** Records a `[[` or a `[`, whichever this is. */
    private fun openBracket(open: ArrayDeque<Open>, start: Int): Int {
        val double = text.startsWith("[[", start)
        open.addLast(Open(if (double) WIKILINK else EXTERNAL_LINK, start))
        return start + if (double) 2 else 1
    }

    /**
     * Matches a closing bracket against the innermost opening of the same kind.
     *
     * Only the innermost: in `[[a{{b]]` the `]]` does not reach past the template that opened
     * after it, and the link is left unmatched, which is what MediaWiki shows.
     */
    private fun closeBracket(open: ArrayDeque<Open>, start: Int): Int {
        val double = text.startsWith("]]", start)
        val kind = if (double) WIKILINK else EXTERNAL_LINK
        val width = if (double) 2 else 1

        val frame = open.lastOrNull()
        if (frame != null && frame.kind == kind) {
            open.removeLast()
            ends[frame.start] = start + width + 1
        }
        return start + width
    }

    // ------------------------------------------------------------------ tags

    /**
     * Records a tag opening, matches a closing one, and steps over the bodies left alone.
     *
     * Only well-formed names are considered, so a `<` in running text costs a character-class
     * check and nothing more.
     */
    private fun tag(openTags: ArrayDeque<Open>, start: Int): Int {
        var i = start + 1
        val closing = i < text.length && text[i] == '/'
        if (closing) i++

        val nameStart = i
        while (i < text.length && text[i].isLetterOrDigit()) i++
        if (i == nameStart) return start + 1

        val name = text.substring(nameStart, i).lowercase()
        if (!closing) return openingTag(openTags, start, i, name)

        matchClosingTag(openTags, start, name)
        return i
    }

    /** Records what an opening tag opens, given the offset [afterName] just past its name. */
    private fun openingTag(
        openTags: ArrayDeque<Open>,
        start: Int,
        afterName: Int,
        name: String,
    ): Int {
        // Everything below needs to know where the opening ends, and a `<name` with no `>` after
        // it is not an opening at all - the `<` is text.
        val gt = openingEnd(afterName)
        if (gt < 0) return start + 1

        // A tag that closes itself is complete where it stands. `<br>` is one by being void and
        // `<ref name=a />` by saying so, and neither has a closing tag to look for - so both are
        // matched here rather than pushed and left open for the rest of the page.
        if (name in VOID_TAGS || text[gt - 1] == '/') {
            ends[start] = gt + 2
            return gt + 1
        }

        if (name in RAW_CONTENT_TAGS) return skipRawTag(start, gt + 1, name)

        openTags.addLast(Open(TAG, start, name = name))
        // Only the name is stepped over, not the attributes, so a template written among them is
        // still matched as one.
        return afterName
    }

    /**
     * Where a tag's opening `>` is, given the offset after its name, or `-1` if it has none.
     *
     * Quoting is respected, so the `>` in `<span title="a > b">` does not end the tag early. A
     * second `<` does end the search: whatever the first one started, it was not a tag.
     */
    private fun openingEnd(from: Int): Int {
        var i = from
        var quote = NO_QUOTE

        while (i < text.length) {
            val char = text[i]
            when {
                quote != NO_QUOTE -> if (char == quote) quote = NO_QUOTE
                char == '"' || char == '\'' -> quote = char
                char == '>' -> return i
                char == '<' -> return -1
            }
            i++
        }
        return -1
    }

    /** Pops back to the matching opening tag, if there is one, discarding what was left open. */
    private fun matchClosingTag(open: ArrayDeque<Open>, start: Int, name: String) {
        val depth = open.indexOfLast { it.kind == TAG && it.name == name }
        if (depth < 0) return

        val end = text.indexOf('>', start)
        if (end < 0) return

        // Anything opened inside the tag and never closed is not going to close now.
        while (open.size > depth) {
            val frame = open.removeLast()
            if (open.size == depth) ends[frame.start] = end + 2
        }
    }

    internal companion object {
        /** Nothing closes the construct opening here. */
        const val UNMATCHED: Int = -1

        private const val BRACES = 1
        private const val WIKILINK = 2
        private const val EXTERNAL_LINK = 3
        private const val TAG = 4

        private const val TEMPLATE_BRACES = 2
        private const val ARGUMENT_BRACES = 3

        /** Stands for "not inside a quoted attribute value". */
        private const val NO_QUOTE = ' '

        private const val COMMENT_OPEN = "<!--"
        private const val COMMENT_CLOSE = "-->"

        /** Tags that never have a body, so `<br>` is complete as written. */
        private val VOID_TAGS = setOf("br", "wbr", "hr", "meta", "link", "img")

        /** Tags whose contents MediaWiki does not parse; their bodies are stepped over whole. */
        private val RAW_CONTENT_TAGS =
            setOf("nowiki", "pre", "syntaxhighlight", "source", "math", "score")

        /** The five characters that open or close anything. */
        private val SIGNIFICANT = charArrayOf('{', '}', '[', ']', '<')

        /** Works out what closes in [text]. */
        fun of(text: String): Scan = Scan(text).apply { scan() }
    }
}
