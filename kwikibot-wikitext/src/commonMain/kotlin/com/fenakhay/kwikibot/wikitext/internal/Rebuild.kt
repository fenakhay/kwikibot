package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.Markup
import com.fenakhay.kwikibot.wikitext.node.Argument
import com.fenakhay.kwikibot.wikitext.node.Attribute
import com.fenakhay.kwikibot.wikitext.node.ExternalLink
import com.fenakhay.kwikibot.wikitext.node.Heading
import com.fenakhay.kwikibot.wikitext.node.Node
import com.fenakhay.kwikibot.wikitext.node.Parameter
import com.fenakhay.kwikibot.wikitext.node.Tag
import com.fenakhay.kwikibot.wikitext.node.Template
import com.fenakhay.kwikibot.wikitext.node.WikiLink

/**
 * A tree rebuilt from the bottom up: by recursion for its first [LEVELS_ON_STACK] levels, and with a stack of
 * its own below that, so that the depth of a tree is a matter of memory rather than of the thread's stack.
 *
 * Each node is first offered to [before], which can settle what it becomes without looking inside it. Then,
 * if [enters] allows, the wikicode in each of its slots that [visits] allows is rebuilt, in slot order, and
 * the node with that wikicode in place is given to [rebuilt], whose answer takes its place, or drops it when
 * `null`.
 *
 * Whatever comes out unchanged is kept as the same instance, the node, its parameters and attributes, and the
 * markup holding them, so rebuilding a tree that nothing changes allocates almost nothing and gives back the
 * tree it was given.
 *
 * @param settles whether [before] is asked about each node; a rebuild that settles none skips the question.
 * @param chooses whether [visits] is asked about each slot; a rebuild that rebuilds every slot skips it.
 */
internal abstract class Rebuild(private val settles: Boolean = false, private val chooses: Boolean = false) {

    /**
     * What [node] becomes without looking inside it, `null` to drop it, or [DESCEND] to rebuild it; asked
     * only of a rebuild made to [settles] nodes.
     */
    open fun before(node: Node): Any? = DESCEND

    /** Whether anything inside [node] is rebuilt; when not, it goes straight to [rebuilt] as it is. */
    open fun enters(node: Node): Boolean = true

    /**
     * Whether the wikicode in place [slot] of [node] is rebuilt, kept as it is otherwise; asked only of a
     * rebuild made to [chooses] slots.
     */
    open fun visits(node: Node, slot: Int): Boolean = true

    /** What [node], [original] with its rebuilt wikicode in place, becomes: itself, another node, or none. */
    abstract fun rebuilt(original: Node, node: Node): Node?

    /**
     * [markup] rebuilt, or [markup] itself when nothing in it changed: by recursion for its first [onStack]
     * levels of nodes, as deep as real pages go and the fastest way through them, and by [run] below that.
     */
    fun rebuild(markup: Markup, onStack: Int = LEVELS_ON_STACK): Markup {
        if (onStack == 0) return run(markup)
        val nodes = markup.nodes
        var changed: ArrayList<Node>? = null
        for (index in nodes.indices) {
            val node = nodes[index]
            val result = rebuildNode(node, onStack)
            if (changed == null && result !== node) changed = prefix(nodes, index)
            if (changed != null && result != null) changed += result
        }
        return changed?.let { Markup.owning(it) } ?: markup
    }

    /** A new list holding the first [count] of [items], with room for the rest. */
    private fun <T> prefix(items: List<T>, count: Int): ArrayList<T> {
        val copy = ArrayList<T>(items.size)
        for (index in 0 until count) copy += items[index]
        return copy
    }

    private fun rebuildNode(node: Node, onStack: Int): Node? {
        if (settles) {
            val settled = before(node)
            if (settled !== DESCEND) return settled as Node?
        }
        if (node.isLeaf || !enters(node)) return rebuilt(node, node)
        val inner = onStack - 1
        val rebuilt =
            when (node) {
                is Template -> rebuildTemplate(node, inner)
                is Tag -> rebuildTag(node, inner)
                is WikiLink -> rebuildLink(node, inner)
                is Argument -> rebuildArgument(node, inner)
                is ExternalLink -> rebuildExternalLink(node, inner)
                is Heading ->
                    slot(node, 0, node.title, inner).let {
                        if (it === node.title) node else node.copy(title = it)
                    }
                else -> node
            }
        return rebuilt(node, rebuilt)
    }

    private fun rebuildLink(link: WikiLink, onStack: Int): WikiLink {
        val target = slot(link, 0, link.target, onStack)
        val text = link.text?.let { slot(link, 1, it, onStack) }
        return if (target === link.target && text === link.text) link
        else link.copy(target = target, text = text)
    }

    private fun rebuildArgument(argument: Argument, onStack: Int): Argument {
        val name = slot(argument, 0, argument.name, onStack)
        val default = argument.default?.let { slot(argument, 1, it, onStack) }
        return if (name === argument.name && default === argument.default) argument
        else argument.copy(name = name, default = default)
    }

    private fun rebuildExternalLink(link: ExternalLink, onStack: Int): ExternalLink {
        val url = slot(link, 0, link.url, onStack)
        val title = link.title?.let { slot(link, 1, it, onStack) }
        return if (url === link.url && title === link.title) link else link.copy(url = url, title = title)
    }

    private fun rebuildTemplate(template: Template, onStack: Int): Template {
        val name = slot(template, 0, template.name, onStack)
        val originals = template.parameters
        var parameters: ArrayList<Parameter>? = null
        for (index in originals.indices) {
            val parameter = originals[index]
            val key = slot(template, 1 + 2 * index, parameter.name, onStack)
            val value = slot(template, 2 + 2 * index, parameter.value, onStack)
            val result =
                if (key === parameter.name && value === parameter.value) parameter
                else parameter.copy(name = key, value = value)
            if (parameters == null && result !== parameter) parameters = prefix(originals, index)
            parameters?.add(result)
        }
        return if (name === template.name && parameters == null) template
        else template.copy(name = name, parameters = parameters ?: originals)
    }

    private fun rebuildTag(tag: Tag, onStack: Int): Tag {
        val contents = tag.contents?.let { slot(tag, 0, it, onStack) }
        val originals = tag.attributes
        var attributes: ArrayList<Attribute>? = null
        for (index in originals.indices) {
            val attribute = originals[index]
            val name = slot(tag, 1 + 2 * index, attribute.name, onStack)
            val value = attribute.value?.let { slot(tag, 2 + 2 * index, it, onStack) }
            val result =
                if (name === attribute.name && value === attribute.value) attribute
                else attribute.copy(name = name, value = value)
            if (attributes == null && result !== attribute) attributes = prefix(originals, index)
            attributes?.add(result)
        }
        return if (contents === tag.contents && attributes == null) tag
        else tag.copy(contents = contents, attributes = attributes ?: originals)
    }

    /** The wikicode [markup] in place [slot] of [node], rebuilt if [visits] says so. */
    private fun slot(node: Node, slot: Int, markup: Markup, onStack: Int): Markup =
        if (!chooses || visits(node, slot)) rebuild(markup, onStack) else markup

    private val frames = ArrayList<Frame>()
    private var depth = 0

    /** [markup] rebuilt, or [markup] itself when nothing in it changed. */
    private fun run(markup: Markup): Markup {
        depth = 0
        push().markup(markup)
        while (true) {
            val frame = frames[depth - 1]
            val inner = if (frame.node == null) advance(frame) else frame.nextSlot()
            if (inner != null) {
                push().markup(inner)
                continue
            }
            val result = if (frame.node == null) frame.finishMarkup() else finishNode(frame)
            depth--
            if (depth == 0) return result as Markup
            frames[depth - 1].accept(result)
        }
    }

    /**
     * Goes on through the nodes of [frame]'s markup, settling each one that needs nothing rebuilt inside it,
     * and returns the first slot of the first one that does, whose frame is then on top; `null` at the end.
     */
    private fun advance(frame: Frame): Markup? {
        val nodes = frame.source.nodes
        while (frame.index < nodes.size) {
            val node = nodes[frame.index]
            val settled = if (settles) before(node) else DESCEND
            when {
                settled !== DESCEND -> frame.accept(settled)
                node.isLeaf || !enters(node) -> frame.accept(rebuilt(node, node))
                else -> {
                    val inner = push()
                    inner.node(node)
                    inner.nextSlot()?.let {
                        return it
                    }
                    depth--
                    frame.accept(finishNode(inner))
                }
            }
        }
        return null
    }

    private fun finishNode(frame: Frame): Node? {
        val original = checkNotNull(frame.node)
        return rebuilt(original, frame.rebuiltNode())
    }

    private fun push(): Frame {
        if (depth == frames.size) frames += Frame()
        return frames[depth++]
    }

    /**
     * One level of the rebuild: a piece of markup with how far through its nodes it has got, or a node with
     * how far through its slots. What was rebuilt differently is collected only from the first change on.
     */
    private inner class Frame {
        var node: Node? = null
        lateinit var source: Markup
        var index = 0
        private var nodes: ArrayList<Node>? = null
        private var slots: Array<Markup?>? = null
        private var slot = 0

        fun markup(markup: Markup) {
            node = null
            source = markup
            index = 0
            nodes = null
        }

        fun node(node: Node) {
            this.node = node
            slot = -1
            slots = null
        }

        /** The next slot of this frame's node that is rebuilt, or `null` when there is none left. */
        fun nextSlot(): Markup? {
            val node = node ?: return null
            while (++slot < node.slots) {
                val inner = node.slot(slot)
                if (inner != null && (!chooses || visits(node, slot))) return inner
            }
            return null
        }

        /** Takes what the node or slot just finished became. */
        fun accept(result: Any?) {
            val node = node
            if (node == null) acceptNode(result as Node?) else acceptSlot(node, result as Markup)
        }

        private fun acceptNode(result: Node?) {
            val original = source.nodes[index]
            var changed = nodes
            if (changed == null && result !== original) {
                changed = ArrayList<Node>(source.nodes.size).apply { addAll(source.nodes.subList(0, index)) }
                nodes = changed
            }
            if (changed != null && result != null) changed += result
            index++
        }

        private fun acceptSlot(node: Node, result: Markup) {
            if (result === node.slot(slot)) return
            val changed = slots ?: Array(node.slots) { node.slot(it) }.also { slots = it }
            changed[slot] = result
        }

        fun finishMarkup(): Markup = nodes?.let { Markup.owning(it) } ?: source

        fun rebuiltNode(): Node {
            val node = checkNotNull(node)
            return slots?.let { node.withSlots(it) } ?: node
        }
    }

    companion object {
        /** What [before] answers to have a node rebuilt rather than settled. */
        val DESCEND: Any = Any()
    }
}

/**
 * This node with the wikicode in each of its places replaced by [values], in the order of [slots].
 *
 * A parameter or attribute whose wikicode is unchanged is kept as it is.
 */
internal fun Node.withSlots(values: Array<Markup?>): Node =
    when (this) {
        is Template ->
            copy(
                name = values[0] ?: name,
                parameters =
                    parameters.mapIndexed { index, parameter ->
                        parameter.withSlots(values[1 + 2 * index], values[2 + 2 * index])
                    },
            )
        is Tag ->
            copy(
                contents = values[0],
                attributes =
                    attributes.mapIndexed { index, attribute ->
                        attribute.withSlots(values[1 + 2 * index], values[2 + 2 * index])
                    },
            )
        is Argument -> copy(name = values[0] ?: name, default = values[1])
        is WikiLink -> copy(target = values[0] ?: target, text = values[1])
        is ExternalLink -> copy(url = values[0] ?: url, title = values[1])
        is Heading -> copy(title = values[0] ?: title)
        else -> this
    }

private fun Parameter.withSlots(name: Markup?, value: Markup?): Parameter =
    if (name === this.name && value === this.value) this
    else copy(name = name ?: this.name, value = value ?: this.value)

private fun Attribute.withSlots(name: Markup?, value: Markup?): Attribute =
    if (name === this.name && value === this.value) this else copy(name = name ?: this.name, value = value)
