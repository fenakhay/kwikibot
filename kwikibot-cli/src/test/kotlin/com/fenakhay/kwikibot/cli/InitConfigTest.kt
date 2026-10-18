package com.fenakhay.kwikibot.cli

import com.fenakhay.kwikibot.bot.BotConfig
import com.github.ajalt.clikt.command.test
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class InitConfigTest {

    private val out = Files.createTempDirectory("kwikibot-cli").resolve("kwikibot.toml")

    private suspend fun initConfig(vararg flags: String) =
        kwikibot(CliEnvironment(searchPath = { emptyList() }))
            .test(listOf("init-config", "--out", "$out") + flags)

    @Test
    fun `init-config writes a configuration to start from, with no password in it`() = runTest {
        val result = initConfig()

        result.statusCode shouldBe 0
        result.stdout shouldBe "wrote $out\nSet the password with: export KWIKIBOT_PASSWORD=...\n"
        out.readText() shouldBe BotConfig.template()
        BotConfig.read(out).login?.passwordEnv shouldBe "KWIKIBOT_PASSWORD"
    }

    @Test
    fun `init-config leaves a file that is there alone, unless forced`() = runTest {
        out.writeText("# mine")

        val refused = initConfig()

        refused.statusCode shouldBe 1
        refused.stderr shouldBe "$out already exists; pass --force to overwrite it\n"
        out.readText() shouldBe "# mine"

        initConfig("--force").statusCode shouldBe 0
        out.readText() shouldBe BotConfig.template()
    }
}
