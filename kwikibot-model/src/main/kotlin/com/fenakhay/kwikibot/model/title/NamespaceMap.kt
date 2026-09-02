package com.fenakhay.kwikibot.model.title

/** How a wiki treats the first character of a title in a given namespace. */
public enum class TitleCase {
    /** The first letter is capitalised automatically — the default nearly everywhere. */
    FIRST_LETTER,

    /** Titles are taken as typed, as on Wiktionary's main space. */
    CASE_SENSITIVE,
}

/**
 * One namespace as a wiki describes it: its number, its names, and its title casing rule.
 *
 * @param id the namespace's number, which is its identity across wikis.
 * @param canonicalName the English canonical name (`Category`), absent for main space.
 * @param localName the wiki's own name for it (`Kategoria`), equal to [canonicalName] on English wikis.
 * @param aliases additional accepted prefixes, such as `WP` for `Wikipedia`.
 * @param case how the wiki cases the first letter of a title in this namespace.
 * @param subpages whether a slash in a title makes a subpage here.
 */
public data class NamespaceInfo(
    val id: Namespace,
    val canonicalName: String?,
    val localName: String?,
    val aliases: List<String> = emptyList(),
    val case: TitleCase = TitleCase.FIRST_LETTER,
    val subpages: Boolean = false,
) {
    /** Every prefix that resolves to this namespace. */
    public val prefixes: List<String>
        get() = buildList {
            localName?.takeIf { it.isNotEmpty() }?.let(::add)
            canonicalName?.takeIf { it.isNotEmpty() && it != localName }?.let(::add)
            addAll(aliases)
        }
}

/**
 * The namespaces of one wiki, indexed for prefix lookup.
 *
 * Prefix matching follows MediaWiki: underscores are equivalent to spaces, surrounding whitespace is ignored,
 * and the comparison is case-insensitive.
 */
public class NamespaceMap(namespaces: Collection<NamespaceInfo>) {

    private val byId: Map<Int, NamespaceInfo> = namespaces.associateBy { it.id.id }

    private val byPrefix: Map<String, NamespaceInfo> = buildMap {
        for (ns in namespaces) {
            for (prefix in ns.prefixes) {
                put(normalizePrefix(prefix), ns)
            }
        }
    }

    /** All namespaces, ordered by number. */
    public val all: List<NamespaceInfo> = namespaces.sortedBy { it.id.id }

    /** What the wiki says about a namespace, or `null` if it has no such namespace. */
    public operator fun get(namespace: Namespace): NamespaceInfo? = byId[namespace.id]

    /** The namespace a title prefix refers to, or `null` if the prefix names none. */
    public fun byPrefix(prefix: String): NamespaceInfo? = byPrefix[normalizePrefix(prefix)]

    /** Renders a local title with its namespace prefix, as MediaWiki would display it. */
    public fun format(title: Title.Local): String {
        val prefix = this[title.namespace]?.localName?.takeIf { it.isNotEmpty() }
        val base = if (prefix == null) title.text else "$prefix:${title.text}"
        return title.fragment?.let { "$base#$it" } ?: base
    }

    private fun normalizePrefix(raw: String): String = raw.replace('_', ' ').trim().lowercase()

    /** The namespace set to fall back on when a wiki's own has not been read. */
    public companion object {
        /**
         * The canonical namespaces every MediaWiki install has, in English.
         *
         * Useful for tests and for parsing before a wiki's own site info has been fetched; real wikis add
         * local names, aliases and custom namespaces on top.
         */
        public val CANONICAL: NamespaceMap =
            NamespaceMap(
                listOf(
                    NamespaceInfo(Namespace.MEDIA, "Media", "Media"),
                    NamespaceInfo(Namespace.SPECIAL, "Special", "Special"),
                    NamespaceInfo(Namespace.MAIN, "", ""),
                    NamespaceInfo(Namespace.TALK, "Talk", "Talk"),
                    NamespaceInfo(Namespace.USER, "User", "User"),
                    NamespaceInfo(Namespace.USER_TALK, "User talk", "User talk"),
                    NamespaceInfo(Namespace.PROJECT, "Project", "Project"),
                    NamespaceInfo(Namespace.PROJECT_TALK, "Project talk", "Project talk"),
                    NamespaceInfo(Namespace.FILE, "File", "File", aliases = listOf("Image")),
                    NamespaceInfo(
                        Namespace.FILE_TALK,
                        "File talk",
                        "File talk",
                        aliases = listOf("Image talk"),
                    ),
                    NamespaceInfo(Namespace.MEDIAWIKI, "MediaWiki", "MediaWiki"),
                    NamespaceInfo(Namespace.MEDIAWIKI_TALK, "MediaWiki talk", "MediaWiki talk"),
                    NamespaceInfo(Namespace.TEMPLATE, "Template", "Template"),
                    NamespaceInfo(Namespace.TEMPLATE_TALK, "Template talk", "Template talk"),
                    NamespaceInfo(Namespace.HELP, "Help", "Help"),
                    NamespaceInfo(Namespace.HELP_TALK, "Help talk", "Help talk"),
                    NamespaceInfo(Namespace.CATEGORY, "Category", "Category"),
                    NamespaceInfo(Namespace.CATEGORY_TALK, "Category talk", "Category talk"),
                )
            )
    }
}

/**
 * The interwiki prefixes a wiki recognises (`w`, `wikt`, `de`, `commons`, …).
 *
 * A recognised prefix means the target is not a page on this wiki — the distinction that keeps a bot from
 * editing the wrong project.
 *
 * @param prefixes every interwiki prefix the wiki accepts.
 * @param selfPrefixes prefixes pointing back at this same wiki, such as `wikt:` and `en:` on en.wiktionary.
 *   MediaWiki strips those and resolves the rest as an ordinary local title, so `wikt:volcano` is the page
 *   `volcano` rather than a link to another project.
 */
public class InterwikiMap(
    prefixes: Collection<String>,
    selfPrefixes: Collection<String> = emptyList(),
) {

    private val prefixes: Set<String> = prefixes.mapTo(HashSet(), ::normalize)

    private val selfPrefixes: Set<String> = selfPrefixes.mapTo(HashSet(), ::normalize)

    /** Whether [prefix] is an interwiki prefix on this wiki rather than page text. */
    public operator fun contains(prefix: String): Boolean = normalize(prefix) in prefixes

    /** Whether [prefix] names this same wiki, and should therefore be stripped. */
    public fun isSelf(prefix: String): Boolean = normalize(prefix) in selfPrefixes

    private fun normalize(raw: String): String = raw.replace('_', ' ').trim().lowercase()

    /** The map to use where a wiki's own interwiki table has not been read. */
    public companion object {
        /** No interwiki prefixes — every prefix is treated as a namespace or page text. */
        public val EMPTY: InterwikiMap = InterwikiMap(emptyList())
    }
}
