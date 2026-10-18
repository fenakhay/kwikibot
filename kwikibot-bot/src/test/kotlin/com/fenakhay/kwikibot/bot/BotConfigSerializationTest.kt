package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.bot.BotConfig.BotIdentity
import com.fenakhay.kwikibot.bot.BotConfig.CacheSettings
import com.fenakhay.kwikibot.bot.BotConfig.HttpSection
import com.fenakhay.kwikibot.bot.BotConfig.LoginSettings
import com.fenakhay.kwikibot.bot.BotConfig.OAuthSettings
import com.fenakhay.kwikibot.bot.BotConfig.RetrySettings
import com.fenakhay.kwikibot.bot.BotConfig.RunSettings
import com.fenakhay.kwikibot.bot.BotConfig.ThrottleSettings
import com.fenakhay.kwikibot.bot.BotConfig.WikiSelection
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * [BotConfig.serializer] is public, so a configuration can be stored and sent as something other than TOML.
 * What it writes has to read back as the same configuration, whether or not the defaults are written out.
 */
class BotConfigSerializationTest {

    private val identity =
        BotIdentity(name = "FenaBot", contact = "https://en.wiktionary.org/wiki/User:FenaBot")

    private val minimal = BotConfig(bot = identity)

    private val firstOfEach =
        BotConfig(
            bot = identity.copy(version = "2.0"),
            wiki = WikiSelection(lang = "fr"),
            throttle = ThrottleSettings(read = "250ms"),
            login = LoginSettings(account = "FenaBot", botName = "compounds"),
            cache = CacheSettings(),
            maxlag = 0,
            oauth = OAuthSettings(),
            run = RunSettings(readConcurrency = 8),
            http = HttpSection(maxRequestsPerHost = 64),
            retry = RetrySettings(maxRetries = 2),
        )

    private val lastOfEach =
        BotConfig(
            bot = identity,
            wiki = WikiSelection(scriptPath = ""),
            throttle = ThrottleSettings(write = "30s"),
            login = LoginSettings(account = "FenaBot", botName = "compounds", optional = true),
            cache = CacheSettings(ttl = "6h"),
            oauth = OAuthSettings(optional = true),
            run = RunSettings(readBatch = 50),
            http = HttpSection(timeout = "2m"),
            retry = RetrySettings(relogins = 0),
        )

    private val full =
        BotConfig(
            bot = BotIdentity(name = "FenaBot", version = "2.0", contact = "https://example.org/FenaBot"),
            wiki =
                WikiSelection(
                    lang = "fr",
                    family = "wikipedia",
                    server = "wiki.example.org",
                    scriptPath = "",
                ),
            throttle = ThrottleSettings(read = "250ms", write = "30s"),
            login =
                LoginSettings(
                    account = "FenaBot",
                    botName = "compounds",
                    passwordEnv = "FENABOT_PASSWORD",
                    optional = true,
                ),
            cache = CacheSettings(path = "/var/cache/fenabot", ttl = "6h"),
            maxlag = 0,
            oauth = OAuthSettings(tokenEnv = "FENABOT_TOKEN", username = "FenaBot", optional = true),
            run = RunSettings(readConcurrency = 8, writeConcurrency = 2, readBatch = 50),
            http = HttpSection(maxRequestsPerHost = 64, maxIdleConnections = 16, timeout = "2m"),
            retry = RetrySettings(maxRetries = 2, initialDelay = "500ms", maxDelay = "30s", relogins = 0),
        )

    private val withoutDefaults = Json { encodeDefaults = false }

    private val withDefaults = Json { encodeDefaults = true }

    private fun Json.roundTrip(config: BotConfig): BotConfig =
        decodeFromString(BotConfig.serializer(), encodeToString(BotConfig.serializer(), config))

    @Test
    fun `a configuration reads back as itself when its defaults are left out`() {
        listOf(minimal, firstOfEach, lastOfEach, full).forEach { config ->
            withoutDefaults.roundTrip(config) shouldBe config
        }
    }

    @Test
    fun `a configuration reads back as itself when its defaults are written out`() {
        listOf(minimal, firstOfEach, lastOfEach, full).forEach { config ->
            withDefaults.roundTrip(config) shouldBe config
        }
    }

    @Test
    fun `a minimal configuration is written as only the identity it cannot do without`() {
        withoutDefaults.encodeToString(BotConfig.serializer(), minimal) shouldBe
            """{"bot":{"name":"FenaBot","contact":"https://en.wiktionary.org/wiki/User:FenaBot"}}"""
    }

    @Test
    fun `a section at its defaults is not written, and one with a field changed writes only that field`() {
        val encoded =
            withoutDefaults.encodeToString(
                BotConfig.serializer(),
                minimal.copy(retry = RetrySettings(relogins = 0)),
            )

        encoded shouldBe
            """{"bot":{"name":"FenaBot","contact":"https://en.wiktionary.org/wiki/User:FenaBot"},""" +
                """"retry":{"relogins":0}}"""
    }

    @Test
    fun `a file with no bot section is refused, since a bot must say who it is`() {
        assertFailsWith<SerializationException> { BotConfig.parse("[wiki]\nlang = \"fr\"\n") }
        assertFailsWith<SerializationException> {
            withoutDefaults.decodeFromString(BotConfig.serializer(), """{"wiki":{"lang":"fr"}}""")
        }
    }

    @Test
    fun `an identity without a contact is refused`() {
        assertFailsWith<SerializationException> { BotConfig.parse("[bot]\nname = \"FenaBot\"\n") }
        assertFailsWith<SerializationException> {
            withoutDefaults.decodeFromString(BotConfig.serializer(), """{"bot":{"name":"FenaBot"}}""")
        }
    }

    @Test
    fun `a login without an account is refused`() {
        assertFailsWith<SerializationException> {
            BotConfig.parse(
                """
                [bot]
                name = "FenaBot"
                contact = "https://example.org/FenaBot"

                [login]
                botName = "compounds"
                """
                    .trimIndent()
            )
        }
        assertFailsWith<SerializationException> {
            withoutDefaults.decodeFromString(
                BotConfig.serializer(),
                """{"bot":{"name":"FenaBot","contact":"https://example.org"},""" +
                    """"login":{"botName":"compounds"}}""",
            )
        }
    }
}
