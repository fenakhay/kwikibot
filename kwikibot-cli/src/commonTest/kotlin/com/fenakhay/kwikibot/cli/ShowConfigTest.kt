package com.fenakhay.kwikibot.cli

import com.github.ajalt.clikt.command.test
import com.github.ajalt.clikt.testing.CliktCommandTestResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

class ShowConfigTest {

    private val directory = temporaryDirectory("kwikibot-cli")
    private val absent = Path(directory, "absent.toml")
    private val file = Path(directory, "kwikibot.toml")

    private suspend fun config(vararg variables: Pair<String, String>): CliktCommandTestResult =
        kwikibot(CliEnvironment(searchPath = { listOf(absent, file) }))
            .test("config", envvars = variables.toMap())

    private fun write(text: String): Path = file.apply { writeText(text.trimIndent()) }

    @Test
    fun `config shows the file found and everything it says but a secret`() = runTest {
        write(
            """
            [bot]
            name = "TestBot"
            version = "2.0"
            contact = "https://example.org/TestBot"

            [wiki]
            server = "wiki.example.org"
            scriptPath = "/w"

            [throttle]
            read = "250ms"
            write = "30s"

            [login]
            account = "TestBot"
            botName = "task"

            [oauth]
            tokenEnv = "TEST_TOKEN"
            optional = true

            [run]
            readConcurrency = 8
            writeConcurrency = 2
            readBatch = 50

            [http]
            maxConcurrentRequests = 24
            timeout = "90s"

            [retry]
            relogins = 5
            """
        )

        val result = config("KWIKIBOT_PASSWORD" to "hunter2")

        result.statusCode shouldBe 0
        result.stdout shouldBe
            """
            file:      $file
            bot:       TestBot/2.0 (https://example.org/TestBot)
            wiki:      wiki.example.org/w
            throttle:  read 250ms, write 30s
            maxlag:    5
            http:      24 requests at once, timeout 90s
            relogins:  5 in ten minutes
            run:       read 8 at once, write 2 at once, 50 pages a request
            oauth:     TEST_TOKEN is NOT SET (optional)
            login:     TestBot@task
            password:  KWIKIBOT_PASSWORD is set

            """
                .trimIndent()
        result.output shouldNotContain "hunter2"
    }

    @Test
    fun `config of a short file shows the defaults it falls back to`() = runTest {
        write(
            """
            maxlag = 0

            [bot]
            name = "TestBot"
            contact = "https://example.org/TestBot"

            [run]
            readBatch = 20
            """
        )

        val result = config()

        result.statusCode shouldBe 0
        result.stdout.lines().drop(1) shouldBe
            listOf(
                "bot:       TestBot/1.0 (https://example.org/TestBot)",
                "wiki:      en.wiktionary",
                "throttle:  read 100ms, write 10s",
                "maxlag:    not sent",
                "http:      3 requests at once, timeout 60s",
                "relogins:  3 in ten minutes",
                "run:       20 pages a request",
                "login:     anonymous",
                "",
            )
    }

    @Test
    fun `a secret held in a blank variable is not set as logging in treats it`() = runTest {
        write(
            """
            [bot]
            name = "TestBot"
            contact = "https://example.org/TestBot"

            [login]
            account = "TestBot"
            botName = "task"
            passwordEnv = "TEST_PASSWORD"
            optional = true
            """
        )

        config("TEST_PASSWORD" to "").stdout.lines().takeLast(2) shouldBe
            listOf("password:  TEST_PASSWORD is NOT SET (optional)", "")
        config("TEST_PASSWORD" to "secret").stdout.lines().takeLast(2) shouldBe
            listOf("password:  TEST_PASSWORD is set (optional)", "")
    }

    @Test
    fun `config with no file says where it looked and fails`() = runTest {
        val result = config()

        result.statusCode shouldBe 1
        result.stdout shouldBe ""
        result.stderr shouldBe "no configuration found. Looked in:\n  $absent\n  $file\n"
    }

    @Test
    fun `config of a file that is not valid says so and fails`() = runTest {
        write("[bot]\nname = 3")

        val result = config()

        result.statusCode shouldBe 1
        result.stderr shouldStartWith "$file is not valid: "
    }

    private fun Path.writeText(text: String) {
        SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
    }
}
