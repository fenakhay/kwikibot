package com.fenakhay.kwikibot.tools

/**
 * A set of wikis whose API is recorded together, and what to do when it changes.
 *
 * MediaWiki describes itself: `paraminfo` names every module, every parameter, the extension behind it and
 * what is on its way out. Kept in a file and diffed, it shows every change a wiki makes to its API.
 */
internal enum class Lane(val title: String, val wikis: Map<String, String>) {
    /**
     * The wikis bots run against, chosen for the extensions they carry rather than their size. Between them
     * they cover core, Wikibase, the file stack and ProofreadPage.
     *
     * test.wikipedia is left out: it gets each week's release a day or two before the wikis above, but it
     * also has extensions installed that they lack, so it would record surface none of them has.
     */
    PRODUCTION(
        "API surface: production",
        mapOf(
            "enwiki" to "en.wikipedia.org",
            "enwiktionary" to "en.wiktionary.org",
            "commonswiki" to "commons.wikimedia.org",
            "wikidatawiki" to "www.wikidata.org",
            "enwikisource" to "en.wikisource.org",
        ),
    )
}
