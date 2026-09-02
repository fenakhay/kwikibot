package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.client.model.MatrixWiki
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.transport.ApiEndpoint

/**
 * A group of wikis that share a domain pattern.
 *
 * A family is the rule for turning a language code into a host, not a table of hosts. The common Wikimedia
 * projects therefore need no data, and anything else is one [custom] call.
 */
public sealed interface Family {

    /** The family name, as it appears in configuration. */
    public val name: String

    /** The endpoint for one wiki of this family. */
    public fun endpoint(code: LangCode): ApiEndpoint

    /** A Wikimedia project served at `<code>.<domain>`, such as `en.wiktionary.org`. */
    public data class Wikimedia(
        /** The family's own name, `wiktionary`. */
        override val name: String,
        /** The domain its wikis sit under, `wiktionary.org`. */
        val domain: String,
    ) : Family {
        override fun endpoint(code: LangCode): ApiEndpoint =
            ApiEndpoint(server = "${code.code}.$domain", scriptPath = "/w")
    }

    /** A Wikimedia project that has one wiki rather than one per language. */
    public data class SingleSite(
        /** The family's own name, `commons`. */
        override val name: String,
        /** Its single host. */
        val server: String,
        /** Where MediaWiki is installed under that host. */
        val scriptPath: String = "/w",
    ) : Family {
        override fun endpoint(code: LangCode): ApiEndpoint =
            ApiEndpoint(server = server, scriptPath = scriptPath)
    }

    /**
     * The Wikimedia families, queried by name.
     *
     * A table rather than a lookup because these are fixed: the set of projects changes on the order of once
     * a decade, while the wikis inside them change weekly, which is what `SiteMatrix` is for.
     */
    public companion object {
        /** Encyclopedias. */
        public val WIKIPEDIA: Family = Wikimedia("wikipedia", "wikipedia.org")
        /** Dictionaries, where a title is a word rather than a subject. */
        public val WIKTIONARY: Family = Wikimedia("wiktionary", "wiktionary.org")
        /** Transcribed source texts. */
        public val WIKISOURCE: Family = Wikimedia("wikisource", "wikisource.org")
        /** Collections of quotations. */
        public val WIKIQUOTE: Family = Wikimedia("wikiquote", "wikiquote.org")
        /** Textbooks and manuals. */
        public val WIKIBOOKS: Family = Wikimedia("wikibooks", "wikibooks.org")
        /** News reporting. */
        public val WIKINEWS: Family = Wikimedia("wikinews", "wikinews.org")
        /** Learning materials. */
        public val WIKIVERSITY: Family = Wikimedia("wikiversity", "wikiversity.org")
        /** Travel guides. */
        public val WIKIVOYAGE: Family = Wikimedia("wikivoyage", "wikivoyage.org")

        /** The shared media repository every other Wikimedia wiki reads files from. */
        public val COMMONS: Family = SingleSite("commons", "commons.wikimedia.org")
        /** The structured-data repository, whose pages are entities rather than prose. */
        public val WIKIDATA: Family = SingleSite("wikidata", "www.wikidata.org")
        /** Coordination across the projects, including bot policy. */
        public val META: Family = SingleSite("meta", "meta.wikimedia.org")
        /** MediaWiki's own documentation, including the API reference. */
        public val MEDIAWIKI: Family = SingleSite("mediawiki", "www.mediawiki.org")

        /** The write-capable test wiki, where live write tests belong. */
        public val TEST: Family = SingleSite("test", "test.wikipedia.org")

        private val BY_NAME: Map<String, Family> =
            listOf(
                    WIKIPEDIA,
                    WIKTIONARY,
                    WIKISOURCE,
                    WIKIQUOTE,
                    WIKIBOOKS,
                    WIKINEWS,
                    WIKIVERSITY,
                    WIKIVOYAGE,
                    COMMONS,
                    WIKIDATA,
                    META,
                    MEDIAWIKI,
                    TEST,
                )
                .associateBy { it.name }

        /** The family of this name, or `null` if it is not one of the known projects. */
        public fun named(name: String): Family? = BY_NAME[name.lowercase()]

        /** A third-party wiki, named by host and the path MediaWiki is installed under. */
        public fun custom(name: String, server: String, scriptPath: String = "/w"): Family =
            SingleSite(name, server, scriptPath)

        /**
         * The family of a wiki at an endpoint, whatever the endpoint is.
         *
         * For a wiki found by [ApiDetector]: the endpoint already says where the API lives, so the family is
         * only a name to hang it on.
         */
        public fun at(endpoint: ApiEndpoint, name: String = endpoint.server): Family =
            SingleSite(name, endpoint.server, endpoint.scriptPath)

        /**
         * The family of one wiki in the site matrix.
         *
         * A wiki created last month is in the matrix and is not in any list this library ships, which is why
         * the matrix is read rather than a shipped table.
         */
        public fun of(wiki: MatrixWiki): Family = at(wiki.endpoint, wiki.project ?: wiki.id.dbName)
    }
}
