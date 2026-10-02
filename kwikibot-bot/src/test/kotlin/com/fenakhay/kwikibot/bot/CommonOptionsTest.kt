package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.bot.run.BotRunBuilder
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CommonOptionsTest {

    @Test
    fun `the common flags are read and everything else is handed back in order`() {
        val (options, rest) =
            CommonOptions.parse(
                listOf(
                        "--save",
                        "--lang",
                        "ar",
                        "--limit=10",
                        "--read-delay",
                        "50ms",
                        "pages.txt",
                        "--resume",
                    )
                    .plus(listOf("--state", "run"))
            )

        options.save shouldBe true
        options.limit shouldBe 10
        options.readDelay shouldBe 50.milliseconds
        options.state shouldBe Path("run")
        options.resume shouldBe true
        rest shouldBe listOf("--lang", "ar", "pages.txt")
    }

    @Test
    fun `everything after a double dash is the bot's own`() {
        val (options, rest) = CommonOptions.parse(arrayOf("--save", "--", "--limit", "3"))

        options.limit shouldBe null
        rest shouldBe listOf("--limit", "3")
    }

    @Test
    fun `a bad or missing value names the flag`() {
        assertFailsWith<IllegalArgumentException> { CommonOptions.parse(listOf("--limit", "many")) }
            .message
            .shouldNotBeNull() shouldContain "--limit"
        assertFailsWith<IllegalArgumentException> { CommonOptions.parse(listOf("--write-delay", "soon")) }
        assertFailsWith<IllegalArgumentException> { CommonOptions.parse(listOf("--stop-page")) }
        assertFailsWith<IllegalArgumentException> { CommonOptions.parse(listOf("--resume")) }
    }

    @Test
    fun `the client takes its pace, connections and contact from the flags`() {
        val base = WikiConfig(UserAgent("Bot", "1.0", "https://example.org/old"))
        val (options, _) =
            CommonOptions.parse(
                listOf("--write-delay", "2s", "--http-connections", "8", "--contact", "me@example.org")
            )

        val config = options.applyTo(base)

        config.throttle.write shouldBe 2.seconds
        config.throttle.read shouldBe base.throttle.read
        config.http.maxRequestsPerHost shouldBe 8
        config.userAgent.contact shouldBe "me@example.org"
        CommonOptions().applyTo(base) shouldBe base
    }

    @Test
    fun `a run takes whether it saves, how far it goes, what stops it and where it records`() {
        val directory = Files.createTempDirectory("kwikibot-options")
        val wiki = FakeWiki(FakePageService("User:Bot/Stop" to "false"))
        val (options, _) =
            CommonOptions.parse(
                listOf("--save", "--limit", "5", "--stop-page", "User:Bot/Stop", "--state", "$directory")
            )
        val builder = BotRunBuilder()

        options.applyTo(builder, wiki)

        builder.dryRun shouldBe false
        builder.limit shouldBe 5
        builder.state.shouldNotBeNull().close()
        builder.resume shouldBe false
    }

    @Test
    fun `the log writes to the files the flags name`() {
        val directory = Files.createTempDirectory("kwikibot-options")
        val wiki = FakeWiki(FakePageService("a" to "x"))
        val skips = directory.resolve("logs/skips.jsonl")
        val options = CommonOptions(skipLog = skips)

        options.runLog(wiki)(PageOutcome.Skipped(wiki.ref("a"), "nothing to do"))

        skips.readText() shouldContain "nothing to do"
    }

    @Test
    fun `the usage names every flag`() {
        listOf("--save", "--limit", "--diff-log", "--skip-log", "--stop-page", "--resume", "--state")
            .forEach {
                CommonOptions.usage shouldContain it
            }
    }
}
