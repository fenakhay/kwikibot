package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.protocol.ParamInfo
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

/**
 * Wildcards covering the whole surface: every action and output format, every submodule of `query`, and
 * `main` itself.
 */
private val PATTERNS = arrayOf("main", "*", "query+*")

/**
 * Writes or checks the recorded surface.
 *
 * The first argument is the file to write. `--check` compares instead of writing, prints what changed, and
 * exits non-zero when the production wikis differ from the file. `--lane beta` reads the beta cluster
 * instead. `--registry <file>` names the values the library sends, so changes that touch them are marked, and
 * `--report <file>` keeps the report as well as printing it.
 */
public fun main(args: Array<String>) {
    exitProcess(runBlocking { writeOrCheckSurface(args, ToolEnvironment.SYSTEM) })
}

/**
 * What [main] does with [args], reading the wikis and printing through [environment]. Returns the status to
 * exit with: 1 when a wiki could not be read or a lane that fails on drift has drifted, 0 otherwise.
 */
internal suspend fun writeOrCheckSurface(args: Array<String>, environment: ToolEnvironment): Int {
    val options = Options.parse(args, environment.workingDirectory)
    val rendered =
        try {
            collect(options.lane, environment)
        } catch (e: WikiError) {
            // A wiki that refuses or cannot be reached ends the run with one line, which also goes to
            // the report, so the job's artifacts keep the reason.
            val message = "${options.lane.title}: could not read the surface: ${e.message}"
            environment.log.appendLine(message)
            options.report?.createParentDirectories()?.writeText("$message\n")
            return 1
        }

    if (!options.checking) {
        options.target.writeText(rendered)
        environment.output.appendLine("wrote ${options.target}")
        return 0
    }

    val recorded = if (options.target.exists()) options.target.readText() else ""
    val usage = options.registry?.takeIf { it.exists() }?.let { Usage.parse(it.readText()) } ?: Usage.NONE
    val report = SurfaceReport.compare(Surface.parse(recorded), Surface.parse(rendered), usage)
    val markdown =
        report.render(options.lane.title, options.target.name) +
            if (report.isEmpty) ""
            else "\nReview the lines above, then run `./gradlew ${options.dumpTask}`.\n"

    environment.output.appendLine(markdown)
    options.report?.createParentDirectories()?.writeText(markdown)
    environment
        .variables("GITHUB_STEP_SUMMARY")
        ?.takeIf { it.isNotEmpty() }
        ?.let { environment.workingDirectory.resolve(it).appendText(markdown + "\n") }

    return if (!report.isEmpty && options.lane.failsOnDrift) 1 else 0
}

private class Options(
    val target: Path,
    val checking: Boolean,
    val lane: Lane,
    val registry: Path?,
    val report: Path?,
) {
    val dumpTask: String
        get() = if (lane == Lane.BETA) "wikiApiBetaDump" else "wikiApiDump"

    companion object {
        /** The options [args] give, with each file in them taken from [directory] unless it names its own. */
        fun parse(args: Array<String>, directory: Path): Options {
            val valued = setOf("--lane", "--registry", "--report")
            val named =
                args.toList().windowed(2).filter { (key, _) -> key in valued }.associate { (k, v) -> k to v }
            val positional = args.filterIndexed { i, arg ->
                !arg.startsWith("--") && args.getOrNull(i - 1) !in valued
            }
            val lane = named["--lane"]?.let { Lane.valueOf(it.uppercase()) } ?: Lane.PRODUCTION

            return Options(
                target =
                    directory.resolve(
                        positional.firstOrNull()
                            ?: if (lane == Lane.BETA) "api-surface-beta.tsv" else "api-surface.tsv"
                    ),
                checking = "--check" in args,
                lane = lane,
                registry = named["--registry"]?.let(directory::resolve),
                report = named["--report"]?.let(directory::resolve),
            )
        }
    }
}

private suspend fun collect(lane: Lane, environment: ToolEnvironment): String {
    // Politely slow, and read-only: this is somebody else's production wiki.
    val userAgent =
        UserAgent(
            "kwikibot-api-surface",
            "0.2.0",
            "https://en.wiktionary.org/wiki/User:Fenakhay",
        )

    val byWiki =
        environment.client().use { client ->
            lane.wikis.mapValues { (_, server) ->
                val transport =
                    KtorTransport(
                        client = client,
                        endpoint = ApiEndpoint(server = server),
                        userAgent = userAgent,
                        throttle = Throttle(read = 500.milliseconds, timeSource = environment.timeSource),
                        // paraminfo describes how the wiki is set up, which replica lag does not change,
                        // so lag is no reason to hold it back. Left on, a lagging Wikidata fails the run.
                        maxlag = null,
                    )

                val modules = ParamInfo(transport).modules(*PATTERNS)
                environment.log.appendLine("$server: ${modules.size} modules")
                Surface.rows(modules)
            }
        }

    return Surface.render(Surface.merge(byWiki))
}
