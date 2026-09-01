package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.wikitext.TextScope
import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.replaceText

/** One search and replace within a fix. */
public data class Replacement(
    /** What to look for. */
    val pattern: Regex,
    /** What to put in its place, with group references as Kotlin spells them. */
    val replacement: String,
)

/**
 * A named bundle of replacements.
 *
 * @param name how the fix is named on the command line.
 * @param description what it does, shown when the fixes are listed.
 * @param replacements the substitutions it makes, applied in order.
 * @param scope which text the replacements may touch. The default is prose, so a fix cannot
 *   rewrite a template parameter or the target of a link by accident — the mistake that turns a
 *   typo fix into a page move.
 */
public data class Fix(
    /** How the fix is named on the command line. */
    val name: String,
    /** What it does, shown when the fixes are listed. */
    val description: String,
    /** The substitutions it makes, applied in order. */
    val replacements: List<Replacement>,
    val scope: TextScope = TextScope.PROSE,
) {

    /** The page with this fix applied. */
    public fun apply(code: Markup): Markup =
        replacements.fold(code) { current, (pattern, replacement) ->
            current.replaceText(pattern, replacement, scope)
        }

    /** The wikitext with this fix applied. */
    public fun apply(wikitext: String): String = apply(Wikitext.parse(wikitext)).serialize()

    /** Whether this fix would change the page. */
    public fun wouldChange(wikitext: String): Boolean = apply(wikitext) != wikitext
}

/**
 * The fixes a bot can be pointed at by name.
 *
 * Only fixes that hold for wikitext generally are registered here. Per-language typo lists are
 * left to the bot that maintains them, since whether a string is a typo depends on the wiki's
 * language; [register] adds them.
 */
public object Fixes {

    private val registry = mutableMapOf<String, Fix>()

    /**
     * Three or more spaces between words, reduced to one.
     *
     * Prose only: runs of spaces lay out tables and indent lists.
     */
    public val EXTRA_SPACES: Fix = Fix(
        name = "extra-spaces",
        description = "Collapse runs of spaces inside a line",
        replacements = listOf(Replacement(Regex("""(?<=\S) {2,}(?=\S)"""), " ")),
    )

    /**
     * An ISO date range written with a hyphen, given an en dash.
     *
     * `1990-1995` between digits only. A hyphen elsewhere is a hyphen.
     */
    public val DATE_RANGES: Fix = Fix(
        name = "date-ranges",
        description = "Use an en dash between years in a range",
        replacements = listOf(Replacement(Regex("""(?<=\b\d{4})-(?=\d{4}\b)"""), "–")),
    )

    /**
     * Three dots given the ellipsis character.
     *
     * Four or more are left alone: they usually mark an ellipsis of omission with its own
     * convention.
     */
    public val ELLIPSIS: Fix = Fix(
        name = "ellipsis",
        description = "Use the ellipsis character for exactly three dots",
        replacements = listOf(Replacement(Regex("""(?<!\.)\.{3}(?!\.)"""), "…")),
    )

    /** Every fix this library ships, plus any that were registered. */
    public val all: Map<String, Fix> get() = registry.toMap()

    /** The fix of this name, or `null`. */
    public operator fun get(name: String): Fix? = registry[name]

    /** Adds a fix, replacing one of the same name. */
    public fun register(fix: Fix) {
        registry[fix.name] = fix
    }

    /**
     * The fixes named, in the order named.
     *
     * @throws IllegalArgumentException naming the ones that exist, since a mistyped fix that
     *   silently does nothing looks exactly like a fix that found nothing to do.
     */
    public fun named(names: List<String>): List<Fix> = names.map { name ->
        requireNotNull(registry[name]) {
            "unknown fix '$name'; available: ${registry.keys.sorted().joinToString(", ")}"
        }
    }

    init {
        listOf(EXTRA_SPACES, DATE_RANGES, ELLIPSIS).forEach { register(it) }
    }
}
