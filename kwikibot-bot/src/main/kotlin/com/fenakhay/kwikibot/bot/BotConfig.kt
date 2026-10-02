package com.fenakhay.kwikibot.bot

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.fenakhay.kwikibot.bot.run.BotRunBuilder
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.cache.DiskCache
import com.fenakhay.kwikibot.net.cache.ResponseCache
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.HttpSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

private val log = KotlinLogging.logger {}

/**
 * A bot's configuration, read from a TOML file into an immutable value.
 *
 * Declarative rather than executable: the file cannot run code, and the worst a malformed one can do is fail
 * to parse.
 *
 * Passwords and tokens are not stored here. The file names an environment variable to read each from, so a
 * configuration can be committed, shared and diffed without a credential in it.
 *
 * ```toml
 * [bot]
 * name = "FenaBot"
 * version = "1.0"
 * contact = "https://en.wiktionary.org/wiki/User:FenaBot"
 *
 * [wiki]
 * lang = "en"
 * family = "wiktionary"
 *
 * [login]
 * account = "FenaBot"
 * botName = "compounds"
 * passwordEnv = "KWIKIBOT_PASSWORD"
 *
 * [run]
 * readConcurrency = 8
 * readBatch = 50
 *
 * [http]
 * maxRequestsPerHost = 32
 * ```
 *
 * An unknown key is logged as a warning, not an error, so a configuration written for a newer version still
 * runs on an older one.
 *
 * @param bot how the bot identifies itself.
 * @param wiki which wiki to work on unless a command says otherwise.
 * @param throttle how fast requests may go out.
 * @param login a bot-password login, or `null`.
 * @param cache where read responses are remembered; absent means they are not.
 * @param maxlag the replication lag above which a wiki should defer our requests, in seconds. Wikimedia asks
 *   bots for 5. Zero means send no `maxlag` at all, which only makes sense for a self-hosted wiki.
 * @param oauth an OAuth 2.0 login, or `null`. Used in place of [login] when both are given and its token is
 *   set.
 * @param run how a bot run reads and writes, for [applyTo].
 * @param http how many connections the client keeps to the wiki, and how long a request may take.
 * @param retry how failed requests and lost sessions are retried.
 */
@Serializable
public data class BotConfig(
    val bot: BotIdentity,
    val wiki: WikiSelection = WikiSelection(),
    val throttle: ThrottleSettings = ThrottleSettings(),
    val login: LoginSettings? = null,
    val cache: CacheSettings? = null,
    val maxlag: Int = DEFAULT_MAXLAG,
    val oauth: OAuthSettings? = null,
    val run: RunSettings = RunSettings(),
    val http: HttpSection = HttpSection(),
    val retry: RetrySettings = RetrySettings(),
) {

    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        bot: BotIdentity,
        wiki: WikiSelection = WikiSelection(),
        throttle: ThrottleSettings = ThrottleSettings(),
        login: LoginSettings? = null,
        cache: CacheSettings? = null,
        maxlag: Int = DEFAULT_MAXLAG,
    ) : this(bot, wiki, throttle, login, cache, maxlag, oauth = null)

    /** The client configuration this file describes. */
    public fun toWikiConfig(): WikiConfig =
        WikiConfig(
            userAgent = UserAgent(bot.name, bot.version, bot.contact),
            throttle = Throttle(read = throttle.readDelay, write = throttle.writeDelay),
            retry = retry.toPolicy(),
            maxlag = maxlag.takeIf { it > 0 },
            cache = cache?.let { DiskCache(Path(it.path), it.timeToLive) } ?: ResponseCache.NONE,
            relogins = retry.relogins,
            http = http.toSettings(),
        )

    /**
     * The credentials this file describes.
     *
     * An OAuth 2.0 token wins over a bot password when both are configured and the token is set.
     *
     * @throws IllegalStateException if a login is configured but the environment variable holding its secret
     *   is not set, and the login is not marked `optional`. A bot that silently falls back to editing
     *   anonymously is worse than one that stops.
     */
    public fun credentials(environment: (String) -> String? = System::getenv): Credentials {
        oauth?.let { settings ->
            val token = environment(settings.tokenEnv)
            if (!token.isNullOrBlank()) return Credentials.OAuth2(token, settings.username)
            check(settings.optional || login != null) {
                "the environment variable ${settings.tokenEnv} is not set, and the configuration says the " +
                    "OAuth token is in it"
            }
        }

        val settings = login ?: return Credentials.Anonymous
        val password = environment(settings.passwordEnv)
        if (password.isNullOrBlank()) {
            check(settings.optional) {
                "the environment variable ${settings.passwordEnv} is not set, and the configuration " +
                    "says the password for ${settings.account} is in it"
            }
            log.warn { "${settings.passwordEnv} is not set; the login is optional, so going on anonymously" }
            return Credentials.Anonymous
        }

        return Credentials.BotPassword(settings.account, settings.botName, password)
    }

    /** The family named in the file. */
    public fun family(): Family =
        Family.named(wiki.family)
            ?: error("unknown family '${wiki.family}'; name a Wikimedia project or set wiki.server")

    /** The language code named in the file. */
    public fun language(): LangCode = LangCode(wiki.lang)

    /**
     * Where the wiki's API is: [WikiSelection.server] if the file names one, or the language and family's.
     */
    public fun endpoint(): ApiEndpoint =
        wiki.server?.let { ApiEndpoint(server = it, scriptPath = wiki.scriptPath) }
            ?: family().endpoint(language())

    /**
     * Opens the wiki the file names on [client].
     *
     * Build the client from [toWikiConfig] and [credentials] so its session is the one the file describes.
     */
    public suspend fun connect(client: WikiClient): Wiki = client.wiki(endpoint())

    /**
     * Applies the file's `[run]` settings to a run, leaving whatever the file does not set as the run had it.
     */
    public fun applyTo(builder: BotRunBuilder) {
        run.readConcurrency?.let { builder.readConcurrency = it }
        run.writeConcurrency?.let { builder.writeConcurrency = it }
        run.readBatch?.let { builder.readBatch = it }
    }

    /** How the bot identifies itself. Required by the Wikimedia user-agent policy. */
    @Serializable
    public data class BotIdentity(
        /** The bot's name, which goes in the user agent. */
        val name: String,
        /** Its version, so an operator can tell two runs apart. */
        val version: String = "1.0",
        /** A URL or address an operator can be reached at. Not optional in practice. */
        val contact: String,
    )

    /**
     * Which wiki to work on unless a command says otherwise.
     *
     * @param lang the language code of the wiki to work on.
     * @param family its project family.
     * @param server the host of a wiki outside Wikimedia's families, such as `wiki.example.org`. When set,
     *   [lang] and [family] are not used to find the wiki.
     * @param scriptPath where [server] keeps `api.php`.
     */
    @Serializable
    public data class WikiSelection(
        val lang: String = "en",
        val family: String = "wiktionary",
        val server: String? = null,
        val scriptPath: String = "/w",
    ) {
        /** The constructor 1.1 compiled against, kept so code built then still links. */
        @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
        public constructor(lang: String = "en", family: String = "wiktionary") : this(lang, family, null)
    }

    /** How fast requests may go out. */
    @Serializable
    public data class ThrottleSettings(
        /** Between reads, as a duration: `100ms`. */
        val read: String = "100ms",
        /** Between writes. Ten seconds is the conventional bot pace on Wikimedia wikis. */
        val write: String = "10s",
    ) {
        internal val readDelay: Duration
            get() = Duration.parse(read)

        internal val writeDelay: Duration
            get() = Duration.parse(write)
    }

    /**
     * Where the account name lives, and where its password does not.
     *
     * @param account the account name, without the bot-password suffix.
     * @param botName the bot password's own name.
     * @param passwordEnv the name of an environment variable holding the bot password. The password itself is
     *   deliberately not a field: a configuration file gets committed.
     * @param optional whether a run may go on anonymously when [passwordEnv] is not set. Off by default, so a
     *   bot that meant to log in stops instead.
     */
    @Serializable
    public data class LoginSettings(
        val account: String,
        @SerialName("botName") val botName: String,
        val passwordEnv: String = "KWIKIBOT_PASSWORD",
        val optional: Boolean = false,
    ) {
        /** The constructor 1.1 compiled against, kept so code built then still links. */
        @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
        public constructor(
            account: String,
            botName: String,
            passwordEnv: String = "KWIKIBOT_PASSWORD",
        ) : this(account, botName, passwordEnv, false)
    }

    /**
     * An OAuth 2.0 login: an owner-only consumer's access token, which needs no session to keep alive.
     *
     * @param tokenEnv the name of an environment variable holding the access token.
     * @param username the account the token belongs to, for the user agent and the logs.
     * @param optional whether a run may go on without it when [tokenEnv] is not set.
     */
    @Serializable
    public data class OAuthSettings(
        val tokenEnv: String = "KWIKIBOT_OAUTH_TOKEN",
        val username: String? = null,
        val optional: Boolean = false,
    )

    /**
     * How a run reads and writes. Anything left out keeps the run's own default.
     *
     * @param readConcurrency how many pages, or batches, a run works on at once.
     * @param writeConcurrency how many edits it has in flight at once.
     * @param readBatch how many pages it reads per request.
     */
    @Serializable
    public data class RunSettings(
        val readConcurrency: Int? = null,
        val writeConcurrency: Int? = null,
        val readBatch: Int? = null,
    )

    /**
     * How many connections the client keeps to the wiki, and how long a request may take.
     *
     * @param maxRequestsPerHost how many requests may be in flight at once. Keep it above a run's read and
     *   write concurrency, or the run waits for connections.
     * @param maxIdleConnections how many idle connections are kept for reuse.
     * @param timeout how long connecting, a request and a silent socket may each take: `60s`.
     */
    @Serializable
    public data class HttpSection(
        val maxRequestsPerHost: Int = HttpSettings.DEFAULT_CONNECTIONS,
        val maxIdleConnections: Int = HttpSettings.DEFAULT_CONNECTIONS,
        val timeout: String = "60s",
    ) {
        internal fun toSettings(): HttpSettings =
            HttpSettings(maxRequestsPerHost, maxIdleConnections, Duration.parse(timeout))
    }

    /**
     * How failed requests and lost sessions are retried. Anything left out keeps the client's default.
     *
     * @param maxRetries how many times a request may be retried before its failure is raised.
     * @param initialDelay the wait before the first retry: `1s`. It doubles each time.
     * @param maxDelay the longest any wait may be.
     * @param relogins how many times in ten minutes a lost session is logged back into; 0 for never.
     */
    @Serializable
    public data class RetrySettings(
        val maxRetries: Int? = null,
        val initialDelay: String? = null,
        val maxDelay: String? = null,
        val relogins: Int = WikiConfig.DEFAULT_RELOGINS,
    ) {
        internal fun toPolicy(): RetryPolicy {
            val defaults = RetryPolicy()
            return RetryPolicy(
                maxRetries = maxRetries ?: defaults.maxRetries,
                initialDelay = initialDelay?.let(Duration::parse) ?: defaults.initialDelay,
                maxDelay = maxDelay?.let(Duration::parse) ?: defaults.maxDelay,
            )
        }
    }

    /** Where read responses are remembered. Absent means they are not. */
    @Serializable
    public data class CacheSettings(
        /** Where on disk to keep them. */
        val path: String = "apicache",
        /** How long an entry stays usable, as a Kotlin duration: `12h`. */
        val ttl: String = "12h",
    ) {
        internal val timeToLive: Duration
            get() = Duration.parse(ttl)
    }

    /** Reading a configuration file, and the defaults it falls back to. */
    public companion object {
        /** What Wikimedia asks well-behaved bots to send. */
        public const val DEFAULT_MAXLAG: Int = 5

        /** The file name looked for in each of [searchPath]. */
        public const val FILE_NAME: String = "kwikibot.toml"

        private val strict = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))

        private val lenient = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

        private const val UNKNOWN_KEY = "Unknown key received"

        /**
         * Reads a configuration from TOML text.
         *
         * An unknown key is logged as a warning and otherwise ignored.
         */
        public fun parse(text: String): BotConfig =
            try {
                strict.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                // ktoml keeps its unknown-key exception internal, so it is recognised by its message.
                val message = e.message.orEmpty()
                if (!message.startsWith(UNKNOWN_KEY)) throw e
                log.warn { "kwikibot.toml: ${message.substringBefore(". Switch")}; ignored" }
                lenient.decodeFromString(serializer(), text)
            }

        /** Reads a configuration from a file. */
        public fun read(path: Path): BotConfig = parse(path.readText())

        /**
         * Finds and reads the configuration, or `null` if there is none.
         *
         * Searched in the order of [searchPath]: the working directory first, so a bot in a checkout uses
         * that checkout's configuration rather than whatever is in the home directory.
         */
        public fun find(explicit: Path? = null): BotConfig? {
            explicit?.let {
                check(it.exists()) { "no configuration at $it" }
                return read(it)
            }
            return searchPath().firstOrNull { it.exists() }?.let { read(it) }
        }

        /**
         * Where a configuration is looked for, in order: the working directory, the home directory,
         * `KWIKIBOT_CONFIG`, the user configuration directory, and on Windows `%APPDATA%\kwikibot`.
         */
        public fun searchPath(): List<Path> = searchPath(System::getenv, System.getProperty("user.home"))

        /** [searchPath] for a given environment and home directory. */
        internal fun searchPath(environment: (String) -> String?, home: String?): List<Path> =
            listOfNotNull(
                    Path(FILE_NAME),
                    home?.let { Path(it, FILE_NAME) },
                    environment("KWIKIBOT_CONFIG")?.let { Path(it) },
                    configHome(environment, home)?.resolve(Path("kwikibot", FILE_NAME)),
                    environment("APPDATA")?.let { Path(it, "kwikibot", FILE_NAME) },
                )
                .distinct()

        /**
         * The directory user configuration lives in.
         *
         * `XDG_CONFIG_HOME` is the config home; `~/.config` is only its default for when the variable is
         * unset. Searching both would name the same file twice on any machine that sets the variable to that
         * default, which is what a Linux desktop does.
         */
        private fun configHome(environment: (String) -> String?, home: String?): Path? =
            environment("XDG_CONFIG_HOME")?.let { Path(it) } ?: home?.let { Path(it, ".config") }

        /**
         * A configuration file to start from.
         *
         * Written by `kwiki init-config`. The login section names an environment variable rather than holding
         * a password.
         */
        public fun template(): String =
            """
            # kwikibot configuration. Passwords are never stored here: the login section names
            # an environment variable to read one from.

            [bot]
            name = "MyBot"
            version = "1.0"
            # Required by the Wikimedia user-agent policy: somewhere an operator can be reached.
            contact = "https://en.wiktionary.org/wiki/User:MyBot"

            [wiki]
            lang = "en"
            family = "wiktionary"
            # Or a wiki of your own:
            # server = "wiki.example.org"
            # scriptPath = "/w"

            [throttle]
            read = "100ms"
            write = "10s"

            # Create one at Special:BotPasswords, then:
            #     export KWIKIBOT_PASSWORD=...
            [login]
            account = "MyBot"
            botName = "mytask"
            passwordEnv = "KWIKIBOT_PASSWORD"
            # optional = true    # go on anonymously when the password is not set

            # Or an owner-only OAuth 2.0 consumer, which needs no session:
            # [oauth]
            # tokenEnv = "KWIKIBOT_OAUTH_TOKEN"

            # How a run reads and writes.
            # [run]
            # readConcurrency = 4
            # writeConcurrency = 1
            # readBatch = 50

            # [http]
            # maxRequestsPerHost = 32
            # timeout = "60s"

            # [retry]
            # maxRetries = 5
            # relogins = 3

            # Remembers read responses, which is for developing a bot rather than running one.
            # [cache]
            # path = "apicache"
            # ttl = "12h"

            """
                .trimIndent()
    }
}
