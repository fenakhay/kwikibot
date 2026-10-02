package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.bot.run.BotRunBuilder
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.io.path.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BotConfigTest {

    private val minimal =
        """
        [bot]
        name = "FenaBot"
        contact = "https://en.wiktionary.org/wiki/User:FenaBot"
        """
            .trimIndent()

    @Test
    fun `a minimal file is enough`() {
        val config = BotConfig.parse(minimal)

        config.bot.name shouldBe "FenaBot"
        config.wiki.lang shouldBe "en"
        config.wiki.family shouldBe "wiktionary"
        config.maxlag shouldBe BotConfig.DEFAULT_MAXLAG
    }

    @Test
    fun `throttles are read as durations, not as numbers of something`() {
        val config =
            BotConfig.parse(
                """
            $minimal

            [throttle]
            read = "250ms"
            write = "30s"
            """
                    .trimIndent()
            )

        config.throttle.readDelay shouldBe 250.milliseconds
        config.throttle.writeDelay shouldBe 30.seconds
    }

    @Test
    fun `the password comes from the environment, never from the file`() {
        val config =
            BotConfig.parse(
                """
            $minimal

            [login]
            account = "FenaBot"
            botName = "compounds"
            passwordEnv = "TEST_PASSWORD"
            """
                    .trimIndent()
            )

        val credentials = config.credentials { name -> if (name == "TEST_PASSWORD") "s3cret" else null }

        val botPassword = credentials.shouldBeInstanceOf<Credentials.BotPassword>()
        botPassword.loginName shouldBe "FenaBot@compounds"
        botPassword.password shouldBe "s3cret"
    }

    @Test
    fun `a configured login with no password in the environment stops rather than editing anonymously`() {
        val config =
            BotConfig.parse(
                """
            $minimal

            [login]
            account = "FenaBot"
            botName = "compounds"
            passwordEnv = "TEST_PASSWORD"
            """
                    .trimIndent()
            )

        val failure = assertFailsWith<IllegalStateException> { config.credentials { null } }

        failure.message.orEmpty() shouldContain "TEST_PASSWORD"
    }

    @Test
    fun `no login section means anonymous, which is a choice rather than a failure`() {
        BotConfig.parse(minimal).credentials { null } shouldBe Credentials.Anonymous
    }

    @Test
    fun `a key nobody recognises is passed over with a warning, so a newer file still runs`() {
        val config =
            BotConfig.parse(
                """
                $minimal

                [throttle]
                reed = "250ms"
                write = "30s"
                """
                    .trimIndent()
            )

        config.throttle.read shouldBe "100ms"
        config.throttle.write shouldBe "30s"
    }

    @Test
    fun `a file that is not TOML is still an error`() {
        assertFailsWith<Exception> { BotConfig.parse("[bot\nname = ") }
    }

    @Test
    fun `an OAuth token is used when it is set, and wins over a bot password`() {
        val config =
            BotConfig.parse(
                """
                $minimal

                [oauth]
                tokenEnv = "TOKEN"
                username = "FenaBot"

                [login]
                account = "FenaBot"
                botName = "compounds"
                """
                    .trimIndent()
            )

        val token = config.credentials { if (it == "TOKEN") "secret" else "password" }
        token.shouldBeInstanceOf<Credentials.OAuth2>().username shouldBe "FenaBot"

        // Without the token, the bot password configured beside it is used.
        config
            .credentials { if (it == "KWIKIBOT_PASSWORD") "password" else null }
            .shouldBeInstanceOf<Credentials.BotPassword>()
    }

    @Test
    fun `a missing secret stops the run unless the login is optional`() {
        val oauth = BotConfig.parse("$minimal\n\n[oauth]\ntokenEnv = \"TOKEN\"\n")
        assertFailsWith<IllegalStateException> { oauth.credentials { null } }

        val optional = BotConfig.parse("$minimal\n\n[oauth]\noptional = true\n")
        optional.credentials { null } shouldBe Credentials.Anonymous

        val login =
            BotConfig.parse("$minimal\n\n[login]\naccount = \"FenaBot\"\nbotName = \"x\"\noptional = true\n")
        login.credentials { null } shouldBe Credentials.Anonymous
    }

    @Test
    fun `run settings apply to a run, leaving what the file does not set alone`() {
        val config = BotConfig.parse("$minimal\n\n[run]\nreadConcurrency = 8\nreadBatch = 50\n")
        val builder = BotRunBuilder()

        config.applyTo(builder)

        builder.readConcurrency shouldBe 8
        builder.readBatch shouldBe 50
        builder.writeConcurrency shouldBe 1
    }

    @Test
    fun `connections, timeouts, retries and relogins reach the client`() {
        val config =
            BotConfig.parse(
                    """
                $minimal

                [http]
                maxRequestsPerHost = 64
                timeout = "2m"

                [retry]
                maxRetries = 2
                initialDelay = "500ms"
                relogins = 0
                """
                        .trimIndent()
                )
                .toWikiConfig()

        config.http.maxRequestsPerHost shouldBe 64
        config.http.timeout shouldBe 2.minutes
        config.retry.maxRetries shouldBe 2
        config.retry.initialDelay shouldBe 500.milliseconds
        config.relogins shouldBe 0
    }

    @Test
    fun `a wiki of one's own is named by its server`() {
        val config = BotConfig.parse("$minimal\n\n[wiki]\nserver = \"wiki.example.org\"\nscriptPath = \"\"\n")

        config.endpoint() shouldBe ApiEndpoint("wiki.example.org", scriptPath = "")
        BotConfig.parse(minimal).endpoint() shouldBe ApiEndpoint("en.wiktionary.org")
    }

    @Test
    fun `the template it writes is a file it can read back`() {
        val config = BotConfig.parse(BotConfig.template())

        config.bot.contact.isNotEmpty() shouldBe true
        config.login?.passwordEnv shouldBe "KWIKIBOT_PASSWORD"
        BotConfig.template() shouldContain "passwordEnv"
    }

    @Test
    fun `the template holds no password`() {
        val text = BotConfig.template()

        text.contains("password = ") shouldBe false
    }

    @Test
    fun `a client configuration is built from the file`() {
        val config =
            BotConfig.parse(
                    """
                    [bot]
                    name = "FenaBot"
                    version = "2.0"
                    contact = "https://example.org/FenaBot"

                    [throttle]
                    read = "100ms"
                    write = "10s"
                    """
                        .trimIndent()
                )
                .toWikiConfig()

        config.userAgent.headerValue shouldContain "FenaBot/2.0"
        config.maxlag shouldBe BotConfig.DEFAULT_MAXLAG
    }

    @Test
    fun `maxlag can be turned off for a wiki you run yourself`() {
        val config =
            BotConfig.parse(
                    """
                    maxlag = 0

                    [bot]
                    name = "FenaBot"
                    contact = "https://example.org/FenaBot"
                    """
                        .trimIndent()
                )
                .toWikiConfig()

        config.maxlag shouldBe null
    }

    @Test
    fun `the family named in the file is resolved`() {
        BotConfig.parse(minimal).family().name shouldBe "wiktionary"

        val unknown =
            BotConfig.parse(
                """
            $minimal

            [wiki]
            family = "notaproject"
            """
                    .trimIndent()
            )
        assertFailsWith<IllegalStateException> { unknown.family() }
    }

    @Test
    fun `an explicit path is read, and a missing one is an error rather than a silent default`() {
        val file = kotlin.io.path.createTempFile("kwikibot", ".toml")
        file.writeText(minimal)

        try {
            BotConfig.find(file)?.bot?.name shouldBe "FenaBot"
        } finally {
            file.deleteExisting()
        }

        assertFailsWith<IllegalStateException> {
            BotConfig.find(Path("no-such-file-anywhere.toml"))
        }
    }

    @Test
    fun `reading a file gives the same result as parsing its text`() {
        val file = kotlin.io.path.createTempFile("kwikibot", ".toml")
        file.writeText(minimal)

        try {
            BotConfig.read(file) shouldBe BotConfig.parse(minimal)
        } finally {
            file.deleteExisting()
        }
    }

    @Test
    fun `the search path looks in the working directory first`() {
        val path = BotConfig.searchPath()

        path.first().toString() shouldBe BotConfig.FILE_NAME
        path.size shouldBe path.distinct().size
    }

    @Test
    fun `the home directory comes right after the working directory, and Windows has its own place`() {
        val environment = mapOf("APPDATA" to "C:/Users/a/AppData/Roaming", "KWIKIBOT_CONFIG" to "/etc/k.toml")
        val path = BotConfig.searchPath(environment::get, "/home/a")

        path.map { it.toString().replace('\\', '/') } shouldBe
            listOf(
                "kwikibot.toml",
                "/home/a/kwikibot.toml",
                "/etc/k.toml",
                "/home/a/.config/kwikibot/kwikibot.toml",
                "C:/Users/a/AppData/Roaming/kwikibot/kwikibot.toml",
            )
    }

    @Test
    fun `every entry on the search path is named for the tool`() {
        BotConfig.searchPath().drop(1).forEach {
            it.toString().contains("kwikibot") shouldBe true
        }
    }
}
