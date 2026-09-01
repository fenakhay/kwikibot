package com.fenakhay.kwikibot.examples.compounds

import com.fenakhay.kwikibot.bot.BotPolicy
import com.fenakhay.kwikibot.bot.Edit
import com.fenakhay.kwikibot.bot.Progress
import com.fenakhay.kwikibot.bot.RunLog
import com.fenakhay.kwikibot.bot.StopPolicy
import com.fenakhay.kwikibot.bot.botRun
import com.fenakhay.kwikibot.bot.reportTo
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.Credentials
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import java.io.Writer
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What to print when the arguments are wrong. */
private const val USAGE = """
compounds — add derived terms to the entries of their components

  --todo PATH        A local copy of the todo list page to work from. Required.
  --contact URL      A URL wiki operators can reach you at. Required by policy.
  --save             Actually save edits. Without it the run computes edits and shows them.
  --limit N          Stop after this many pages.
  --diff-log PATH    Write unified diffs of the edits here.
  --skip-log PATH    Write a JSON Lines record of every page left alone here.
  --stop-page TITLE  Page that must say 'false' for the bot to run.

Credentials come from the environment, so that a password is not left in shell history or
visible in the process list: KWIKIBOT_ACCOUNT, KWIKIBOT_BOT_NAME, KWIKIBOT_PASSWORD. Without
all three the bot runs anonymously, which is enough for a dry run.
"""

/** The page that must say `false` before this bot will edit, unless `--stop-page` says otherwise. */
private const val DEFAULT_STOP_PAGE = "User:FenaBot/Stop"

/**
 * Adds the derived terms listed at `Wiktionary:Todo/compounds not linked to from components`
 * to the entries of their components.
 *
 * The bot is the plumbing between three pure pieces: [TodoList] reads the work list,
 * [DerivedTerms] computes each edit, and [Summaries] describes it. Everything else — reading
 * pages, pacing, retries, the stop check, the diffs and the skip log — belongs to the library.
 *
 * ```
 * compounds --todo todo.wikitext --contact https://en.wiktionary.org/wiki/User:FenaBot --limit 5
 * compounds --todo todo.wikitext --contact … --save --diff-log logs/diffs.log
 * ```
 *
 * The run is assembled here rather than inherited from a base class the library supplies, because
 * assembling it is the part of writing a bot worth seeing — and because a library should have an
 * opinion about editing wikis and none about how a program is started. kwikibot hands over the
 * pieces ([botRun], [BotPolicy], [RunLog], [Progress], [reportTo]); this decides how they fit and
 * owns its own command line.
 */
public suspend fun main(args: Array<String>) {
    val options = runCatching { Options.parse(args) }.getOrElse { failure ->
        System.err.println("compounds: ${failure.message}")
        System.err.println(USAGE)
        exitProcess(2)
    }

    val config = WikiConfig(
        userAgent = UserAgent("compounds-not-linked-bot", "0.1.0", options.contact),
        // The wiki's own guidance for bots: a read every tenth of a second, an edit every ten
        // seconds.
        throttle = Throttle(read = 100.milliseconds, write = 10.seconds),
    )

    WikiClient(config, credentials = credentialsFromEnvironment()).use { client ->
        val wiki = client.wiki(LangCode("en"), Family.WIKTIONARY)
        val report = run(wiki, options)
        println(report)
    }
}

/**
 * Reads the todo list, works through it, and returns what happened.
 *
 * The log files are opened around the run and closed however it ends, so an interrupted run still
 * leaves a readable diff log.
 */
private suspend fun run(wiki: Wiki, options: Options) =
    options.diffLog.writer().use { diffs ->
        options.skipLog.writer().use { skips ->
            val tasks = TodoList.parsePage(options.todo.readText()).tasks
            val progress = Progress(total = options.limit ?: tasks.size)

            wiki.botRun {
                // Each task names the entry to edit and the terms to add to it. Titles that
                // resolve off this wiki are refused by wiki.ref, which is the second of the two
                // gates the todo parser opens the first of.
                source(tasks.asFlow().map { wiki.ref(it.title) })

                transform { page ->
                    val task = tasks.first { it.title == page.title.text }

                    val result = DerivedTerms.add(
                        text = page.text,
                        title = task.title,
                        lang = task.lang,
                        terms = task.terms,
                    )

                    when (result.status) {
                        TransformResult.Status.SKIPPED -> skip(result.reason)
                        TransformResult.Status.UNCHANGED -> null
                        TransformResult.Status.CHANGED ->
                            Edit(result.text, Summaries.forEdit(result.added, result.rules))
                    }
                }

                // Writing is opt-in, so the first run of a changed bot shows what it would do
                // rather than doing it.
                dryRun = !options.save
                limit = options.limit

                // Honours {{nobots}} as this account. The builder cannot fill this in itself
                // because it does not know which account the session logged in as.
                exclusionPolicy = BotPolicy(wiki.identity.name)

                // Fail-closed: if the stop page cannot be read, the bot does not edit.
                stopPolicy = StopPolicy.page(wiki.pages, wiki.ref(options.stopPage))

                onOutcome = reportTo(RunLog(diffs = diffs, skips = skips), progress)
            }.also { progress.finish() }
        }
    }

/** What the command line asked for. */
private class Options(
    val todo: Path,
    val contact: String,
    val save: Boolean,
    val limit: Int?,
    val diffLog: Path?,
    val skipLog: Path?,
    val stopPage: String,
) {
    companion object {
        /**
         * Reads the arguments, or throws with what was wrong.
         *
         * Hand-written because the example is meant to be read end to end, and because a bot's
         * front end is its own business — kwikibot has an opinion about editing wikis and none
         * about how a program is invoked.
         */
        fun parse(args: Array<String>): Options {
            val values = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()
            var index = 0

            while (index < args.size) {
                val argument = args[index]
                require(argument.startsWith("--")) { "unexpected argument '$argument'" }

                if (argument == "--save") {
                    flags += argument
                    index++
                    continue
                }

                val value = args.getOrNull(index + 1)
                require(value != null && !value.startsWith("--")) { "$argument needs a value" }
                values[argument] = value
                index += 2
            }

            return Options(
                todo = Path(required(values, "--todo")),
                contact = required(values, "--contact"),
                save = "--save" in flags,
                limit = values["--limit"]?.let {
                    it.toIntOrNull() ?: throw IllegalArgumentException("--limit needs a number")
                },
                diffLog = values["--diff-log"]?.let(::Path),
                skipLog = values["--skip-log"]?.let(::Path),
                stopPage = values["--stop-page"] ?: DEFAULT_STOP_PAGE,
            )
        }

        private fun required(values: Map<String, String>, name: String): String =
            values[name] ?: throw IllegalArgumentException("$name is required")
    }
}

/**
 * Bot credentials from the environment, or anonymous.
 *
 * Anonymous is enough to read, which is enough for a dry run — so a first look at what the bot
 * would do needs no account at all.
 */
private fun credentialsFromEnvironment(): Credentials {
    val account = System.getenv("KWIKIBOT_ACCOUNT") ?: return Credentials.Anonymous
    val botName = System.getenv("KWIKIBOT_BOT_NAME") ?: return Credentials.Anonymous
    val password = System.getenv("KWIKIBOT_PASSWORD") ?: return Credentials.Anonymous
    return Credentials.BotPassword(account, botName, password)
}

/** Opens a log file, creating its directory, or returns `null` when none was asked for. */
private fun Path?.writer(): Writer? {
    if (this == null) return null
    createParentDirectories()
    return bufferedWriter()
}
