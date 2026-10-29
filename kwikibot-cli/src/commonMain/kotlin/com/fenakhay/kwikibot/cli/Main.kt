package com.fenakhay.kwikibot.cli

import com.fenakhay.kwikibot.bot.BotConfig
import com.fenakhay.kwikibot.client.ApiDetector
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.Kwikibot
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/**
 * The `kwikibot` command-line tool.
 *
 * Deliberately small: enough to check that credentials work and to look at a page, which is what is needed to
 * tell whether a misbehaving bot or the wiki is at fault.
 */
internal class Kwikibot : SuspendingCliktCommand(name = "kwikibot") {
    override fun help(context: Context): String = "Talk to a MediaWiki wiki."

    override suspend fun run(): Unit = Unit
}

/**
 * What the commands read from the machine they run on, which a test replaces.
 *
 * Environment variables are read through Clikt instead, whose context a test can set as well.
 *
 * @param searchPath where a configuration is looked for when `--config` names none, in order.
 * @param engine the HTTP engine wikis are reached through, or `null` for the one on the classpath.
 */
internal class CliEnvironment(
    val searchPath: () -> List<Path>,
    val engine: HttpClientEngine? = null,
) {
    /** The first configuration on [searchPath] that exists, or `null` if none does. */
    fun configFile(): Path? = searchPath().firstOrNull { SystemFileSystem.exists(it) }

    companion object {
        /** The machine's own: the library's search path, and the network. */
        val SYSTEM = CliEnvironment(searchPath = BotConfig::searchPath)
    }
}

/** Options shared by every subcommand that opens a wiki. */
internal abstract class WikiCommand(name: String, private val environment: CliEnvironment) :
    SuspendingCliktCommand(name = name) {

    private val lang: String? by
        option(
            "--lang",
            help = "Language code of the wiki, such as 'en'. Defaults to what the config says.",
        )

    private val family: String? by
        option(
            "--family",
            help = "Project family: wiktionary, wikipedia, commons, wikidata, test…",
        )

    private val contact: String? by
        option(
            "--contact",
            help = "A URL or address wiki operators can reach you at. Required by policy.",
        )

    private val configPath: Path? by
        option(
                "--config",
                help = "Configuration file. Defaults to the first kwikibot.toml on the search path.",
            )
            .convert { Path(it) }

    private val account: String? by
        option(
            "--account",
            help = "Account name, for actions that need a login.",
        )

    private val botName: String? by
        option(
            "--bot-name",
            help = "Bot password name from Special:BotPasswords.",
        )

    private val password: String? by
        option(
            "--password",
            help = "Bot password. Prefer the KWIKIBOT_PASSWORD environment variable.",
            envvar = "KWIKIBOT_PASSWORD",
        )

    /** Does the work, with the wiki already open. */
    protected abstract suspend fun run(wiki: Wiki)

    final override suspend fun run() {
        // The file supplies what the flags do not, so a configured checkout needs no flags and
        // a one-off invocation needs no file.
        val file = (configPath ?: environment.configFile())?.let(::readConfiguration)
        val config = reportingMisconfiguration { wikiConfig(file) }
        val credentials = reportingMisconfiguration { credentials(file) }
        val endpoint = reportingMisconfiguration { endpoint(file) }

        WikiClient(config, credentials, engine = environment.engine).use { client ->
            try {
                run(client.wiki(endpoint))
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
        return file?.credentials(currentContext.readEnvvar) ?: Credentials.Anonymous
    }

    /** What the file says, overridden by the flags, or the flags alone. */
    private fun wikiConfig(file: BotConfig?): WikiConfig {
        file?.toWikiConfig()?.let { fromFile ->
            return contact?.let { fromFile.copy(userAgent = userAgent(it)) } ?: fromFile
        }

        val given =
            contact
                ?: throw CliktError(
                    "no configuration found and no --contact given; run 'kwikibot init-config' " +
                        "or pass --contact"
                )
        return WikiConfig(userAgent = userAgent(given))
    }

    /**
     * Where the wiki is: a language or family flag wins, then the file, which may name a server of its own.
     */
    private fun endpoint(file: BotConfig?): ApiEndpoint =
        if (lang == null && family == null && file != null) {
            file.endpoint()
        } else {
            resolveFamily(file).endpoint(language(file))
        }

    private fun language(file: BotConfig?): LangCode =
        if (lang != null) LangCode(lang!!) else file?.language() ?: LangCode("en")

    private fun resolveFamily(file: BotConfig?): Family {
        val named = family ?: return file?.family() ?: Family.WIKTIONARY
        return Family.named(named) ?: throw CliktError("unknown family: $named")
    }
}

/** Reports who the wiki thinks we are — the first thing to check when a bot cannot edit. */
internal class WhoAmI(environment: CliEnvironment) : WikiCommand("whoami", environment) {
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
internal class GetPage(environment: CliEnvironment) : WikiCommand("get", environment) {
    override fun help(context: Context): String = "Print the wikitext of a page."

    private val title: String by argument(help = "Page title.")

    override suspend fun run(wiki: Wiki) {
        val content = wiki.pages.content(wiki.ref(title)) ?: throw CliktError("no such page: $title")
        echo(content.text)
    }
}

/**
 * Writes a configuration file to start from.
 *
 * The template holds no password: it names an environment variable to read one from, so the file can be
 * committed and shared without leaking a credential.
 */
internal class InitConfig : SuspendingCliktCommand(name = "init-config") {
    override fun help(context: Context): String = "Write a starter kwikibot.toml."

    private val out: Path by
        option("--out", help = "Where to write it.").convert { Path(it) }.default(Path(BotConfig.FILE_NAME))

    private val force: Boolean by option("--force", help = "Overwrite an existing file.").flag()

    override suspend fun run() {
        if (SystemFileSystem.exists(out) && !force) {
            throw CliktError("$out already exists; pass --force to overwrite it")
        }

        SystemFileSystem.sink(out).buffered().use { it.writeString(BotConfig.template()) }
        echo("wrote $out")
        echo("Set the password with: export KWIKIBOT_PASSWORD=...")
    }
}

/** Shows where the configuration was found and what it says, without the password. */
internal class ShowConfig(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "config") {
    override fun help(context: Context): String = "Show the configuration in effect."

    override suspend fun run() {
        val found =
            environment.configFile()
                ?: throw CliktError(
                    environment.searchPath().joinToString("\n", "no configuration found. Looked in:\n") {
                        "  $it"
                    }
                )

        val config = readConfiguration(found)
        echo("file:      $found")
        echo("bot:       ${config.bot.name}/${config.bot.version} (${config.bot.contact})")
        echo("wiki:      ${wiki(config.wiki)}")
        echo("throttle:  read ${config.throttle.read}, write ${config.throttle.write}")
        echo("maxlag:    ${config.maxlag.takeIf { it > 0 } ?: "not sent"}")
        echo(
            "http:      ${config.http.maxConcurrentRequests} requests at once, timeout ${config.http.timeout}"
        )
        echo("relogins:  ${config.retry.relogins} in ten minutes")
        run(config.run)?.let { echo("run:       $it") }
        config.oauth?.let { echo("oauth:     ${secret(it.tokenEnv, it.optional)}") }

        val login = config.login
        if (login == null) {
            echo("login:     anonymous")
        } else {
            echo("login:     ${login.account}@${login.botName}")
            echo("password:  ${secret(login.passwordEnv, login.optional)}")
        }
    }

    private fun wiki(wiki: BotConfig.WikiSelection): String =
        wiki.server?.let { it + wiki.scriptPath } ?: "${wiki.lang}.${wiki.family}"

    private fun run(run: BotConfig.RunSettings): String? =
        listOfNotNull(
                run.readConcurrency?.let { "read $it at once" },
                run.writeConcurrency?.let { "write $it at once" },
                run.readBatch?.let { "$it pages a request" },
            )
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

    /**
     * Whether the variable holding a secret is set, never what is in it. A blank one is not set, as logging
     * in treats it.
     */
    private fun secret(variable: String, optional: Boolean): String {
        val present = !currentContext.readEnvvar(variable).isNullOrBlank()
        return "$variable is ${if (present) "set" else "NOT SET"}${if (optional) " (optional)" else ""}"
    }
}

/**
 * Finds a wiki's API from any of its pages.
 *
 * Third-party wikis put MediaWiki wherever they like, and every install advertises where in the page head.
 * This reads that rather than guessing.
 */
internal class Detect(private val environment: CliEnvironment) : SuspendingCliktCommand(name = "detect") {
    override fun help(context: Context): String = "Find the api.php of the wiki at a URL."

    private val url: String by argument(help = "Any page of the wiki.")

    private val contact: String by
        option(
                "--contact",
                help = "A URL or address wiki operators can reach you at.",
            )
            .default("https://github.com/fenakhay/kwikibot")

    override suspend fun run() {
        val endpoint =
            WikiHttpClient.create(engine = environment.engine).use { client ->
                ApiDetector.detect(url, client, userAgent(contact))
            } ?: throw CliktError("no MediaWiki API advertised at $url")

        echo("api:        ${endpoint.apiUrl}")
        echo("server:     ${endpoint.server}")
        echo("scriptPath: ${endpoint.scriptPath.ifEmpty { "(none)" }}")
    }
}

/** Checks that the configured credentials actually log in. */
internal class Login(environment: CliEnvironment) : WikiCommand("login", environment) {
    override fun help(context: Context): String = "Log in and report the result."

    override suspend fun run(wiki: Wiki) {
        val identity = wiki.identity
        if (identity.isAnonymous) throw CliktError("not logged in: no credentials were configured")
        echo("logged in to ${wiki.id} as ${identity.name}")
        echo("groups: ${identity.groups.sorted().joinToString(", ").ifEmpty { "none" }}")
    }
}

/**
 * Reports the library version and what it is running on.
 *
 * The first thing to paste into a bug report, and deliberately nothing more than that: not the machine name,
 * not the working directory, not the user.
 */
internal class Version : SuspendingCliktCommand(name = "version") {
    override fun help(context: Context): String = "Show the library and runtime versions."

    override suspend fun run() {
        echo(Kwikibot.report().trimEnd())
    }
}

/** Runs the `kwikibot` command. */
public fun main(args: Array<String>): Unit = runBlocking {
    KotlinLoggingConfiguration.logStartupMessage = false
    configureLogging()
    kwikibot(CliEnvironment.SYSTEM).main(args)
}

/** The `kwikibot` command with its subcommands, reading from [environment]. */
internal fun kwikibot(environment: CliEnvironment): SuspendingCliktCommand =
    Kwikibot()
        .subcommands(
            WhoAmI(environment),
            GetPage(environment),
            Login(environment),
            InitConfig(),
            ShowConfig(environment),
            Detect(environment),
            Version(),
        )

/** How this tool identifies itself to a wiki, for the operator [contact] reaches. */
private fun userAgent(contact: String): UserAgent = UserAgent(Kwikibot.NAME, Kwikibot.version, contact)

/**
 * The configuration at [path]. A missing, invalid or unreadable file stops the command with a message saying
 * which.
 */
private fun readConfiguration(path: Path): BotConfig =
    try {
        BotConfig.read(path)
    } catch (e: IOException) {
        val problem =
            if (SystemFileSystem.exists(path)) "cannot read $path: ${e.message}"
            else "no configuration at $path"
        throw CliktError(problem, e)
    } catch (e: IllegalArgumentException) {
        throw CliktError("$path is not valid: ${e.message}", e)
    }

/**
 * What [block] makes of the configuration. A configuration it cannot use stops the command with a one-line
 * message saying what is wrong.
 */
private inline fun <T> reportingMisconfiguration(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalStateException) {
        throw CliktError(e.message ?: e.toString(), e)
    } catch (e: IllegalArgumentException) {
        throw CliktError(e.message ?: e.toString(), e)
    }
