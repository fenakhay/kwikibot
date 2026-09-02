package com.fenakhay.kwikibot.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.fenakhay.kwikibot.bot.BotConfig
import com.fenakhay.kwikibot.client.ApiDetector
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.Credentials
import com.fenakhay.kwikibot.net.Kwikibot
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.WikiHttpClient
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * The `kwikibot` command-line tool.
 *
 * Deliberately small: enough to check that credentials work and to look at a page, which is what
 * is needed to tell whether a misbehaving bot or the wiki is at fault.
 */
public class Kwikibot : SuspendingCliktCommand(name = "kwikibot") {
    override fun help(context: Context): String = "Talk to a MediaWiki wiki."

    override suspend fun run(): Unit = Unit
}

/** Options shared by every subcommand that opens a wiki. */
public abstract class WikiCommand(name: String) : SuspendingCliktCommand(name = name) {

    private val lang: String? by option(
        "--lang",
        help = "Language code of the wiki, such as 'en'. Defaults to what the config says.",
    )

    private val family: String? by option(
        "--family",
        help = "Project family: wiktionary, wikipedia, commons, wikidata, test…",
    )

    private val contact: String? by option(
        "--contact",
        help = "A URL or address wiki operators can reach you at. Required by policy.",
    )

    private val configPath: Path? by option(
        "--config",
        help = "Configuration file. Defaults to the first kwikibot.toml on the search path.",
    ).path()

    private val account: String? by option(
        "--account",
        help = "Account name, for actions that need a login.",
    )

    private val botName: String? by option(
        "--bot-name",
        help = "Bot password name from Special:BotPasswords.",
    )

    private val password: String? by option(
        "--password",
        help = "Bot password. Prefer the KWIKIBOT_PASSWORD environment variable.",
        envvar = "KWIKIBOT_PASSWORD",
    )

    /** Does the work, with the wiki already open. */
    protected abstract suspend fun run(wiki: Wiki)

    final override suspend fun run() {
        // The file supplies what the flags do not, so a configured checkout needs no flags and
        // a one-off invocation needs no file.
        val file = BotConfig.find(configPath)
        val config = wikiConfig(file)

        // A misconfigured bot is an ordinary outcome of running a command-line tool, so it is
        // reported as a message. A stack trace here would say nothing the message does not.
        val credentials = try {
            credentials(file)
        } catch (e: IllegalStateException) {
            throw CliktError(e.message ?: "credentials are not configured", e)
        }

        WikiClient(config, credentials = credentials).use { client ->
            try {
                run(client.wiki(language(file), resolveFamily(file)))
            } catch (e: WikiError) {
                throw CliktError(e.message ?: e.toString(), e)
            }
        }
    }

    /** Flags win over the file, and the file wins over editing anonymously. */
    private fun credentials(file: BotConfig?): Credentials {
        val account = account
        val botName = botName
        val password = password

        if (account != null && botName != null && password != null) {
            return Credentials.BotPassword(account, botName, password)
        }
        return file?.credentials() ?: Credentials.Anonymous
    }

    /** What the file says, overridden by the flags, or the flags alone. */
    private fun wikiConfig(file: BotConfig?): WikiConfig {
        file?.toWikiConfig()?.let { fromFile ->
            return contact?.let { fromFile.copy(userAgent = UserAgent("kwikibot", VERSION, it)) }
                ?: fromFile
        }

        val given = contact ?: throw CliktError(
            "no configuration found and no --contact given; run 'kwikibot init-config' " +
                "or pass --contact",
        )
        return WikiConfig(userAgent = UserAgent("kwikibot", VERSION, given))
    }

    private fun language(file: BotConfig?): LangCode =
        if (lang != null) LangCode(lang!!) else file?.language() ?: LangCode("en")

    private fun resolveFamily(file: BotConfig?): Family {
        val named = family ?: return file?.family() ?: Family.WIKTIONARY
        return Family.named(named) ?: throw CliktError("unknown family: $named")
    }

    private companion object {
        const val VERSION = "1.0.0"
    }
}

/** Reports who the wiki thinks we are — the first thing to check when a bot cannot edit. */
public class WhoAmI : WikiCommand("whoami") {
    override fun help(context: Context): String = "Show the account the wiki sees."

    override suspend fun run(wiki: Wiki) {
        val identity = wiki.identity
        echo("wiki:   ${wiki.id} (${wiki.info.siteName}, MediaWiki ${wiki.info.version})")
        echo("user:   ${identity.name}${if (identity.isAnonymous) " (anonymous)" else ""}")
        echo("groups: ${identity.groups.sorted().joinToString(", ").ifEmpty { "none" }}")
        echo("bot:    ${identity.isBot}")
    }
}

/** Prints a page's wikitext, so a bot's input can be inspected without a browser. */
public class GetPage : WikiCommand("get") {
    override fun help(context: Context): String = "Print the wikitext of a page."

    private val title: String by argument(help = "Page title.")

    override suspend fun run(wiki: Wiki) {
        val content = wiki.pages.content(wiki.ref(title))
        if (content == null) {
            echo("no such page: $title", err = true)
            return
        }
        echo(content.text)
    }
}

/**
 * Writes a configuration file to start from.
 *
 * The template holds no password: it names an environment variable to read one from, so the file
 * can be committed and shared without leaking a credential.
 */
public class InitConfig : SuspendingCliktCommand(name = "init-config") {
    override fun help(context: Context): String = "Write a starter kwikibot.toml."

    private val out: Path by option("--out", help = "Where to write it.")
        .path()
        .default(Path.of(BotConfig.FILE_NAME))

    private val force: Boolean by option("--force", help = "Overwrite an existing file.").flag()

    override suspend fun run() {
        if (out.exists() && !force) {
            echo("$out already exists; pass --force to overwrite it", err = true)
            return
        }

        out.writeText(BotConfig.template())
        echo("wrote $out")
        echo("Set the password with: export KWIKIBOT_PASSWORD=...")
    }
}

/** Shows where the configuration was found and what it says, without the password. */
public class ShowConfig : SuspendingCliktCommand(name = "config") {
    override fun help(context: Context): String = "Show the configuration in effect."

    override suspend fun run() {
        val found = BotConfig.searchPath().firstOrNull { it.exists() }
        if (found == null) {
            echo("no configuration found. Looked in:", err = true)
            BotConfig.searchPath().forEach { echo("  $it", err = true) }
            return
        }

        val config = BotConfig.read(found)
        echo("file:      $found")
        echo("bot:       ${config.bot.name}/${config.bot.version} (${config.bot.contact})")
        echo("wiki:      ${config.wiki.lang}.${config.wiki.family}")
        echo("throttle:  read ${config.throttle.read}, write ${config.throttle.write}")
        echo("maxlag:    ${config.maxlag.takeIf { it > 0 } ?: "not sent"}")

        val login = config.login
        if (login == null) {
            echo("login:     anonymous")
        } else {
            // Whether the variable is set, never what is in it.
            val present = System.getenv(login.passwordEnv) != null
            echo("login:     ${login.account}@${login.botName}")
            echo("password:  ${login.passwordEnv} is ${if (present) "set" else "NOT SET"}")
        }
    }
}

/**
 * Finds a wiki's API from any of its pages.
 *
 * Third-party wikis put MediaWiki wherever they like, and every install advertises where in the
 * page head. This reads that rather than guessing.
 */
public class Detect : SuspendingCliktCommand(name = "detect") {
    override fun help(context: Context): String = "Find the api.php of the wiki at a URL."

    private val url: String by argument(help = "Any page of the wiki.")

    private val contact: String by option(
        "--contact",
        help = "A URL or address wiki operators can reach you at.",
    ).default("https://github.com/fenakhay/kwikibot")

    override suspend fun run() {
        val userAgent = UserAgent("kwikibot", VERSION, contact)
        val client = WikiHttpClient.create()

        try {
            val endpoint = ApiDetector.detect(url, client, userAgent)
            if (endpoint == null) {
                echo("no MediaWiki API advertised at $url", err = true)
                return
            }
            echo("api:        ${endpoint.apiUrl}")
            echo("server:     ${endpoint.server}")
            echo("scriptPath: ${endpoint.scriptPath.ifEmpty { "(none)" }}")
        } finally {
            client.close()
        }
    }

    private companion object {
        const val VERSION = "1.0.0"
    }
}

/** Checks that the configured credentials actually log in. */
public class Login : WikiCommand("login") {
    override fun help(context: Context): String = "Log in and report the result."

    override suspend fun run(wiki: Wiki) {
        val identity = wiki.identity
        if (identity.isAnonymous) {
            echo("not logged in: no credentials were configured", err = true)
            return
        }
        echo("logged in to ${wiki.id} as ${identity.name}")
        echo("groups: ${identity.groups.sorted().joinToString(", ").ifEmpty { "none" }}")
    }
}

/**
 * Reports the library version and what it is running on.
 *
 * The first thing to paste into a bug report, and deliberately nothing more than that: not the
 * machine name, not the working directory, not the user.
 */
public class Version : SuspendingCliktCommand(name = "version") {
    override fun help(context: Context): String = "Show the library and runtime versions."

    override suspend fun run() {
        echo(Kwikibot.report().trimEnd())
    }
}

/** Runs the `kwikibot` command. */
public suspend fun main(args: Array<String>) {
    Kwikibot()
        .subcommands(
            WhoAmI(),
            GetPage(),
            Login(),
            InitConfig(),
            ShowConfig(),
            Detect(),
            Version(),
        )
        .main(args)
}
