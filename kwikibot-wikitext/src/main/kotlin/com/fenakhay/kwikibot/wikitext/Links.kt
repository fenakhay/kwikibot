package com.fenakhay.kwikibot.wikitext

/**
 * The category links on a page.
 *
 * @param prefixes the namespace names that mean "category" on this wiki; the canonical English
 *   name by default, since the wikitext module knows nothing about any particular wiki.
 */
public fun Markup.categories(prefixes: Set<String> = setOf("Category")): List<WikiLink> =
    wikilinks().filter { link -> link.title.namespacePrefix() in prefixes.map { it.lowercase() } }

/**
 * The category names on a page, without their namespace prefix or sort key.
 *
 * A leading colon is stripped first: `[[:Category:Foo]]` links to the category rather than
 * filing the page in it, but it still names that category.
 */
public fun Markup.categoryNames(prefixes: Set<String> = setOf("Category")): List<String> =
    categories(prefixes).map { it.title.trimStart(':').substringAfter(':').trim() }

/**
 * The language links on a page: `[[fr:Foo]]` and friends.
 *
 * @param codes the language codes this wiki recognises. A prefix is only a language link if the
 *   wiki says so, which is why the caller supplies them.
 */
public fun Markup.languageLinks(codes: Set<String>): List<WikiLink> =
    wikilinks().filter { link -> link.title.namespacePrefix() in codes.map { it.lowercase() } }

private fun String.namespacePrefix(): String? =
    trimStart(':').substringBefore(':', missingDelimiterValue = "").trim().lowercase().ifEmpty { null }
