package com.fenakhay.kwikibot.wikitext

/**
 * The links at the bottom of a page: categories and interlanguage links.
 *
 * What counts as correct order differs by project, so nothing is reordered unless asked and the
 * comparator is a parameter.
 */

/**
 * Adds a category, unless the page is already in it.
 *
 * Placed at the end, after the existing categories, which is where every project puts them. A
 * page whose categories are somewhere else keeps them there: moving them would be a second
 * change that was not requested.
 *
 * @param name the category to add, with or without its prefix.
 * @param sortKey the key to file it under, or `null` to file it under the page name.
 * @param prefix the namespace prefix to write, which differs between wikis.
 */
public fun Markup.addCategory(
    name: String,
    sortKey: String? = null,
    prefix: String = CATEGORY,
): Markup {
    if (categoryNames(setOf(prefix)).any { it.equals(name, ignoreCase = true) }) return this

    val link = WikiLink(
        target = Markup.of("$prefix:$name"),
        text = sortKey?.let { Markup.of(it) },
    )

    val last = nodes.indexOfLast { it is WikiLink && it.isCategory(prefix) }
    return if (last < 0) {
        // No categories yet: the page gains a blank line and then the category, which is the
        // shape every project's footer has.
        Markup(nodes + TextNode("\n") + link + TextNode("\n"))
    } else {
        Markup(nodes.take(last + 1) + TextNode("\n") + link + nodes.drop(last + 1))
    }
}

/** Removes a category, and the line break that was holding it, if it was on its own line. */
public fun Markup.removeCategory(name: String, prefix: String = CATEGORY): Markup {
    val target = nodes.filterIsInstance<WikiLink>()
        .firstOrNull { it.isCategory(prefix) && it.categoryName().equals(name, ignoreCase = true) }
        ?: return this

    return Markup(nodes.withoutCategory(target))
}

/**
 * Replaces one category with another, keeping the sort key.
 *
 * The sort key belongs to the page's place in the category, not to the category, so a page filed
 * under `Smith, John` stays filed under `Smith, John` when it moves.
 */
public fun Markup.changeCategory(
    from: String,
    to: String,
    prefix: String = CATEGORY,
): Markup = Markup(
    nodes.map { node ->
        val link = node as? WikiLink ?: return@map node
        if (!link.isCategory(prefix) || !link.categoryName().equals(from, ignoreCase = true)) {
            return@map node
        }
        link.copy(target = Markup.of("$prefix:$to"))
    },
)

/**
 * Sorts the interlanguage links.
 *
 * Alphabetical by code unless a project says otherwise, which is what most now use: the ordering
 * conventions that differed by project were mostly abandoned when interlanguage links moved to
 * Wikidata, and the links that remain are hand-added exceptions.
 *
 * Only the links move. Whatever sat between them — comments, templates, blank lines — stays
 * where it was, because it is usually a note about one of them.
 */
public fun Markup.sortLanguageLinks(
    codes: Set<String>,
    order: Comparator<String> = naturalOrder(),
): Markup {
    val positions = nodes.withIndex()
        .filter { (_, node) -> node is WikiLink && node.languageCode(codes) != null }
        .map { it.index }
    if (positions.size < 2) return this

    val sorted = positions
        .map { nodes[it] as WikiLink }
        .sortedWith(compareBy(order) { it.languageCode(codes).orEmpty() })

    val rebuilt = nodes.toMutableList()
    positions.forEachIndexed { index, position -> rebuilt[position] = sorted[index] }
    return Markup(rebuilt)
}

/** The language code a link points at, or `null` if it is not an interlanguage link. */
internal fun WikiLink.languageCode(codes: Set<String>): String? {
    val prefix = title.trimStart(':').substringBefore(':', "").trim().lowercase()
    return prefix.takeIf { it.isNotEmpty() && it in codes.map { code -> code.lowercase() } }
}

private fun WikiLink.isCategory(prefix: String): Boolean =
    title.trimStart(':').substringBefore(':', "").trim().equals(prefix, ignoreCase = true)

private fun WikiLink.categoryName(): String = title.trimStart(':').substringAfter(':').trim()

/**
 * The nodes without a category link, and without the blank line it was sitting on.
 *
 * Leaving the newline behind turns a removal into a growing gap at the bottom of the page, one
 * blank line per category ever removed.
 */
private fun List<Node>.withoutCategory(target: WikiLink): List<Node> {
    val index = indexOf(target)
    if (index < 0) return this

    val before = getOrNull(index - 1) as? TextNode
    val after = getOrNull(index + 1) as? TextNode

    return when {
        // On its own line: the newline before it goes with it.
        before != null && before.text.endsWith("\n") && after?.text?.startsWith("\n") == true ->
            take(index - 1) + TextNode(before.text.dropLast(1)) + drop(index + 1)

        else -> take(index) + drop(index + 1)
    }
}

/** The canonical English category namespace, which the wikitext module knows nothing beyond. */
private const val CATEGORY = "Category"
