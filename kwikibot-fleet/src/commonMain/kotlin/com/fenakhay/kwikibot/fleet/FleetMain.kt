package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.bot.BotStart
import com.fenakhay.kwikibot.bot.Prompt
import com.fenakhay.kwikibot.bot.TerminalPrompt
import com.fenakhay.kwikibot.bot.botMain
import com.fenakhay.kwikibot.net.environmentVariable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext

private const val USAGE = "usage: <program> list | run <bot> [flags] | run --all [flags] | serve [flags]"

private const val BAD_USAGE = 2
private const val FAILED = 1

/**
 * Starts a program of many bots: `list`, `run <bot>`, `run --all`, or `serve`, which keeps running the ones
 * with a schedule.
 *
 * Reads the common flags and `kwikibot.toml` as `botMain` does. A bot naming an account of its own logs in
 * with the credentials under `KWIKIBOT_<ACCOUNT>_`. The stop page is `--stop-page`, or else
 * `User:<account>/Stop`.
 *
 * ```
 * fun main(args: Array<String>) = fleetMain(args, listOf(NullEditBot(), NewsletterBot()))
 * ```
 *
 * Exits with 0 only if every run finished, with 1 if one did not, and with 2 for a bad command line.
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
            credentials = { account -> config.credentials(accountEnvironment(account)) },
        ),
): Int {
    val command = arguments.firstOrNull()
    if (command == "list") {
        BotRegistry(bots).all.forEach { bot -> out("${bot.id}: ${bot.description}") }
        return 0
    }

    val rest = arguments.drop(1)
    val all = "--all" in rest
    val named = rest.filterNot { it == "--all" }
    val isRun = command == "run" && (all || named.isNotEmpty())
    if (!isRun && command != "serve") {
        out(USAGE)
        return BAD_USAGE
    }
    if (command == "run" && !all && BotRegistry(bots).find(named.first()) == null) {
        out("there is no bot '${named.first()}'; there are: ${bots.map { it.id }.sorted()}")
        return BAD_USAGE
    }

    val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
    val stopPage = options.stopPage ?: "User:${wiki.identity.name}/Stop"
    val fleet =
        Fleet(
            bots,
            accounts,
            config.endpoint(),
            SharedStop.onPage(wiki, stopPage),
            scope,
            questions = { bot, question ->
                prompt.confirm("[$bot] ${question.title} ${question.summary}".trim())
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
            val _ = fleet.start(named.first(), options, named.drop(1))
        }
        fleet.awaitIdle()
        val unfinished = fleet.runs.value.filter { it.status != RunStatus.DONE }
        unfinished.forEach {
            out("[${it.botId}] ended ${it.status}${it.failure?.let { f -> ": $f" }.orEmpty()}")
        }
        return if (unfinished.isEmpty()) 0 else FAILED
    } finally {
        scope.cancel()
        accounts.close()
    }
}

/** What [read] holds for [account], under `KWIKIBOT_<ACCOUNT>_` rather than `KWIKIBOT_`. */
internal fun accountEnvironment(
    account: String?,
    read: (String) -> String? = ::environmentVariable,
): (String) -> String? = { name ->
    read(if (account == null) name else name.replaceFirst("KWIKIBOT_", "KWIKIBOT_${account.uppercase()}_"))
}
