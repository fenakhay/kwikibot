package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.KtorTransport
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.WikiHttpClient
import com.fenakhay.kwikibot.protocol.ModuleDescription
import com.fenakhay.kwikibot.protocol.ParamDescription
import com.fenakhay.kwikibot.protocol.ParamInfo
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

/**
 * Records what the reference wikis say their API accepts.
 *
 * MediaWiki describes itself: `paraminfo` names every module, every parameter, the extension
 * behind it and what is on its way out. Kept in a file and diffed, that turns "a wiki gained
 * something" from an accident into a build failure.
 *
 * The wikis are chosen for the extensions they carry rather than their size. Between them they
 * cover core, Wikibase and the file stack.
 *
 * Deliberately production only. test.wikipedia runs a train ahead, but its module list differs
 * from production mostly in which extensions are installed there - Translate, Flow, WikiLambda -
 * so including it records surface that no wiki a bot runs against actually has.
 */
private val REFERENCE_WIKIS = listOf(
    "en.wikipedia.org",
    "en.wiktionary.org",
    "commons.wikimedia.org",
    "www.wikidata.org",
)

/** Wildcards covering the whole surface: every action, and every submodule of `query`. */
private val PATTERNS = arrayOf("*", "query+*")

private const val NO_PARAMETER = "-"

private const val COLUMNS = "module\tparameter\tgroup\tsource\tflags\tdetail"

/** One row of the file: a module, or one parameter of one. */
private data class Row(
    val module: String,
    val parameter: String,
    val group: String,
    val source: String,
    val flags: String,
    val detail: String,
) : Comparable<Row> {

    // Sorted so the file is stable across runs and the diff stays readable: a module immediately
    // followed by its own parameters.
    override fun compareTo(other: Row): Int =
        compareValuesBy(this, other, { it.module }, { it.parameter })

    fun render(): String = "$module\t$parameter\t$group\t$source\t$flags\t$detail"
}

/**
 * Writes or checks the recorded surface.
 *
 * The first argument is the file to write; `--check` compares instead of writing and
 * exits non-zero when the wikis offer something the file does not list.
 */
public fun main(args: Array<String>) {
    val target = Path(args.firstOrNull() ?: "api-surface.tsv")
    val checking = "--check" in args

    val rendered = runBlocking { collect() }

    if (!checking) {
        target.writeText(rendered)
        println("wrote $target")
        return
    }

    report(target, rendered)
}

private fun report(target: Path, rendered: String) {
    val recorded = if (target.exists()) target.readText() else ""
    if (recorded == rendered) {
        println("$target matches the reference wikis")
        return
    }

    val was = recorded.lines().toSet()
    val now = rendered.lines().toSet()

    (now - was).filter { it.isNotBlank() }.sorted().forEach { println("+ $it") }
    (was - now).filter { it.isNotBlank() }.sorted().forEach { println("- $it") }

    System.err.println(
        "\n$target is out of date. Review the lines above, then run ./gradlew wikiApiDump.",
    )
    exitProcess(1)
}

private suspend fun collect(): String {
    val rows = sortedSetOf<Row>()

    // Politely slow, and read-only: this is somebody else's production wiki.
    val userAgent = UserAgent(
        "kwikibot-api-surface",
        "0.1.0",
        "https://en.wiktionary.org/wiki/User:Fenakhay",
    )

    WikiHttpClient.create().use { client ->
        REFERENCE_WIKIS.forEach { server ->
            val transport = KtorTransport(
                client = client,
                endpoint = ApiEndpoint(server = server),
                userAgent = userAgent,
                throttle = Throttle(read = 500.milliseconds),
                // paraminfo is answered from configuration, not from a database replica, so
                // replica lag is no reason to refuse it. Left on, a lagging Wikidata fails the
                // run for a query that put no load on what was lagging.
                maxlag = null,
            )

            val modules = ParamInfo(transport).modules(*PATTERNS)
            System.err.println("$server: ${modules.size} modules")
            modules.forEach { rows += it.rows() }
        }
    }

    return (listOf(COLUMNS) + rows.map { it.render() }).joinToString("\n", postfix = "\n")
}

private fun ModuleDescription.rows(): List<Row> {
    val self = Row(
        module = path,
        parameter = NO_PARAMETER,
        group = group.orEmpty(),
        source = source.orEmpty(),
        flags = flags(),
        detail = listOfNotNull(
            prefix.takeIf { it.isNotEmpty() }?.let { "prefix=$it" },
            "write".takeIf { isWrite },
            "post".takeIf { mustBePosted },
        ).joinToString(" "),
    )

    return listOf(self) + parameters.values.map { it.row(this) }
}

private fun ModuleDescription.flags(): String = listOfNotNull(
    "deprecated".takeIf { deprecated },
    "internal".takeIf { internal },
).joinToString(",")

/**
 * One parameter, named as it goes on the wire.
 *
 * `paraminfo` reports names without the module's prefix and gives the prefix separately, so its
 * `show` on `query+usercontribs` is the `ucshow` a caller actually sends. Recording the wire name
 * is what makes this file searchable against the code that sends it.
 */
private fun ParamDescription.row(module: ModuleDescription) = Row(
    module = module.path,
    parameter = module.prefix + name,
    group = module.group.orEmpty(),
    source = module.source.orEmpty(),
    flags = listOfNotNull(
        "deprecated".takeIf { deprecated },
        "required".takeIf { required },
        "multi".takeIf { multiValued },
        "sensitive".takeIf { sensitive },
    ).joinToString(","),
    detail = detail(),
)

private fun ParamDescription.detail(): String = listOfNotNull(
    values.takeIf { it.isNotEmpty() }
        ?.let { accepted -> "enum=${accepted.sorted().joinToString("|")}" },
    type?.let { "type=$it" },
    default?.takeIf { it.isNotEmpty() }?.let { "default=$it" },
    limit?.let { "limit=$it" },
    highLimit?.let { "highlimit=$it" },
    deprecatedValues.takeIf { it.isNotEmpty() }
        ?.let { "deprecatedvalues=${it.sorted().joinToString("|")}" },
).joinToString(" ")
