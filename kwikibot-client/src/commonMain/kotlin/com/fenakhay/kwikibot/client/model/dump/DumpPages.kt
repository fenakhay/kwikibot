@file:Suppress(
    "MagicNumber",
    "TooManyFunctions",
    "LoopWithTooManyJumpStatements",
    "LongMethod",
    "CyclomaticComplexMethod",
)

package com.fenakhay.kwikibot.client.model.dump

import com.fenakhay.kwikibot.client.model.DumpPage
import com.fenakhay.kwikibot.model.MwTimestamp

private val PAGE_OPEN = "<page>".encodeToByteArray()
private val PAGE_CLOSE = "</page>".encodeToByteArray()
private val DUMP_CLOSE = "</mediawiki>".encodeToByteArray()
private val DTD = "<!DOCTYPE".encodeToByteArray()

private const val LESS = '<'.code.toByte()
private const val GREATER = '>'.code.toByte()
private const val SLASH = '/'.code.toByte()
private const val AMPERSAND = '&'.code.toByte()
private const val BANG = '!'.code.toByte()
private const val QUESTION = '?'.code.toByte()

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
 * A dump escapes `<` in text, so the bytes `<page>` and `</page>` mark real boundaries and a page can be cut
 * out by searching. That is what lets separate threads parse separate pages. MediaWiki writes no CDATA
 * sections; a dump from elsewhere with `</page>` inside one would be cut wrongly.
 */
internal class PageSplitter {
    private var carry = ByteArray(1 shl 16)
    private var length = 0
    private var searched = 0
    private var inPage = false
    private var pageStart = 0
    private var finished = false
    private var seenPrologue = false

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
        if (inPage) truncated("a page")
        if (!finished) truncated("the dump's last page")
    }

    @PublishedApi
    internal fun append(bytes: ByteArray, count: Int) {
        if (length + count > carry.size) carry = carry.copyOf(maxOf(carry.size * 2, length + count))
        bytes.copyInto(carry, length, 0, count)
        length += count
        if (!seenPrologue && indexOf(carry, DTD, 0, length) >= 0)
            corrupt("this dump has a DTD, which is refused")
    }

    @PublishedApi
    internal fun next(): ByteArray? {
        if (!inPage) {
            val start = indexOf(carry, PAGE_OPEN, searched, length)
            if (start < 0) {
                if (indexOf(carry, DUMP_CLOSE, searched, length) >= 0) finished = true
                searched = maxOf(searched, length - DUMP_CLOSE.size + 1)
                return null
            }
            seenPrologue = true
            pageStart = start
            inPage = true
            searched = start + PAGE_OPEN.size
        }
        val close = indexOf(carry, PAGE_CLOSE, searched, length)
        if (close < 0) {
            searched = maxOf(searched, length - PAGE_CLOSE.size + 1)
            return null
        }
        val end = close + PAGE_CLOSE.size
        inPage = false
        searched = end
        return carry.copyOfRange(pageStart, end)
    }

    @PublishedApi
    internal fun compact() {
        val keepFrom = if (inPage) pageStart else maxOf(0, minOf(searched, length))
        if (keepFrom == 0) return
        val kept = length - keepFrom
        carry.copyInto(carry, 0, keepFrom, length)
        length = kept
        searched -= keepFrom
        if (inPage) pageStart = 0
    }
}

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
    return textOf(bytes, quoteAt + 1, close)
}

/** The text in a range: UTF-8, with its entities and CDATA sections resolved. */
private fun textOf(bytes: ByteArray, start: Int, end: Int): String {
    var plain = true
    var index = start
    while (index < end) {
        val byte = bytes[index]
        if (byte == AMPERSAND || byte == LESS) {
            plain = false
            break
        }
        index++
    }
    if (plain) return decodeUtf8(bytes, start, end)
    return decodeEntities(decodeUtf8(bytes, start, end))
}

private fun decodeEntities(raw: String): String {
    val result = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val ampersand = raw.indexOf('&', index)
        val cdata = raw.indexOf("<![CDATA[", index)
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
            result.append(raw, next + 9, close)
            index = close + 3
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
        name.startsWith("#x") -> codePoint(name.substring(2).toIntOrNull(16), name)
        name.startsWith("#") -> codePoint(name.substring(1).toIntOrNull(), name)
        else -> corrupt("the entity &$name; is not one XML defines, and entities are not expanded")
    }

private fun codePoint(value: Int?, name: String): String {
    if (value == null || value < 0 || value > 0x10FFFF) corrupt("&$name; is not a character")
    if (value < 0x10000) return value.toChar().toString()
    val offset = value - 0x10000
    return charArrayOf(
            (0xD800 + (offset shr 10)).toChar(),
            (0xDC00 + (offset and 0x3FF)).toChar(),
        )
        .concatToString()
}
