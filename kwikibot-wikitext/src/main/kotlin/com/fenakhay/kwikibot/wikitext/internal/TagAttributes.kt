package com.fenakhay.kwikibot.wikitext.internal

/**
 * An extension tag's attributes read as MediaWiki's `Sanitizer::decodeTagAttributes` reads them, for an
 * attribute that changes how the tag's body is read, such as `<pre format="wikitext">`.
 */
internal object TagAttributes {

    /**
     * The value of the attribute [name], given in lower case, among [text]'s characters [from] until [to], or
     * `null` if it is absent.
     *
     * Names are compared ignoring case and the last one written wins. A name with no `=` has an empty value.
     * A value's runs of whitespace become one space, it is trimmed, and its numeric character references are
     * decoded. Named references are left as written, since none stands for an ASCII letter or digit.
     */
    fun value(text: CharSequence, from: Int, to: Int, name: String): String? {
        var found: String? = null
        var at = skipSeparators(text, from, to)
        while (at < to) {
            val nameEnd = nameEnd(text, at, to)
            val value = valueAfter(text, nameEnd, to)
            if (
                nameEnd - at == name.length && text.regionMatches(at, name, 0, name.length, ignoreCase = true)
            ) {
                found = value.text
            }
            at = skipSeparators(text, value.next, to)
        }
        return found?.let { decodeNumeric(WHITESPACE.replace(it, " ").trim()) }
    }

    /**
     * Whether a `<pre>` with these attributes has `format="wikitext"`, which MediaWiki parses as wikitext.
     */
    fun isWikitextPre(text: CharSequence, from: Int, to: Int): Boolean =
        value(text, from, to, FORMAT) == WIKITEXT

    /** An attribute's value as written, and where reading goes on after it. */
    private class Value(val text: String, val next: Int)

    /** The value after a name ending at [nameEnd]: quoted, unquoted up to whitespace or `>`, or none. */
    private fun valueAfter(text: CharSequence, nameEnd: Int, to: Int): Value {
        val equals = skipSpaces(text, nameEnd, to)
        if (equals >= to || text[equals] != '=') return Value("", nameEnd)

        val start = skipSpaces(text, equals + 1, to)
        val quote = text.getOrNull(start)?.takeIf { it == '"' || it == '\'' }
        if (quote == null) {
            var end = start
            while (end < to && !isSpace(text[end]) && text[end] != '>') end++
            return Value(text.substring(start, end), end)
        }
        var close = start + 1
        while (close < to && text[close] != quote) close++
        return Value(text.substring(start + 1, close), minOf(close + 1, to))
    }

    private fun nameEnd(text: CharSequence, start: Int, to: Int): Int {
        var at = start + 1
        while (at < to && isNameChar(text[at])) at++
        return at
    }

    /** Past whitespace, `/` and `>`, which separate attributes and start none. */
    private fun skipSeparators(text: CharSequence, from: Int, to: Int): Int {
        var at = from
        while (at < to && isSeparator(text[at])) at++
        return at
    }

    private fun isSeparator(char: Char): Boolean = isSpace(char) || char == '/' || char == '>'

    private fun skipSpaces(text: CharSequence, from: Int, to: Int): Int {
        var at = from
        while (at < to && isSpace(text[at])) at++
        return at
    }

    private fun decodeNumeric(value: String): String =
        if ('&' !in value) {
            value
        } else {
            NUMERIC_REFERENCE.replace(value) { match ->
                val (decimal, hex) = match.destructured
                val code = if (decimal.isNotEmpty()) decimal.toIntOrNull() else hex.toIntOrNull(HEX)
                if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code))
                else match.value
            }
        }

    /**
     * The whitespace MediaWiki's attribute grammar knows: tab, line feed, form feed, carriage return, space.
     */
    private fun isSpace(char: Char): Boolean =
        char == ' ' || char == '\t' || char == '\n' || char == '\r' || char == '\u000C'

    private fun isNameChar(char: Char): Boolean = !isSpace(char) && char != '/' && char != '>' && char != '='

    private const val FORMAT = "format"
    private const val WIKITEXT = "wikitext"
    private const val HEX = 16
    private val WHITESPACE = Regex("[\t\r\n ]+")
    private val NUMERIC_REFERENCE = Regex("&#(?:([0-9]+)|[xX]([0-9A-Fa-f]+));")
}
