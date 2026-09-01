package com.fenakhay.kwikibot.wikitext

/**
 * One tidying pass over a page.
 *
 * Each is separate and named so a caller can choose: what counts as tidy differs between
 * projects, and a pass that is right on Wikipedia can be wrong on Wiktionary.
 */
public fun interface CosmeticPass {

    /** The page after this pass. Returning the input unchanged means the pass found nothing. */
    public fun apply(code: Markup): Markup
}

/**
 * Tidying passes over a page.
 *
 * **Nothing is enabled by default.** Many wikis prohibit cosmetic-only edits, and a pass that is
 * correct on one project can be wrong on another: de-piping `[[foo|foos]]` breaks Wiktionary
 * entries where the pipe is a deliberate link to a lemma. [CosmeticChanges] is assembled from
 * named passes, and [SAFE] is the subset with no project-specific behaviour.
 *
 * **Passes operate on the parsed page rather than its text.** Templates, comments and `<nowiki>`
 * are structure here, so a pass that must not touch them does not see them.
 *
 * ```
 * val tidied = CosmeticChanges.SAFE.apply(Wikitext.parse(page.text))
 * ```
 */
public class CosmeticChanges(private val passes: List<CosmeticPass>) {

    /** The page with every pass applied, in order. */
    public fun apply(code: Markup): Markup = passes.fold(code) { current, pass ->
        pass.apply(current)
    }

    /** The page with every pass applied, as text. */
    public fun apply(wikitext: String): String = apply(Wikitext.parse(wikitext)).serialize()

    /** Whether any pass would change this page. */
    public fun wouldChange(wikitext: String): Boolean = apply(wikitext) != wikitext

    /** The passes that ship with the library, and the set applied by default. */
    public companion object {

        /**
         * Trailing whitespace at the end of lines.
         *
         * Invisible both in the rendered page and in a diff, which is why it accumulates.
         * Nothing inside `<pre>` or `<nowiki>` is touched, where whitespace is content rather
         * than formatting.
         */
        public val TRAILING_WHITESPACE: CosmeticPass = textPass { it.replace(TRAILING, "") }

        /**
         * Three or more blank lines in a row, reduced to two.
         *
         * MediaWiki renders any run of blank lines the same, so the extra ones are noise a later
         * editor has to scroll past.
         */
        public val EXTRA_BLANK_LINES: CosmeticPass = textPass { it.replace(BLANK_RUN, "\n\n\n") }

        /**
         * `<b>` and `<i>` written as the wiki markup that means the same thing.
         *
         * Not `<strong>` or `<em>`: those carry meaning HTML keeps and wiki markup does not, and
         * replacing them changes what a screen reader says.
         */
        public val HTML_EMPHASIS: CosmeticPass = CosmeticPass { code ->
            code.mapNodes { node ->
                val tag = node as? Tag ?: return@mapNodes node
                when {
                    tag.wikiMarkup != null -> node
                    tag.name.equals("b", ignoreCase = true) -> tag.asMarkup("'''")
                    tag.name.equals("i", ignoreCase = true) -> tag.asMarkup("''")
                    else -> node
                }
            }
        }

        /**
         * `&nbsp;` and its kind left alone; everything else decoded.
         *
         * Four of the five that stay mean something as markup — decoding `&lt;` would turn text
         * into a tag. The fifth is `&nbsp;`, where decoding turns a deliberate non-breaking space
         * into a character no later editor can tell from an ordinary one.
         */
        public val HTML_ENTITIES: CosmeticPass = CosmeticPass { code ->
            code.mapNodes { node ->
                val entity = node as? HtmlEntity ?: return@mapNodes node
                entity.decoded()?.let { TextNode(it) } ?: node
            }
        }

        /**
         * A link whose display text is its own target: `[[foo|foo]]` becomes `[[foo]]`.
         *
         * Only the exact case. `[[Foo|foo]]` is left alone, because on a case-sensitive wiki
         * those are different pages, and on a case-insensitive one the difference is what the
         * sentence needed.
         */
        public val REDUNDANT_LINK_TEXT: CosmeticPass = CosmeticPass { code ->
            code.mapNodes { node ->
                val link = node as? WikiLink ?: return@mapNodes node
                val text = link.text ?: return@mapNodes node
                if (text.serialize() == link.target.serialize()) link.copy(text = null) else node
            }
        }

        /**
         * `<br>` written as `<br />`.
         *
         * Both are accepted by MediaWiki, so this is only a house style — which is why it is not
         * in [SAFE].
         */
        public val SELF_CLOSING_BR: CosmeticPass = CosmeticPass { code ->
            code.mapNodes { node ->
                val tag = node as? Tag ?: return@mapNodes node
                if (tag.name.equals("br", ignoreCase = true) && tag.implicitClose) {
                    tag.copy(implicitClose = false, padding = tag.padding.ifEmpty { " " })
                } else {
                    node
                }
            }
        }

        /**
         * Sections with a heading and nothing under them.
         *
         * Only a section with no content at all: a section holding a comment or a template has
         * something in it, whether or not it renders.
         */
        public val EMPTY_SECTIONS: CosmeticPass = CosmeticPass { code ->
            Markup(dropEmptySections(code.nodes))
        }

        /**
         * The passes with no project-specific behaviour.
         *
         * Whitespace, redundant blank lines, and a link whose text repeats its target exactly.
         * Everything else is a house style and is chosen explicitly.
         */
        public val SAFE: CosmeticChanges = CosmeticChanges(
            listOf(TRAILING_WHITESPACE, EXTRA_BLANK_LINES, REDUNDANT_LINK_TEXT),
        )

        /** A tidier built from named passes. */
        public fun of(vararg passes: CosmeticPass): CosmeticChanges = CosmeticChanges(passes.toList())

        /**
         * The entities that must not be decoded.
         *
         * Three because they are markup, and `&nbsp;` because decoding it produces a character
         * indistinguishable from a space in every editor.
         */
        private val KEEP_ENCODED = setOf("lt", "gt", "amp", "nbsp", "quot")

        private val TRAILING = Regex("""[ \t]+(?=\n)""")
        private val BLANK_RUN = Regex("""\n{4,}""")

        /** A pass that rewrites the text nodes of a page and leaves its structure alone. */
        private fun textPass(transform: (String) -> String): CosmeticPass = CosmeticPass { code ->
            code.mapNodes { node ->
                val text = node as? TextNode ?: return@mapNodes node
                TextNode(transform(text.text))
            }
        }

        /** The character an entity stands for, or `null` if it is one to leave alone. */
        private fun HtmlEntity.decoded(): String? = when {
            value.lowercase() in KEEP_ENCODED -> null
            numeric -> codePoint()?.let { String(Character.toChars(it)) }
            else -> NAMED[value.lowercase()]
        }

        private fun HtmlEntity.codePoint(): Int? =
            if (hexChar != null) value.toIntOrNull(HEX) else value.toIntOrNull()

        private const val HEX = 16

        /**
         * The named entities worth decoding.
         *
         * Deliberately short. A long table would decode entities whose replacement looks
         * identical to the entity in some fonts, producing an unreviewable diff.
         */
        private val NAMED = mapOf(
            "ndash" to "–",
            "mdash" to "—",
            "hellip" to "…",
            "times" to "×",
            "middot" to "·",
            "bull" to "•",
            "deg" to "°",
            "plusmn" to "±",
            "frac12" to "½",
            "eacute" to "é",
            "egrave" to "è",
            "agrave" to "à",
            "uuml" to "ü",
            "ouml" to "ö",
            "auml" to "ä",
            "szlig" to "ß",
            "ccedil" to "ç",
            "ntilde" to "ñ",
        )

        /** Drops each heading followed by nothing but whitespace, and that whitespace with it. */
        private fun dropEmptySections(nodes: List<Node>): List<Node> {
            val kept = mutableListOf<Node>()
            var index = 0

            while (index < nodes.size) {
                val node = nodes[index]
                if (node !is Heading) {
                    kept += node
                    index++
                    continue
                }

                var end = index + 1
                while (end < nodes.size && nodes[end] !is Heading) end++
                val body = nodes.subList(index + 1, end)

                if (body.any { it !is TextNode || it.text.isNotBlank() }) {
                    kept += node
                    index++
                } else {
                    // The heading goes and so does the blank space under it. Keeping that space
                    // would leave a growing gap where sections used to be.
                    kept.removeTrailingBlankText()
                    index = end
                }
            }

            return kept
        }

        private fun MutableList<Node>.removeTrailingBlankText() {
            while (isNotEmpty()) {
                val last = last()
                if (last is TextNode && last.text.isBlank()) removeAt(lastIndex) else break
            }
        }

        private fun Tag.asMarkup(markup: String) = copy(
            wikiMarkup = markup,
            attributes = emptyList(),
        )
    }
}
