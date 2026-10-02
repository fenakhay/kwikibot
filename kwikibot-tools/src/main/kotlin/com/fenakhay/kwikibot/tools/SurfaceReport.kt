package com.fenakhay.kwikibot.tools

/**
 * What the library sends, read from the `build/wire-registry.tsv` that `WireContractTest` writes.
 *
 * Lets a report say which changes touch kwikibot.
 */
internal class Usage(private val symbols: Map<Pair<String, String>, Map<String, Set<String>>>) {

    /**
     * The declarations that send [values] of [parameter] on [module], or that send it at all if [values] is
     * null.
     */
    fun of(module: String, parameter: String, values: Collection<String>? = null): List<String> {
        val sent =
            if (parameter == NO_PARAMETER) {
                symbols.filterKeys { it.first == module }.values.flatMap { it.values }
            } else {
                symbols[module to parameter]
                    ?.let { byValue ->
                        if (values == null) byValue.values else values.mapNotNull { byValue[it] }
                    }
                    .orEmpty()
            }

        val all = sent.flatten().toSortedSet()
        // For named values, each symbol. For a whole parameter, each enum once rather than every entry.
        return if (values == null) all.map { it.substringBeforeLast('.') }.distinct() else all.toList()
    }

    companion object {
        val NONE = Usage(emptyMap())

        fun parse(text: String): Usage {
            val lines = text.lines().filter { it.isNotBlank() }
            val header = lines.firstOrNull()?.split('\t') ?: return NONE
            val rows = lines.drop(1).map { it.split('\t') }
            val cell = { row: List<String>, name: String -> row.getOrNull(header.indexOf(name)).orEmpty() }

            return Usage(
                rows.groupBy({ cell(it, "module") to cell(it, "parameter") }).mapValues { (_, sent) ->
                    sent.groupBy({ cell(it, "value") }, { cell(it, "symbol") }).mapValues { it.value.toSet() }
                }
            )
        }
    }
}

/** One line of a report: what changed about one row. */
internal data class Change(
    val module: String,
    val parameter: String,
    val text: String,
    val usedBy: List<String>,
) {

    fun render(): String {
        val name = if (parameter == NO_PARAMETER) "`$module` (module)" else "`$module $parameter`"
        val used =
            usedBy.takeIf { it.isNotEmpty() }?.let { " — **used by kwikibot**: ${it.joinToString(", ")}" }
        return "- $name" + text.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty() + used.orEmpty()
    }
}

/**
 * How the surface the wikis report differs from the one recorded, sorted by what a reader has to do.
 *
 * Deprecations come first, because they have a deadline. Then what went away, what is new, and what changed,
 * the last broken down field by field.
 */
internal class SurfaceReport(
    val deprecated: List<Change>,
    val removed: List<Change>,
    val added: List<Change>,
    val changed: List<Change>,
) {
    val isEmpty: Boolean
        get() = deprecated.isEmpty() && removed.isEmpty() && added.isEmpty() && changed.isEmpty()

    /** The report as Markdown, for a terminal, a job summary and an artifact alike. */
    fun render(title: String, file: String): String = buildString {
        appendLine("## $title")
        appendLine()
        if (isEmpty) {
            appendLine("`$file` matches the reference wikis.")
            return@buildString
        }

        appendLine(
            "`$file` differs from what the reference wikis report: ${added.size} added, ${removed.size} " +
                "removed, ${changed.size} changed, ${deprecated.size} newly deprecated."
        )
        section("Newly deprecated", deprecated)
        section("Removed", removed)
        section("Added", added)
        section("Changed", changed)
    }

    private fun StringBuilder.section(heading: String, changes: List<Change>) {
        if (changes.isEmpty()) return
        appendLine()
        appendLine("### $heading")
        appendLine()
        changes.forEach { appendLine(it.render()) }
    }

    companion object {
        fun compare(recorded: List<Row>, current: List<Row>, usage: Usage = Usage.NONE): SurfaceReport {
            val was = recorded.associateBy { it.key }
            val now = current.associateBy { it.key }

            val removed =
                (was.keys - now.keys).sortedWith(KEYS).map { (module, parameter) ->
                    Change(module, parameter, "", usage.of(module, parameter))
                }
            val added =
                (now.keys - was.keys).sortedWith(KEYS).map { key ->
                    val row = now.getValue(key)
                    Change(row.module, row.parameter, row.describe(), emptyList())
                }

            val deprecated = mutableListOf<Change>()
            val changed = mutableListOf<Change>()
            (was.keys intersect now.keys).sortedWith(KEYS).forEach { key ->
                val before = was.getValue(key)
                val after = now.getValue(key)
                if (before == after) return@forEach

                deprecation(before, after, usage)?.let { deprecated += it }
                differences(before, after)
                    .takeIf { it.isNotEmpty() }
                    ?.let { fields ->
                        val lost = before.values("enum") - after.values("enum")
                        val used = usage.of(after.module, after.parameter, lost.takeIf { it.isNotEmpty() })
                        changed += Change(after.module, after.parameter, fields.joinToString("; "), used)
                    }
            }

            return SurfaceReport(deprecated, removed, added, changed)
        }

        /** What [after] newly deprecates: itself, or some of its values. */
        private fun deprecation(before: Row, after: Row, usage: Usage): Change? {
            val values = after.values("deprecatedvalues") - before.values("deprecatedvalues")
            val whole = "deprecated" in after.flags && "deprecated" !in before.flags
            if (values.isEmpty() && !whole) return null

            val text =
                listOfNotNull(
                        "the whole ${if (after.parameter == NO_PARAMETER) "module" else "parameter"}"
                            .takeIf { whole },
                        values.sorted().joinToString(", ").takeIf { values.isNotEmpty() },
                    )
                    .joinToString("; ")
            val used = usage.of(after.module, after.parameter, values.takeIf { !whole })
            return Change(after.module, after.parameter, text, used)
        }

        /** Every way [after] differs from [before], other than becoming deprecated. */
        private fun differences(before: Row, after: Row): List<String> = buildList {
            if (before.group != after.group) add("group ${before.group.orNone()} → ${after.group.orNone()}")
            if (before.source != after.source)
                add("source ${before.source.orNone()} → ${after.source.orNone()}")
            // Becoming deprecated has its own section; ceasing to be is a change like any other.
            val newlyDeprecated = "deprecated" in after.flags && "deprecated" !in before.flags
            setChange("flags", before.flags, if (newlyDeprecated) after.flags - "deprecated" else after.flags)
                ?.let { add(it) }
            (Row.DETAIL_ORDER + (before.detail.keys + after.detail.keys)).distinct().forEach { key ->
                when {
                    key == "deprecatedvalues" -> {
                        val undeprecated = before.values(key) - after.values(key)
                        if (undeprecated.isNotEmpty())
                            add("no longer deprecated: ${undeprecated.sorted().joinToString(", ")}")
                    }
                    key in Row.SETS -> setChange(key, before.values(key), after.values(key))?.let { add(it) }
                    before.detail[key] != after.detail[key] ->
                        add("$key ${before.detail[key].orNone()} → ${after.detail[key].orNone()}")
                }
            }
            if (before.wikis != after.wikis) add("wikis ${before.wikis.orAll()} → ${after.wikis.orAll()}")
        }

        private fun setChange(name: String, before: Set<String>, after: Set<String>): String? {
            val gained = (after - before).sorted().map { "+$it" }
            val lost = (before - after).sorted().map { "−$it" }
            return (gained + lost).takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = "$name ")
        }

        private fun String?.orNone(): String = this?.ifEmpty { null } ?: "(none)"

        private fun Set<String>.orAll(): String = if (isEmpty()) "(all)" else sorted().joinToString(",")

        private fun Row.describe(): String =
            listOfNotNull(
                    source.takeIf { it.isNotEmpty() }?.let { "($it)" },
                    flags
                        .takeIf { it.isNotEmpty() }
                        ?.let { Row.FLAG_ORDER.filter { flag -> flag in it }.joinToString(",") },
                    renderDetail().takeIf { it.isNotEmpty() },
                    wikis.takeIf { it.isNotEmpty() }?.let { "on ${it.sorted().joinToString(", ")}" },
                )
                .joinToString(" ")

        private val KEYS: Comparator<Pair<String, String>> = compareBy({ it.first }, { it.second })
    }
}
