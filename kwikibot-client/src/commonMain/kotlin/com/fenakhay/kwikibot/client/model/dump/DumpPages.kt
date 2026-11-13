@file:Suppress(
    "MagicNumber",
    "TooManyFunctions",
    "LoopWithTooManyJumpStatements",
    "LongMethod",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "ComplexCondition",
)

package com.fenakhay.kwikibot.client.model.dump

import com.fenakhay.kwikibot.client.model.DumpPage
import com.fenakhay.kwikibot.model.MwTimestamp

private val PAGE = "page".encodeToByteArray()
private val REVISION = "revision".encodeToByteArray()
private val DUMP = "mediawiki".encodeToByteArray()
private val DTD = "!DOCTYPE".encodeToByteArray()
private val XML_DECLARATION = "<?xml".encodeToByteArray()
private val DECLARATION_END = "?>".encodeToByteArray()
private val ENCODING = "encoding".encodeToByteArray()
private val UTF8_ENCODINGS = setOf("utf-8", "utf8", "us-ascii", "ascii")

private const val LESS = '<'.code.toByte()
private const val GREATER = '>'.code.toByte()
private const val SLASH = '/'.code.toByte()
private const val AMPERSAND = '&'.code.toByte()
private const val BANG = '!'.code.toByte()
private const val QUESTION = '?'.code.toByte()
private const val CARRIAGE_RETURN = '\r'.code.toByte()

private const val NO_MATCH = -1
private const val INCOMPLETE = -2
private const val MAX_DECLARATION = 1 shl 12

/**
 * The most of a page that is held to parse it: its first revision and what comes before.
 *
 * MediaWiki keeps a revision's text in a column of 16 MiB at most, and escaping can make that six times
 * longer. Anything larger than this is no page, and refusing it keeps a small crafted file from filling the
 * heap.
 */
private const val MAX_PAGE = 1 shl 28

/** Where [pattern] first appears in [bytes] between [from] and [to], or -1. */
internal fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int, to: Int): Int {
    val first = pattern[0]
    val last = to - pattern.size
    var index = from
    while (index <= last) {
        if (bytes[index] == first) {
            var matched = 1
            while (matched < pattern.size && bytes[index + matched] == pattern[matched]) matched++
            if (matched == pattern.size) return index
        }
        index++
    }
    return -1
}

/**
 * Cuts a stream of XML into the bytes of its pages, without decoding any of it.
 *
 * A dump escapes `<` in text, so the tags that open and close a page mark real boundaries and a page can be
 * cut out by searching. That is what lets separate threads parse separate pages. Only the first revision of a
 * page is kept, as that is all [parsePage] reads, and the rest is passed over without being held, so a
 * full-history dump takes no more memory than any other. MediaWiki writes no CDATA sections; a dump from
 * elsewhere with `</page>` inside one would be cut wrongly.
 *
 * @param maxPage the most of a page to hold before refusing it.
 */
internal class PageSplitter(private val maxPage: Int = MAX_PAGE) {
    private var carry = ByteArray(1 shl 16)
    private var length = 0
    private var searched = 0
    private var state = State.OUTSIDE
    private var pageStart = 0
    private var firstRevision: ByteArray? = null
    private var finished = false
    private var seenPrologue = false
    private var declarationRead = false

    private enum class State {
        OUTSIDE,
        IN_PAGE,
        PAST_FIRST_REVISION,
    }

    /** Takes the next [count] bytes of the XML, and hands on each page that is now whole. */
    inline fun feed(bytes: ByteArray, count: Int, page: (ByteArray) -> Unit) {
        append(bytes, count)
        while (true) page(next() ?: break)
        compact()
    }

    /**
     * Says the XML has ended.
     *
     * @throws DumpFormatException if it ended before the dump did.
     */
    fun finish() {
        if (state != State.OUTSIDE) truncated("a page")
        if (!finished) truncated("the dump's last page")
    }

    @PublishedApi
    internal fun append(bytes: ByteArray, count: Int) {
        if (length + count > carry.size) carry = carry.copyOf(maxOf(carry.size * 2, length + count))
        bytes.copyInto(carry, length, 0, count)
        length += count
    }

    @PublishedApi
    internal fun next(): ByteArray? {
        if (!declarationRead && !readDeclaration()) return null
        while (true) {
            val at = nextLess(carry, searched, length)
            if (at < 0) {
                searched = length
                refuseIfTooLong()
                return null
            }
            val result =
                when (state) {
                    State.OUTSIDE -> outside(at)
                    State.IN_PAGE -> inPage(at)
                    State.PAST_FIRST_REVISION -> pastFirstRevision(at)
                }
            if (result === WAIT) {
                searched = at
                refuseIfTooLong()
                return null
            }
            if (result != null) return result
        }
    }

    /** The page this tag makes whole, or [WAIT] until more has arrived, or `null` if it makes none. */
    private fun outside(at: Int): ByteArray? {
        if (!seenPrologue) {
            when (startsWith(carry, at + 1, length, DTD)) {
                true -> corrupt("this dump has a DTD, which is refused")
                null -> return WAIT
                false -> Unit
            }
        }
        val open = tagEnd(at, PAGE, closing = false)
        if (open == INCOMPLETE) return WAIT
        if (open == NO_MATCH) {
            val close = tagEnd(at, DUMP, closing = true)
            if (close == INCOMPLETE) return WAIT
            if (close != NO_MATCH) finished = true
            searched = at + 1
            return null
        }
        seenPrologue = true
        searched = open
        if (carry[open - 2] == SLASH) return carry.copyOfRange(at, open)
        pageStart = at
        state = State.IN_PAGE
        return null
    }

    private fun inPage(at: Int): ByteArray? {
        val close = tagEnd(at, PAGE, closing = true)
        if (close == INCOMPLETE) return WAIT
        if (close != NO_MATCH) {
            state = State.OUTSIDE
            searched = close
            return carry.copyOfRange(pageStart, close)
        }
        val revision = tagEnd(at, REVISION, closing = true)
        if (revision == INCOMPLETE) return WAIT
        if (revision != NO_MATCH) {
            firstRevision = carry.copyOfRange(pageStart, revision)
            state = State.PAST_FIRST_REVISION
            searched = revision
            return null
        }
        searched = at + 1
        return null
    }

    private fun pastFirstRevision(at: Int): ByteArray? {
        val close = tagEnd(at, PAGE, closing = true)
        if (close == INCOMPLETE) return WAIT
        if (close == NO_MATCH) {
            searched = at + 1
            return null
        }
        val page = firstRevision
        firstRevision = null
        state = State.OUTSIDE
        searched = close
        return page
    }

    /**
     * Past the `>` of the tag at [at] if it is the one named, [NO_MATCH] if it is another, or [INCOMPLETE] if
     * what has arrived does not say yet.
     */
    private fun tagEnd(at: Int, name: ByteArray, closing: Boolean): Int {
        var index = at + 1
        if (closing) {
            if (index >= length) return INCOMPLETE
            if (carry[index] != SLASH) return NO_MATCH
            index++
        }
        when (startsWith(carry, index, length, name)) {
            false -> return NO_MATCH
            null -> return INCOMPLETE
            true -> index += name.size
        }
        if (index >= length) return INCOMPLETE
        val after = carry[index]
        if (after != GREATER && !isSpace(after) && (closing || after != SLASH)) return NO_MATCH

        var quote: Byte = 0
        while (index < length) {
            val byte = carry[index]
            when {
                quote != 0.toByte() -> if (byte == quote) quote = 0
                byte == '"'.code.toByte() || byte == '\''.code.toByte() -> quote = byte
                byte == GREATER -> return index + 1
            }
            index++
        }
        return INCOMPLETE
    }

    private fun refuseIfTooLong() {
        val from = if (state == State.IN_PAGE) pageStart else searched
        if (length - from > maxPage) corrupt("a page of this dump is longer than any page can be")
    }

    /** Refuses a dump that says it is not in UTF-8. Says whether enough has arrived to tell. */
    private fun readDeclaration(): Boolean {
        if (length < XML_DECLARATION.size + 3) return false
        if (carry[0] == 0xFE.toByte() || carry[0] == 0xFF.toByte()) {
            corrupt("this dump is in UTF-16, and only UTF-8 is read")
        }
        val start = if (carry[0] == 0xEF.toByte()) 3 else 0
        if (startsWith(carry, start, length, XML_DECLARATION) == true) {
            val end = indexOf(carry, DECLARATION_END, start, length)
            if (end < 0 && length < MAX_DECLARATION) return false
            if (end >= 0) refuseEncoding(start + XML_DECLARATION.size, end)
        }
        declarationRead = true
        return true
    }

    private fun refuseEncoding(from: Int, to: Int) {
        val named = indexOf(carry, ENCODING, from, to)
        if (named < 0) return
        var index = named + ENCODING.size
        while (index < to && (isSpace(carry[index]) || carry[index] == '='.code.toByte())) index++
        if (index >= to) return
        val quote = carry[index]
        val end = (index + 1 until to).firstOrNull { carry[it] == quote } ?: return
        val encoding = carry.decodeToString(index + 1, end)
        if (encoding.lowercase() !in UTF8_ENCODINGS) {
            corrupt("this dump says it is in $encoding, and only UTF-8 is read")
        }
    }

    @PublishedApi
    internal fun compact() {
        val keepFrom = if (state == State.IN_PAGE) pageStart else minOf(searched, length)
        if (keepFrom == 0) return
        val kept = length - keepFrom
        carry.copyInto(carry, 0, keepFrom, length)
        length = kept
        searched -= keepFrom
        if (state == State.IN_PAGE) pageStart = 0
    }

    private companion object {
        /** What says more input is needed, compared only by identity. */
        val WAIT = ByteArray(0)
    }
}

/** Whether [prefix] is at [at], or `null` if what has arrived before [to] does not say yet. */
private fun startsWith(bytes: ByteArray, at: Int, to: Int, prefix: ByteArray): Boolean? {
    for (offset in prefix.indices) {
        if (at + offset >= to) return null
        if (bytes[at + offset] != prefix[offset]) return false
    }
    return true
}

private fun isSpace(byte: Byte): Boolean =
    byte == ' '.code.toByte() ||
        byte == '\n'.code.toByte() ||
        byte == '\t'.code.toByte() ||
        byte == CARRIAGE_RETURN

/**
 * The page in [bytes], which run from `<page>` to `</page>`, or `null` if it is in no namespace in
 * [namespaces].
 *
 * A dump puts a page's namespace before its text, so a page not asked for is left before its text is looked
 * at, and nothing after the first revision is looked at at all.
 */
internal fun parsePage(bytes: ByteArray, namespaces: Set<Int>): DumpPage? {
    var title = ""
    var namespace = 0
    var pageId = 0L
    var revisionId = 0L
    var text = ""
    var timestamp: kotlin.time.Instant? = null
    var contributor: String? = null
    var comment: String? = null
    var isRedirect = false
    var redirectTarget: String? = null
    var inRevision = false
    var inContributor = false

    var position = 0
    val end = bytes.size
    while (position < end) {
        val open = nextLess(bytes, position, end)
        if (open < 0) break
        val after = open + 1
        if (after >= end) truncated("a tag")
        val mark = bytes[after]
        if (mark == BANG || mark == QUESTION) {
            position = skipDeclaration(bytes, after, end)
            continue
        }

        val close = tagEnd(bytes, after, end)
        val closing = mark == SLASH
        val nameStart = if (closing) after + 1 else after
        val selfClosed = bytes[close - 1] == SLASH
        var nameEnd = nameStart
        while (nameEnd < close && !isNameEnd(bytes[nameEnd])) nameEnd++
        position = close + 1

        if (closing) {
            when {
                matches(bytes, nameStart, nameEnd, "revision") -> break
                matches(bytes, nameStart, nameEnd, "contributor") -> inContributor = false
            }
            continue
        }

        if (matches(bytes, nameStart, nameEnd, "revision")) {
            inRevision = true
            continue
        }
        if (matches(bytes, nameStart, nameEnd, "contributor")) {
            inContributor = true
            continue
        }
        if (matches(bytes, nameStart, nameEnd, "redirect")) {
            isRedirect = true
            redirectTarget = attribute(bytes, nameEnd, close, "title")
            continue
        }
        if (
            matches(bytes, nameStart, nameEnd, "page") ||
                selfClosed && !isTextField(bytes, nameStart, nameEnd)
        ) {
            continue
        }
        if (!isTextField(bytes, nameStart, nameEnd)) {
            position = skipElement(bytes, close + 1, end, nameStart, nameEnd)
            continue
        }

        val valueStart = close + 1
        val valueEnd = if (selfClosed) valueStart else elementEnd(bytes, valueStart, end, nameStart, nameEnd)
        when {
            matches(bytes, nameStart, nameEnd, "title") -> title = textOf(bytes, valueStart, valueEnd)
            matches(bytes, nameStart, nameEnd, "ns") -> {
                namespace = textOf(bytes, valueStart, valueEnd).trim().toIntOrNull() ?: 0
                if (namespaces.isNotEmpty() && namespace !in namespaces) return null
            }
            matches(bytes, nameStart, nameEnd, "id") -> {
                val id = textOf(bytes, valueStart, valueEnd).trim().toLongOrNull() ?: 0L
                when {
                    inContributor -> Unit
                    inRevision -> revisionId = id
                    else -> pageId = id
                }
            }
            matches(bytes, nameStart, nameEnd, "timestamp") ->
                timestamp = MwTimestamp.parseOrNull(textOf(bytes, valueStart, valueEnd))
            matches(bytes, nameStart, nameEnd, "username") || matches(bytes, nameStart, nameEnd, "ip") ->
                contributor = textOf(bytes, valueStart, valueEnd)
            matches(bytes, nameStart, nameEnd, "comment") -> comment = textOf(bytes, valueStart, valueEnd)
            matches(bytes, nameStart, nameEnd, "text") -> text = textOf(bytes, valueStart, valueEnd)
        }
        position = if (selfClosed) valueStart else indexOfGreater(bytes, valueEnd, end) + 1
    }

    return DumpPage(
        title = title,
        namespace = namespace,
        pageId = pageId,
        revisionId = revisionId,
        text = text,
        timestamp = timestamp,
        contributor = contributor,
        comment = comment,
        isRedirect = isRedirect,
        redirectTarget = redirectTarget,
    )
}

private const val CDATA_OPEN = "<![CDATA["

private val TEXT_FIELDS =
    listOf("title", "ns", "id", "timestamp", "username", "ip", "comment", "text").map {
        it.encodeToByteArray()
    }

private fun isTextField(bytes: ByteArray, start: Int, end: Int): Boolean = TEXT_FIELDS.any { field ->
    matches(bytes, start, end, field)
}

private fun matches(bytes: ByteArray, start: Int, end: Int, name: ByteArray): Boolean {
    if (end - start != name.size) return false
    for (index in name.indices) if (bytes[start + index] != name[index]) return false
    return true
}

private fun matches(bytes: ByteArray, start: Int, end: Int, name: String): Boolean {
    if (end - start != name.length) return false
    for (index in name.indices) if (bytes[start + index].toInt() != name[index].code) return false
    return true
}

private fun nextLess(bytes: ByteArray, from: Int, to: Int): Int {
    var index = from
    while (index < to) {
        if (bytes[index] == LESS) return index
        index++
    }
    return -1
}

private fun indexOfGreater(bytes: ByteArray, from: Int, to: Int): Int {
    var index = from
    while (index < to) {
        if (bytes[index] == GREATER) return index
        index++
    }
    truncated("a tag")
}

private fun isNameEnd(byte: Byte): Boolean =
    byte <= ' '.code.toByte() && byte >= 0 || byte == SLASH || byte == GREATER

/** The `>` closing the tag whose name starts at [from], passing over any `>` inside a quoted attribute. */
private fun tagEnd(bytes: ByteArray, from: Int, to: Int): Int {
    var index = from
    var quote: Byte = 0
    while (index < to) {
        val byte = bytes[index]
        when {
            quote != 0.toByte() -> if (byte == quote) quote = 0
            byte == '"'.code.toByte() || byte == '\''.code.toByte() -> quote = byte
            byte == GREATER -> return index
        }
        index++
    }
    truncated("a tag")
}

/** Past a comment, or refuses a DTD. */
private fun skipDeclaration(bytes: ByteArray, from: Int, to: Int): Int {
    if (from + 2 < to && bytes[from] == BANG && bytes[from + 1] == '-'.code.toByte()) {
        val close = indexOf(bytes, "-->".encodeToByteArray(), from, to)
        if (close < 0) truncated("a comment")
        return close + 3
    }
    if (bytes[from] == QUESTION) return indexOfGreater(bytes, from, to) + 1
    corrupt("a page has a declaration in it, which is refused")
}

private fun skipElement(bytes: ByteArray, from: Int, to: Int, nameStart: Int, nameEnd: Int): Int =
    indexOfGreater(bytes, elementEnd(bytes, from, to, nameStart, nameEnd), to) + 1

/** Where the closing tag of the element named by that range starts, at or after [from]. */
private fun elementEnd(bytes: ByteArray, from: Int, to: Int, nameStart: Int, nameEnd: Int): Int {
    var index = from
    val length = nameEnd - nameStart
    while (true) {
        index = nextLess(bytes, index, to)
        if (index < 0) truncated("an element")
        if (index + 1 < to && bytes[index + 1] == SLASH && index + 2 + length <= to) {
            var same = true
            for (offset in 0 until length) {
                if (bytes[index + 2 + offset] != bytes[nameStart + offset]) {
                    same = false
                    break
                }
            }
            if (same) return index
        }
        index++
    }
}

private fun attribute(bytes: ByteArray, from: Int, to: Int, name: String): String? {
    val wanted = "$name=".encodeToByteArray()
    val found = indexOf(bytes, wanted, from, to)
    if (found < 0) return null
    val quoteAt = found + wanted.size
    if (quoteAt >= to) return null
    val quote = bytes[quoteAt]
    var close = quoteAt + 1
    while (close < to && bytes[close] != quote) close++
    return attributeText(bytes, quoteAt + 1, close)
}

/** The text in a range: UTF-8, with its line ends normalised and its entities and CDATA sections resolved. */
private fun textOf(bytes: ByteArray, start: Int, end: Int): String {
    var plain = true
    var index = start
    while (index < end) {
        val byte = bytes[index]
        if (byte == AMPERSAND || byte == LESS || byte == CARRIAGE_RETURN) {
            plain = false
            break
        }
        index++
    }
    if (plain) return decodeUtf8(bytes, start, end)
    return decodeEntities(normaliseLineEnds(decodeUtf8(bytes, start, end)))
}

/**
 * The value of an attribute: as [textOf], with the white space written out in it read as spaces, as XML says.
 */
private fun attributeText(bytes: ByteArray, start: Int, end: Int): String {
    val raw = normaliseLineEnds(decodeUtf8(bytes, start, end)).replace('\n', ' ').replace('\t', ' ')
    return if ('&' in raw) decodeEntities(raw) else raw
}

/** XML reads `\r\n` and a lone `\r` written in a document as `\n`. A reference to one stays what it says. */
private fun normaliseLineEnds(raw: String): String =
    if ('\r' in raw) raw.replace("\r\n", "\n").replace('\r', '\n') else raw

private fun decodeEntities(raw: String): String {
    val result = StringBuilder(raw.length)
    var index = 0
    var cdata = raw.indexOf(CDATA_OPEN)
    while (index < raw.length) {
        val ampersand = raw.indexOf('&', index)
        val next =
            when {
                ampersand < 0 -> cdata
                cdata < 0 -> ampersand
                else -> minOf(ampersand, cdata)
            }
        if (next < 0) {
            result.append(raw, index, raw.length)
            break
        }
        result.append(raw, index, next)
        if (next == cdata) {
            val close = raw.indexOf("]]>", next)
            if (close < 0) truncated("a CDATA section")
            result.append(raw, next + CDATA_OPEN.length, close)
            index = close + 3
            cdata = raw.indexOf(CDATA_OPEN, index)
        } else {
            val close = raw.indexOf(';', next)
            if (close < 0) corrupt("an entity is not closed")
            appendEntity(result, raw, next + 1, close)
            index = close + 1
        }
    }
    return result.toString()
}

private fun appendEntity(result: StringBuilder, raw: String, start: Int, end: Int) {
    val length = end - start
    val first = raw[start]
    when {
        length == 3 && first == 'a' && raw[start + 1] == 'm' && raw[start + 2] == 'p' -> result.append('&')
        length == 2 && first == 'l' && raw[start + 1] == 't' -> result.append('<')
        length == 2 && first == 'g' && raw[start + 1] == 't' -> result.append('>')
        else -> result.append(entity(raw.substring(start, end)))
    }
}

private fun entity(name: String): String =
    when {
        name == "amp" -> "&"
        name == "lt" -> "<"
        name == "gt" -> ">"
        name == "quot" -> "\""
        name == "apos" -> "'"
        name.startsWith("#x") -> codePoint(digits(name.substring(2))?.toIntOrNull(16), name)
        name.startsWith("#") -> codePoint(digits(name.substring(1))?.toIntOrNull(), name)
        else -> corrupt("the entity &$name; is not one XML defines, and entities are not expanded")
    }

/** A reference's number, with no sign: Kotlin would read one, and XML has none. */
private fun digits(number: String): String? = number.takeIf { it.firstOrNull()?.isLetterOrDigit() == true }

/** Whether XML lets a document hold this character, by its production for `Char`. */
private fun isXmlCharacter(value: Int): Boolean =
    value == 0x9 ||
        value == 0xA ||
        value == 0xD ||
        value in 0x20..0xD7FF ||
        value in 0xE000..0xFFFD ||
        value in 0x10000..0x10FFFF

private fun codePoint(value: Int?, name: String): String {
    if (value == null || !isXmlCharacter(value)) corrupt("&$name; is not a character")
    if (value < 0x10000) return value.toChar().toString()
    val offset = value - 0x10000
    return charArrayOf(
            (0xD800 + (offset shr 10)).toChar(),
            (0xDC00 + (offset and 0x3FF)).toChar(),
        )
        .concatToString()
}
