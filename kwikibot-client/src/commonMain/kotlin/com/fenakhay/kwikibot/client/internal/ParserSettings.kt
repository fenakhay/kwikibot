package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.internal.wire.Registry
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.decode.array
import com.fenakhay.kwikibot.protocol.decode.boolean
import com.fenakhay.kwikibot.protocol.decode.flag
import com.fenakhay.kwikibot.protocol.decode.int
import com.fenakhay.kwikibot.protocol.decode.obj
import com.fenakhay.kwikibot.protocol.decode.objects
import com.fenakhay.kwikibot.protocol.decode.string
import com.fenakhay.kwikibot.protocol.decode.strings
import com.fenakhay.kwikibot.protocol.throwOnError
import com.fenakhay.kwikibot.wikitext.MagicWords
import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.TitleRules
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * How one wiki's wikitext parses and how it names pages, read from its site info once and kept.
 *
 * A page parses the same way on two wikis only when their extension tags, URL schemes and namespace names
 * agree, so a bot reading a wiki's pages reads them with that wiki's settings.
 */
internal class ParserSettings(private val transport: MediaWikiTransport) {

    private val mutex = Mutex()

    @Volatile private var settings: Settings? = null

    suspend fun options(): ParseOptions = settings().options

    suspend fun rules(): TitleRules = settings().rules

    suspend fun redirects(): MagicWords = settings().redirects

    private suspend fun settings(): Settings =
        settings ?: mutex.withLock { settings ?: read(transport).also { settings = it } }

    /**
     * What the site info says.
     *
     * @param options how the wiki's wikitext parses.
     * @param rules how it names pages.
     * @param redirects the spellings of the magic word a redirect starts with, `#REDIRECT` and the wiki's
     *   own.
     */
    data class Settings(val options: ParseOptions, val rules: TitleRules, val redirects: MagicWords)

    companion object {
        /** Asks the wiki behind [transport], without keeping the answer. */
        suspend fun read(transport: MediaWikiTransport): Settings {
            val response =
                transport
                    .call(
                        ApiRequest.of("query", "meta" to "siteinfo", "siprop" to Registry.PARSER_INFO.joined)
                    )
                    .throwOnError()
            return decode(response.obj("query") ?: JsonObject(emptyMap()))
        }

        /**
         * Reads the settings out of a `meta=siteinfo` answer, keeping MediaWiki's defaults for anything
         * missing.
         */
        fun decode(query: JsonObject): Settings {
            val namespaces = namespaceNames(query)
            val magicWords = query.array("magicwords").objects()

            val defaults = ParseOptions.DEFAULT
            val options =
                ParseOptions(
                    extensionTags =
                        query.array("extensiontags")?.strings()?.mapTo(HashSet()) {
                            it.removePrefix("<").removeSuffix(">")
                        } ?: defaults.extensionTags,
                    parsedTags = defaults.parsedTags,
                    htmlTags = defaults.htmlTags,
                    protocols = query.array("protocols")?.strings() ?: defaults.protocols,
                    languageConversion =
                        query.obj("general")?.boolean("langconversion") ?: defaults.languageConversion,
                    fileNamespaces =
                        namespaces.filterValues { it == FILE }.keys.ifEmpty { defaults.fileNamespaces },
                )

            val functionHooks = query.array("functionhooks")?.strings()?.toSet()
            val variableIds = query.array("variables")?.strings()?.toSet()
            fun words(of: Set<String>): List<JsonObject> = magicWords.filter { it.string("name") in of }
            fun prefixes(of: Set<String>): Set<String> =
                words(of)
                    .flatMap { it.array("aliases").strings() }
                    .mapTo(HashSet()) { it.removeSuffix(":") }
                    .ifEmpty { of }

            val rules =
                TitleRules(
                    namespaces = namespaces.ifEmpty { TitleRules.CANONICAL_NAMESPACES },
                    canonicalNames = canonicalNames(query).ifEmpty { TitleRules.CANONICAL_NAMES },
                    caseSensitive = caseSensitive(query),
                    functions = functionHooks?.let { functions(words(it)) } ?: TitleRules.CORE_FUNCTIONS,
                    variables =
                        variableIds?.let { ids -> magicWordsOf(words(ids)) { _, alias -> alias } }
                            ?: TitleRules.CORE_VARIABLES,
                    substitutions = prefixes(TitleRules.SUBSTITUTIONS),
                    modifiers = prefixes(TitleRules.MODIFIERS),
                )

            val redirects =
                words(setOf(REDIRECT))
                    .takeIf { it.isNotEmpty() }
                    ?.let { magicWordsOf(it) { _, alias -> alias } } ?: DEFAULT_REDIRECTS

            return Settings(options, rules, redirects)
        }

        /**
         * The functions among [words], named as MediaWiki registers them: without a trailing colon, and with
         * a `#` unless the function is written without one.
         *
         * The site info does not say which functions take no `#`. Core's are known by id, and an extension's
         * are taken to be those with an alias ending in a colon, such as `LC:`, which is how core writes most
         * of its own.
         */
        private fun functions(words: List<JsonObject>): MagicWords =
            magicWordsOf(words) { word, alias ->
                val noHash =
                    word.string("name") in TitleRules.CORE_FUNCTION_IDS ||
                        word.array("aliases").strings().any { it.endsWith(COLON) }
                (if (noHash) "" else "#") + alias.removeSuffix(COLON).removeSuffix(WIDE_COLON)
            }

        /** Each alias of [words], passed through [name], sorted by whether its magic word keeps case. */
        private fun magicWordsOf(words: List<JsonObject>, name: (JsonObject, String) -> String): MagicWords {
            val exact = HashSet<String>()
            val folded = HashSet<String>()
            for (word in words) {
                val target = if (word.flag("case-sensitive")) exact else folded
                word.array("aliases").strings().mapTo(target) { name(word, it) }
            }
            return MagicWords(exact, folded)
        }

        /** Every name and alias of every namespace, with the namespace it names. */
        private fun namespaceNames(query: JsonObject): Map<String, Int> = buildMap {
            for (namespace in namespaceEntries(query)) {
                val id = namespace.int("id") ?: continue
                namespace.string("name")?.takeIf { it.isNotEmpty() }?.let { put(it, id) }
                namespace.string("canonical")?.takeIf { it.isNotEmpty() }?.let { put(it, id) }
            }
            for (alias in query.array("namespacealiases").objects()) {
                val id = alias.int("id") ?: continue
                alias.string("alias")?.let { put(it, id) }
            }
        }

        private fun canonicalNames(query: JsonObject): Map<Int, String> = buildMap {
            for (namespace in namespaceEntries(query)) {
                val id = namespace.int("id")
                val name = namespace.string("canonical") ?: namespace.string("name")
                if (id != null && !name.isNullOrEmpty()) put(id, name)
            }
        }

        private fun caseSensitive(query: JsonObject): Set<Int> =
            namespaceEntries(query)
                .filter { it.string("case") == "case-sensitive" }
                .mapNotNullTo(HashSet()) { it.int("id") }

        private fun namespaceEntries(query: JsonObject): List<JsonObject> =
            query.obj("namespaces")?.values.orEmpty().filterIsInstance<JsonObject>()

        private const val FILE = 6

        /** The id of the magic word a redirect starts with. */
        private const val REDIRECT = "redirect"

        /** How a redirect starts on a wiki that has not said: MediaWiki's own spelling, in any case. */
        val DEFAULT_REDIRECTS = MagicWords(caseInsensitive = setOf("#REDIRECT"))

        private const val COLON = ":"

        /** The full-width colon, which MediaWiki also drops from the end of a function's name. */
        private val WIDE_COLON = Char(0xFF1A).toString()
    }
}
