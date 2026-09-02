package com.fenakhay.kwikibot.examples.compounds

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink
import com.fenakhay.kwikibot.wikitext.ops.Section
import com.fenakhay.kwikibot.wikitext.ops.outline

/**
 * Where things go in a Wiktionary entry.
 *
 * Navigating the section tree rather than working in character offsets: the parser round-trips byte for byte,
 * so serialization puts back the parts an edit did not touch without anything here having to preserve them.
 */
public object EntryLayout {

    /** Section a term list is added to. */
    public const val DERIVED_TERMS: String = "Derived terms"

    /** Section a term counts as already listed in, as well as [DERIVED_TERMS]. */
    public const val RELATED_TERMS: String = "Related terms"

    /**
     * Headings that may host a derived terms subsection.
     *
     * Anything not listed is not a part of speech, which is what keeps `References`, `Further reading` and
     * `Anagrams` from being treated as one.
     */
    public val POS_HEADINGS: Set<String> =
        setOf(
            "abbreviation",
            "acronym",
            "adjective",
            "adnominal",
            "adverb",
            "affix",
            "article",
            "circumfix",
            "classifier",
            "conjunction",
            "contraction",
            "counter",
            "determiner",
            "ideophone",
            "idiom",
            "infix",
            "initialism",
            "interfix",
            "interjection",
            "letter",
            "noun",
            "numeral",
            "number",
            "ordinal number",
            "participle",
            "particle",
            "phrase",
            "postposition",
            "prefix",
            "preposition",
            "prepositional phrase",
            "pronoun",
            "proper noun",
            "proverb",
            "punctuation mark",
            "root",
            "suffix",
            "syllable",
            "symbol",
            "verb",
        )

    /**
     * The order subsections follow a definition list in, from the example entry in
     * [WT:EL](https://en.wiktionary.org/wiki/Wiktionary:Entry_layout) "List of headings".
     */
    private val POS_SUBSECTIONS =
        listOf(
            "usage notes",
            "reconstruction notes",
            "inflection",
            "declension",
            "conjugation",
            "alternative forms",
            "alternative reconstructions",
            "synonyms",
            "antonyms",
            "hypernyms",
            "hyponyms",
            "meronyms",
            "holonyms",
            "troponyms",
            "coordinate terms",
            "derived terms",
            "related terms",
            "collocations",
            "descendants",
            "translations",
        )

    /**
     * Headings that close out a part-of-speech section.
     *
     * WT:EL puts References, Further reading and Anagrams at the language level and does not mention
     * Statistics at all — but all four occur as POS children in practice, `{{rank}}` alone putting a
     * Statistics section in some 43,000 entries. Every one of them belongs after Derived terms, and listing
     * them is what stops [rank] calling them unknown: an unknown heading is not treated as a successor, so a
     * new section would be appended below them instead of above.
     */
    private val TRAILING_SUBSECTIONS =
        listOf(
            "statistics",
            "see also",
            "references",
            "further reading",
            "anagrams",
        )

    /** Where a new subsection belongs, relative to the ones already there. */
    public val ELE_ORDER: List<String> = POS_SUBSECTIONS + TRAILING_SUBSECTIONS

    private val NUMBERED_ETYMOLOGY = Regex("""^etymology\s+\d+$""", RegexOption.IGNORE_CASE)

    /** Templates that file a page in a category, which belong at the very bottom. */
    private val CATEGORY_TEMPLATES =
        setOf(
            "cln",
            "c",
            "catlangname",
            "catlangcode",
            "topics",
            "top",
            "defaultsort",
        )

    /** The language section of a page, or `null` when the entry has none. */
    public fun language(page: Markup, language: String): Section? =
        page.outline().find(language, level = LANGUAGE_LEVEL)

    /** Whether the entry uses `===Etymology 1===` numbering, which pushes parts of speech down a level. */
    public fun usesNumberedEtymologies(language: Section): Boolean =
        language.subsections.any { NUMBERED_ETYMOLOGY.matches(it.title.orEmpty()) }

    /** The heading level parts of speech sit at: 4 under numbered etymologies, otherwise 3. */
    public fun posLevel(language: Section): Int =
        if (usesNumberedEtymologies(language)) LANGUAGE_LEVEL + 2 else LANGUAGE_LEVEL + 1

    /** The part-of-speech sections of a language section, at whichever level they sit. */
    public fun posSections(language: Section): List<Section> {
        val level = posLevel(language)
        return language.all().filter { it.level == level && it.title?.lowercase() in POS_HEADINGS }.toList()
    }

    /**
     * Where a heading sits in [ELE_ORDER], or `-1` for one outside the canonical sequence.
     *
     * Unknown headings rank before everything, so a new section is never inserted above a heading the bot
     * does not understand — it appends after instead.
     */
    public fun rank(title: String): Int = ELE_ORDER.indexOf(title.trim().lowercase())

    /**
     * Splits a section's own content from the page furniture that must stay beneath it.
     *
     * Categories, topic templates and a `----` rule belong at the very bottom of a language section, so a
     * subsection appended at the end has to land above them rather than below.
     */
    public fun splitTrailingFurniture(nodes: List<Node>): Pair<List<Node>, List<Node>> {
        var cut = nodes.size
        while (cut > 0 && nodes[cut - 1].isFurniture()) cut--

        val content = nodes.take(cut).toMutableList()
        val furniture = nodes.drop(cut).toMutableList()

        // A `----` rule shares a text node with the prose above it, so the node has to be split
        // rather than classified whole: otherwise a new section lands below the rule, outside
        // the language it belongs to.
        val last = content.lastOrNull()
        if (last is TextNode) {
            val (kept, trailing) = splitTrailingLines(last.text)
            if (trailing.isNotEmpty()) {
                content[content.lastIndex] = TextNode(kept)
                furniture.add(0, TextNode(trailing))
            }
        }

        return content to furniture
    }

    /**
     * Splits text into what it says and the furniture lines that end it.
     *
     * Walks back over whole lines, terminators included, so the newline that ends the last real line stays
     * with it rather than being mistaken for a blank line of its own.
     */
    private fun splitTrailingLines(text: String): Pair<String, String> {
        var end = text.length
        while (end > 0) {
            val lineStart = text.lastIndexOf('\n', end - 2) + 1
            if (!text.substring(lineStart, end).isFurnitureLine()) break
            end = lineStart
        }
        return text.take(end) to text.drop(end)
    }

    private fun String.isFurnitureLine(): Boolean {
        val line = trim()
        return line.isEmpty() || (line.length >= RULE_LENGTH && line.all { it == '-' })
    }

    /**
     * Where a subsection called [heading] belongs among [section]'s existing children.
     *
     * The first child that should follow it decides: the new section goes directly before that one. When
     * nothing should follow, it goes last.
     */
    public fun insertionIndex(section: Section, heading: String): Int {
        val newRank = rank(heading)
        val following = section.subsections.indexOfFirst { rank(it.title.orEmpty()) > newRank }
        return if (following < 0) section.subsections.size else following
    }

    /** Whether a node is page furniture rather than content. */
    private fun Node.isFurniture(): Boolean =
        when (this) {
            // Whitespace between furniture is furniture too, but real text is not.
            is TextNode ->
                text.isBlank() || text.trim().all { it == '-' } && text.trim().length >= RULE_LENGTH
            is WikiLink -> title.trimStart(':').substringBefore(':').equals("Category", ignoreCase = true)
            is Template -> title.lowercase() in CATEGORY_TEMPLATES
            else -> false
        }

    private const val LANGUAGE_LEVEL = 2
    private const val RULE_LENGTH = 4
}
