package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.ARGUMENT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.COMMENT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.COMMENT_OPEN_ENDED
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.EXT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.EXT_SHORT
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.LINK
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.LINK_INVALID
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.LINK_VALID
import com.fenakhay.kwikibot.wikitext.internal.Preprocessor.Companion.TEMPLATE

/**
 * Says what MediaWiki reads after its preprocessor, for the [Tokenizer] to emit.
 *
 * Links, HTML tags, bold and italic, external links and bare URLs are all read in the text the templates
 * expand to, which is not known here, so each has to be found inside one segment of the page and none is read
 * across a template's boundaries. Each also has a partner to find (a `]`, a closing tag, a second `''`).
 * Every search for one either skips what it has already seen or remembers that it failed, so a page of
 * unclosed openings is read in one pass.
 *
 * Nothing here emits anything: the answers are positions, and the [Tokenizer] decides what to do with them.
 */
internal class Scanner(
    private val text: String,
    private val pp: Preprocessor,
    private val options: ParseOptions,
) {

    private val kinds = pp.kinds

    /** Where the document being read starts: the page, or the body of an extension tag. */
    var documentStart: Int = 0

    /**
     * The end of a construct opening at [at] that the text around it treats as one opaque piece, or -1.
     *
     * Templates, arguments, comments and extension tags always are, since MediaWiki has expanded or removed
     * them by the time it reads the text around them. A link is when [links] asks and it is one.
     */
    fun opaqueEnd(at: Int, limit: Int, links: Boolean = true): Int {
        val end =
            when (kinds[at]) {
                TEMPLATE,
                ARGUMENT,
                COMMENT,
                COMMENT_OPEN_ENDED,
                EXT,
                EXT_SHORT -> pp.end(at)
                LINK,
                LINK_VALID,
                LINK_INVALID -> if (links && at + 1 < limit && text[at + 1] == '[') linkEnd(at) else -1
                else -> -1
            }
        return if (end in 0..limit) end else -1
    }

    /** The end of a template, argument or comment opening at [at], or -1: what a title or a URL may hold. */
    private fun expansionEnd(at: Int): Int {
        val kind = kinds[at]
        return if (kind == TEMPLATE || kind == ARGUMENT || kind == COMMENT) pp.end(at) else -1
    }

    // ------------------------------------------------------------------ links

    /**
     * Where the link opening at [start] ends, or -1 if MediaWiki would not make it a link.
     *
     * The preprocessor pairs `[[` with `]]` only so a `|` between them does not split a template; whether the
     * pair is a link is decided later, by `handleInternalLinks`, and on rules of its own. Those are checked
     * once and the answer kept.
     */
    fun linkEnd(start: Int): Int {
        if (kinds[start] == LINK) kinds[start] = if (isValidLink(start)) LINK_VALID else LINK_INVALID
        return if (kinds[start] == LINK_VALID) pp.end(start) else -1
    }

    /**
     * Whether the `[[` at [start] makes a link, by MediaWiki's rules.
     *
     * The target has to be made of characters a title may hold, and must not be a URL. The text after the `|`
     * must not be empty and must not hold another `[[`, except in a file link, whose caption can. And
     * MediaWiki splits a run of `[` into pairs from the left, so in `[[[x]]]` the pair the preprocessor
     * closed is not the pair MediaWiki reads as a link.
     */
    private fun isValidLink(start: Int): Boolean {
        val end = pp.end(start)
        val inner = end - LINK_BRACKETS
        val targetEnd = targetEnd(start + LINK_BRACKETS, inner)
        val valid =
            (start - bracketRunStart(start)) % 2 == 0 &&
                targetEnd >= 0 &&
                // A target starting with a URL scheme, `//` included, is no link.
                schemeAt(skipSpaces(start + LINK_BRACKETS, inner), inner) == null &&
                (targetEnd == inner || labelAllowed(start + LINK_BRACKETS, targetEnd, inner))

        if (valid && takesThirdBracket(end, targetEnd, inner)) pp.ends[start] = end + 2
        return valid
    }

    /**
     * Whether the link whose text runs from [pipe] to [inner] ends one `]` later than the preprocessor said.
     *
     * In `[[a|[http://x y]]]` the preprocessor closed the link on the first two `]`, and MediaWiki moves the
     * third inside so the external link in the text closes.
     */
    private fun takesThirdBracket(end: Int, pipe: Int, inner: Int): Boolean =
        pipe < inner && end < text.length && text[end] == ']' && hasBracket(pipe, inner)

    /**
     * Where a link target starting at [from] ends (at its `|`, or at [inner]), or -1 if it is no title.
     *
     * A template or argument in it counts as title, since it expands to one; a comment does not, since it is
     * removed.
     */
    private fun targetEnd(from: Int, inner: Int): Int {
        var at = from
        var hasTitle = false
        while (at < inner && text[at] != '|') {
            val skip = expansionEnd(at)
            if (skip >= 0) {
                if (kinds[at] != COMMENT) hasTitle = true
                at = skip
            } else {
                val char = text[at]
                if (kinds[at] != 0.toByte() || !isTitleChar(char)) return -1
                if (char != ' ' && char != '_') hasTitle = true
                at++
            }
        }
        return if (hasTitle) at else -1
    }

    /** Whether the text of a link whose target is [targetStart] to [pipe] may be a link's text. */
    private fun labelAllowed(targetStart: Int, pipe: Int, inner: Int): Boolean {
        if (pipe + 1 >= inner) return false
        if (isFileLink(targetStart, pipe)) return true

        var at = pipe + 1
        while (at < inner) {
            val skip = pp.end(at).takeIf { isOpaqueKind(kinds[at]) } ?: -1
            if (skip >= 0) {
                at = skip
            } else {
                if (text[at] == '[' && at + 1 < inner && text[at + 1] == '[') return false
                at++
            }
        }
        return true
    }

    /** Whether the link text between [pipe] and [inner] holds a `[` of its own. */
    private fun hasBracket(pipe: Int, inner: Int): Boolean {
        var at = pipe + 1
        while (at < inner) {
            val skip = pp.end(at).takeIf { isOpaqueKind(kinds[at]) } ?: -1
            if (skip >= 0) {
                at = skip
            } else {
                if (text[at] == '[') return true
                at++
            }
        }
        return false
    }

    // The last run of `[` measured, so the links opening in one long run measure it once.
    private var bracketRunFrom = -1
    private var bracketRunTo = -1

    /** Where the run of `[` that [start] is in begins. */
    private fun bracketRunStart(start: Int): Int {
        if (start in bracketRunFrom until bracketRunTo) return bracketRunFrom
        var from = start
        while (from > documentStart && text[from - 1] == '[') from--
        var to = start
        while (to < text.length && text[to] == '[') to++
        bracketRunFrom = from
        bracketRunTo = to
        return from
    }

    /** The `|` that ends the target of the link at [start], or -1 if it has none. */
    fun linkPipe(start: Int, inner: Int): Int {
        var at = start + LINK_BRACKETS
        while (at < inner && text[at] != '|') {
            val skip = expansionEnd(at)
            at = if (skip >= 0) skip else at + 1
        }
        return if (at < inner) at else -1
    }

    /**
     * Whether the valid link at [start] is a file shown as a figure: framed, a thumbnail, or aligned, which
     * MediaWiki renders as a block. A file with none of those options is shown inline.
     */
    fun isFigure(start: Int): Boolean {
        val inner = pp.end(start) - LINK_BRACKETS
        val pipe = linkPipe(start, inner)
        if (pipe < 0 || !isFileLink(start + LINK_BRACKETS, pipe)) return false
        var optionStart = pipe + 1
        var at = optionStart
        while (at <= inner) {
            if (at == inner || text[at] == '|') {
                if (isFigureOption(optionStart, at)) return true
                optionStart = at + 1
                at++
            } else {
                val skip = if (kinds[at] == 0.toByte()) -1 else opaqueEnd(at, inner)
                at = if (skip > at) skip else at + 1
            }
        }
        return false
    }

    private fun isFigureOption(from: Int, to: Int): Boolean {
        val option = text.substring(from, to).trim()
        return option in FIGURE_OPTIONS || option.startsWith("thumb=") || option.startsWith("thumbnail=")
    }

    /** Whether the target between [from] and [to] names a file, whose caption may hold links. */
    private fun isFileLink(from: Int, to: Int): Boolean {
        val target = skipSpaces(from, to)
        val colon = text.indexOf(':', target)
        if (target >= to || text[target] == ':' || colon !in target until to) return false
        val prefix = text.substring(target, colon).trim().replace('_', ' ').lowercase()
        return prefix in options.fileNamespacesLower
    }

    private fun skipSpaces(from: Int, to: Int): Int {
        var at = from
        while (at < to && text[at] == ' ') at++
        return at
    }

    // ------------------------------------------------------------------ external links

    // What [bracketedLinkAt] found.
    var urlEnd: Int = 0
        private set

    var labelStart: Int = 0
        private set

    var labelEnd: Int = 0
        private set

    /**
     * Whether `[url text]` opens at [start] and closes by [limit], setting [urlEnd], [labelStart] and
     * [labelEnd] if so.
     *
     * MediaWiki's rule, from `handleExternalLinks`: a scheme, at least one URL character, any run of spaces,
     * and text up to the first `]` with no line break in it. A template inside the URL is part of it, as its
     * expansion would be.
     */
    fun bracketedLinkAt(start: Int, limit: Int): Boolean {
        val scheme = schemeAt(start + 1, limit) ?: return false
        val urlStart = start + 1 + scheme.length

        val address = if (urlStart < limit && text[urlStart] == '[') ipv6End(urlStart, limit) else urlStart
        val url = if (address < 0) -1 else urlRun(address, limit, free = false)
        if (url <= urlStart) return false

        var at = url
        while (at < limit && isSpaceSeparator(text[at])) at++
        val close = closingBracket(at, limit)
        if (close < 0) return false

        urlEnd = url
        labelStart = at
        labelEnd = close
        return true
    }

    /** The end of an IPv6 address in brackets at [at], `[::1]`, or -1. */
    private fun ipv6End(at: Int, limit: Int): Int {
        var close = at + 1
        while (close < limit && isAddressChar(text[close])) close++
        return if (close < limit && text[close] == ']' && close > at + 1) close + 1 else -1
    }

    /**
     * Where a bare URL starting at [start] ends, or -1 if none starts there.
     *
     * Only an absolute scheme starts one, at the start of a word. Trailing punctuation is left out of it as
     * MediaWiki leaves it out: a sentence ending "see https://example.org." links the site, not the full
     * stop.
     */
    fun freeLinkEnd(start: Int, limit: Int): Int {
        if (start > 0 && isWordChar(text[start - 1])) return -1
        val scheme = absoluteSchemeAt(start, limit) ?: return -1

        val bodyStart = start + scheme.length
        val end = trimUrl(bodyStart, urlRun(bodyStart, limit, free = true))
        return if (end > bodyStart) end else -1
    }

    /** Whether a word starting at [at] could be a URL, which is when its letters run into a `:`. */
    fun couldStartUrl(at: Int, limit: Int): Boolean {
        var end = at
        while (end < limit && end - at <= MAX_SCHEME && isSchemeChar(text[end])) end++
        return end > at && end < limit && text[end] == ':'
    }

    /** Where the last [urlRun] last left a template or comment: only text after it can be trimmed. */
    private var urlTextStart = 0

    /**
     * The URL characters from [from], with any template or comment in them, up to [limit].
     *
     * A bare URL also stops at `''`, which MediaWiki has turned into a tag by the time it looks for URLs, and
     * at an escaped `<`, `>` or no-break space.
     */
    private fun urlRun(from: Int, limit: Int, free: Boolean): Int {
        var at = from
        urlTextStart = from
        var stopped = false
        while (at < limit && !stopped) {
            val skip = expansionEnd(at)
            when {
                skip in 0..limit -> {
                    at = skip
                    urlTextStart = skip
                }
                skip > limit || !isUrlChar(text[at]) || (free && stopsBareUrl(at, limit)) -> stopped = true
                else -> at++
            }
        }
        return at
    }

    /** Whether a bare URL stops at [at]: at `''`, or at `&lt;`, `&gt;` or `&nbsp;`. */
    private fun stopsBareUrl(at: Int, limit: Int): Boolean =
        when (text[at]) {
            APOSTROPHE -> at + 1 < limit && text[at + 1] == APOSTROPHE
            '&' -> URL_STOPPING_ENTITY.matchesAt(text, at)
            else -> false
        }

    /** Where a bare URL running from [from] to [to] ends once trailing punctuation is left out. */
    private fun trimUrl(from: Int, to: Int): Int {
        // A template at the end could expand to anything, so only the text after the last one is trimmed.
        val textStart = urlTextStart
        val parenthesis = text.indexOf('(', from) in from until to

        var end = to
        while (end > textStart && trims(text[end - 1], parenthesis)) end--

        // `&amp;` keeps its semicolon.
        if (end < to && text[end] == ';' && endsWithEntityName(textStart, end)) end++
        return end
    }

    private fun trims(char: Char, parenthesis: Boolean): Boolean =
        char in URL_TRAILING || (!parenthesis && char == ')')

    /** Whether the text before [end] is `&name`, `&#123` or `&#x7B`, as MediaWiki's check reads them. */
    private fun endsWithEntityName(from: Int, end: Int): Boolean {
        val name = runStart(from, end, ::isAsciiLetter)
        if (name < end && precededBy(from, name, "&")) return true
        val decimal = runStart(from, end) { it in '0'..'9' }
        if (decimal < end && precededBy(from, decimal, "&#")) return true
        val hex = runStart(from, end, ::isHexDigit)
        return hex < end && (precededBy(from, hex, "&#x") || precededBy(from, hex, "&#X"))
    }

    /** Where the run of characters that match, ending at [end], starts, not before [from]. */
    private inline fun runStart(from: Int, end: Int, matches: (Char) -> Boolean): Int {
        var at = end
        while (at > from && matches(text[at - 1])) at--
        return at
    }

    private fun precededBy(from: Int, at: Int, prefix: String): Boolean =
        at - prefix.length >= from && text.startsWith(prefix, at - prefix.length)

    private var bracketMemoLimit = -1
    private var bracketMemoFrom = -1
    private var bracketMemoTo = -1

    /**
     * The `]` that closes an external link whose text starts at [from], or -1.
     *
     * A failed search is remembered: `[http://a [http://b [http://c` on one long line would otherwise search
     * to its end once per bracket.
     */
    private fun closingBracket(from: Int, limit: Int): Int {
        if (limit == bracketMemoLimit && from >= bracketMemoFrom && from < bracketMemoTo) return -1

        var at = from
        var found = -1
        while (at < limit && found < 0) {
            val skip = if (kinds[at] == 0.toByte()) -1 else opaqueEnd(at, limit)
            val char = text[at]
            when {
                skip >= 0 -> at = skip
                char == ']' -> found = at
                endsLinkText(char) -> break
                else -> at++
            }
        }
        if (found >= 0) return found

        bracketMemoLimit = limit
        bracketMemoFrom = from
        bracketMemoTo = at
        return -1
    }

    /** The scheme at [position], `//` included, or `null`. */
    fun schemeAt(position: Int, limit: Int): String? {
        if (position >= limit) return null
        val code = text[position].code
        val candidates = if (code < ASCII) options.schemesByFirst[code] else null
        return candidates?.firstOrNull { scheme ->
            position + scheme.length <= limit && text.startsWith(scheme, position, ignoreCase = true)
        }
    }

    /** The scheme at [position] other than `//`, which never makes a bare link. */
    private fun absoluteSchemeAt(position: Int, limit: Int): String? =
        schemeAt(position, limit)?.takeIf { it != PROTOCOL_RELATIVE }

    // ------------------------------------------------------------------ HTML tags

    // What [htmlTagAt] found, kept in fields so recognising a tag allocates nothing.
    var tagNameEnd: Int = 0
        private set

    var tagEnd: Int = 0
        private set

    var tagClosing: Boolean = false
        private set

    var tagSelfClosing: Boolean = false
        private set

    var tagVoid: Boolean = false
        private set

    /**
     * Whether an HTML tag the wiki allows starts at [start] and ends by [limit], setting [tagNameEnd],
     * [tagEnd] and the flags if so.
     *
     * MediaWiki's sanitizer reads a tag as `<`, a name, anything but `>`, and `>` or `/>`, and lets it
     * through only if the name is one it allows: `<img>` and `<foo>` are text. A template or comment among
     * the attributes is fine, since it is gone by the time the sanitizer looks.
     */
    fun htmlTagAt(start: Int, limit: Int): Boolean {
        val closing = start + 1 < limit && text[start + 1] == '/'
        val nameStart = if (closing) start + 2 else start + 1
        val nameEnd = htmlNameEnd(nameStart, limit)
        val end = if (nameEnd < 0) -1 else tagClose(nameEnd, limit, closing)
        if (end < 0) return false

        tagNameEnd = nameEnd
        tagEnd = end
        tagClosing = closing
        tagSelfClosing = !closing && end - 1 >= nameEnd && text[end - 1] == '/'
        tagVoid = TagNames.VOID.contains(text, nameStart, nameEnd)
        return true
    }

    /** The end of an allowed tag name starting at [from], or -1. */
    private fun htmlNameEnd(from: Int, limit: Int): Int {
        if (from >= limit || !isAsciiLetter(text[from])) return -1
        var at = from
        while (at < limit && !endsHtmlName(text[at])) at++
        val allowed =
            at - from <= MAX_HTML_NAME &&
                (options.htmlTagNames.contains(text, from, at) ||
                    TagNames.TRANSCLUSION.contains(text, from, at))
        return if (allowed) at else -1
    }

    /**
     * The `>` that ends a tag whose name ends at [from], or -1.
     *
     * A `<` before it means this was never a tag. A template or comment may sit among an opening tag's
     * attributes; one in a closing tag means it is not read as a tag here.
     */
    private fun tagClose(from: Int, limit: Int, closing: Boolean): Int {
        var at = from
        while (at < limit) {
            val kind = kinds[at]
            val skip = if (kind == 0.toByte() || closing) -1 else opaqueEnd(at, limit, links = false)
            when {
                skip >= 0 -> at = skip
                kind != 0.toByte() && (closing || Preprocessor.isHeading(kind)) -> return -1
                text[at] == '>' -> return at
                text[at] == '<' -> return -1
                else -> at++
            }
        }
        return -1
    }

    private val pairing by lazy { TagPairing(text, pp, this, options) }

    /**
     * Pairs the HTML tags of the segment between [from] and [limit], read in [mode], where MediaWiki ends the
     * elements they open. See [TagPairing].
     */
    fun pairTags(from: Int, limit: Int, mode: BodyMode) {
        pairing.pair(from, limit, mode, documentStart)
    }

    // ------------------------------------------------------------------ bold and italic

    private val styleMemoLimit = intArrayOf(-1, -1)
    private val styleMemoFrom = intArrayOf(-1, -1)
    private val styleMemoTo = intArrayOf(-1, -1)

    /**
     * The `''` or `'''` that closes [markup] opened before [from], or -1.
     *
     * The partner is looked for on the same line, as MediaWiki applies apostrophes a line at a time, and at
     * the same level: a `''` inside a link is not this one's partner, since MediaWiki reads a link's text on
     * its own, and one inside a template is not either, since what the template expands to is not known here.
     * A search that fails is remembered, so a line of unpartnered `''` stays linear.
     */
    fun styleCloser(from: Int, limit: Int, markup: String): Int {
        val slot = markup.length - ITALIC_LENGTH
        if (styleMemoLimit[slot] == limit && from >= styleMemoFrom[slot] && from < styleMemoTo[slot])
            return -1

        var at = from
        var found = -1
        while (at < limit && found < 0 && text[at] != '\n') {
            val skip = if (kinds[at] == 0.toByte()) -1 else opaqueEnd(at, limit)
            when {
                skip >= 0 -> at = skip
                text[at] == APOSTROPHE && text.startsWith(markup, at) -> found = at
                else -> at++
            }
        }
        if (found >= 0 && found + markup.length <= limit) return found

        styleMemoLimit[slot] = limit
        styleMemoFrom[slot] = from
        styleMemoTo[slot] = at
        return -1
    }

    internal companion object {
        const val ASCII = 128
        const val LINK_BRACKETS = 2
        const val APOSTROPHE = '\''
        private const val ITALIC_LENGTH = 2
        private const val MAX_HTML_NAME = 12
        private const val MAX_SCHEME = 16
        private const val PROTOCOL_RELATIVE = "//"
        private const val DELETE = 0x7F

        /** U+FFFD, which MediaWiki keeps out of URLs and link text. */
        private const val REPLACEMENT = 0xFFFD

        /**
         * The image options that make MediaWiki render a file as a figure, a block, in their English
         * spelling.
         */
        private val FIGURE_OPTIONS =
            setOf("thumb", "thumbnail", "frame", "framed", "left", "right", "center", "centre", "none")

        /** Punctuation a bare URL never ends with. A `)` joins them when the URL has no `(`. */
        private const val URL_TRAILING = ",;.:!?"

        /** The entities that end a bare URL, from `Parser::makeFreeExternalLink`. */
        private val URL_STOPPING_ENTITY = Regex("&(lt|gt|nbsp|#x0*(3[CcEe]|[Aa]0)|#0*(60|62|160));")

        /** What MediaWiki allows in a title, `Title::legalChars()` with `#` and `%`. */
        private val TITLE_CHARS =
            BooleanArray(ASCII).apply {
                for (char in " %!\"$&'()*,-./:;=?@\\^_`~+#") this[char.code] = true
                for (char in '0'..'9') this[char.code] = true
                for (char in 'A'..'Z') this[char.code] = true
                for (char in 'a'..'z') this[char.code] = true
            }

        fun isTitleChar(char: Char): Boolean = char.code >= ASCII || TITLE_CHARS[char.code]

        /** Templates, arguments, comments and extension tags, which no link text, URL or tag reaches into. */
        fun isOpaqueKind(kind: Byte): Boolean =
            kind == TEMPLATE || kind == ARGUMENT || kind == COMMENT || kind == EXT || kind == EXT_SHORT

        /** `EXT_LINK_URL_CLASS`: anything but brackets, `<`, `>`, `"`, controls, spaces and U+FFFD. */
        fun isUrlChar(char: Char): Boolean =
            when {
                char.code <= ' '.code || char.code == DELETE || char.code == REPLACEMENT -> false
                char == '[' || char == ']' || char == '<' || char == '>' || char == '"' -> false
                else -> !isSpaceSeparator(char)
            }

        /** What a bracketed link's text may not hold: a line break, a control character or U+FFFD. */
        fun endsLinkText(char: Char): Boolean = (char < ' ' && char != '\t') || char.code == REPLACEMENT

        /** Unicode's space separators, `\p{Zs}`. */
        fun isSpaceSeparator(char: Char): Boolean =
            Character.getType(char) == Character.SPACE_SEPARATOR.toInt()

        /** A word character for `\b` with Unicode on: a letter, a digit or `_`. */
        fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

        fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

        /** What a URL scheme is spelled with, by RFC 3986. */
        fun isSchemeChar(char: Char): Boolean =
            isAsciiLetter(char) || char in '0'..'9' || char == '+' || char == '-' || char == '.'

        fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

        /** What an IPv6 address in a URL is written with: hex digits, `:` and `.`. */
        fun isAddressChar(char: Char): Boolean = isHexDigit(char) || char == ':' || char == '.'

        /** What ends an HTML tag name for the sanitizer: whitespace, `/`, `>` or another `<`. */
        fun endsHtmlName(char: Char): Boolean =
            when (char) {
                ' ',
                '\t',
                '\n',
                '\r',
                '\u000B',
                '\u000C',
                '/',
                '>',
                '<',
                '\u0000' -> true
                else -> false
            }

        fun isLink(kind: Byte): Boolean = kind == LINK || kind == LINK_VALID || kind == LINK_INVALID
    }
}
