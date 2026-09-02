package com.fenakhay.kwikibot.examples.compounds

/**
 * Parses `Wiktionary:Todo/compounds not linked to from components` list pages.
 *
 * Each line carries both the entry to edit and the terms to add to it, in one of two layouts.
 *
 * The **snippet** layout (the `Ti-Z` list) embeds a copy-pasteable section:
 * ```
 * * '''[[Special:Edit/volcano|volcano]]''' <br><br>====Derived terms====<br>{{col|en|[[hypervolcano]]|[[vog]]
 * ```
 *
 * Its `{{col}}` is deliberately left unterminated: an unclosed `{{` makes MediaWiki render the snippet
 * literally instead of expanding it into a column table. Nothing here depends on the closing braces — the
 * line is scanned for wikilinks after the marker.
 *
 * The **bare** layout (`A-I`, `J-Pe`) is a target, a colon, and pipe-separated terms:
 * ```
 * * '''[[Special:Edit/Amerindian|Amerindian]]''': [[Amerindianism]]|[[Amerindianist]]
 * ```
 *
 * It carries no language code, so terms are taken as [DEFAULT_LANG]. That is sound for this series: the lists
 * are generated from English compounds, and every `{{col}}` in the snippet layout says `en`.
 */
public object TodoList {

    /** The language assumed by the bare layout, which records none of its own. */
    public const val DEFAULT_LANG: String = "en"

    /** `[[Special:Edit/volcano|volcano]]` — the page the terms must be added to. */
    private val TARGET = Regex("""\[\[\s*Special:Edit/([^\[\]]+?)]]""", RegexOption.IGNORE_CASE)

    /** `{{col|en|`, `{{col3|en|`, `{{der4|en|` — the container, and the language code. */
    private val CONTAINER =
        Regex(
            """\{\{\s*(?:col|der|rel)[0-9]*\s*\|\s*([a-z][a-z0-9-]*)\s*\|""",
            RegexOption.IGNORE_CASE,
        )

    /** Any wikilink; the inner text is split on `|` and `#` afterwards. */
    private val LINK = Regex("""\[\[([^\[\]]+?)]]""")

    /**
     * The `''':` that closes the bolded target and opens a bare term list.
     *
     * Matched at the end of the target link rather than searched for, so a snippet line — whose target is
     * followed by `<br>` — can never be mistaken for a bare one.
     */
    private val BARE_PAYLOAD = Regex("""\s*(?:''')?\s*:\s*""")

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Prefixes that take a title off en.wiktionary's main namespace.
     *
     * The lists contain a handful of `Special:Edit/w:Etsy` targets. This is the first of two gates; the
     * second is [com.fenakhay.kwikibot.client.Wiki.ref], which refuses an interwiki title outright and needs
     * no list kept up to date.
     */
    private val OFF_WIKI =
        setOf(
            // sister projects and meta-wikis
            "b",
            "c",
            "commons",
            "d",
            "foundation",
            "incubator",
            "m",
            "meta",
            "mediawikiwiki",
            "mw",
            "n",
            "outreach",
            "phab",
            "q",
            "s",
            "species",
            "translatewiki",
            "v",
            "voy",
            "w",
            "wikibooks",
            "wikidata",
            "wikinews",
            "wikipedia",
            "wikiquote",
            "wikisource",
            "wikispecies",
            "wikiversity",
            "wikivoyage",
            "wikt",
            // en.wiktionary namespaces; entries live in main space, which has no prefix
            "appendix",
            "category",
            "citations",
            "concordance",
            "help",
            "image",
            "index",
            "media",
            "mediawiki",
            "module",
            "rhymes",
            "reconstruction",
            "sign gloss",
            "special",
            "talk",
            "template",
            "thesaurus",
            "transwiki",
            "user",
            "wiktionary",
        )

    /** `fr:`, `zh-min-nan:` — interwiki links to other-language projects. */
    private val LANGUAGE_PREFIX = Regex("""^[a-z]{2,3}(?:-[a-z0-9-]+)?$""")

    /** How a line was written. */
    /** The forms a line on the list page is written in. */
    public enum class Layout {
        /** A line quoting the wikitext around the term. */
        SNIPPET,

        /** A line naming the page and the terms and nothing else. */
        BARE,
    }

    /** One line as written, before any filtering. */
    public data class RawLine(
        /** The page the line names. */
        val title: String,
        /** The language section the terms belong in. */
        val lang: String,
        /** The terms to add. */
        val terms: List<String>,
        /** How the line was written, kept so a skipped line can be reported as read. */
        val layout: Layout,
    )

    /** One unit of work: add [terms] to [title]'s derived terms. */
    public data class Task(
        /** The page to edit. */
        val title: String,
        /** The language section to edit within it. */
        val lang: String,
        /** The terms to add to it. */
        val terms: List<String>,
    )

    /** What a whole list page parsed into. */
    public data class ParseReport(
        /** The work the page describes. */
        val tasks: List<Task>,
        /**
         * How many lines were not understood, which is worth reporting rather than discarding: a list page
         * that parses to nothing looks the same as one with nothing to do.
         */
        val skippedLines: Int,
    )

    /**
     * Whether a title carries an interwiki or namespace prefix.
     *
     * Talk forms (`User talk:`) are covered because the prefix is matched whole. A leading colon and titles
     * that merely contain one, such as `:-)`, are not prefixed and stay usable.
     */
    public fun isOffWiki(title: String): Boolean {
        val head = title.substringBefore(':', missingDelimiterValue = "")
        if (head.isEmpty() || ':' !in title) return false

        val key = head.trim().lowercase().removeSuffix(" talk")
        return key in OFF_WIKI || LANGUAGE_PREFIX.matches(key)
    }

    /** Page-title normalization: unescape entities, underscores to spaces, collapse whitespace. */
    public fun normalizeTitle(raw: String): String =
        WHITESPACE.replace(unescapeEntities(raw).replace('_', ' '), " ").trim()

    /** `x|display` becomes `x`; `x#English` becomes `x`; then normalized. */
    public fun linkTarget(inner: String): String =
        normalizeTitle(inner.substringBefore('|').substringBefore('#'))

    /**
     * The structure of one `*` line, with no filtering applied.
     *
     * `null` only when the line has no `Special:Edit` target or no payload marker at all. Off-wiki targets,
     * self-references and duplicates are reported as written, because the report of what was *not* done needs
     * to see them.
     */
    public fun extractLine(line: String): RawLine? {
        val text = line.trim()
        if (!text.startsWith("*")) return null

        val target = TARGET.find(text)
        val title = target?.let { linkTarget(it.groupValues[1]) }.orEmpty()
        if (target == null || title.isEmpty()) return null

        val payload = payloadAfter(text, target.range.last + 1) ?: return null

        val terms =
            LINK.findAll(text.substring(payload.start))
                .map { linkTarget(it.groupValues[1]) }
                .filter { it.isNotEmpty() }
                .toList()

        return RawLine(title, payload.lang, terms, payload.layout)
    }

    /** Where a line's terms begin, and what the layout says about them. */
    private data class Payload(val start: Int, val lang: String, val layout: Layout)

    /**
     * Finds the marker that opens the term list, or `null` when the line has none.
     *
     * The container is looked for first: a snippet line carries `{{col|en|`, and its language code is the one
     * to use. Only a line without one can be a bare list, and its separator is matched at the end of the
     * target link rather than searched for, so the colon inside `Special:Edit` cannot open a payload.
     */
    private fun payloadAfter(text: String, from: Int): Payload? {
        CONTAINER.find(text, from)?.let { container ->
            return Payload(
                start = container.range.last + 1,
                lang = container.groupValues[1].lowercase(),
                layout = Layout.SNIPPET,
            )
        }

        val bare = BARE_PAYLOAD.matchAt(text, from) ?: return null
        return Payload(bare.range.last + 1, DEFAULT_LANG, Layout.BARE)
    }

    /** Drops self-references and duplicates, keeping first-seen order. */
    public fun usableTerms(title: String, terms: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        // A component never lists itself, and a term repeated in one line is noise.
        terms.filter { it.isNotEmpty() && it != title }.forEach { seen += it }
        return seen.toList()
    }

    /** Parses one line, or `null` when it is not usable work. */
    public fun parseLine(line: String): Task? {
        val raw = extractLine(line) ?: return null
        if (isOffWiki(raw.title)) return null

        val terms = usableTerms(raw.title, raw.terms)
        return if (terms.isEmpty()) null else Task(raw.title, raw.lang, terms)
    }

    /**
     * Emits a list line in the source page's own layout, byte for byte.
     *
     * The snippet layout's `{{col}}` is left unterminated, exactly as the lists write it: an unclosed `{{` is
     * what makes MediaWiki show the snippet rather than expand it.
     */
    public fun renderLine(
        title: String,
        terms: List<String>,
        lang: String = DEFAULT_LANG,
        layout: Layout = Layout.BARE,
    ): String {
        val links = terms.joinToString("|") { "[[$it]]" }
        val target = "'''[[Special:Edit/$title|$title]]'''"

        return when (layout) {
            Layout.SNIPPET -> "* $target <br><br>====Derived terms====<br>{{col|$lang|$links"
            Layout.BARE -> "* $target: $links"
        }
    }

    /** Combines tasks aimed at the same title and language, keeping first-seen order. */
    public fun merge(tasks: List<Task>): List<Task> {
        val merged = LinkedHashMap<Pair<String, String>, LinkedHashSet<String>>()
        for (task in tasks) {
            merged.getOrPut(task.title to task.lang) { LinkedHashSet() } += task.terms
        }
        return merged.map { (key, terms) -> Task(key.first, key.second, terms.toList()) }
    }

    /** Parses a whole list page, counting the `*` lines that yielded no work. */
    public fun parsePage(text: String): ParseReport {
        val tasks = mutableListOf<Task>()
        var skipped = 0

        for (line in text.lineSequence()) {
            val stripped = line.trim()
            if (stripped.isEmpty()) continue

            val task = parseLine(stripped)
            when {
                task != null -> tasks += task
                // Only "*" lines were ever meant to be entries; the rest is page furniture.
                stripped.startsWith("*") -> skipped++
            }
        }

        return ParseReport(merge(tasks), skipped)
    }

    /** Decodes the HTML entities these lists actually contain. */
    private fun unescapeEntities(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.drop(2).toIntOrNull(HEX)?.let { String(Character.toChars(it)) }

                body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }

                else -> NAMED[body.lowercase()]
            } ?: match.value
        }
    }

    private const val HEX = 16

    private val ENTITY = Regex("""&(#\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);""")

    private val NAMED =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "ndash" to "–",
            "mdash" to "—",
        )
}
