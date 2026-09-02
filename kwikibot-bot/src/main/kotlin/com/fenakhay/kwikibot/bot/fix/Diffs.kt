package com.fenakhay.kwikibot.bot.fix

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils

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
        val revised = after.lines()
        val patch = DiffUtils.diff(original, revised)

        if (patch.deltas.isEmpty()) return ""

        return UnifiedDiffUtils.generateUnifiedDiff("old/$title", "new/$title", original, patch, context)
            .joinToString("\n")
    }
}
