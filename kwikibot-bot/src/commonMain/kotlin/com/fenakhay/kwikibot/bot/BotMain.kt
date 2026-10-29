package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.environmentVariable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.writeString

/**
 * What [botMain] hands to a bot: the logged-in wiki and what the command line said.
 *
 * @param wiki the wiki `kwikibot.toml` names, opened as its account.
 * @param options the common flags.
 * @param arguments what the common flags did not take, in order, for the bot to read itself.
 * @param config the configuration the bot started from.
 */
public class BotStart(
    public val wiki: Wiki,
    public val options: CommonOptions,
    public val arguments: List<String>,
    public val config: BotConfig,
)

/**
 * Starts a bot: reads the common flags and `kwikibot.toml`, logs in, checks the account, runs [block] with
 * the wiki, and exits with the status [block] returns.
 *
 * On the JVM and natively this returns only by exiting. On JavaScript it starts the bot and returns at once;
 * Node exits with the status once [block] is done.
 *
 * [block] runs only once the account is logged in, unless [anonymous] allows an anonymous session, and with
 * `--save` only if the account is in the bot group, or `--trial` says it is saving the trial edits a wiki
 * asks for before it grants the bot flag. The account is printed on standard error. When the bot cannot
 * start, because no configuration was found, a password is not set, the wiki refused the login or a flag is
 * wrong, the reason is printed as one line on standard error and the exit status is 1, or 2 for a bad flag. A
 * failure [block] lets through is printed with its stack trace and also ends with status 1.
 *
 * ```
 * fun main(args: Array<String>) = botMain(args) {
 *     val report = wiki.botRun {
 *         options.applyTo(this, wiki)
 *         config.applyTo(this)
 *         source(wiki.lists.transclusions(wiki.ref("Template:old")))
 *         transform { page -> Edit(page.text.replace("{{old", "{{new"), "old → new") }
 *     }
 *     if (report.clean && !report.stopped) 0 else 1
 * }
 * ```
 *
 * @param args the command line, common flags and the bot's own.
 * @param anonymous whether the bot may run without logging in, when `kwikibot.toml` names no login. A bot
 *   that saves still may not.
 * @param block the bot, returning its exit status.
 */
public expect fun botMain(
    args: Array<String>,
    anonymous: Boolean = false,
    block: suspend BotStart.() -> Int,
)

/** What [startBot] reads from the machine it runs on, which a test replaces. */
internal class BotEnvironment(
    val findConfig: () -> BotConfig?,
    val variables: (String) -> String?,
    val errors: Sink,
    val open: suspend (BotConfig, WikiConfig, Credentials, suspend (Wiki) -> Int) -> Int,
) {
    companion object {
        val SYSTEM =
            BotEnvironment(
                findConfig = { BotConfig.find() },
                variables = ::environmentVariable,
                errors = standardError(),
                open = { config, wikiConfig, credentials, use ->
                    WikiClient(wikiConfig, credentials).use { use(config.connect(it)) }
                },
            )
    }
}

/** What [botMain] does before it exits, returning the exit status. */
internal suspend fun startBot(
    args: List<String>,
    anonymous: Boolean,
    environment: BotEnvironment,
    block: suspend BotStart.() -> Int,
): Int =
    try {
        val (options, arguments) = commonOptions(args)
        // A bot that saves always runs logged in.
        val mayBeAnonymous = anonymous && !options.save
        val config = configuration(environment)
        val credentials = credentials(config, environment, mayBeAnonymous)
        open(environment, config, options.applyTo(config.toWikiConfig()), credentials) { wiki ->
            checkAccount(wiki, options, mayBeAnonymous, environment.errors)
            BotStart(wiki, options, arguments, config).block()
        }
    } catch (e: StartFailed) {
        environment.errors.line(e.message)
        e.status
    }

/**
 * The status [botMain] exits with: what [startBot] returns, or for a failure it lets through, that failure's
 * stack trace on [BotEnvironment.errors] and [FAILED], as the JVM ends a process whose `main` throws. Every
 * platform goes through this, since on JavaScript a failed coroutine only reaches the console, and Node would
 * then exit with 0.
 */
internal suspend fun exitStatus(
    args: List<String>,
    anonymous: Boolean,
    environment: BotEnvironment,
    block: suspend BotStart.() -> Int,
): Int = runCatching {
    startBot(args, anonymous, environment, block)
}
    .getOrElse { failure ->
        if (failure is CancellationException) throw failure
        environment.errors.line(failure.stackTraceToString())
        FAILED
    }

/** Why the bot could not start, and the exit status for it. */
private class StartFailed(message: String, val status: Int = FAILED, cause: Throwable? = null) :
    Exception(message, cause)

private fun commonOptions(args: List<String>): Pair<CommonOptions, List<String>> =
    try {
        CommonOptions.parse(args)
    } catch (e: IllegalArgumentException) {
        throw StartFailed(e.message ?: "bad arguments", USAGE, e)
    }

private fun configuration(environment: BotEnvironment): BotConfig =
    runCatching { environment.findConfig() }.getOrElse { throw unreadable(it) }
        ?: throw StartFailed(
            "no ${BotConfig.FILE_NAME} found; looked in ${BotConfig.searchPath().joinToString(", ")}"
        )

/** The failure to report when the configuration cannot be read, or [e] itself if it is something else. */
private fun unreadable(e: Throwable): Throwable {
    val name = BotConfig.FILE_NAME
    return when (e) {
        is IllegalStateException -> StartFailed(e.message ?: "cannot read $name", cause = e)
        is IllegalArgumentException -> StartFailed("$name is not valid: ${e.message}", cause = e)
        is IOException -> StartFailed("cannot read $name: ${e.message}", cause = e)
        else -> e
    }
}

private fun credentials(
    config: BotConfig,
    environment: BotEnvironment,
    mayBeAnonymous: Boolean,
): Credentials {
    val credentials =
        try {
            config.credentials(environment.variables)
        } catch (e: IllegalStateException) {
            throw StartFailed(e.message ?: "cannot read the credentials", cause = e)
        }
    if (credentials == Credentials.Anonymous && !mayBeAnonymous) {
        throw StartFailed("${BotConfig.FILE_NAME} names no login, and this bot runs logged in")
    }
    return credentials
}

/**
 * Opens the wiki and hands it to [use]. A refused login or an unreachable wiki stops the start; the same
 * errors thrown from [use] are left to the bot.
 */
private suspend fun open(
    environment: BotEnvironment,
    config: BotConfig,
    wikiConfig: WikiConfig,
    credentials: Credentials,
    use: suspend (Wiki) -> Int,
): Int {
    var opened = false
    return try {
        environment.open(config, wikiConfig, credentials) { wiki ->
            opened = true
            use(wiki)
        }
    } catch (e: WikiError) {
        if (opened || (e !is WikiError.Auth && e !is WikiError.Transport)) throw e
        val failure = if (e is WikiError.Auth) "could not log in" else "could not reach the wiki"
        throw StartFailed("$failure: ${e.message}", cause = e)
    }
}

/** Refuses an account the bot may not run as, and says on [errors] which one it runs as. */
private fun checkAccount(wiki: Wiki, options: CommonOptions, mayBeAnonymous: Boolean, errors: Sink) {
    val identity = wiki.identity
    if (identity.isAnonymous) {
        if (!mayBeAnonymous) throw StartFailed("not logged in: the wiki sees an anonymous user")
        errors.line("not logged in")
        return
    }
    if (options.save && !identity.isBot && !options.trial) {
        throw StartFailed("${identity.name} is not in the bot group; not saving without --trial")
    }
    errors.line("logged in as ${identity.name} (${identity.groups.sorted().joinToString(", ")})")
    if (options.save && !identity.isBot) {
        errors.line("trial: saving without the bot flag, at most ${options.limit} pages")
    }
}

private const val FAILED = 1
private const val USAGE = 2

/** Writes [text] as one line and flushes it, so it shows before whatever the bot prints next. */
private fun Sink.line(text: String?) {
    writeString(text.orEmpty())
    writeString("\n")
    flush()
}
