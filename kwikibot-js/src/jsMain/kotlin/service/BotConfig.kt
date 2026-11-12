import com.fenakhay.kwikibot.bot.BotConfig as ClientBotConfig
import com.fenakhay.kwikibot.net.cache.ResponseCache as ClientResponseCache
import kotlin.js.Promise
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.io.files.Path

/** How the bot identifies itself. Required by the Wikimedia user-agent policy. */
@JsExport
public class BotIdentity(
    /** The bot's name, which goes in the user agent. */
    public val name: String,
    /** A URL or address an operator can be reached at. */
    public val contact: String,
    /** Its version, so an operator can tell two runs apart; `"1.0"` unless given. */
    public val version: String = "1.0",
)

/** Which wiki to work on unless a command says otherwise. */
@JsExport
public class WikiSelection(
    /** The language code of the wiki to work on; `"en"` unless given. */
    public val lang: String = "en",
    /** Its project family; `"wiktionary"` unless given. */
    public val family: String = "wiktionary",
    /** The host of a wiki outside Wikimedia's families, which [lang] and [family] then do not find. */
    public val server: String? = null,
    /** Where [server] keeps `api.php`; `"/w"` unless given. */
    public val scriptPath: String = "/w",
)

/** How fast requests may go out. */
@JsExport
public class ThrottleSettings(
    /** Between reads, as a Kotlin duration such as `"100ms"`, which is the default. */
    public val read: String = "100ms",
    /** Between writes, `"10s"` unless given, the conventional bot pace on Wikimedia wikis. */
    public val write: String = "10s",
)

/** Where the account name lives, and where its password does not. */
@JsExport
public class LoginSettings(
    /** The account name, without the bot-password suffix. */
    public val account: String,
    /** The bot password's own name. */
    public val botName: String,
    /** The name of an environment variable holding the bot password; `"KWIKIBOT_PASSWORD"` unless given. */
    public val passwordEnv: String = "KWIKIBOT_PASSWORD",
    /** Whether a run may go on anonymously when [passwordEnv] is not set; not unless given. */
    public val optional: Boolean = false,
)

/** An OAuth 2.0 login: an owner-only consumer's access token, which needs no login round trip. */
@JsExport
public class OAuthSettings(
    /**
     * The name of an environment variable holding the access token; `"KWIKIBOT_OAUTH_TOKEN"` unless given.
     */
    public val tokenEnv: String = "KWIKIBOT_OAUTH_TOKEN",
    /** The account the token belongs to, for the user agent and the logs. */
    public val username: String? = null,
    /** Whether a run may go on without it when [tokenEnv] is not set; not unless given. */
    public val optional: Boolean = false,
)

/** How a run reads and writes. Anything left out keeps the run's own default. */
@JsExport
public class RunSettings(
    /** How many pages, or batches, a run works on at once. */
    public val readConcurrency: Int? = null,
    /** How many edits it has in flight at once. */
    public val writeConcurrency: Int? = null,
    /** How many pages it reads per request. */
    public val readBatch: Int? = null,
)

/** How many requests the client has in flight, and how long a request may take. */
@JsExport
public class HttpSection(
    /** How many requests may be in flight at once, to every wiki together; 3 unless given. */
    public val maxConcurrentRequests: Int = ClientBotConfig.HttpSection().maxConcurrentRequests,
    /** How many idle connections are kept for reuse. */
    public val maxIdleConnections: Int = ClientBotConfig.HttpSection().maxIdleConnections,
    /** How long connecting, a request and a silent socket may each take; `"60s"` unless given. */
    public val timeout: String = "60s",
)

/** How failed requests and lost sessions are retried. Anything left out keeps the client's default. */
@JsExport
public class RetrySettings(
    /** How many times a request may be retried before its failure is raised. */
    public val maxRetries: Int? = null,
    /** The wait before the first retry, such as `"1s"`. It doubles each time. */
    public val initialDelay: String? = null,
    /** The longest any wait may be. */
    public val maxDelay: String? = null,
    /** How many times in ten minutes a lost session is logged back into; 0 for never. */
    public val relogins: Int = ClientBotConfig.RetrySettings().relogins,
)

/** Where read responses are remembered. */
@JsExport
public class CacheSettings(
    /** Where on disk to keep them; `"apicache"` unless given. */
    public val path: String = "apicache",
    /** How long an entry stays usable, as a Kotlin duration; `"12h"` unless given. */
    public val ttl: String = "12h",
)

/**
 * A bot's configuration, as a TOML file says it, with no credential in it: the file names an environment
 * variable to read each from, so it can be committed. `parseBotConfig` and `findBotConfig` read one, and
 * `createBotConfig` makes one.
 */
@JsExport
@OptIn(DelicateCoroutinesApi::class)
public class BotConfig internal constructor(internal val config: ClientBotConfig) {

    /** How the bot identifies itself. */
    public val bot: BotIdentity
        get() = config.bot.let { BotIdentity(it.name, it.contact, it.version) }

    /** Which wiki to work on unless a command says otherwise. */
    public val wiki: WikiSelection
        get() = config.wiki.let { WikiSelection(it.lang, it.family, it.server, it.scriptPath) }

    /** How fast requests may go out. */
    public val throttle: ThrottleSettings
        get() = ThrottleSettings(config.throttle.read, config.throttle.write)

    /** A bot-password login, or `null`. */
    public val login: LoginSettings?
        get() = config.login?.let { LoginSettings(it.account, it.botName, it.passwordEnv, it.optional) }

    /** Where read responses are remembered, or `null` when they are not. */
    public val cache: CacheSettings?
        get() = config.cache?.let { CacheSettings(it.path, it.ttl) }

    /** The replication lag in seconds above which a wiki should defer requests; 0 sends no `maxlag`. */
    public val maxlag: Int
        get() = config.maxlag

    /** An OAuth 2.0 login, or `null`. Used in place of [login] when both are given and its token is set. */
    public val oauth: OAuthSettings?
        get() = config.oauth?.let { OAuthSettings(it.tokenEnv, it.username, it.optional) }

    /** How a bot run reads and writes. */
    public val run: RunSettings
        get() = config.run.let { RunSettings(it.readConcurrency, it.writeConcurrency, it.readBatch) }

    /** How many connections the client keeps to the wiki, and how long a request may take. */
    public val http: HttpSection
        get() = config.http.let { HttpSection(it.maxConcurrentRequests, it.maxIdleConnections, it.timeout) }

    /** How failed requests and lost sessions are retried. */
    public val retry: RetrySettings
        get() = config.retry.let { RetrySettings(it.maxRetries, it.initialDelay, it.maxDelay, it.relogins) }

    /**
     * What to open a client with, as this configuration describes it, for `createClient` and `connectWith`.
     * The login is left to [credentials]. With a `[cache]`, it needs a filesystem, which a browser does not
     * have.
     */
    public fun connectOptions(): ConnectOptions {
        val built = config.toWikiConfig()
        val options: dynamic = js("({})")
        options.userAgent = UserAgent(built.userAgent.tool, built.userAgent.version, built.userAgent.contact)

        val throttle: dynamic = js("({})")
        throttle.read = milliseconds(built.throttle.read)
        throttle.write = milliseconds(built.throttle.write)
        options.throttle = throttle

        val retry: dynamic = js("({})")
        retry.maxRetries = built.retry.maxRetries
        retry.initialDelay = milliseconds(built.retry.initialDelay)
        retry.maxDelay = milliseconds(built.retry.maxDelay)
        options.retry = retry

        options.maxlag = built.maxlag
        options.relogins = built.relogins

        val http: dynamic = js("({})")
        http.maxConcurrentRequests = built.http.maxConcurrentRequests
        http.maxIdleConnections = built.http.maxIdleConnections
        http.timeout = milliseconds(built.http.timeout)
        options.http = http

        config.cache?.let { settings ->
            requireFileSystem("a configuration with a [cache]")
            options.cache = DiskCache(settings.path, milliseconds(Duration.parse(settings.ttl)))
        }
        check(config.cache != null || built.cache === ClientResponseCache.NONE)
        return options.unsafeCast<ConnectOptions>()
    }

    /**
     * The login this configuration describes, or `null` for none: an `OAuth2` when its token is set, else a
     * `BotPassword`.
     *
     * @param environment reads a variable, the process environment unless given.
     * @throws IllegalStateException when a login is configured and its variable is not set, and the login is
     *   not marked `optional`.
     */
    public fun credentials(environment: ((name: String) -> String?)? = null): BotPassword? =
        loginOf(if (environment == null) config.credentials() else config.credentials(environment))

    /** The family named in the file, or a failure when the file names no family there is. */
    public fun family(): Family = Family(config.family())

    /** The language code named in the file. */
    public fun language(): String = config.language().code

    /** Where the wiki's API is. */
    public fun endpoint(): Endpoint = Endpoint(config.endpoint())

    /**
     * Opens the wiki the file names on [client], which [connectOptions] and [credentials] make the one the
     * file describes. Closing it leaves the client open.
     */
    public fun connect(client: WikiClient): Promise<Wiki> = GlobalScope.promise {
        Wiki(config.connect(client.client), CoroutineScope(SupervisorJob())) {}
    }

    private fun milliseconds(duration: Duration): Double = duration.inWholeMilliseconds.toDouble()
}

/** What a configuration is made of, as `createBotConfig` takes it. */
@JsExport
public external interface BotConfigInit {
    /** How the bot identifies itself. */
    public val bot: BotIdentity

    /** Which wiki to work on unless a command says otherwise. */
    public val wiki: WikiSelection?

    /** How fast requests may go out. */
    public val throttle: ThrottleSettings?

    /** A bot-password login. */
    public val login: LoginSettings?

    /** Where read responses are remembered; nowhere unless given. */
    public val cache: CacheSettings?

    /** The replication lag above which a wiki should defer requests, in seconds; 5 unless given. */
    public val maxlag: Int?

    /** An OAuth 2.0 login. */
    public val oauth: OAuthSettings?

    /** How a bot run reads and writes. */
    public val run: RunSettings?

    /** How many connections the client keeps to the wiki, and how long a request may take. */
    public val http: HttpSection?

    /** How failed requests and lost sessions are retried. */
    public val retry: RetrySettings?
}

/** Makes a configuration from [init]. */
@JsExport
@Suppress("CyclomaticComplexMethod")
public fun createBotConfig(init: BotConfigInit): BotConfig {
    val defaults =
        ClientBotConfig(ClientBotConfig.BotIdentity(init.bot.name, init.bot.version, init.bot.contact))
    return BotConfig(
        defaults.copy(
            wiki =
                init.wiki?.let {
                    ClientBotConfig.WikiSelection(it.lang, it.family, it.server, it.scriptPath)
                } ?: defaults.wiki,
            throttle =
                init.throttle?.let { ClientBotConfig.ThrottleSettings(it.read, it.write) }
                    ?: defaults.throttle,
            login =
                init.login?.let {
                    ClientBotConfig.LoginSettings(it.account, it.botName, it.passwordEnv, it.optional)
                },
            cache = init.cache?.let { ClientBotConfig.CacheSettings(it.path, it.ttl) },
            maxlag = init.maxlag ?: defaults.maxlag,
            oauth = init.oauth?.let { ClientBotConfig.OAuthSettings(it.tokenEnv, it.username, it.optional) },
            run =
                init.run?.let {
                    ClientBotConfig.RunSettings(it.readConcurrency, it.writeConcurrency, it.readBatch)
                } ?: defaults.run,
            http =
                init.http?.let {
                    ClientBotConfig.HttpSection(it.maxConcurrentRequests, it.maxIdleConnections, it.timeout)
                } ?: defaults.http,
            retry =
                init.retry?.let {
                    ClientBotConfig.RetrySettings(it.maxRetries, it.initialDelay, it.maxDelay, it.relogins)
                } ?: defaults.retry,
        )
    )
}

/** The file name a configuration is looked for under. */
@JsExport public const val BOT_CONFIG_FILE_NAME: String = ClientBotConfig.FILE_NAME

/** The `maxlag` Wikimedia asks well-behaved bots to send. */
@JsExport public const val BOT_CONFIG_DEFAULT_MAXLAG: Int = ClientBotConfig.DEFAULT_MAXLAG

/**
 * Reads a configuration from TOML [text]. An unknown key is logged as a warning and otherwise ignored, so a
 * file written for a newer version still runs.
 */
@JsExport public fun parseBotConfig(text: String): BotConfig = BotConfig(ClientBotConfig.parse(text))

/** Reads a configuration from the file at [path]. Node only. */
@JsExport
public fun readBotConfig(path: String): BotConfig {
    requireFileSystem("reading a configuration")
    return BotConfig(ClientBotConfig.read(Path(path)))
}

/**
 * Reads the configuration at [explicit], throwing when it cannot, or when none is given finds it along
 * `botConfigSearchPath`, or `null` if there is none. Node only.
 */
@JsExport
public fun findBotConfig(explicit: String? = null): BotConfig? {
    requireFileSystem("finding a configuration")
    return ClientBotConfig.find(explicit?.let(::Path))?.let(::BotConfig)
}

/**
 * Where a configuration is looked for, in order: the working directory, the home directory,
 * `KWIKIBOT_CONFIG`, the user configuration directory, and on Windows `%APPDATA%\kwikibot`.
 */
@JsExport
public fun botConfigSearchPath(): Array<String> =
    ClientBotConfig.searchPath().map { it.toString() }.toTypedArray()

/** A configuration file to start from, whose login section names an environment variable. */
@JsExport public fun botConfigTemplate(): String = ClientBotConfig.template()
