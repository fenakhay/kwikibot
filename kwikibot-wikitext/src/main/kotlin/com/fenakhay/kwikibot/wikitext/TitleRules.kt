package com.fenakhay.kwikibot.wikitext

/**
 * How a wiki names pages, as far as telling which page a `{{…}}` transcludes needs.
 *
 * Two templates are the same template when MediaWiki resolves both names to one page, and that depends on the
 * wiki: its namespace names and their aliases, whether a namespace keeps the case of a title's first letter
 * (the Template namespace does on en.wiktionary, where `{{l}}` and `{{L}}` are different templates), and
 * which names are parser functions and variables rather than pages at all. [DEFAULT] is what MediaWiki ships
 * with; a client opened on a wiki reads the wiki's own.
 *
 * @param namespaces every namespace name and alias, mapped to the namespace's number. Compared ignoring case,
 *   with underscores read as spaces.
 * @param canonicalNames the name each namespace is written with in a key.
 * @param caseSensitive the namespaces whose titles keep their first letter as written.
 * @param functions the parser functions written without a `#`, such as `lc` and `DEFAULTSORT`, as MediaWiki
 *   registers them: without the trailing colon. Any name starting with `#` is a parser function whatever this
 *   holds.
 * @param variables the variables, such as `PAGENAME` and `!`.
 * @param substitutions the prefixes that save a template's expansion instead of the template, `subst` and
 *   `safesubst`. MediaWiki reads them before it looks for a variable.
 * @param modifiers the other prefixes that change how a template is transcluded without changing which one,
 *   `msg`, `msgnw` and `raw`. MediaWiki reads them after it has looked for a variable, so `{{msg:PAGENAME}}`
 *   transcludes a page.
 */
public data class TitleRules(
    val namespaces: Map<String, Int> = CANONICAL_NAMESPACES,
    val canonicalNames: Map<Int, String> = CANONICAL_NAMES,
    val caseSensitive: Set<Int> = emptySet(),
    val functions: MagicWords = CORE_FUNCTIONS,
    val variables: MagicWords = CORE_VARIABLES,
    val substitutions: Set<String> = SUBSTITUTIONS,
    val modifiers: Set<String> = MODIFIERS,
) {
    private val namespacesNormalised: Map<String, Int> = namespaces.mapKeys { normaliseName(it.key) }

    private val substitutionsLower: Set<String> = substitutions.mapTo(HashSet()) { it.lowercase() }

    private val modifiersLower: Set<String> = modifiers.mapTo(HashSet()) { it.lowercase() }

    /** Whether `{{title}}`, with no arguments, is a parser function or a variable rather than a page. */
    public fun isParserFunction(title: String): Boolean = isParserFunction(title, hasArguments = false)

    /**
     * Whether a template whose name reads [title] is a parser function or a variable rather than a page.
     *
     * As MediaWiki reads it: past any `subst:`, a name that is a variable is one only when the template has
     * no arguments, so `{{PAGENAME|x}}` transcludes a page. Past any `msg:`, `msgnw:` or `raw:`, a name is a
     * function when what comes before its first colon names one.
     *
     * @param title the template's name as written.
     * @param hasArguments whether the template has any parameters, an empty one included.
     */
    public fun isParserFunction(title: String, hasArguments: Boolean): Boolean {
        val substituted = stripOnce(title.trim(), substitutionsLower).trim()
        if (!hasArguments && substituted in variables) return true

        val name = withoutModifiers(substituted)
        if (name.startsWith('#')) return true
        val colon = name.indexOfFirst { it == ':' || it == WIDE_COLON }
        return colon > 0 && name.substring(0, colon) in functions
    }

    /** The page `{{title}}`, with no arguments, transcludes; see the other overload. */
    public fun key(title: String): String? = key(title, hasArguments = false)

    /**
     * The page [title] transcludes, normalised: modifiers dropped, spaces collapsed, the namespace resolved
     * and the first letter folded where the namespace allows. `null` for a parser function or variable.
     *
     * @param title the template's name as written.
     * @param hasArguments whether the template has any parameters, which decides whether a variable's name is
     *   a variable or a page.
     */
    public fun key(title: String, hasArguments: Boolean): String? {
        if (isParserFunction(title, hasArguments)) return null
        var name = withoutModifiers(stripOnce(title.trim(), substitutionsLower).trim()).substringBefore('#')
        var namespace = TEMPLATE

        if (name.startsWith(':')) {
            namespace = MAIN
            name = name.substring(1)
        } else {
            val colon = name.indexOf(':')
            if (colon > 0) {
                val resolved = namespacesNormalised[normaliseName(name.substring(0, colon))]
                if (resolved != null) {
                    namespace = resolved
                    name = name.substring(colon + 1)
                }
            }
        }

        name = normaliseTitle(name)
        if (name.isEmpty()) return null
        if (namespace !in caseSensitive) name = name.replaceFirstChar { it.uppercase() }

        return when (namespace) {
            TEMPLATE -> name
            MAIN -> ":$name"
            else -> (canonicalNames[namespace] ?: namespace.toString()) + ":" + name
        }
    }

    /**
     * [name] without its `msg:` or `msgnw:` and its `raw:`, as MediaWiki strips them: at most two, and
     * nothing trimmed after them.
     */
    private fun withoutModifiers(name: String): String =
        stripOnce(stripOnce(name, modifiersLower), modifiersLower)

    /** [name] without one leading prefix from [prefixes], whose colon must follow it directly. */
    private fun stripOnce(name: String, prefixes: Set<String>): String {
        val colon = name.indexOf(':')
        return if (colon > 0 && name.substring(0, colon).lowercase() in prefixes) name.substring(colon + 1)
        else name
    }

    /** The namespaces, functions and variables MediaWiki ships with. */
    public companion object {
        private const val MAIN = 0
        private const val TEMPLATE = 10

        // Before [DEFAULT], which reads it while it is built. The characters MediaWiki's TitleParser reads
        // as spaces.
        private val SPACES = Regex("[ _\u00A0\u1680\u180E\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+")

        /** MediaWiki's namespace names and its two built-in aliases. */
        public val CANONICAL_NAMESPACES: Map<String, Int> =
            mapOf(
                "Media" to -2,
                "Special" to -1,
                "Talk" to 1,
                "User" to 2,
                "User talk" to 3,
                "Project" to 4,
                "Project talk" to 5,
                "File" to 6,
                "Image" to 6,
                "File talk" to 7,
                "Image talk" to 7,
                "MediaWiki" to 8,
                "MediaWiki talk" to 9,
                "Template" to 10,
                "Template talk" to 11,
                "Help" to 12,
                "Help talk" to 13,
                "Category" to 14,
                "Category talk" to 15,
            )

        /** The name each of MediaWiki's namespaces is written with. */
        public val CANONICAL_NAMES: Map<Int, String> =
            CANONICAL_NAMESPACES.entries
                .filter { it.key != "Image" && it.key != "Image talk" }
                .associate { it.value to it.key }

        /** The prefixes that save a template's expansion rather than the template. */
        public val SUBSTITUTIONS: Set<String> = setOf("subst", "safesubst")

        /** The other prefixes that change how a template is transcluded but not which one. */
        public val MODIFIERS: Set<String> = setOf("msg", "msgnw", "raw")

        /** The full-width colon, which MediaWiki also reads as the end of a function's name. */
        private val WIDE_COLON = Char(0xFF1A)

        /** The ids of the magic words MediaWiki registers as functions written without `#`. */
        public val CORE_FUNCTION_IDS: Set<String> =
            setOf(
                "anchorencode",
                "basepagename",
                "basepagenamee",
                "bcp47",
                "bidi",
                "canonicalurl",
                "canonicalurle",
                "cascadingsources",
                "categorysort",
                "contentmodel",
                "defaultsort",
                "dir",
                "displaytitle",
                "filepath",
                "formal",
                "formatnum",
                "fullpagename",
                "fullpagenamee",
                "fullurl",
                "fullurle",
                "gender",
                "grammar",
                "int",
                "interlanguagelink",
                "interwikilink",
                "isbn",
                "language",
                "lc",
                "lcfirst",
                "localurl",
                "localurle",
                "namespace",
                "namespacee",
                "namespacenumber",
                "ns",
                "nse",
                "numberingroup",
                "numberofactiveusers",
                "numberofadmins",
                "numberofarticles",
                "numberofedits",
                "numberoffiles",
                "numberofpages",
                "numberofusers",
                "padleft",
                "padright",
                "pageid",
                "pagename",
                "pagenamee",
                "pagesincategory",
                "pagesize",
                "plural",
                "protectionexpiry",
                "protectionlevel",
                "revisionday",
                "revisionday2",
                "revisionid",
                "revisionmonth",
                "revisionmonth1",
                "revisiontimestamp",
                "revisionuser",
                "revisionyear",
                "rootpagename",
                "rootpagenamee",
                "subjectpagename",
                "subjectpagenamee",
                "subjectspace",
                "subjectspacee",
                "subpagename",
                "subpagenamee",
                "talkpagename",
                "talkpagenamee",
                "talkspace",
                "talkspacee",
                "uc",
                "ucfirst",
                "urlencode",
            )

        /** MediaWiki's parser functions written without `#`, `{{lc:…}}` and the rest. */
        public val CORE_FUNCTIONS: MagicWords =
            MagicWords(
                caseSensitive =
                    setOf(
                        "#FORMAL",
                        "#bcp47",
                        "#contentmodel",
                        "#dir",
                        "#interlanguagelink",
                        "#interwikilink",
                        "#isbn",
                        "ARTICLEPAGENAME",
                        "ARTICLEPAGENAMEE",
                        "ARTICLESPACE",
                        "ARTICLESPACEE",
                        "BASEPAGENAME",
                        "BASEPAGENAMEE",
                        "CASCADINGSOURCES",
                        "CATEGORYSORT",
                        "DEFAULTCATEGORYSORT",
                        "DEFAULTSORT",
                        "DEFAULTSORTKEY",
                        "DISPLAYTITLE",
                        "FULLPAGENAME",
                        "FULLPAGENAMEE",
                        "NAMESPACE",
                        "NAMESPACEE",
                        "NAMESPACENUMBER",
                        "NUMBERINGROUP",
                        "NUMBEROFACTIVEUSERS",
                        "NUMBEROFADMINS",
                        "NUMBEROFARTICLES",
                        "NUMBEROFEDITS",
                        "NUMBEROFFILES",
                        "NUMBEROFPAGES",
                        "NUMBEROFUSERS",
                        "NUMINGROUP",
                        "PAGENAME",
                        "PAGENAMEE",
                        "PAGESINCAT",
                        "PAGESINCATEGORY",
                        "PAGESIZE",
                        "PROTECTIONEXPIRY",
                        "PROTECTIONLEVEL",
                        "REVISIONDAY",
                        "REVISIONDAY2",
                        "REVISIONID",
                        "REVISIONMONTH",
                        "REVISIONMONTH1",
                        "REVISIONTIMESTAMP",
                        "REVISIONUSER",
                        "REVISIONYEAR",
                        "ROOTPAGENAME",
                        "ROOTPAGENAMEE",
                        "SUBJECTPAGENAME",
                        "SUBJECTPAGENAMEE",
                        "SUBJECTSPACE",
                        "SUBJECTSPACEE",
                        "SUBPAGENAME",
                        "SUBPAGENAMEE",
                        "TALKPAGENAME",
                        "TALKPAGENAMEE",
                        "TALKSPACE",
                        "TALKSPACEE",
                    ),
                caseInsensitive =
                    setOf(
                        "#language",
                        "anchorencode",
                        "bidi",
                        "canonicalurl",
                        "canonicalurle",
                        "filepath",
                        "formatnum",
                        "fullurl",
                        "fullurle",
                        "gender",
                        "grammar",
                        "int",
                        "lc",
                        "lcfirst",
                        "localurl",
                        "localurle",
                        "ns",
                        "nse",
                        "padleft",
                        "padright",
                        "pageid",
                        "plural",
                        "uc",
                        "ucfirst",
                        "urlencode",
                    ),
            )

        /** MediaWiki's variables, `{{PAGENAME}}` and the rest. */
        public val CORE_VARIABLES: MagicWords =
            MagicWords(
                caseSensitive =
                    setOf(
                        "!",
                        "#bcp47",
                        "#contentmodel",
                        "#dir",
                        "#isbn",
                        "=",
                        "ARTICLEPAGENAME",
                        "ARTICLEPAGENAMEE",
                        "ARTICLESPACE",
                        "ARTICLESPACEE",
                        "BASEPAGENAME",
                        "BASEPAGENAMEE",
                        "CASCADINGSOURCES",
                        "CONTENTLANG",
                        "CONTENTLANGUAGE",
                        "CURRENTDAY",
                        "CURRENTDAY2",
                        "CURRENTDAYNAME",
                        "CURRENTDOW",
                        "CURRENTHOUR",
                        "CURRENTMONTH",
                        "CURRENTMONTH1",
                        "CURRENTMONTH2",
                        "CURRENTMONTHABBREV",
                        "CURRENTMONTHNAME",
                        "CURRENTMONTHNAMEGEN",
                        "CURRENTTIME",
                        "CURRENTTIMESTAMP",
                        "CURRENTVERSION",
                        "CURRENTWEEK",
                        "CURRENTYEAR",
                        "DIRECTIONMARK",
                        "DIRMARK",
                        "FULLPAGENAME",
                        "FULLPAGENAMEE",
                        "LOCALDAY",
                        "LOCALDAY2",
                        "LOCALDAYNAME",
                        "LOCALDOW",
                        "LOCALHOUR",
                        "LOCALMONTH",
                        "LOCALMONTH1",
                        "LOCALMONTH2",
                        "LOCALMONTHABBREV",
                        "LOCALMONTHNAME",
                        "LOCALMONTHNAMEGEN",
                        "LOCALTIME",
                        "LOCALTIMESTAMP",
                        "LOCALWEEK",
                        "LOCALYEAR",
                        "NAMESPACE",
                        "NAMESPACEE",
                        "NAMESPACENUMBER",
                        "NUMBEROFACTIVEUSERS",
                        "NUMBEROFADMINS",
                        "NUMBEROFARTICLES",
                        "NUMBEROFEDITS",
                        "NUMBEROFFILES",
                        "NUMBEROFPAGES",
                        "NUMBEROFUSERS",
                        "PAGELANGUAGE",
                        "PAGENAME",
                        "PAGENAMEE",
                        "REVISIONDAY",
                        "REVISIONDAY2",
                        "REVISIONID",
                        "REVISIONMONTH",
                        "REVISIONMONTH1",
                        "REVISIONSIZE",
                        "REVISIONTIMESTAMP",
                        "REVISIONUSER",
                        "REVISIONYEAR",
                        "ROOTPAGENAME",
                        "ROOTPAGENAMEE",
                        "SITENAME",
                        "SUBJECTPAGENAME",
                        "SUBJECTPAGENAMEE",
                        "SUBJECTSPACE",
                        "SUBJECTSPACEE",
                        "SUBPAGENAME",
                        "SUBPAGENAMEE",
                        "TALKPAGENAME",
                        "TALKPAGENAMEE",
                        "TALKSPACE",
                        "TALKSPACEE",
                        "USERLANGUAGE",
                    ),
                caseInsensitive =
                    setOf(
                        "#language",
                        "articlepath",
                        "pageid",
                        "scriptpath",
                        "server",
                        "servername",
                        "stylepath",
                    ),
            )

        /** MediaWiki's defaults, for a wiki whose own rules are not known. */
        public val DEFAULT: TitleRules = TitleRules()

        /** A namespace name as compared: lowercased, underscores as spaces, runs of spaces as one. */
        internal fun normaliseName(name: String): String = normaliseTitle(name).lowercase()

        /** A title's text with each run of underscores and spaces as one space, and the ends trimmed. */
        internal fun normaliseTitle(title: String): String = title.replace(SPACES, " ").trim()
    }
}
