package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.bot.run.BotRunBuilder
import com.fenakhay.kwikibot.bot.run.RunLog
import com.fenakhay.kwikibot.bot.run.RunState
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.net.Throttle
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.time.Duration

/**
 * The flags most bots take, read from their arguments without a command-line library.
 *
 * These say whether to save, how far to go, where to log and which page stops the bot. Whatever else is on
 * the command line is handed back, so a bot keeps its own flags and its own way of reading them.
 *
 * ```
 * fun main(args: Array<String>) = runBlocking {
 *     val (options, rest) = CommonOptions.parse(args)
 *     val config = options.applyTo(BotConfig.find()!!.toWikiConfig())
 *     WikiClient(config, Credentials.fromEnvironment()).use { client ->
 *         val wiki = client.wiki(LangCode("en"), Family.WIKTIONARY)
 *         wiki.botRun {
 *             options.applyTo(this, wiki)
 *             onOutcome = options.runLog(wiki)
 *             …
 *         }
 *     }
 * }
 * ```
 *
 * @param save whether to save edits, `--save`. A run is a dry run without it.
 * @param limit how many pages to work through at most, `--limit`.
 * @param diffLog where to write diffs, `--diff-log`.
 * @param skipLog where to write the pages left alone, `--skip-log`.
 * @param stopPage the page that stops the bot when it says anything but `false`, `--stop-page`.
 * @param readDelay the least time between reads, `--read-delay 100ms`.
 * @param writeDelay the least time between writes, `--write-delay 10s`.
 * @param httpConnections how many requests may be in flight at once, `--http-connections`.
 * @param contact where an operator can be reached, for the user agent, `--contact`.
 * @param state where the run records what it has done, `--state`.
 * @param resume whether to carry on from where a run into [state] stopped, `--resume`.
 */
public data class CommonOptions(
    val save: Boolean = false,
    val limit: Int? = null,
    val diffLog: Path? = null,
    val skipLog: Path? = null,
    val stopPage: String? = null,
    val readDelay: Duration? = null,
    val writeDelay: Duration? = null,
    val httpConnections: Int? = null,
    val contact: String? = null,
    val state: Path? = null,
    val resume: Boolean = false,
) {
    init {
        require(!resume || state != null) { "--resume needs --state, the directory to resume from" }
    }

    /** [config] with what these flags change about the client: its pace, its connections and its contact. */
    public fun applyTo(config: WikiConfig): WikiConfig {
        val throttle =
            if (readDelay == null && writeDelay == null) {
                config.throttle
            } else {
                Throttle(
                    read = readDelay ?: config.throttle.read,
                    write = writeDelay ?: config.throttle.write,
                )
            }
        val http =
            httpConnections?.let { config.http.copy(maxRequestsPerHost = it, maxIdleConnections = it) }
                ?: config.http
        val userAgent = contact?.let { config.userAgent.copy(contact = it) } ?: config.userAgent
        return config.copy(throttle = throttle, http = http, userAgent = userAgent)
    }

    /**
     * Applies these flags to a run on [wiki]: whether it saves, how far it goes, its stop page and its state.
     *
     * The state is opened here and flushed as it is written, so it needs no closing for a bot that runs once
     * and exits.
     */
    public fun applyTo(builder: BotRunBuilder, wiki: Wiki) {
        builder.dryRun = !save
        limit?.let { builder.limit = it }
        stopPage?.let { builder.stopPolicy = StopPolicy.page(wiki.pages, wiki.ref(it)) }
        state?.let {
            builder.state = RunState(it)
            builder.resume = resume
        }
    }

    /**
     * A log writing diffs to [diffLog] and skips to [skipLog], added to whatever is already in them.
     *
     * Each record is flushed as it is written, so the files are complete at any moment the bot stops.
     */
    public fun runLog(wiki: Wiki): RunLog =
        RunLog(diffs = diffLog?.let(::append), skips = skipLog?.let(::append), namespaces = wiki.namespaces)

    private fun append(path: Path): Writer {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        return Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    /** Reading the flags. */
    public companion object {
        /** The flags, one per line, for a bot to print in its own help. */
        public val usage: String =
            """
            --save                  save edits; without it a run only shows what it would do
            --limit N               work through at most N pages
            --diff-log FILE         write the diff of each edit to FILE
            --skip-log FILE         write each page left alone, and why, to FILE
            --stop-page TITLE       stop when TITLE says anything but "false"
            --read-delay DURATION   wait at least this long between reads: 100ms
            --write-delay DURATION  wait at least this long between writes: 10s
            --http-connections N    let N requests be in flight at once
            --contact URL           where an operator can be reached, for the user agent
            --state DIR             record what the run does in DIR
            --resume                carry on from where the run recorded in --state stopped
            """
                .trimIndent()

        /** The flags that take nothing after them. */
        private val SWITCHES: Map<String, (CommonOptions) -> CommonOptions> =
            mapOf("--save" to { it.copy(save = true) })

        /** The flags that take a value, and how each reads it. */
        private val VALUES: Map<String, (CommonOptions, String) -> CommonOptions> =
            mapOf(
                "--limit" to { options, value -> options.copy(limit = positive("--limit", value)) },
                "--diff-log" to { options, value -> options.copy(diffLog = Path(value)) },
                "--skip-log" to { options, value -> options.copy(skipLog = Path(value)) },
                "--stop-page" to { options, value -> options.copy(stopPage = value) },
                "--read-delay" to
                    { options, value ->
                        options.copy(readDelay = duration("--read-delay", value))
                    },
                "--write-delay" to
                    { options, value ->
                        options.copy(writeDelay = duration("--write-delay", value))
                    },
                "--http-connections" to
                    { options, value ->
                        options.copy(httpConnections = positive("--http-connections", value))
                    },
                "--contact" to { options, value -> options.copy(contact = value) },
                "--state" to { options, value -> options.copy(state = Path(value)) },
            )

        /**
         * Reads the common flags out of [args], and hands back everything else in the order it came.
         *
         * A flag's value may follow it or be joined to it with `=`, `--limit 10` or `--limit=10`. Everything
         * after `--` is handed back untouched.
         *
         * @throws IllegalArgumentException naming the flag, when one is missing its value or has a bad one.
         */
        public fun parse(args: List<String>): Pair<CommonOptions, List<String>> {
            // `--resume` is held back until the end, since it is only valid once `--state` has been read.
            var options = CommonOptions()
            var resume = false
            val rest = mutableListOf<String>()
            var index = 0

            while (index < args.size) {
                val arg = args[index]
                val name = arg.substringBefore('=')
                val joined = if ('=' in arg) arg.substringAfter('=') else null
                val switch = SWITCHES[arg]
                val valued = VALUES[name]
                when {
                    arg == "--" -> {
                        rest += args.subList(index + 1, args.size)
                        index = args.size
                    }
                    arg == "--resume" -> resume = true
                    switch != null -> options = switch(options)
                    valued != null -> {
                        val value =
                            joined
                                ?: args.getOrNull(++index)
                                ?: throw IllegalArgumentException("$name needs a value")
                        options = valued(options, value)
                    }
                    else -> rest += arg
                }
                index++
            }

            return options.copy(resume = resume) to rest
        }

        /** Reads the common flags out of [args]. */
        public fun parse(args: Array<String>): Pair<CommonOptions, List<String>> = parse(args.toList())

        private fun positive(flag: String, value: String): Int =
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("$flag needs a positive number, not '$value'")

        private fun duration(flag: String, value: String): Duration = runCatching {
            Duration.parse(value)
        }
            .getOrElse {
                throw IllegalArgumentException("$flag needs a duration such as 100ms or 10s, not '$value'")
            }
    }
}
