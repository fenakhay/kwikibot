package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.internal.wire.Registry
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.throwOnError
import com.fenakhay.kwikibot.wikitext.MagicWords
import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.TitleRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How one wiki's wikitext parses and how it names pages, read from its site info once and kept.
 *
 * A page parses the same way on two wikis only when their extension tags, URL schemes and namespace names
 * agree, so a bot reading a wiki's pages reads them with that wiki's settings.
 */
internal class ParserSettings(private val transport: MediaWikiTransport) {

    private val mutex = Mutex()

    @Volatile private var settings: Pair<ParseOptions, TitleRules>? = null

    suspend fun options(): ParseOptions = settings().first

    suspend fun rules(): TitleRules = settings().second

    private suspend fun settings(): Pair<ParseOptions, TitleRules> =
        settings ?: mutex.withLock { settings ?: read(transport).also { settings = it } }

    companion object {
        /** Asks the wiki behind [transport], without keeping the answer. */
        suspend fun read(transport: MediaWikiTransport): Pair<ParseOptions, TitleRules> {
            val response =
                transport
                    .call(
                        ApiRequest.of("query", "meta" to "siteinfo", "siprop" to Registry.PARSER_INFO.joined)
                    )
                    .throwOnError()
            return decode(response["query"] as? JsonObject ?: JsonObject(emptyMap()))
        }

        /**
         * Reads the settings out of a `meta=siteinfo` answer, keeping MediaWiki's defaults for anything
         * missing.
         */
        fun decode(query: JsonObject): Pair<ParseOptions, TitleRules> {
            val namespaces = namespaceNames(query)
            val magicWords = (query["magicwords"] as? JsonArray).orEmpty().map { it.jsonObject }

            val defaults = ParseOptions.DEFAULT
            val options =
                ParseOptions(
                    extensionTags =
                        query.strings("extensiontags")?.mapTo(HashSet()) {
                            it.removePrefix("<").removeSuffix(">")
                        } ?: defaults.extensionTags,
                    parsedTags = defaults.parsedTags,
                    htmlTags = defaults.htmlTags,
                    protocols = query.strings("protocols") ?: defaults.protocols,
                    languageConversion =
                        (query["general"] as? JsonObject)?.get("langconversion")?.jsonPrimitive?.booleanOrNull
                            ?: defaults.languageConversion,
                    fileNamespaces =
                        namespaces.filterValues { it == FILE }.keys.ifEmpty { defaults.fileNamespaces },
                )

            val functionHooks = query.strings("functionhooks")?.toSet()
            val variableIds = query.strings("variables")?.toSet()
            fun words(of: Set<String>): List<JsonObject> = magicWords.filter { it.text("name") in of }
            fun prefixes(of: Set<String>): Set<String> =
                words(of)
                    .flatMap { it.strings("aliases").orEmpty() }
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

            return options to rules
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
                    word.text("name") in TitleRules.CORE_FUNCTION_IDS ||
                        word.strings("aliases").orEmpty().any { it.endsWith(COLON) }
                (if (noHash) "" else "#") + alias.removeSuffix(COLON).removeSuffix(WIDE_COLON)
            }

        /** Each alias of [words], passed through [name], sorted by whether its magic word keeps case. */
        private fun magicWordsOf(words: List<JsonObject>, name: (JsonObject, String) -> String): MagicWords {
            val exact = HashSet<String>()
            val folded = HashSet<String>()
            for (word in words) {
                val target = if (word.isCaseSensitive()) exact else folded
                word.strings("aliases").orEmpty().mapTo(target) { name(word, it) }
            }
            return MagicWords(exact, folded)
        }

        /**
         * Whether a `magicwords` entry is case-sensitive, a flag that is `true` or, in the old format, empty.
         */
        private fun JsonObject.isCaseSensitive(): Boolean {
            val flag = this["case-sensitive"] as? JsonPrimitive ?: return false
            return flag.booleanOrNull ?: true
        }

        /** Every name and alias of every namespace, with the namespace it names. */
        private fun namespaceNames(query: JsonObject): Map<String, Int> = buildMap {
            for (namespace in namespaceEntries(query)) {
                val id = namespace["id"]?.jsonPrimitive?.intOrNull ?: continue
                namespace.text("name")?.takeIf { it.isNotEmpty() }?.let { put(it, id) }
                namespace.text("canonical")?.takeIf { it.isNotEmpty() }?.let { put(it, id) }
            }
            for (alias in (query["namespacealiases"] as? JsonArray).orEmpty().map { it.jsonObject }) {
                val id = alias["id"]?.jsonPrimitive?.intOrNull ?: continue
                alias.text("alias")?.let { put(it, id) }
            }
        }

        private fun canonicalNames(query: JsonObject): Map<Int, String> = buildMap {
            for (namespace in namespaceEntries(query)) {
                val id = namespace["id"]?.jsonPrimitive?.intOrNull
                val name = namespace.text("canonical") ?: namespace.text("name")
                if (id != null && !name.isNullOrEmpty()) put(id, name)
            }
        }

        private fun caseSensitive(query: JsonObject): Set<Int> =
            namespaceEntries(query)
                .filter { it.text("case") == "case-sensitive" }
                .mapNotNullTo(HashSet()) { it["id"]?.jsonPrimitive?.intOrNull }

        private fun namespaceEntries(query: JsonObject): List<JsonObject> =
            (query["namespaces"] as? JsonObject)?.values?.mapNotNull { it as? JsonObject }.orEmpty()

        private fun JsonObject.strings(key: String): List<String>? =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

        private const val FILE = 6

        private const val COLON = ":"

        /** The full-width colon, which MediaWiki also drops from the end of a function's name. */
        private val WIDE_COLON = Char(0xFF1A).toString()
    }
}
