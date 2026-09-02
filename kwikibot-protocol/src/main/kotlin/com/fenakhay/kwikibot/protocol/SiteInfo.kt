package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceInfo
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.TitleCase
import com.fenakhay.kwikibot.model.user.TempAccountConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a wiki says about itself.
 *
 * Everything a title needs to be parsed correctly lives here — the namespaces, their aliases, their casing
 * rules, and the interwiki prefixes — which is why fetching it is the first thing a session does.
 */
public data class SiteInfo(
    /** The wiki's database name, which is its identity across the fleet. */
    val id: WikiId,
    /** The name the wiki calls itself, `Wiktionary`. */
    val siteName: String,
    /** Its content language, which is not always the language of its titles. */
    val language: LangCode,
    /** Its host, without a scheme. */
    val server: String,
    /** The path pattern articles are served under, carrying a `$1` for the title. */
    val articlePath: String,
    /** The title of its main page. */
    val mainPage: String,
    /** The MediaWiki version string, `MediaWiki 1.47.0-wmf.17`. */
    val generator: String,
    /** Its namespaces, which a title cannot be parsed without. */
    val namespaces: NamespaceMap,
    /** Its interwiki prefixes, which decide whether a title belongs to another wiki. */
    val interwiki: InterwikiMap,
    /**
     * The zone the wiki writes local times in: `Europe/Berlin`, `UTC`.
     *
     * Not a formality. A signature on de.wikipedia reads `21:43, 31. Aug. 2026 (CEST)`, and the time in it is
     * Berlin time; reading it as UTC puts the reply two hours in the past, which is enough to archive a
     * thread that is still active.
     */
    val timezone: String = "UTC",
    /** Minutes that zone is ahead of UTC at the moment the site info was read. */
    val timeOffsetMinutes: Int = 0,
    /** Whether this wiki auto-creates temporary accounts, and the shape of their names. */
    val tempAccounts: TempAccountConfig = TempAccountConfig.DISABLED,
    /**
     * The extensions installed, by name.
     *
     * What a wiki can do is not what MediaWiki can do: Wikibase, ProofreadPage, GeoData and the rest are each
     * an extension, and a bot that needs one should say so before it starts rather than discover it in an
     * error on the first page.
     */
    val extensions: List<String> = emptyList(),
) {
    /** The MediaWiki version, as reported in `generator` (`1.47.0-wmf.17`). */
    val version: String
        get() = generator.removePrefix("MediaWiki ").trim()

    /** Whether [extension] is installed, compared case-insensitively as MediaWiki names them. */
    public fun hasExtension(extension: String): Boolean = extensions.any {
        it.equals(extension, ignoreCase = true)
    }

    /** What to ask a wiki for, and how to read the answer. */
    public companion object {

        /** The `siprop` values needed to build a complete [SiteInfo]. */
        public const val PROPERTIES: String =
            "general|namespaces|namespacealiases|interwikimap|extensions|autocreatetempuser"

        /**
         * Decodes a `meta=siteinfo` response.
         *
         * Tolerates a partial response: a query that asked for fewer properties yields empty namespace and
         * interwiki maps rather than failing, since some callers only want the general block.
         */
        public fun decode(response: JsonObject): SiteInfo {
            val query =
                response["query"]?.jsonObject
                    ?: throw WikiError.Api(
                        "nositeinfo",
                        "no query block in the siteinfo response",
                        "query+siteinfo",
                    )
            val general = query["general"]?.jsonObject

            return SiteInfo(
                id = WikiId(general.string("wikiid").ifEmpty { general.string("sitename") }),
                siteName = general.string("sitename"),
                language = LangCode(general.string("lang").ifEmpty { "en" }),
                // The server is protocol-relative in the API ("//en.wikipedia.org").
                server =
                    general.string("server").removePrefix("https:").removePrefix("http:").removePrefix("//"),
                articlePath = general.string("articlepath"),
                mainPage = general.string("mainpage"),
                generator = general.string("generator"),
                namespaces = decodeNamespaces(query),
                interwiki = decodeInterwiki(query, general.string("server")),
                timezone = general.string("timezone").ifEmpty { "UTC" },
                timeOffsetMinutes = general?.get("timeoffset")?.jsonPrimitive?.intOrNull ?: 0,
                extensions =
                    query["extensions"]
                        ?.jsonArray
                        ?.map { it.jsonObject.string("name") }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty(),
                tempAccounts = decodeTempAccounts(query),
            )
        }

        private fun decodeNamespaces(query: JsonObject): NamespaceMap {
            val entries = query["namespaces"]?.jsonObject ?: return NamespaceMap(emptyList())

            val aliases: Map<Int, List<String>> =
                query["namespacealiases"]
                    ?.jsonArray
                    ?.map { it.jsonObject }
                    ?.groupBy({ it["id"]!!.jsonPrimitive.int }, { it.string("alias") })
                    .orEmpty()

            return NamespaceMap(
                entries.values.map { element ->
                    val namespace = element.jsonObject
                    val id = namespace["id"]!!.jsonPrimitive.int
                    NamespaceInfo(
                        id = Namespace(id),
                        canonicalName = namespace.string("canonical"),
                        localName = namespace.string("name"),
                        aliases = aliases[id].orEmpty(),
                        case = namespace.titleCase(),
                        subpages = namespace["subpages"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                }
            )
        }

        /**
         * Reads the interwiki map, separating out the prefixes that point back at this wiki.
         *
         * MediaWiki resolves a self-pointing prefix locally — `wikt:volcano` on en.wiktionary is the page
         * `volcano` — so the parser has to know which prefixes those are.
         */
        private fun decodeInterwiki(query: JsonObject, server: String): InterwikiMap {
            val rows = query["interwikimap"]?.jsonArray?.map { it.jsonObject } ?: return InterwikiMap.EMPTY

            val host = server.removePrefix("https:").removePrefix("http:").removePrefix("//")
            val prefixes = rows.map { it.string("prefix") }
            val self =
                rows.filter { host.isNotEmpty() && host in it.string("url") }.map { it.string("prefix") }

            return InterwikiMap(prefixes, self)
        }

        /**
         * Reads `autocreatetempuser`, absent on a wiki too old to report it.
         *
         * An absent block means the feature does not exist there, which is the same outcome as it being
         * switched off.
         */
        private fun decodeTempAccounts(query: JsonObject): TempAccountConfig {
            val block = query["autocreatetempuser"]?.jsonObject ?: return TempAccountConfig.DISABLED

            return TempAccountConfig(
                enabled = block["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                matchPatterns = block["matchPatterns"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        }

        private fun JsonObject?.string(key: String): String = this?.get(key)?.jsonPrimitive?.content.orEmpty()

        private fun JsonObject.titleCase(): TitleCase =
            if (string("case") == "case-sensitive") TitleCase.CASE_SENSITIVE else TitleCase.FIRST_LETTER
    }
}
