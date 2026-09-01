package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.net.Credentials
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BotConfigTest {

    private val minimal = """
        [bot]
        name = "FenaBot"
        contact = "https://en.wiktionary.org/wiki/User:FenaBot"
    """.trimIndent()

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
        val config = BotConfig.parse(
            """
            $minimal

            [throttle]
            read = "250ms"
            write = "30s"
            """.trimIndent(),
        )

        config.throttle.readDelay shouldBe 250.milliseconds
        config.throttle.writeDelay shouldBe 30.seconds
    }

    @Test
    fun `the password comes from the environment, never from the file`() {
        val config = BotConfig.parse(
            """
            $minimal

            [login]
            account = "FenaBot"
            botName = "compounds"
            passwordEnv = "TEST_PASSWORD"
            """.trimIndent(),
        )

        val credentials = config.credentials { name -> if (name == "TEST_PASSWORD") "s3cret" else null }

        val botPassword = credentials.shouldBeInstanceOf<Credentials.BotPassword>()
        botPassword.loginName shouldBe "FenaBot@compounds"
        botPassword.password shouldBe "s3cret"
    }

    @Test
    fun `a configured login with no password in the environment stops rather than editing anonymously`() {
        val config = BotConfig.parse(
            """
            $minimal

            [login]
            account = "FenaBot"
            botName = "compounds"
            passwordEnv = "TEST_PASSWORD"
            """.trimIndent(),
        )

        val failure = assertFailsWith<IllegalStateException> { config.credentials { null } }

        failure.message.orEmpty() shouldContain "TEST_PASSWORD"
    }

    @Test
    fun `no login section means anonymous, which is a choice rather than a failure`() {
        BotConfig.parse(minimal).credentials { null } shouldBe Credentials.Anonymous
    }

    @Test
    fun `a key nobody recognises is an error rather than a silent no-op`() {
        assertFailsWith<Exception> {
            BotConfig.parse(
                """
                $minimal

                [throttle]
                reed = "250ms"
                """.trimIndent(),
            )
        }
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
        val config = BotConfig.parse(
            """
            [bot]
            name = "FenaBot"
            version = "2.0"
            contact = "https://example.org/FenaBot"

            [throttle]
            read = "100ms"
            write = "10s"
            """.trimIndent(),
        ).toWikiConfig()

        config.userAgent.headerValue shouldContain "FenaBot/2.0"
        config.maxlag shouldBe BotConfig.DEFAULT_MAXLAG
    }

    @Test
    fun `maxlag can be turned off for a wiki you run yourself`() {
        val config = BotConfig.parse(
            """
            maxlag = 0

            [bot]
            name = "FenaBot"
            contact = "https://example.org/FenaBot"
            """.trimIndent(),
        ).toWikiConfig()

        config.maxlag shouldBe null
    }

    @Test
    fun `the family named in the file is resolved`() {
        BotConfig.parse(minimal).family().name shouldBe "wiktionary"

        val unknown = BotConfig.parse(
            """
            $minimal

            [wiki]
            family = "notaproject"
            """.trimIndent(),
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
            BotConfig.find(kotlin.io.path.Path("no-such-file-anywhere.toml"))
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
    fun `every entry on the search path is named for the tool`() {
        BotConfig.searchPath().drop(1).forEach {
            it.toString().contains("kwikibot") shouldBe true
        }
    }
}
