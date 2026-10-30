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
 *
 * A tree is written by recursion for its first [LEVELS_ON_STACK] levels, which is as deep as real pages go
 * and the fastest way to write them, and with a [Walk], a stack of its own, below that, so that how deep a
 * tree nests is a matter of memory rather than of the thread's stack.
 */
internal object Writer {

    /** Hears where each node, parameter, attribute and piece of markup was written. */
    fun interface Listener {
        fun written(item: Any, start: Int, end: Int)
    }

    fun write(node: Node): String =
        if (node is TextNode) node.text else StringBuilder().also { append(it, node, null) }.toString()

    fun write(markup: Markup): String =
        (markup.nodes.singleOrNull() as? TextNode)?.text
            ?: StringBuilder().also { append(it, markup, null) }.toString()

    fun write(parameter: Parameter): String = StringBuilder().also { append(it, parameter, null) }.toString()

    fun write(attribute: Attribute): String = StringBuilder().also { append(it, attribute, null) }.toString()

    fun append(out: StringBuilder, markup: Markup, listener: Listener?) {
        markup(out, markup, listener, LEVELS_ON_STACK)
    }

    /** Writes [markup] by recursion for its first [onStack] levels of nodes only, and with a [Walk] below. */
    fun append(out: StringBuilder, markup: Markup, onStack: Int, listener: Listener?) {
        markup(out, markup, listener, onStack)
    }

    fun append(out: StringBuilder, node: Node, listener: Listener?) {
        node(out, node, listener, LEVELS_ON_STACK)
    }

    fun append(out: StringBuilder, parameter: Parameter, listener: Listener?) {
        parameter(out, parameter, listener, LEVELS_ON_STACK)
    }

    fun append(out: StringBuilder, attribute: Attribute, listener: Listener?) {
        attribute(out, attribute, listener, LEVELS_ON_STACK)
    }

    private fun markup(out: StringBuilder, markup: Markup, listener: Listener?, onStack: Int) {
        val start = out.length
        val nodes = markup.nodes
        for (index in nodes.indices) node(out, nodes[index], listener, onStack)
        listener?.written(markup, start, out.length)
    }

    /** Writes [node], by recursion while [onStack] levels are left and with a [Walk] once none is. */
    private fun node(out: StringBuilder, node: Node, listener: Listener?, onStack: Int) {
        if (onStack == 0 && !node.isLeaf) {
            Walk(out, listener).run(node)
            return
        }
        val start = out.length
        val inner = onStack - 1
        when (node) {
            is TextNode -> out.append(node.text)
            is Template -> {
                out.append("{{")
                markup(out, node.name, listener, inner)
                val parameters = node.parameters
                for (index in parameters.indices) {
                    out.append('|')
                    parameter(out, parameters[index], listener, inner)
                }
                out.append("}}")
            }
            is WikiLink -> pair(out, "[[", node.target, node.text, "]]", listener, inner)
            is Tag -> tag(out, node, listener, inner)
            is Comment,
            is HtmlEntity -> leaf(out, node)
            is Argument -> pair(out, "{{{", node.name, node.default, "}}}", listener, inner)
            is ExternalLink -> externalLink(out, node, listener, inner)
            is Heading -> {
                repeat(node.level) { out.append('=') }
                markup(out, node.title, listener, inner)
                repeat(node.level) { out.append('=') }
            }
        }
        listener?.written(node, start, out.length)
    }

    /** A positional parameter's name is its number, which was never written. */
    private fun parameter(out: StringBuilder, parameter: Parameter, listener: Listener?, onStack: Int) {
        val start = out.length
        if (parameter.showKey) {
            markup(out, parameter.name, listener, onStack)
            out.append('=')
        }
        markup(out, parameter.value, listener, onStack)
        listener?.written(parameter, start, out.length)
    }

    private fun attribute(out: StringBuilder, attribute: Attribute, listener: Listener?, onStack: Int) {
        val start = out.length
        out.append(attribute.padFirst)
        markup(out, attribute.name, listener, onStack)
        val value = attribute.value
        if (value != null) {
            out.append(attribute.padBeforeEq).append('=').append(attribute.padAfterEq)
            attribute.quote?.let { out.append(it) }
            markup(out, value, listener, onStack)
            attribute.quote?.let { out.append(it) }
        }
        listener?.written(attribute, start, out.length)
    }

    /** `open first|second close`, the second part and its `|` only when there is one. */
    private fun pair(
        out: StringBuilder,
        open: String,
        first: Markup,
        second: Markup?,
        close: String,
        listener: Listener?,
        onStack: Int,
    ) {
        out.append(open)
        markup(out, first, listener, onStack)
        if (second != null) {
            out.append('|')
            markup(out, second, listener, onStack)
        }
        out.append(close)
    }

    private fun externalLink(out: StringBuilder, link: ExternalLink, listener: Listener?, onStack: Int) {
        if (!link.brackets) {
            markup(out, link.url, listener, onStack)
            return
        }
        out.append('[')
        markup(out, link.url, listener, onStack)
        link.title?.let {
            out.append(link.separator)
            markup(out, it, listener, onStack)
        }
        out.append(']')
    }

    /**
     * A tag: as wiki markup on each side of its contents, or as HTML, its name, each attribute, then its
     * contents and closing tag.
     *
     * `<br>` and `<br/>` are both self-closing, and a bot must not turn one into the other, so which was
     * written is remembered rather than inferred from the name.
     */
    private fun tag(out: StringBuilder, tag: Tag, listener: Listener?, onStack: Int) {
        val markup = tag.wikiMarkup
        if (markup != null) {
            out.append(markup)
            if (!tag.selfClosing) {
                tag.contents?.let { markup(out, it, listener, onStack) }
                out.append(markup)
            }
            return
        }

        out.append('<').append(tag.name)
        val attributes = tag.attributes
        for (index in attributes.indices) attribute(out, attributes[index], listener, onStack)
        out.append(tag.padding)

        if (tag.selfClosing) {
            out.append(if (tag.implicitClose) ">" else "/>")
            return
        }

        out.append('>')
        tag.contents?.let { markup(out, it, listener, onStack) }
        out.append(tag.closingTag)
    }

    /** Writes a comment or an entity. */
    private fun leaf(out: StringBuilder, node: Node) {
        when (node) {
            is TextNode -> out.append(node.text)
            is Comment -> {
                out.append("<!--").append(node.contents)
                if (node.closed) out.append("-->")
            }
            is HtmlEntity -> {
                out.append('&')
                if (node.numeric) out.append('#')
                node.hexChar?.let { out.append(it) }
                out.append(node.value).append(';')
            }
            else -> Unit
        }
    }

    /** Writes [node] if nothing is nested in it, telling [listener], and says whether it did. */
    private fun leaf(out: StringBuilder, node: Node, listener: Listener?): Boolean {
        if (!node.isLeaf) return false
        val start = out.length
        leaf(out, node)
        listener?.written(node, start, out.length)
        return true
    }

    /**
     * One write of a subtree too deep for recursion.
     *
     * Each item on the stack, a piece of markup, a node, a parameter or an attribute, has a step: how much of
     * it has been written. Taking a step writes the markup before the item's next part and gives that part,
     * which goes on top; an item with no part left is finished, and comes off with its range told to the
     * listener, after everything inside it, as recursion would tell it.
     */
    private class Walk(private val out: StringBuilder, private val listener: Listener?) {
        private var items = arrayOfNulls<Any>(INITIAL_DEPTH)

        /** For each item on the stack: its kind, its step, and where it started in [out]. */
        private var state = IntArray(INITIAL_DEPTH * STRIDE)
        private var depth = 0

        fun run(root: Any) {
            enter(root)
            while (depth > 0) {
                val top = depth - 1
                val item = items[top] ?: return
                val part = step(item, top * STRIDE)
                if (part == null) finish(item, top) else enter(part)
            }
        }

        /**
         * Starts on [item]; a piece of markup is written straight through up to its first node with more in
         * it.
         */
        private fun enter(item: Any) {
            if (item !is Markup) {
                push(item, kindOf(item), 0, out.length)
                return
            }
            val start = out.length
            val nodes = item.nodes
            for (index in nodes.indices) {
                val node = nodes[index]
                if (!leaf(out, node, listener)) {
                    push(item, MARKUP, index + 1, start)
                    push(node, kindOf(node), 0, out.length)
                    return
                }
            }
            listener?.written(item, start, out.length)
        }

        private fun push(item: Any, kind: Int, step: Int, start: Int) {
            if (depth == items.size) {
                items = items.copyOf(depth * 2)
                state = state.copyOf(depth * 2 * STRIDE)
            }
            val at = depth * STRIDE
            items[depth] = item
            state[at] = kind
            state[at + 1] = step
            state[at + 2] = start
            depth++
        }

        private fun finish(item: Any, top: Int) {
            items[top] = null
            depth--
            listener?.written(item, state[top * STRIDE + 2], out.length)
        }

        /**
         * Writes what comes before the next part of [item], whose state starts at [at] in [state], and
         * returns that part, or `null` when it has none.
         */
        private fun step(item: Any, at: Int): Any? {
            val step = state[at + 1]++
            return when (state[at]) {
                MARKUP -> markup(item as Markup, at, step)
                TEMPLATE -> template(item as Template, step)
                PARAMETER -> parameter(item as Parameter, at, step)
                TAG -> tag(item as Tag, step)
                WIKI_MARKUP -> wikiMarkup(item as Tag, step)
                WIKILINK -> (item as WikiLink).let { pair("[[", it.target, it.text, "]]", step) }
                ARGUMENT -> (item as Argument).let { pair("{{{", it.name, it.default, "}}}", step) }
                EXTERNAL_LINK -> externalLink(item as ExternalLink, step)
                HEADING -> heading(item as Heading, step)
                ATTRIBUTE -> attribute(item as Attribute, step)
                else -> null
            }
        }

        /** Writes the nodes of [markup] from [from] on up to the first with something nested in it. */
        private fun markup(markup: Markup, at: Int, from: Int): Node? {
            val nodes = markup.nodes
            var index = from
            while (index < nodes.size) {
                val node = nodes[index++]
                if (!leaf(out, node, listener)) {
                    state[at + 1] = index
                    return node
                }
            }
            return null
        }

        private fun template(template: Template, step: Int): Any? {
            if (step == 0) {
                out.append("{{")
                return template.name
            }
            val parameter = template.parameters.getOrNull(step - 1)
            if (parameter == null) {
                out.append("}}")
                return null
            }
            out.append('|')
            return parameter
        }

        /** A positional parameter's name is its number, which was never written. */
        private fun parameter(parameter: Parameter, at: Int, step: Int): Any? =
            when {
                step == 0 && parameter.showKey -> parameter.name
                step == 0 -> {
                    state[at + 1] = AFTER_VALUE
                    parameter.value
                }
                step == 1 -> {
                    out.append('=')
                    parameter.value
                }
                else -> null
            }

        /** `open first|second close`, the second part and its `|` only when there is one. */
        private fun pair(open: String, first: Markup, second: Markup?, close: String, step: Int): Any? {
            if (step == 0) {
                out.append(open)
                return first
            }
            if (step == 1 && second != null) {
                out.append('|')
                return second
            }
            out.append(close)
            return null
        }

        private fun externalLink(link: ExternalLink, step: Int): Any? {
            if (!link.brackets) return if (step == 0) link.url else null
            if (step == 0) {
                out.append('[')
                return link.url
            }
            val title = link.title
            if (step == 1 && title != null) {
                out.append(link.separator)
                return title
            }
            out.append(']')
            return null
        }

        private fun heading(heading: Heading, step: Int): Any? {
            repeat(heading.level) { out.append('=') }
            return if (step == 0) heading.title else null
        }

        /** A tag written as wiki markup, `'''` or `*`: the markup on each side of its contents. */
        private fun wikiMarkup(tag: Tag, step: Int): Any? {
            val markup = tag.wikiMarkup.orEmpty()
            if (step > 0) {
                out.append(markup)
                return null
            }
            out.append(markup)
            if (tag.selfClosing) return null
            tag.contents?.let {
                return it
            }
            out.append(markup)
            return null
        }

        /**
         * An HTML or extension tag: its name, each attribute, then its contents and closing tag.
         *
         * `<br>` and `<br/>` are both self-closing, and a bot must not turn one into the other, so which was
         * written is remembered rather than inferred from the name.
         */
        private fun tag(tag: Tag, step: Int): Any? {
            val attributes = tag.attributes
            if (step == 0) out.append('<').append(tag.name)
            if (step < attributes.size) return attributes[step]
            if (step == attributes.size) {
                out.append(tag.padding)
                if (tag.selfClosing) {
                    out.append(if (tag.implicitClose) ">" else "/>")
                    return null
                }
                out.append('>')
                tag.contents?.let {
                    return it
                }
            }
            out.append(tag.closingTag)
            return null
        }

        private fun attribute(attribute: Attribute, step: Int): Any? {
            if (step == 0) {
                out.append(attribute.padFirst)
                return attribute.name
            }
            val value = attribute.value
            if (step == 1 && value != null) {
                out.append(attribute.padBeforeEq).append('=').append(attribute.padAfterEq)
                attribute.quote?.let { out.append(it) }
                return value
            }
            if (value != null) attribute.quote?.let { out.append(it) }
            return null
        }

        private companion object {
            const val INITIAL_DEPTH = 8
            const val STRIDE = 3

            /** The step of a positional parameter whose value has been written. */
            const val AFTER_VALUE = 2

            const val MARKUP = 1
            const val TEMPLATE = 2
            const val PARAMETER = 3
            const val TAG = 4
            const val WIKI_MARKUP = 5
            const val WIKILINK = 6
            const val ARGUMENT = 7
            const val EXTERNAL_LINK = 8
            const val HEADING = 9
            const val ATTRIBUTE = 10

            /** What [step] does with an item, decided once when it goes on the stack. */
            fun kindOf(item: Any): Int =
                when (item) {
                    is Template -> TEMPLATE
                    is Parameter -> PARAMETER
                    is Tag -> if (item.wikiMarkup != null) WIKI_MARKUP else TAG
                    is WikiLink -> WIKILINK
                    is Argument -> ARGUMENT
                    is ExternalLink -> EXTERNAL_LINK
                    is Heading -> HEADING
                    is Attribute -> ATTRIBUTE
                    is Markup -> MARKUP
                    else -> 0
                }
        }
    }
}
