package com.fenakhay.kwikibot.wikitext

/**
 * Names a wiki recognises in `{{…}}`, each compared as its magic word says: exactly, or ignoring case.
 *
 * MediaWiki marks every magic word case-sensitive or not. `{{DEFAULTSORT:x}}` is a function and
 * `{{defaultsort:x}}` a template, while `{{lc:x}}` and `{{LC:x}}` are both functions.
 *
 * @param caseSensitive the names compared exactly.
 * @param caseInsensitive the names compared ignoring case.
 */
public data class MagicWords(
    val caseSensitive: Set<String> = emptySet(),
    val caseInsensitive: Set<String> = emptySet(),
) {
    private val folded: Set<String> = caseInsensitive.mapTo(HashSet()) { it.lowercase() }

    /** Whether [name] is one of these names. */
    public operator fun contains(name: String): Boolean = name in caseSensitive || name.lowercase() in folded
}
