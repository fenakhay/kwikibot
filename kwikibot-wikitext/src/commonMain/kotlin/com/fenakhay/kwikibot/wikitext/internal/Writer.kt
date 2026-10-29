package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Attribute
import com.fenakhay.kwikibot.wikitext.node.Comment
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.HtmlEntity
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Parameter
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.TextNode
import com.fenakhay.kwikibot.wikitext.node.WikiLink

/**
 * Writes a tree back as wikitext, into one buffer.
 *
 * The one place serialization lives. `serialize()` on every node and [Markup.ranges] both come here, so the
 * offsets a caller is given always match the text it is given.
 */
internal object Writer {

    /** Hears where each node, parameter, attribute and piece of markup was written. */
    fun interface Listener {
        fun written(item: Any, start: Int, end: Int)
    }

    fun write(node: Node): String = StringBuilder().also { append(it, node, null) }.toString()

    fun write(markup: Markup): String = StringBuilder().also { append(it, markup, null) }.toString()

    fun write(parameter: Parameter): String = StringBuilder().also { append(it, parameter, null) }.toString()

    fun write(attribute: Attribute): String = StringBuilder().also { append(it, attribute, null) }.toString()

    fun append(out: StringBuilder, markup: Markup, listener: Listener?) {
        val start = out.length
        for (node in markup.nodes) append(out, node, listener)
        listener?.written(markup, start, out.length)
    }

    @Suppress("CyclomaticComplexMethod") // One branch per node type.
    fun append(out: StringBuilder, node: Node, listener: Listener?) {
        val start = out.length
        when (node) {
            is TextNode -> out.append(node.text)
            is Comment -> {
                out.append("<!--").append(node.contents)
                if (node.closed) out.append("-->")
            }
            is Template -> {
                out.append("{{")
                append(out, node.name, listener)
                for (parameter in node.parameters) {
                    out.append('|')
                    append(out, parameter, listener)
                }
                out.append("}}")
            }
            is Argument -> {
                out.append("{{{")
                append(out, node.name, listener)
                node.default?.let {
                    out.append('|')
                    append(out, it, listener)
                }
                out.append("}}}")
            }
            is WikiLink -> {
                out.append("[[")
                append(out, node.target, listener)
                node.text?.let {
                    out.append('|')
                    append(out, it, listener)
                }
                out.append("]]")
            }
            is ExternalLink -> externalLink(out, node, listener)
            is Heading -> {
                repeat(node.level) { out.append('=') }
                append(out, node.title, listener)
                repeat(node.level) { out.append('=') }
            }
            is HtmlEntity -> {
                out.append('&')
                if (node.numeric) out.append('#')
                node.hexChar?.let { out.append(it) }
                out.append(node.value).append(';')
            }
            is Tag -> tag(out, node, listener)
        }
        listener?.written(node, start, out.length)
    }

    fun append(out: StringBuilder, parameter: Parameter, listener: Listener?) {
        val start = out.length
        if (parameter.showKey) {
            append(out, parameter.name, listener)
            out.append('=')
        }
        append(out, parameter.value, listener)
        listener?.written(parameter, start, out.length)
    }

    fun append(out: StringBuilder, attribute: Attribute, listener: Listener?) {
        val start = out.length
        out.append(attribute.padFirst)
        append(out, attribute.name, listener)
        val value = attribute.value
        if (value != null) {
            out.append(attribute.padBeforeEq).append('=').append(attribute.padAfterEq)
            attribute.quote?.let { out.append(it) }
            append(out, value, listener)
            attribute.quote?.let { out.append(it) }
        }
        listener?.written(attribute, start, out.length)
    }

    private fun externalLink(out: StringBuilder, link: ExternalLink, listener: Listener?) {
        if (!link.brackets) {
            append(out, link.url, listener)
            return
        }
        out.append('[')
        append(out, link.url, listener)
        link.title?.let {
            out.append(link.separator)
            append(out, it, listener)
        }
        out.append(']')
    }

    private fun tag(out: StringBuilder, tag: Tag, listener: Listener?) {
        val markup = tag.wikiMarkup
        if (markup != null) {
            out.append(markup)
            if (!tag.selfClosing) {
                tag.contents?.let { append(out, it, listener) }
                out.append(markup)
            }
            return
        }

        out.append('<').append(tag.name)
        for (attribute in tag.attributes) append(out, attribute, listener)
        out.append(tag.padding)

        if (tag.selfClosing) {
            // "<br>" and "<br/>" are both self-closing, and a bot must not turn one into the
            // other, so which was written is remembered rather than inferred from the name.
            out.append(if (tag.implicitClose) ">" else "/>")
            return
        }

        out.append('>')
        tag.contents?.let { append(out, it, listener) }
        out.append(tag.closingTag)
    }
}
