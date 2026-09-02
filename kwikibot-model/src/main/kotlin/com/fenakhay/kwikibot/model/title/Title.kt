package com.fenakhay.kwikibot.model.title

/**
 * The result of parsing a raw link target.
 *
 * Parsing is total: every input produces one of the three cases rather than throwing, so an off-wiki target
 * cannot be mistaken for a local page. A bot that only ever edits its own wiki handles [Local] and reports
 * the rest.
 */
public sealed interface Title {

    /** The `#fragment` part, if the raw title carried one. */
    public val fragment: String?

    /** A page on this wiki. */
    public data class Local(
        /** Which namespace the page is in. */
        val namespace: Namespace,
        /** The title after the namespace prefix, with the prefix already removed. */
        val text: String,
        override val fragment: String? = null,
    ) : Title {
        init {
            require(text.isNotEmpty()) { "local title text must not be empty" }
        }

        /**
         * A debugging rendering using canonical English namespace names.
         *
         * Custom namespaces have no canonical name without the site info of the wiki, so they render as
         * `ns118:` rather than guessing. Use [NamespaceMap.format] for display.
         */
        override fun toString(): String {
            val info = NamespaceMap.CANONICAL[namespace]
            val prefix =
                when {
                    namespace == Namespace.MAIN -> ""
                    !info?.canonicalName.isNullOrEmpty() -> "${info.canonicalName}:"
                    else -> "ns${namespace.id}:"
                }
            return buildString {
                append(prefix).append(text)
                fragment?.let { append('#').append(it) }
            }
        }
    }

    /**
     * A target on another wiki or project, such as `w:Etsy` or `de:Haus`.
     *
     * A distinct case rather than a local title, so an interwiki target cannot reach a write path that
     * expects a page on this wiki.
     */
    public data class Interwiki(
        /** The interwiki prefix that sends this title to another wiki. */
        val prefix: String,
        /** Everything after the prefix, which is that wiki's title and not this one's. */
        val rest: String,
        override val fragment: String? = null,
    ) : Title {
        override fun toString(): String = buildString {
            append(prefix).append(':').append(rest)
            fragment?.let { append('#').append(it) }
        }
    }

    /** A target MediaWiki would reject. */
    public data class Invalid(
        /** The text as it was given, before any normalisation. */
        val raw: String,
        /** Which rule it broke. */
        val reason: Reason,
    ) : Title {
        override val fragment: String?
            get() = null

        override fun toString(): String = "invalid title (${reason.name.lowercase()}): $raw"

        /** Why a raw title cannot name a page. */
        public enum class Reason {
            /** Nothing left after normalization. */
            EMPTY,

            /** Contains one of `# < > [ ] { } |` or a control character. */
            ILLEGAL_CHARACTER,

            /** A relative path segment (`.`, `..`) that MediaWiki refuses to resolve. */
            RELATIVE_PATH,

            /** Three or more consecutive tildes, which MediaWiki expands on save. */
            SIGNATURE,

            /**
             * Contains a URL percent-escape such as `%C3`. MediaWiki forbids these in titles so that a title
             * and its URL encoding cannot name different pages.
             */
            PERCENT_ESCAPE,

            /** Longer than 255 bytes in UTF-8. */
            TOO_LONG,

            /**
             * A talk page written as `Talk:Project:Foo` instead of `Project talk:Foo`.
             *
             * `Category:Template:Foo` is fine by contrast — only the talk namespace rejects a following
             * namespace prefix.
             */
            TALK_OF_NON_MAIN,
        }
    }

    /** Parsing, and the rules a wiki applies to a title before storing it. */
    public companion object {
        /** The hard limit MediaWiki places on a page title, in UTF-8 bytes. */
        public const val MAX_BYTES: Int = 255

        private val ILLEGAL = charArrayOf('#', '<', '>', '[', ']', '{', '}', '|')

        private val NAMED_ENTITIES =
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

        private val ENTITY = Regex("&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

        private val PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")

        /** Characters below this are C0 controls; 0x7F is DEL. Neither may appear in a title. */
        private const val FIRST_PRINTABLE = 0x20
        private const val DELETE = 0x7F
        private const val HEX = 16

        /**
         * Parses [raw] the way MediaWiki resolves a link target.
         *
         * Applies the normalizations MediaWiki performs — underscores to spaces, whitespace collapsing, HTML
         * entity decoding, a single leading colon stripped, and the first-letter casing rule of the resolved
         * namespace — then classifies the result. Percent-escapes are rejected rather than decoded, as
         * MediaWiki does.
         *
         * @param raw the title as it was written, before any normalisation.
         * @param namespaces the namespaces of the wiki; defaults to the canonical English set.
         * @param interwiki the interwiki prefixes of the wiki; defaults to none, which makes every prefix
         *   either a namespace or plain title text.
         * @param defaultNamespace the namespace to assume when the title carries no prefix.
         */
        public fun parse(
            raw: String,
            namespaces: NamespaceMap = NamespaceMap.CANONICAL,
            interwiki: InterwikiMap = InterwikiMap.EMPTY,
            defaultNamespace: Namespace = Namespace.MAIN,
        ): Title {
            var text = normalizeWhitespace(decodeEntities(raw))

            // A leading colon suppresses the special meaning of a category or file link; the
            // namespace itself still applies, so ":Category:Foo" is a page in Category space.
            if (text.startsWith(":")) text = text.removePrefix(":").trim()

            val (beforeFragment, fragment) = splitFragment(text)
            text = normalizeWhitespace(beforeFragment)

            if (text.isEmpty()) return Invalid(raw, Invalid.Reason.EMPTY)

            return when (val prefixes = resolvePrefixes(text, namespaces, interwiki, defaultNamespace)) {
                is Prefixes.OffWiki -> Interwiki(prefixes.prefix, prefixes.rest, fragment)
                is Prefixes.Rejected -> Invalid(raw, prefixes.reason)
                is Prefixes.Local -> localTitle(raw, prefixes, namespaces, fragment)
            }
        }

        private fun localTitle(
            raw: String,
            prefixes: Prefixes.Local,
            namespaces: NamespaceMap,
            fragment: String?,
        ): Title {
            val text = normalizeWhitespace(prefixes.rest)
            if (text.isEmpty()) return Invalid(raw, Invalid.Reason.EMPTY)

            validate(text)?.let {
                return Invalid(raw, it)
            }

            val case = namespaces[prefixes.namespace]?.case ?: TitleCase.FIRST_LETTER
            val cased =
                if (case == TitleCase.FIRST_LETTER) {
                    text.replaceFirstChar { it.uppercaseChar() }
                } else {
                    text
                }
            return Local(prefixes.namespace, cased, fragment)
        }

        /** What the leading `prefix:` chain of a title resolved to. */
        private sealed interface Prefixes {
            data class Local(val namespace: Namespace, val rest: String) : Prefixes

            data class OffWiki(val prefix: String, val rest: String) : Prefixes

            data class Rejected(val reason: Invalid.Reason) : Prefixes
        }

        /**
         * Consumes the leading `prefix:` chain, namespace before interwiki.
         *
         * On en.wiktionary `Wiktionary` is both the project namespace and an interwiki prefix, and MediaWiki
         * reads `Wiktionary:Todo` as the local project page. Once a namespace is found no further prefix is
         * interpreted, so `Wiktionary:w:Etsy` stays a local page whose name happens to contain a colon.
         */
        private fun resolvePrefixes(
            text: String,
            namespaces: NamespaceMap,
            interwiki: InterwikiMap,
            defaultNamespace: Namespace,
        ): Prefixes {
            var rest = text

            while (true) {
                val colon = rest.indexOf(':')
                if (colon <= 0) return Prefixes.Local(defaultNamespace, rest)

                val prefix = rest.substring(0, colon).trim()
                val remainder = rest.substring(colon + 1).trim()

                val namespace = namespaces.byPrefix(prefix)
                if (namespace != null) {
                    return namespaced(namespace, remainder, namespaces)
                }

                // A prefix naming this same wiki is stripped, and what follows is parsed as an
                // ordinary local title — including its own namespace prefix.
                if (!interwiki.isSelf(prefix)) {
                    return if (prefix in interwiki) {
                        Prefixes.OffWiki(prefix.replace('_', ' ').trim(), remainder)
                    } else {
                        Prefixes.Local(defaultNamespace, rest)
                    }
                }

                if (remainder.isEmpty()) return Prefixes.Rejected(Invalid.Reason.EMPTY)
                rest = remainder
            }
        }

        private fun namespaced(
            namespace: NamespaceInfo,
            remainder: String,
            namespaces: NamespaceMap,
        ): Prefixes =
            when {
                // "Category:" names a namespace and nothing in it, which MediaWiki rejects rather
                // than reading as a page literally called "Category:".
                remainder.isEmpty() -> Prefixes.Rejected(Invalid.Reason.EMPTY)

                namespace.id == Namespace.TALK && startsWithNamespace(remainder, namespaces) ->
                    Prefixes.Rejected(Invalid.Reason.TALK_OF_NON_MAIN)

                else -> Prefixes.Local(namespace.id, remainder)
            }

        private fun validate(text: String): Invalid.Reason? =
            when {
                text.any { it in ILLEGAL } -> Invalid.Reason.ILLEGAL_CHARACTER
                text.any { it.code < FIRST_PRINTABLE || it.code == DELETE } ->
                    Invalid.Reason.ILLEGAL_CHARACTER
                PERCENT_ESCAPE.containsMatchIn(text) -> Invalid.Reason.PERCENT_ESCAPE
                text.contains("~~~") -> Invalid.Reason.SIGNATURE
                isRelativePath(text) -> Invalid.Reason.RELATIVE_PATH
                text.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> Invalid.Reason.TOO_LONG
                else -> null
            }

        private fun startsWithNamespace(text: String, namespaces: NamespaceMap): Boolean {
            val colon = text.indexOf(':')
            if (colon <= 0) return false
            return namespaces.byPrefix(text.substring(0, colon)) != null
        }

        private fun isRelativePath(text: String): Boolean =
            text == "." ||
                text == ".." ||
                text.startsWith("./") ||
                text.startsWith("../") ||
                text.endsWith("/.") ||
                text.endsWith("/..") ||
                text.contains("/./") ||
                text.contains("/../")

        private fun splitFragment(text: String): Pair<String, String?> {
            val hash = text.indexOf('#')
            if (hash < 0) return text to null
            val fragment = normalizeWhitespace(text.substring(hash + 1))
            return text.substring(0, hash) to fragment.ifEmpty { null }
        }

        private fun normalizeWhitespace(text: String): String {
            val spaced =
                buildString(text.length) {
                    for (ch in text) append(if (ch == '_') ' ' else ch)
                }
            return spaced.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        }

        private fun decodeEntities(text: String): String {
            if ('&' !in text) return text
            return ENTITY.replace(text) { match ->
                val body = match.groupValues[1]
                val decoded =
                    when {
                        body.startsWith("#x") || body.startsWith("#X") ->
                            body.drop(2).toIntOrNull(HEX)?.let { String(Character.toChars(it)) }

                        body.startsWith("#") ->
                            body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }

                        else -> NAMED_ENTITIES[body.lowercase()]
                    }
                decoded ?: match.value
            }
        }
    }
}
