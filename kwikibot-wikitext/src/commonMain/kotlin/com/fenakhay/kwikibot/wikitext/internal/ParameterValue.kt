package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.Wikitext
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.TextNode

/**
 * Wikitext made safe to put into a template parameter.
 *
 * MediaWiki splits a template's parameters on every `|` and at the first `=` that no template, argument,
 * link, language conversion, heading, comment or extension tag encloses. An external link, an HTML tag or
 * plain text encloses nothing, so a value such as `[https://example.org/?a=b|c]` written into a parameter is,
 * to the wiki, two parameters and a name. A bot editing one parameter would quietly rewrite the template's
 * structure.
 *
 * So a `|` that would split is written `{{!}}`, which the wiki turns back into a `|` after it has split the
 * parameters, and [splitsAtEquals] says whether an `=` would make a positional value into a named one, which
 * the caller avoids by writing the position as a name: `2=a=b`.
 *
 * Braces and brackets that would open or close a construct of their own cannot be made safe without changing
 * what the value says, so they are refused.
 */
internal class ParameterValue private constructor(val text: String, val splitsAtEquals: Boolean) {

    companion object {
        /**
         * The tags whose contents can hold anything: MediaWiki's preprocessor splits no parameter inside an
         * extension tag.
         */
        private val OPAQUE = ParseOptions.DEFAULT.extensionTags

        /** Constructs that would open or close something in the template the value goes into. */
        private val UNBALANCED = listOf("{{", "}}", "[[")

        /**
         * [value] made safe for a parameter.
         *
         * @throws IllegalArgumentException if the value opens or closes a template or a link it does not also
         *   close or open, which would change the template it is put into.
         */
        fun of(value: String): ParameterValue {
            val escaper = Escaper(value)
            val escaped = escaper.rebuild(Wikitext.parse(value)).serialize()
            return ParameterValue(escaped, escaper.equals)
        }
    }

    /** Escapes what would split a parameter, noting whether an `=` would. */
    private class Escaper(private val value: String) : Rebuild() {
        var equals = false
            private set

        /**
         * Templates, arguments, links, comments, headings and extension tags each enclose what is inside
         * them.
         */
        override fun enters(node: Node): Boolean = node is ExternalLink || (node is Tag && opens(node))

        override fun rebuilt(original: Node, node: Node): Node {
            if (node is TextNode) return TextNode(escape(node.text))
            if (node is Tag && opens(node) && node.attributes.any { it.value != null }) equals = true
            return node
        }

        private fun opens(tag: Tag): Boolean = tag.name.lowercase() !in OPAQUE

        private fun escape(text: String): String {
            require(UNBALANCED.none { it in text }) {
                "the value '$value' opens or closes a template or link it does not also close or open"
            }
            if ('=' in text) equals = true
            return text.replace("|", "{{!}}")
        }
    }
}
