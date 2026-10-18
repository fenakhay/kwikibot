package com.fenakhay.kwikibot.bot.fix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Edit summaries and other text in the language of the wiki being edited.
 *
 * A bot editing fr.wiktionary should leave French summaries. Message bundles are keyed by language and
 * support `$1` placeholders and `{{PLURAL:}}`, which languages need where a count changes the word after it.
 *
 * ```
 * val messages = Messages.fromJson(mapOf("en" to enJson, "fr" to frJson))
 * messages["added-terms", "fr", 3]   // "3 termes ajoutés"
 * ```
 *
 * A key missing in the requested language falls back through [fallbacks] and then to English. A key missing
 * everywhere returns the key itself rather than throwing: a bot that has done the work should not lose the
 * edit because a translation is missing.
 */
public class Messages(
    private val bundles: Map<String, Map<String, String>>,
    private val fallbacks: Map<String, List<String>> = FALLBACKS,
) {

    /**
     * The message for [key] in [language], with `$1`, `$2`… replaced by [arguments].
     *
     * `{{PLURAL:$1|singular|plural}}` is expanded using the rule for the language, not the rule for English,
     * which is why this is not string interpolation.
     */
    public operator fun get(key: String, language: String, vararg arguments: Any?): String {
        val template = lookup(key, language) ?: return key
        return expand(template, language, arguments.toList())
    }

    /** Whether [key] exists in [language] or anything it falls back to. */
    public fun has(key: String, language: String): Boolean = lookup(key, language) != null

    /** The languages this instance holds messages for. */
    public val languages: Set<String>
        get() = bundles.keys

    /**
     * The chain of languages tried for [language], in order.
     *
     * Ending in English, which every bundle in practice has, because an English summary is better than a raw
     * message key in the page history.
     */
    public fun chain(language: String): List<String> =
        (listOf(language) + fallbacks[language].orEmpty() + DEFAULT).distinct()

    private fun lookup(key: String, language: String): String? =
        chain(language).firstNotNullOfOrNull { bundles[it]?.get(key) }

    private fun expand(template: String, language: String, arguments: List<Any?>): String {
        // Plural first: its branches may themselves contain placeholders.
        val pluralised = expandPlural(template, language, arguments)

        return arguments.foldIndexed(pluralised) { index, text, argument ->
            text.replace("$${index + 1}", argument.toString())
        }
    }

    /**
     * Expands every `{{PLURAL:$n|form|form|…}}` in a message.
     *
     * Written by hand rather than with a regex over the whole thing, because the forms can contain the braces
     * of another template and a regex would stop at the first `}}`.
     */
    private fun expandPlural(template: String, language: String, arguments: List<Any?>): String {
        var text = template

        while (true) {
            val start = text.indexOf(PLURAL_OPEN, ignoreCase = true).takeIf { it >= 0 } ?: return text
            val end = matchingClose(text, start) ?: return text

            val body = text.substring(start + PLURAL_OPEN.length, end)
            val parts = splitTopLevel(body)
            val countText = parts.first().trim()
            val forms = parts.drop(1)

            val count = resolveCount(countText, arguments)
            val chosen =
                if (count == null || forms.isEmpty()) {
                    forms.lastOrNull().orEmpty()
                } else {
                    pluralForm(count, forms, language)
                }

            text = text.substring(0, start) + chosen + text.substring(end + CLOSE.length)
        }
    }

    /**
     * Splits on the `|` that separate plural forms, ignoring the ones inside something else.
     *
     * A form can hold a template or a link, and both use `|` for their own parameters. Splitting on every `|`
     * cuts `{{many|$1}}` in half and yields a form that is not one.
     */
    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var index = 0

        while (index < body.length) {
            when {
                body.startsWith("{{", index) || body.startsWith("[[", index) -> {
                    depth++
                    current.append(body, index, index + 2)
                    index += 2
                }

                body.startsWith("}}", index) || body.startsWith("]]", index) -> {
                    depth--
                    current.append(body, index, index + 2)
                    index += 2
                }

                body[index] == '|' && depth == 0 -> {
                    parts += current.toString()
                    current.clear()
                    index++
                }

                else -> {
                    current.append(body[index])
                    index++
                }
            }
        }

        parts += current.toString()
        return parts
    }

    /** The number a `{{PLURAL:` refers to: a placeholder to substitute, or a literal. */
    private fun resolveCount(countText: String, arguments: List<Any?>): Long? {
        if (!countText.startsWith("$")) return countText.toLongOrNull()

        val index = countText.drop(1).toIntOrNull()?.minus(1) ?: return null
        return arguments.getOrNull(index)?.let { it.toString().toLongOrNull() }
    }

    /**
     * The index of the `}}` that closes the `{{` at [start].
     *
     * Counted rather than searched, so a plural form containing another template still ends in the right
     * place.
     */
    private fun matchingClose(text: String, start: Int): Int? {
        var depth = 0
        var index = start

        while (index < text.length - 1) {
            when {
                text.startsWith("{{", index) -> {
                    depth++
                    index += 2
                }

                text.startsWith(CLOSE, index) -> {
                    depth--
                    if (depth == 0) return index
                    index += 2
                }

                else -> index++
            }
        }
        return null
    }

    /**
     * Which of [forms] a count takes in [language], by its CLDR plural rule.
     *
     * A language CLDR has no rule for takes the rule of the first language in its [chain] that has one, as
     * MediaWiki does with its fallbacks: Egyptian Arabic counts as Arabic. The chain ends in English, which
     * has one.
     */
    private fun pluralForm(count: Long, forms: List<String>, language: String): String {
        val index = chain(language).asSequence().mapNotNull { PluralForms.index(count, it) }.first()

        // A bundle that supplies fewer forms than the language has gets its last one, which is
        // the general case in every language that has one.
        return forms.getOrNull(index) ?: forms.last()
    }

    /** The message bundles that ship with the library. */
    public companion object {
        /** The language every chain ends at. */
        public const val DEFAULT: String = "en"

        private const val PLURAL_OPEN = "{{PLURAL:"
        private const val CLOSE = "}}"

        /**
         * Which language to try when one has no message.
         *
         * Deliberately smaller than MediaWiki's full fallback graph, which exists for interface messages seen
         * by readers. An edit summary needs only the cases where speakers of one language routinely read
         * another.
         */
        public val FALLBACKS: Map<String, List<String>> =
            mapOf(
                "nb" to listOf("no"),
                "nn" to listOf("nb", "no"),
                "no" to listOf("nb"),
                "be-tarask" to listOf("be"),
                "zh-hans" to listOf("zh"),
                "zh-hant" to listOf("zh"),
                "zh-tw" to listOf("zh-hant", "zh"),
                "zh-hk" to listOf("zh-hant", "zh"),
                "pt-br" to listOf("pt"),
                "sr-ec" to listOf("sr"),
                "sr-el" to listOf("sr"),
                "ca" to listOf("es"),
                "gl" to listOf("pt", "es"),
                "arz" to listOf("ar"),
                "ary" to listOf("ar"),
            )

        /**
         * Messages from JSON bundles, one per language.
         *
         * The format is MediaWiki's own: a flat object of key to message, with `@metadata` ignored, so a
         * bundle can be taken from translatewiki unchanged.
         */
        public fun fromJson(bundles: Map<String, String>): Messages =
            Messages(
                bundles.mapValues { (_, text) ->
                    Json.parseToJsonElement(text)
                        .jsonObject
                        .filterKeys { !it.startsWith("@") }
                        .mapValues { (_, value) -> value.jsonPrimitive.content }
                }
            )

        /** Messages from bundles already parsed into maps. */
        public fun of(bundles: Map<String, Map<String, String>>): Messages = Messages(bundles)
    }
}
