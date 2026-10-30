package com.fenakhay.kwikibot.wikitext.internal

import com.fenakhay.kwikibot.wikitext.node.Node

/**
 * The node lists a depth-first walk has still to finish, innermost last, each with how far it has got.
 *
 * Walking a tree with this instead of with recursion keeps a node's cost the same however deep it sits, and
 * keeps the depth a tree may have from being a matter of stack. A walk takes [next] until it is `null`, and
 * pushes what it wants to visit inside a node, last first, before taking the next one.
 */
internal class NodeStack {
    private val lists = ArrayList<List<Node>>()
    private var positions = IntArray(INITIAL_DEPTH)

    /** Puts [nodes] on top, to be walked before what is under them. */
    fun push(nodes: List<Node>) {
        if (nodes.isEmpty()) return
        if (lists.size == positions.size) positions = positions.copyOf(positions.size * 2)
        positions[lists.size] = 0
        lists.add(nodes)
    }

    /** Whether a node is left to walk, dropping the lists already finished. */
    fun isNotEmpty(): Boolean {
        while (lists.isNotEmpty() && positions[lists.lastIndex] == lists[lists.lastIndex].size) {
            lists.removeAt(lists.lastIndex)
        }
        return lists.isNotEmpty()
    }

    /** The next node, or `null` once every list is finished. */
    fun next(): Node? {
        while (lists.isNotEmpty()) {
            val level = lists.lastIndex
            val nodes = lists[level]
            val position = positions[level]
            if (position < nodes.size) {
                positions[level] = position + 1
                return nodes[position]
            }
            lists.removeAt(level)
        }
        return null
    }

    private companion object {
        const val INITIAL_DEPTH = 16
    }
}

/**
 * Walks a tree depth first: a node, then the wikicode nested in it slot by slot, then the node after it.
 *
 * The walk allocates nothing per node, so a node costs the same however deep it sits.
 */
internal class NodeWalk(top: List<Node>) : Iterator<Node> {
    private val stack = NodeStack().apply { push(top) }

    override fun hasNext(): Boolean = stack.isNotEmpty()

    override fun next(): Node {
        val node = stack.next() ?: throw NoSuchElementException()
        for (slot in node.slots - 1 downTo 0) {
            node.slot(slot)?.let { stack.push(it.nodes) }
        }
        return node
    }
}
