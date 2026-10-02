package com.fenakhay.kwikibot.tools

import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import com.fenakhay.kwikibot.protocol.ParamInfo
import java.nio.file.Path
import kotlin.io.path.Path
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
    val options = Options.parse(args)
    val rendered = runBlocking { collect(options.lane) }

    if (!options.checking) {
        options.target.writeText(rendered)
        println("wrote ${options.target}")
        return
    }

    val recorded = if (options.target.exists()) options.target.readText() else ""
    val usage = options.registry?.takeIf { it.exists() }?.let { Usage.parse(it.readText()) } ?: Usage.NONE
    val report = SurfaceReport.compare(Surface.parse(recorded), Surface.parse(rendered), usage)
    val markdown =
        report.render(options.lane.title, options.target.name) +
            if (report.isEmpty) ""
            else "\nReview the lines above, then run `./gradlew ${options.dumpTask}`.\n"

    println(markdown)
    options.report?.let { it.createParentDirectories().writeText(markdown) }
    System.getenv("GITHUB_STEP_SUMMARY")
        ?.takeIf { it.isNotEmpty() }
        ?.let { Path(it).appendText(markdown + "\n") }

    if (!report.isEmpty && options.lane.failsOnDrift) exitProcess(1)
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
        fun parse(args: Array<String>): Options {
            val valued = setOf("--lane", "--registry", "--report")
            val named =
                args.toList().windowed(2).filter { (key, _) -> key in valued }.associate { (k, v) -> k to v }
            val positional = args.filterIndexed { i, arg ->
                !arg.startsWith("--") && args.getOrNull(i - 1) !in valued
            }
            val lane = named["--lane"]?.let { Lane.valueOf(it.uppercase()) } ?: Lane.PRODUCTION

            return Options(
                target =
                    Path(
                        positional.firstOrNull()
                            ?: if (lane == Lane.BETA) "api-surface-beta.tsv" else "api-surface.tsv"
                    ),
                checking = "--check" in args,
                lane = lane,
                registry = named["--registry"]?.let { Path(it) },
                report = named["--report"]?.let { Path(it) },
            )
        }
    }
}

private suspend fun collect(lane: Lane): String {
    // Politely slow, and read-only: this is somebody else's production wiki.
    val userAgent =
        UserAgent(
            "kwikibot-api-surface",
            "0.2.0",
            "https://en.wiktionary.org/wiki/User:Fenakhay",
        )

    val byWiki =
        WikiHttpClient.create().use { client ->
            lane.wikis.mapValues { (_, server) ->
                val transport =
                    KtorTransport(
                        client = client,
                        endpoint = ApiEndpoint(server = server),
                        userAgent = userAgent,
                        throttle = Throttle(read = 500.milliseconds),
                        // paraminfo describes how the wiki is set up, which replica lag does not change,
                        // so lag is no reason to hold it back. Left on, a lagging Wikidata fails the run.
                        maxlag = null,
                    )

                val modules = ParamInfo(transport).modules(*PATTERNS)
                System.err.println("$server: ${modules.size} modules")
                Surface.rows(modules)
            }
        }

    return Surface.render(Surface.merge(byWiki))
}
