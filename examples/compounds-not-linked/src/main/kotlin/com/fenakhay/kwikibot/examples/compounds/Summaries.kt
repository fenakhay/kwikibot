package com.fenakhay.kwikibot.examples.compounds

/**
 * The edit summaries this bot writes.
 *
 * Every summary names the todo page, so an edit can be traced back to the request that produced it, and says
 * when the list was rewritten rather than merely extended — a reformat should never be a surprise to a reader
 * of their watchlist.
 */
public object Summaries {

    /** The page these tasks come from. */
    public const val TODO_PAGE: String = "Wiktionary:Todo/compounds not linked to from components"

    private const val TODO_LINK = "[[$TODO_PAGE]]"

    /** `Bot: add 3 derived terms ([[Wiktionary:Todo/…]])`, plus a note when the list was rewritten. */
    public fun forEdit(added: List<String>, rules: Set<TransformResult.Rule>): String {
        val noun = if (added.size == 1) "term" else "terms"
        val summary = "Bot: add ${added.size} derived $noun ($TODO_LINK)"

        return if (TransformResult.Rule.NORMALIZE_CONTAINER in rules) {
            "$summary; normalize list to {{col}}"
        } else {
            summary
        }
    }
}
