package com.fenakhay.kwikibot.wikitext.internal

/**
 * A set of tag names that can be asked about a stretch of text without copying it out.
 *
 * Every `<` on a page is checked against the wiki's tag names, often more than once. Cutting the name out and
 * lowercasing it would allocate two strings each time, so names are grouped by length and first letter and a
 * lookup compares the text in place against the one or two names that could match.
 */
internal class NameSet(names: Collection<String>) {

    /** The names, lower-cased and each once, in the order [indexOf] numbers them. */
    val names: List<String> = names.map { it.lowercase() }.distinct()

    private val longest: Int = this.names.maxOfOrNull { it.length } ?: 0

    /** The indexes of the names of each length and first letter, at `length * ASCII + first letter`. */
    private val buckets: Array<IntArray?> =
        arrayOfNulls<IntArray>((longest + 1) * ASCII).also { table ->
            for ((key, group) in
                this.names.indices.groupBy { keyOf(this.names[it].length, this.names[it][0]) }) {
                if (key >= 0) table[key] = group.toIntArray()
            }
        }

    /** Where the name between [from] and [to] is in [names], compared ignoring case, or -1. */
    fun indexOf(text: CharSequence, from: Int, to: Int): Int {
        val length = to - from
        val key = if (length in 1..longest) keyOf(length, text[from]) else -1
        val bucket = (if (key >= 0) buckets[key] else null) ?: return -1
        for (index in bucket) {
            if (text.regionMatches(from, names[index], 0, length, ignoreCase = true)) return index
        }
        return -1
    }

    /** Whether the text between [from] and [to] is one of the names, compared ignoring case. */
    fun contains(text: CharSequence, from: Int, to: Int): Boolean = indexOf(text, from, to) >= 0

    /** Whether [name] is one of the names, compared ignoring case. */
    operator fun contains(name: String): Boolean = contains(name, 0, name.length)

    private fun keyOf(length: Int, first: Char): Int {
        val lower = first.lowercaseChar()
        return if (lower.code >= ASCII) -1 else length * ASCII + lower.code
    }

    private companion object {
        const val ASCII = 128
    }
}
