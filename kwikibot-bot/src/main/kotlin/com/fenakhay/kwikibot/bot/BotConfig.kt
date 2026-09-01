package com.fenakhay.kwikibot.bot

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.fenakhay.kwikibot.client.Family
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.net.Credentials
import com.fenakhay.kwikibot.net.DiskCache
import com.fenakhay.kwikibot.net.ResponseCache
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration

/**
 * A bot's configuration, read from a TOML file into an immutable value.
 *
 * Declarative rather than executable: the file cannot run code, and the worst a malformed one can
 * do is fail to parse.
 *
 * **Passwords are not stored here.** The file names an environment variable to read the
 * password from, so a configuration can be committed, shared and diffed without a credential
 * ever being in it.
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
 * ```
 */
@Serializable
public data class BotConfig(
    val bot: BotIdentity,
    val wiki: WikiSelection = WikiSelection(),
    /** How fast requests may go out. */
    val throttle: ThrottleSettings = ThrottleSettings(),
    val login: LoginSettings? = null,
    /** Where read responses are remembered. Absent means they are not. */
    val cache: CacheSettings? = null,
    /**
     * The replication lag above which a wiki should defer our requests, in seconds.
     *
     * Wikimedia asks bots for 5. Zero means send no `maxlag` at all, which only makes sense for
     * a self-hosted wiki.
     */
    val maxlag: Int = DEFAULT_MAXLAG,
) {

    /** The client configuration this file describes. */
    public fun toWikiConfig(): WikiConfig = WikiConfig(
        userAgent = UserAgent(bot.name, bot.version, bot.contact),
        throttle = Throttle(read = throttle.readDelay, write = throttle.writeDelay),
        maxlag = maxlag.takeIf { it > 0 },
        cache = cache?.let { DiskCache(Path(it.path), it.timeToLive) } ?: ResponseCache.NONE,
    )

    /**
     * The credentials this file describes.
     *
     * @throws IllegalStateException if a login is configured but the environment variable holding
     *   its password is not set — a bot that silently falls back to editing anonymously is worse
     *   than one that stops.
     */
    public fun credentials(environment: (String) -> String? = System::getenv): Credentials {
        val settings = login ?: return Credentials.Anonymous

        val password = environment(settings.passwordEnv)
        checkNotNull(password) {
            "the environment variable ${settings.passwordEnv} is not set, and the configuration " +
                "says the password for ${settings.account} is in it"
        }

        return Credentials.BotPassword(settings.account, settings.botName, password)
    }

    /** The family named in the file. */
    public fun family(): Family = Family.named(wiki.family)
        ?: error("unknown family '${wiki.family}'; name a Wikimedia project or set wiki.server")

    /** The language code named in the file. */
    public fun language(): LangCode = LangCode(wiki.lang)

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

    /** Which wiki to work on unless a command says otherwise. */
    @Serializable
    public data class WikiSelection(
        /** The language code of the wiki to work on. */
        val lang: String = "en",
        /** Its project family. */
        val family: String = "wiktionary",
    )

    /** How fast requests may go out. */
    @Serializable
    public data class ThrottleSettings(
        /** Between reads, as a duration: `100ms`. */
        val read: String = "100ms",
        /** Between writes. Ten seconds is the conventional bot pace on Wikimedia wikis. */
        val write: String = "10s",
    ) {
        internal val readDelay: Duration get() = Duration.parse(read)
        internal val writeDelay: Duration get() = Duration.parse(write)
    }

    /**
     * Where the account name lives, and where its password does not.
     *
     * @param account the account name, without the bot-password suffix.
     * @param botName the bot password's own name.
     * @param passwordEnv the name of an environment variable holding the bot password. The
     *   password itself is deliberately not a field: a configuration file gets committed.
     */
    @Serializable
    public data class LoginSettings(
        /** The account name, without the bot-password suffix. */
        val account: String,
        /** The bot password's own name. */
        @SerialName("botName") val botName: String,
        val passwordEnv: String = "KWIKIBOT_PASSWORD",
    )

    /** Where read responses are remembered. Absent means they are not. */
    @Serializable
    public data class CacheSettings(
        /** Where on disk to keep them. */
        val path: String = "apicache",
        /** How long an entry stays usable, as a Kotlin duration: `12h`. */
        val ttl: String = "12h",
    ) {
        internal val timeToLive: Duration get() = Duration.parse(ttl)
    }

    /** Reading a configuration file, and the defaults it falls back to. */
    public companion object {
        /** What Wikimedia asks well-behaved bots to send. */
        public const val DEFAULT_MAXLAG: Int = 5

        /** The file name looked for in each of [searchPath]. */
        public const val FILE_NAME: String = "kwikibot.toml"

        private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))

        /** Reads a configuration from TOML text. */
        public fun parse(text: String): BotConfig = toml.decodeFromString(serializer(), text)

        /** Reads a configuration from a file. */
        public fun read(path: Path): BotConfig = parse(path.readText())

        /**
         * Finds and reads the configuration, or `null` if there is none.
         *
         * Searched in the order of [searchPath]: the working directory first, so a bot in a
         * checkout uses that checkout's configuration rather than whatever is in the home
         * directory.
         */
        public fun find(explicit: Path? = null): BotConfig? {
            explicit?.let {
                check(it.exists()) { "no configuration at $it" }
                return read(it)
            }
            return searchPath().firstOrNull { it.exists() }?.let { read(it) }
        }

        /** Where a configuration is looked for, in order. */
        public fun searchPath(): List<Path> = listOfNotNull(
            Path(FILE_NAME),
            System.getenv("KWIKIBOT_CONFIG")?.let { Path(it) },
            configHome()?.resolve(Path("kwikibot", FILE_NAME)),
        )

        /**
         * The directory user configuration lives in.
         *
         * `XDG_CONFIG_HOME` is the config home; `~/.config` is only its default for when the
         * variable is unset. Searching both would name the same file twice on any machine that
         * sets the variable to that default, which is what a Linux desktop does.
         */
        private fun configHome(): Path? =
            System.getenv("XDG_CONFIG_HOME")?.let { Path(it) }
                ?: System.getProperty("user.home")?.let { Path(it, ".config") }

        /**
         * A configuration file to start from.
         *
         * Written by `kwiki init-config`. The login section names an environment variable
         * rather than holding a password.
         */
        public fun template(): String = """
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

            [throttle]
            read = "100ms"
            write = "10s"

            # Create one at Special:BotPasswords, then:
            #     export KWIKIBOT_PASSWORD=...
            [login]
            account = "MyBot"
            botName = "mytask"
            passwordEnv = "KWIKIBOT_PASSWORD"

            # Remembers read responses, which is for developing a bot rather than running one.
            # [cache]
            # path = "apicache"
            # ttl = "12h"

        """.trimIndent()
    }
}
