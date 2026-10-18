package com.fenakhay.kwikibot.cli

import com.fenakhay.kwikibot.bot.BotConfig
import com.fenakhay.kwikibot.net.Kwikibot
import com.github.ajalt.clikt.command.test
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class KwikibotTest {

    private val environment = CliEnvironment(searchPath = { emptyList() })

    @Test
    fun `the help names every command and what it does`() = runTest {
        val result = kwikibot(environment).test("--help")

        result.statusCode shouldBe 0
        result.stdout.lines()[2].trim() shouldBe "Talk to a MediaWiki wiki."
        val commands =
            result.stdout
                .substringAfter("Commands:\n")
                .lines()
                .filter { it.isNotBlank() }
                .associate { it.trim().substringBefore(' ') to it.trim().substringAfter(' ').trim() }
        commands shouldBe
            mapOf(
                "whoami" to "Show the account the wiki sees.",
                "get" to "Print the wikitext of a page.",
                "login" to "Log in and report the result.",
                "init-config" to "Write a starter kwikibot.toml.",
                "config" to "Show the configuration in effect.",
                "detect" to "Find the api.php of the wiki at a URL.",
                "version" to "Show the library and runtime versions.",
            )
    }

    @Test
    fun `version reports the library and what it runs on`() = runTest {
        val result = kwikibot(environment).test("version")

        result.statusCode shouldBe 0
        result.stdout shouldBe Kwikibot.report()
    }

    @Test
    fun `main runs the command line it is given`() = runTest {
        val printed = ByteArrayOutputStream()
        val standardOutput = System.out
        System.setOut(PrintStream(printed, true))
        try {
            main(arrayOf("version"))
        } finally {
            System.setOut(standardOutput)
        }

        printed.toString() shouldBe Kwikibot.report()
    }

    @Test
    fun `the machine's configuration is looked for where the library looks`() {
        CliEnvironment.SYSTEM.searchPath() shouldBe BotConfig.searchPath()
    }
}
