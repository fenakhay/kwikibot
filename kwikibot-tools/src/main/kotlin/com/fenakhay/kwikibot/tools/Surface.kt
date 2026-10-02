package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.protocol.ModuleDescription
import com.fenakhay.kwikibot.protocol.ParamDescription

/** The placeholder for a parameter whose values are one wiki's configuration rather than its API. */
internal const val CONFIG = "<config>"

internal const val NO_PARAMETER = "-"

internal const val COLUMNS = "module\tparameter\tgroup\tsource\tflags\tdetail\twikis"

/** One row of the file: a module, or one parameter of one. */
internal data class Row(
    /** The module's path, such as `query+abusefilters`. */
    val module: String,
    /** The parameter as it is sent, such as `abfprop`, or [NO_PARAMETER] for the module itself. */
    val parameter: String,
    /** For a submodule of `query`, which of `list`, `prop` or `meta` it belongs to. */
    val group: String = "",
    /** What provides the module: `MediaWiki`, or an extension's name. */
    val source: String = "",
    /** `deprecated`, `required` and the like. */
    val flags: Set<String> = emptySet(),
    /** The `key=value` facts, in [DETAIL_ORDER]. A bare word such as `write` has an empty value. */
    val detail: Map<String, String> = emptyMap(),
    /** The reference wikis the row was seen on, empty when it was seen on all of them. */
    val wikis: Set<String> = emptySet(),
) {
    val key: Pair<String, String>
        get() = module to parameter

    /** The values of a `|`-separated detail, such as `enum`. */
    fun values(key: String): Set<String> = detail[key]?.split('|')?.toSet().orEmpty()

    fun render(): String =
        listOf(
                module,
                parameter,
                group,
                source,
                FLAG_ORDER.filter { it in flags }.plus(flags - FLAG_ORDER.toSet()).joinToString(","),
                renderDetail(),
                wikis.sorted().joinToString(","),
            )
            .joinToString("\t")
            .trimEnd('\t')

    fun renderDetail(): String =
        (DETAIL_ORDER.filter { it in detail } + (detail.keys - DETAIL_ORDER.toSet()).sorted()).joinToString(
            " "
        ) { key ->
            if (key in BARE) key else "$key=${detail.getValue(key)}"
        }

    /** This row and [other], the same row as another wiki reports it, as one. */
    fun mergedWith(other: Row): Row =
        copy(
            group = group.ifEmpty { other.group },
            source = source.ifEmpty { other.source },
            flags = flags + other.flags,
            detail =
                (detail.keys + other.detail.keys).associateWith { key ->
                    if (key in SETS) {
                        (values(key) + other.values(key)).sorted().joinToString("|")
                    } else {
                        detail[key] ?: other.detail.getValue(key)
                    }
                },
        )

    /**
     * This row with a list of site configuration replaced by [CONFIG].
     *
     * Change tags, user groups, rights and the wikis of a farm are values a wiki's administrators add all the
     * time. They say nothing about the API, and recording them would fail the check every week.
     */
    fun collapsed(): Row =
        if ("enum" in detail && isConfiguration(module, parameter)) copy(detail = detail + ("enum" to CONFIG))
        else this

    companion object {
        /** Flags in the order they are written. */
        val FLAG_ORDER = listOf("deprecated", "internal", "required", "multi", "sensitive")

        /** Detail keys in the order they are written. */
        val DETAIL_ORDER =
            listOf(
                "prefix",
                "write",
                "post",
                "enum",
                "type",
                "default",
                "limit",
                "highlimit",
                "max",
                "highmax",
                "deprecatedvalues",
                "internalvalues",
            )

        /** Details that are a set of values, which merging unites rather than picks between. */
        val SETS = setOf("enum", "deprecatedvalues", "internalvalues")

        private val BARE = setOf("write", "post")

        /**
         * Where one detail ends and the next begins.
         *
         * Not at every space: a value can contain one, as `default=Main Page` does. A module's details have
         * no free text, and are the only ones with bare words.
         */
        private val FIELD = Regex(" (?=(?:${DETAIL_ORDER.joinToString("|")})=)")

        /** Reads a row back from [line], given the file's [header]. */
        fun parse(header: List<String>, line: String): Row {
            val cells = line.split('\t')
            val cell = { name: String ->
                header.indexOf(name).takeIf { it >= 0 }?.let { cells.getOrNull(it) }.orEmpty()
            }
            val parameter = cell("parameter")
            val fields = cell("detail").split(if (parameter == NO_PARAMETER) Regex(" ") else FIELD)

            return Row(
                module = cell("module"),
                parameter = parameter,
                group = cell("group"),
                source = cell("source"),
                flags = cell("flags").split(',').filter { it.isNotEmpty() }.toSet(),
                detail =
                    fields
                        .filter { it.isNotEmpty() }
                        .associate { field ->
                            if (field in BARE) field to ""
                            else field.substringBefore('=') to field.substringAfter('=')
                        },
                wikis = cell("wikis").split(',').filter { it.isNotEmpty() }.toSet(),
            )
        }
    }
}

/** A file's worth of rows, read from or written to `api-surface.tsv`. */
internal object Surface {

    fun render(rows: Collection<Row>): String =
        (listOf(COLUMNS) + rows.sortedWith(ORDER).map { it.render() }).joinToString("\n", postfix = "\n")

    fun parse(text: String): List<Row> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val header = lines.first().split('\t')
        return lines.drop(1).map { Row.parse(header, it) }
    }

    /**
     * Every wiki's rows as one list.
     *
     * A row present on some wikis and not others is kept, and says which. Values are united, so a value one
     * wiki accepts is recorded even when another does not; otherwise the first wiki to report a row would
     * decide it.
     */
    fun merge(byWiki: Map<String, List<Row>>): List<Row> {
        val merged = linkedMapOf<Pair<String, String>, Row>()
        val seenOn = mutableMapOf<Pair<String, String>, MutableSet<String>>()

        byWiki.forEach { (wiki, rows) ->
            rows.forEach { row ->
                merged.merge(row.key, row) { first, next -> first.mergedWith(next) }
                seenOn.getOrPut(row.key) { mutableSetOf() } += wiki
            }
        }

        return merged.values
            .map { row -> row.copy(wikis = seenOn.getValue(row.key).takeIf { it != byWiki.keys }.orEmpty()) }
            .sortedWith(ORDER)
    }

    /** One wiki's modules as rows, with configuration collapsed. */
    fun rows(modules: List<ModuleDescription>): List<Row> =
        modules.flatMap { it.rows() }.map { it.collapsed() }

    private val ORDER: Comparator<Row> = compareBy({ it.module }, { it.parameter })
}

private fun ModuleDescription.rows(): List<Row> {
    val self =
        Row(
            module = path,
            parameter = NO_PARAMETER,
            group = group.orEmpty(),
            source = source.orEmpty(),
            flags = setOfNotNull("deprecated".takeIf { deprecated }, "internal".takeIf { internal }),
            detail =
                buildMap {
                    if (prefix.isNotEmpty()) put("prefix", prefix)
                    if (isWrite) put("write", "")
                    if (mustBePosted) put("post", "")
                },
        )

    return listOf(self) + parameters.values.map { it.row(this) }
}

/**
 * One parameter, named as it goes on the wire.
 *
 * `paraminfo` reports names without the module's prefix and gives the prefix separately, so its `show` on
 * `query+usercontribs` is the `ucshow` a caller sends. Recording the wire name makes this file searchable
 * against the code that sends it.
 */
private fun ParamDescription.row(module: ModuleDescription) =
    Row(
        module = module.path,
        parameter = module.prefix + name,
        group = module.group.orEmpty(),
        source = module.source.orEmpty(),
        flags =
            setOfNotNull(
                "deprecated".takeIf { deprecated },
                "required".takeIf { required },
                "multi".takeIf { multiValued },
                "sensitive".takeIf { sensitive },
            ),
        detail =
            buildMap {
                // Qualified: inside buildMap, a bare "values" is the map being built.
                this@row.values.takeIf { it.isNotEmpty() }?.let { put("enum", it.sorted().joinToString("|")) }
                type?.let { put("type", it) }
                default?.takeIf { it.isNotEmpty() }?.let { put("default", it) }
                valueLimit?.let { put("limit", it.toString()) }
                highValueLimit?.let { put("highlimit", it.toString()) }
                limit?.let { put("max", it.toString()) }
                highLimit?.let { put("highmax", it.toString()) }
                deprecatedValues
                    .takeIf { it.isNotEmpty() }
                    ?.let { put("deprecatedvalues", it.sorted().joinToString("|")) }
                internalValues
                    .takeIf { it.isNotEmpty() }
                    ?.let { put("internalvalues", it.sorted().joinToString("|")) }
            },
    )

/**
 * Whether the values [parameter] of [module] takes are site configuration rather than API.
 *
 * Listed rather than guessed from the name: `translationentitysearch grouptypes` ends like a group list and
 * is a fixed set of three.
 */
internal fun isConfiguration(module: String, parameter: String): Boolean =
    parameter in CONFIGURED_NAMES ||
        (module to parameter) in CONFIGURED_PARAMETERS ||
        (module.startsWith("query+") && USER_LISTS.any { parameter.endsWith(it) }) ||
        (module.startsWith("wb") && parameter in WIKIBASE_SITES)

/** Change tags, under the names every module gives them. */
private val CONFIGURED_NAMES = setOf("tags", "tagfilter")

/**
 * The user groups an account can be given, the icons of the Codex library MediaWiki bundles, and the wikis of
 * a farm.
 */
private val CONFIGURED_PARAMETERS =
    setOf(
        "tag" to "add",
        "userrights" to "add",
        "userrights" to "remove",
        "globaluserrights" to "add",
        "globaluserrights" to "remove",
        "query+codexicons" to "names",
        "query+globalusage" to "gusite",
        "query+unreadnotificationpages" to "unpwikis",
        "query+notifications" to "notwikis",
        "echomarkread" to "wikis",
    )

/** How a query names a list of user groups or rights: `augroup`, `pcexcludegroup`, `aurights`. */
private val USER_LISTS = listOf("group", "rights")

private val WIKIBASE_SITES = setOf("sites", "sitefilter", "site", "linksite", "fromsite", "tosite")
