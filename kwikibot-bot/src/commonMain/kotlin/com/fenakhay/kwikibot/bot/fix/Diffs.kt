package com.fenakhay.kwikibot.bot.fix

import io.github.petertrr.diffutils.diff
import io.github.petertrr.diffutils.patch.Delta

/** Renders what an edit would change, for review before anything is saved. */
public object Diffs {

    /** How many unchanged lines to show around each change. */
    public const val DEFAULT_CONTEXT: Int = 3

    /**
     * A unified diff of one page edit.
     *
     * Lines are split without their terminators and the diff supplies its own. Keeping the terminators makes
     * a page whose last line has no trailing newline render its `-` and the following `+` on one physical
     * line, which reads as content loss when the only change is an added newline at the end of the file.
     */
    public fun unified(
        before: String,
        after: String,
        title: String,
        context: Int = DEFAULT_CONTEXT,
    ): String {
        val original = before.lines()
        val deltas = diff(original, after.lines()).deltas

        if (deltas.isEmpty()) return ""

        return buildList {
                add("--- old/$title")
                add("+++ new/$title")
                for (hunk in hunks(deltas, context)) addAll(hunk.render(original, context))
            }
            .joinToString("\n")
    }

    /**
     * The changes grouped into hunks: one starts where the context after the change before it would no longer
     * reach the context before it, and context that only touches still joins them.
     */
    private fun hunks(deltas: List<Delta<String>>, context: Int): List<List<Delta<String>>> =
        deltas.fold(mutableListOf<MutableList<Delta<String>>>()) { hunks, delta ->
            val last = hunks.lastOrNull()?.last()
            if (
                last != null &&
                    last.source.position + last.source.size() + context >= delta.source.position - context
            ) {
                hunks.last() += delta
            } else {
                hunks += mutableListOf(delta)
            }
            hunks
        }

    /**
     * One hunk: its header, then the unchanged lines and the changes in the order of the original, at most
     * [context] unchanged lines either side.
     */
    private fun List<Delta<String>>.render(original: List<String>, context: Int): List<String> {
        val body = mutableListOf<String>()
        var removed = 0
        var added = 0

        fun unchanged(from: Int, until: Int) {
            for (line in from until until) body += " ${original[line]}"
            removed += until - from
            added += until - from
        }

        val first = first()
        unchanged(maxOf(0, first.source.position - context), first.source.position)
        for ((index, delta) in withIndex()) {
            if (index > 0) {
                val previous = this[index - 1]
                unchanged(previous.source.position + previous.source.size(), delta.source.position)
            }
            delta.source.lines.forEach { body += "-$it" }
            delta.target.lines.forEach { body += "+$it" }
            removed += delta.source.size()
            added += delta.target.size()
        }
        val last = last()
        val after = last.source.position + last.source.size()
        unchanged(after, minOf(original.size, after + context))

        val from = maxOf(1, first.source.position + 1 - context)
        val to = maxOf(1, first.target.position + 1 - context)
        return listOf("@@ -$from,$removed +$to,$added @@") + body
    }
}
