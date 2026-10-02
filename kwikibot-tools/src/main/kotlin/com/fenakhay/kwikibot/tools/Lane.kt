package com.fenakhay.kwikibot.tools

/**
 * A set of wikis whose API is recorded together, and what to do when it changes.
 *
 * MediaWiki describes itself: `paraminfo` names every module, every parameter, the extension behind it and
 * what is on its way out. Kept in a file and diffed, it shows every change a wiki makes to its API.
 */
internal enum class Lane(val title: String, val wikis: Map<String, String>, val failsOnDrift: Boolean) {
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
        failsOnDrift = true,
    ),

    /**
     * The beta cluster, which runs MediaWiki's master branch and so shows a deprecation about a week before
     * production does. Reported, never failed on, since beta can be broken.
     *
     * Its old `beta.wmflabs.org` names still answer, but with a redirect this tool would have to follow.
     */
    BETA(
        "API surface: beta cluster (early warning)",
        mapOf(
            "enwiki" to "en.wikipedia.beta.wmcloud.org",
            "commonswiki" to "commons.wikimedia.beta.wmcloud.org",
            "wikidatawiki" to "www.wikidata.beta.wmcloud.org",
        ),
        failsOnDrift = false,
    ),
}
