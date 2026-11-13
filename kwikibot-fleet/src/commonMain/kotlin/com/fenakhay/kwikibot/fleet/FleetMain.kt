package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.bot.BotConfig
import com.fenakhay.kwikibot.bot.BotStart
import com.fenakhay.kwikibot.bot.Prompt
import com.fenakhay.kwikibot.bot.TerminalPrompt
import com.fenakhay.kwikibot.bot.botMain
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.environmentVariable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val USAGE = "usage: <program> list | run <bot> [flags] | run --all [flags] | serve [flags]"

private const val ALL = "--all"
private const val BAD_USAGE = 2
private const val FAILED = 1

/**
 * Starts a program of many bots: `list`, `run <bot>`, `run --all`, or `serve`, which keeps running the ones
 * with a schedule.
 *
 * Reads the common flags and `kwikibot.toml` as `botMain` does. A bot naming an account of its own logs in
 * with the credentials under `KWIKIBOT_<ACCOUNT>_`, as [Accounts.namedCredentials] reads them, and its run
 * fails if they are not there. A run told to save needs an account in the bot group, or `--trial`. The stop
 * page is `--stop-page`, or else `User:<account>/Stop`.
 *
 * ```
 * fun main(args: Array<String>) = fleetMain(args, listOf(OldTemplateBot(), NewsletterBot()))
 * ```
 *
 * It exits with:
 *
 * |Status|When                                                                                            |
 * |------|------------------------------------------------------------------------------------------------|
 * |0     |`list`, or every run ended [RunStatus.DONE] and none said it [stopped early][RunContext.stopped]|
 * |1     |a run ended [RunStatus.FAILED], [RunStatus.CANCELLED] or [RunStatus.DECLINED], or stopped early |
 * |1     |the bot could not start                                                                         |
 * |2     |a bad command line                                                                              |
 *
 * Each run counted against it gets a line saying how it ended and why. A run somebody declined is not a fault
 * and is reported as what it is, but what it was asked to do did not happen, which is what a script calling
 * this needs to know.
 *
 * `--all` means every bot only straight after `run`, on its own. After a bot's name it is that bot's own
 * argument, and anywhere else it is a bad command line.
 *
 * A question parks only the lane that asked, with one on screen at a time.
 *
 * @param args the command line.
 * @param bots the bots.
 * @param prompt who answers a run's question, which is the terminal by default.
 */
public fun fleetMain(args: Array<String>, bots: List<Bot>, prompt: Prompt = TerminalPrompt()) {
    botMain(args, anonymous = true) { runCommand(bots, prompt) }
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal suspend fun BotStart.runCommand(
    bots: List<Bot>,
    prompt: Prompt,
    out: (String) -> Unit = ::println,
    accounts: Accounts =
        Accounts(
            config.toWikiConfig().let(options::applyTo),
            credentials = { account -> credentialsFor(config, account) },
        ),
): Int {
    val command = arguments.firstOrNull()
    val rest = arguments.drop(1)
    val all = rest == listOf(ALL)
    val understood =
        when (command) {
            "list",
            "serve" -> rest.isEmpty()
            "run" -> all || rest.firstOrNull().let { it != null && it != ALL }
            else -> false
        }
    if (!understood) {
        out(USAGE)
        return BAD_USAGE
    }
    if (command == "list") {
        BotRegistry(bots).all.forEach { bot -> out("${bot.id}: ${bot.description}") }
        return 0
    }
    if (command == "run" && !all && BotRegistry(bots).find(rest.first()) == null) {
        out("there is no bot '${rest.first()}'; there are: ${bots.map { it.id }.sorted()}")
        return BAD_USAGE
    }

    val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
    val stopPage = options.stopPage ?: "User:${wiki.identity.name}/Stop"
    val oneAtATime = Mutex()
    val fleet =
        Fleet(
            bots,
            accounts,
            config.endpoint(),
            SharedStop.onPage(wiki, stopPage),
            scope,
            questions = { bot, question ->
                oneAtATime.withLock {
                    prompt.confirm("[$bot] ${question.title} ${question.summary}".trim())
                }
            },
            out = out,
        )

    try {
        if (command == "serve") {
            fleet.serve(options)
            return 0
        }
        if (all) {
            bots.forEach {
                val _ = fleet.start(it.id, options)
            }
        } else {
            val _ = fleet.start(rest.first(), options, rest.drop(1))
        }
        fleet.awaitIdle()
        val unclean = fleet.runs.value.filter { it.status != RunStatus.DONE || it.stoppedBecause != null }
        unclean.forEach { run ->
            val why = run.failure ?: run.stoppedBecause
            out("[${run.botId}] ended ${run.status}${why?.let { ": $it" }.orEmpty()}")
        }
        return if (unclean.isEmpty()) 0 else FAILED
    } finally {
        scope.cancel()
        accounts.close()
    }
}

/**
 * The credentials of [account]: what `kwikibot.toml` describes for the default one, and for a named one only
 * what the environment holds under its own name, since the file's login belongs to the default account.
 */
internal fun credentialsFor(
    config: BotConfig,
    account: String?,
    read: (String) -> String? = ::environmentVariable,
): Credentials = if (account == null) config.credentials(read) else Accounts.namedCredentials(account, read)
