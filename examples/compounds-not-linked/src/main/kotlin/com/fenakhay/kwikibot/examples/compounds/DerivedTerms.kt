package com.fenakhay.kwikibot.examples.compounds

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.ops.Section
import com.fenakhay.kwikibot.wikitext.ops.outline

/** What the transform did, or why it declined to do anything. */
public data class TransformResult(
    /** The page text after the transform, unchanged when nothing was done. */
    val text: String,
    /** Whether anything changed, and if not, whether that was deliberate. */
    val status: Status,
    /** Why the page was skipped, empty when it was not. */
    val reason: String = "",
    /** The terms added, which the edit summary names. */
    val added: List<String> = emptyList(),
    /** Which rules fired, so a run can report what it actually did. */
    val rules: Set<Rule> = emptySet(),
) {
    /** What became of the page. */
    public enum class Status {
        /** The text was changed and is worth saving. */
        CHANGED,

        /** The transform ran and produced what was already there. */
        UNCHANGED,

        /** The transform declined to run, for the reason given. */
        SKIPPED,
    }

    /** The things the transform is allowed to do. */
    public enum class Rule {
        /** Added terms to a list that was already there. */
        ADD_DERIVED_TERMS,

        /** Created the derived-terms section, because the entry had none. */
        CREATE_SECTION,

        /** Rewrote the list into the shape the entry's other sections use. */
        NORMALIZE_CONTAINER,
    }

    /** Whether there is an edit to save. */
    public val changed: Boolean
        get() = status == Status.CHANGED
}

/**
 * Adds derived terms to one entry, or explains why it will not.
 *
 * Pure: no wiki access, so every rule can be exercised offline against real page text.
 *
 * The placement rules, in order:
 * 1. The entry must have exactly one part-of-speech section under the target language. Zero or several is a
 *    skip rather than a guess: filing a term under the wrong sense is worse than deferring it.
 * 2. If that section already has a derived terms subsection, its contents are read, merged with the new
 *    terms, sorted, and re-emitted as a single `{{col}}`.
 * 3. Otherwise a new subsection is created where WT:EL says it belongs: after the synonyms and other nyms,
 *    before related terms, descendants and translations.
 */
public object DerivedTerms {

    private val LANGUAGE_HEADINGS = mapOf("en" to "English")

    /** The level-two heading for a language code, or `null` if this bot does not know it. */
    public fun languageHeading(code: String): String? = LANGUAGE_HEADINGS[code.trim().lowercase()]

    /** Adds [terms] to [title]'s derived terms section under [lang]. */
    public fun add(
        text: String,
        title: String,
        lang: String,
        terms: List<String>,
    ): TransformResult {
        val page = Wikitext.parse(text)

        return when (val located = locate(page, lang)) {
            is Located.Refused -> skip(text, located.reason)
            is Located.Found -> edit(page, located, text, title, lang, terms)
        }
    }

    /** Works out and applies the edit, once the section to change is known. */
    @Suppress("LongParameterList") // Each argument is a distinct fact about the job.
    private fun edit(
        page: Markup,
        located: Located.Found,
        text: String,
        title: String,
        lang: String,
        terms: List<String>,
    ): TransformResult {
        val (language, pos) = located.language to located.pos

        val wanted = wantedTerms(language, title, terms)
        val derived =
            pos.subsections.filter {
                it.title?.lowercase() == EntryLayout.DERIVED_TERMS.lowercase()
            }

        when {
            wanted.isEmpty() -> return TransformResult(text, TransformResult.Status.UNCHANGED)
            derived.size > 1 -> return skip(text, "multiple_derived_sections")
        }

        // A section with anything after it needs a blank line below it; one at the end of the
        // page does not, and adding one there would leave a stray newline in the diff. The last
        // section of the page being *inside* this one still means nothing follows it.
        val followed = page.outline().all().last() !in pos.all().toSet()

        val updated =
            when (val outcome = plan(pos, derived.singleOrNull(), lang, wanted, followed)) {
                is Outcome.Refused -> return skip(text, outcome.reason)
                is Outcome.Ok -> outcome.update
            }

        val rebuilt = page.outline().replace(pos, updated.section).serialize()

        return if (rebuilt == text) {
            TransformResult(text, TransformResult.Status.UNCHANGED)
        } else {
            TransformResult(
                text = rebuilt,
                status = TransformResult.Status.CHANGED,
                added = wanted,
                rules = updated.rules,
            )
        }
    }

    /**
     * Finds the one part-of-speech section to edit, or says why there is not one.
     *
     * Zero or several is a refusal rather than a guess: filing a term under the wrong sense is worse than
     * deferring it for review.
     */
    private fun locate(page: Markup, lang: String): Located {
        val heading = languageHeading(lang) ?: return Located.Refused("unknown_lang_code:$lang")

        val language = EntryLayout.language(page, heading) ?: return Located.Refused("no_lang_section")

        val candidates = EntryLayout.posSections(language)
        return when (candidates.size) {
            0 -> Located.Refused("no_pos_section")
            1 -> Located.Found(language, candidates.single())
            else -> Located.Refused("ambiguous_pos:${describe(candidates)}")
        }
    }

    /** The section to edit, or why there is not one. */
    private sealed interface Located {
        data class Found(val language: Section, val pos: Section) : Located

        data class Refused(val reason: String) : Located
    }

    /** The terms worth adding: not the entry itself, not already listed, not repeated. */
    private fun wantedTerms(language: Section, title: String, terms: List<String>): List<String> {
        val already = existingListedTerms(language)
        val seen = LinkedHashSet<String>()

        for (raw in terms) {
            val term = TodoList.normalizeTitle(raw)
            if (term.isEmpty() || term == title || term in already) continue
            seen += term
        }
        return seen.toList()
    }

    /**
     * Terms already listed under any derived or related terms heading in the language section.
     *
     * Related terms counts as well as derived terms: a term listed there is not missing, and adding it again
     * would be noise.
     */
    private fun existingListedTerms(language: Section): Set<String> =
        language
            .all()
            .filter { it.title?.lowercase() in LISTING_HEADINGS }
            .flatMap { Containers.listedTerms(it.content.serialize()).asSequence() }
            .toSet()

    /** Either the section to write, or the reason the page is being left alone. */
    private fun plan(
        pos: Section,
        derived: Section?,
        lang: String,
        wanted: List<String>,
        followed: Boolean,
    ): Outcome =
        if (derived == null) {
            Outcome.Ok(createSection(pos, lang, wanted, followed))
        } else {
            extendSection(pos, derived, lang, wanted)
        }

    /** A part-of-speech section with the new terms merged into its existing list. */
    private fun extendSection(
        pos: Section,
        derived: Section,
        lang: String,
        wanted: List<String>,
    ): Outcome {
        val body = derived.content.serialize()
        val container = Containers.read(body) ?: return Outcome.Refused("unrecognized_container")

        val containerLang = container.lang?.trim()
        if (containerLang != null && !containerLang.equals(lang, ignoreCase = true)) {
            return Outcome.Refused("container_lang_mismatch:$containerLang")
        }

        val merged = container.entries + wanted.map { Entry(it, it) }
        val rendered = Containers.render(lang, merged, container)

        // Whatever blank lines followed the old list follow the new one, so the section's
        // spacing is not quietly rewritten along with its contents.
        val trailing = body.takeLastWhile { it == '\n' }.ifEmpty { "\n" }
        val updatedDerived = derived.withContent(Wikitext.parse("\n" + rendered + trailing))

        val rules = mutableSetOf(TransformResult.Rule.ADD_DERIVED_TERMS)
        // Only claim a normalization when the existing content was actually rewritten;
        // appending to an already-canonical {{col}} is a plain addition.
        val canonical = Containers.render(lang, container.entries, container)
        if (container.kind != Container.Kind.EMPTY && canonical != body.trim()) {
            rules += TransformResult.Rule.NORMALIZE_CONTAINER
        }

        return Outcome.Ok(Update(pos.replace(derived, updatedDerived), rules))
    }

    /** A part-of-speech section with a new derived terms subsection in the right place. */
    private fun createSection(
        pos: Section,
        lang: String,
        wanted: List<String>,
        followed: Boolean,
    ): Update {
        val rendered = Containers.render(lang, wanted.map { Entry(it, it) })
        val heading = Heading(Markup.of(EntryLayout.DERIVED_TERMS), level = pos.level + 1)
        val index = EntryLayout.insertionIndex(pos, EntryLayout.DERIVED_TERMS)

        val section =
            if (index < pos.subsections.size) {
                val newSection = Section(heading, Wikitext.parse("\n$rendered\n\n").nodes)
                pos.copy(subsections = pos.subsections.take(index) + newSection + pos.subsections.drop(index))
            } else {
                // Appending: the page furniture at the end — categories, topic templates, a rule —
                // has to stay below the new section rather than above it.
                val (stripped, furniture) = takeTrailingFurniture(pos)
                // A blank line goes below the list whenever something follows it: the furniture
                // that just moved past it, or the next section. Furniture that already begins with
                // a newline supplies its own, and at the very end of a page a blank line would only
                // be a stray newline in the diff.
                val furnitureText = furniture.joinToString("") { it.serialize() }
                val below =
                    when {
                        furnitureText.startsWith("\n") -> "\n"
                        furniture.isNotEmpty() || followed -> "\n\n"
                        else -> "\n"
                    }
                val newSection =
                    Section(
                        heading = heading,
                        nodes = Wikitext.parse("\n$rendered$below").nodes + furniture,
                    )
                ensureBlankLineBefore(stripped).let { it.copy(subsections = it.subsections + newSection) }
            }

        return Update(
            section,
            setOf(TransformResult.Rule.ADD_DERIVED_TERMS, TransformResult.Rule.CREATE_SECTION),
        )
    }

    /**
     * Strips the trailing page furniture from wherever it ended up.
     *
     * Categories written at the bottom of an entry belong, structurally, to whichever section happens to be
     * last — so they are taken from the deepest last subsection, not from the section being appended to.
     */
    private fun takeTrailingFurniture(section: Section): Pair<Section, List<Node>> {
        if (section.subsections.isEmpty()) {
            val (content, furniture) = EntryLayout.splitTrailingFurniture(section.nodes)
            return section.copy(nodes = content) to furniture
        }

        val (last, furniture) = takeTrailingFurniture(section.subsections.last())
        return section.copy(subsections = section.subsections.dropLast(1) + last) to furniture
    }

    /** Makes sure a section's content ends with a blank line, so a new heading is not glued on. */
    private fun ensureBlankLineBefore(section: Section): Section {
        if (section.subsections.isNotEmpty()) {
            val last = ensureBlankLineBefore(section.subsections.last())
            return section.copy(subsections = section.subsections.dropLast(1) + last)
        }

        val text = section.content.serialize()
        val missing =
            when {
                text.isEmpty() -> "\n\n"
                text.endsWith("\n\n") -> ""
                text.endsWith("\n") -> "\n"
                else -> "\n\n"
            }
        if (missing.isEmpty()) return section

        return section.withContent(Wikitext.parse(text + missing))
    }

    /**
     * Names the ambiguous sections, annotating repeats.
     *
     * `Proper noun x2` rather than `Proper noun, Proper noun`: two sections sharing a title, one per numbered
     * etymology, must not read as a single unambiguous one.
     */
    private fun describe(sections: List<Section>): String =
        sections
            .groupingBy { it.title.orEmpty() }
            .eachCount()
            .toSortedMap()
            .map { (name, count) -> if (count == 1) name else "$name x$count" }
            .joinToString(",")

    private data class Update(val section: Section, val rules: Set<TransformResult.Rule>)

    /**
     * The result of planning an edit.
     *
     * A reason travels with the refusal rather than in a field on this object: the transform is a singleton,
     * and two runs sharing it must not be able to overwrite each other's reason.
     */
    private sealed interface Outcome {
        data class Ok(val update: Update) : Outcome

        data class Refused(val reason: String) : Outcome
    }

    private fun skip(text: String, reason: String) =
        TransformResult(text, TransformResult.Status.SKIPPED, reason)

    private val LISTING_HEADINGS =
        setOf(
            EntryLayout.DERIVED_TERMS.lowercase(),
            EntryLayout.RELATED_TERMS.lowercase(),
        )
}
