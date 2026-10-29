package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.bot.run.BotRunBuilder
import com.fenakhay.kwikibot.bot.run.Edit
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
    fun `a number that is not positive names the flag`() {
        listOf("--limit" to "0", "--limit" to "-3", "--http-connections" to "0", "--http-connections" to "x")
            .forEach { (flag, value) ->
                assertFailsWith<IllegalArgumentException> { CommonOptions.parse(listOf(flag, value)) }
                    .message shouldBe "$flag needs a positive number, not '$value'"
            }
    }

    @Test
    fun `the client takes its pace connections and contact from the flags`() {
        val base = WikiConfig(UserAgent("Bot", "1.0", "https://example.org/old"))
        val (options, _) =
            CommonOptions.parse(
                listOf("--write-delay", "2s", "--http-connections", "8", "--contact", "me@example.org")
            )

        val config = options.applyTo(base)

        config.throttle.write shouldBe 2.seconds
        config.throttle.read shouldBe base.throttle.read
        config.http.maxConcurrentRequests shouldBe 8
        config.userAgent.contact shouldBe "me@example.org"
        CommonOptions().applyTo(base) shouldBe base
    }

    @Test
    fun `a run takes whether it saves and how far it goes and what stops it and where it records`() {
        val directory = temporaryDirectory("kwikibot-options")
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
    fun `a run the flags say nothing about is left as it was except that it does not save`() {
        val builder = BotRunBuilder().apply { dryRun = false }

        CommonOptions().applyTo(builder, FakeWiki())

        builder.dryRun shouldBe true
        builder.limit shouldBe null
        builder.stopPolicy shouldBe StopPolicy.NONE
        builder.state shouldBe null
    }

    @Test
    fun `a diff log alone gets the diffs and nothing is written for skips`() {
        val directory = temporaryDirectory("kwikibot-options")
        val wiki = FakeWiki(FakePageService("a" to "x"))
        val diffs = Path(directory, "diffs.txt")

        CommonOptions(diffLog = diffs).runLog(wiki).let { log ->
            log.record(PageOutcome.Pending(wiki.ref("a"), Edit("y", "s"), "x"))
            log.record(PageOutcome.Skipped(wiki.ref("a"), "nothing to do"))
        }

        diffs.readText() shouldContain "+y"
        SystemFileSystem.list(directory).map { it.name } shouldBe listOf("diffs.txt")
    }

    @Test
    fun `the log writes to the files the flags name`() {
        val directory = temporaryDirectory("kwikibot-options")
        val wiki = FakeWiki(FakePageService("a" to "x"))
        val skips = Path(directory, "logs", "skips.jsonl")
        val options = CommonOptions(skipLog = skips)

        options.runLog(wiki).record(PageOutcome.Skipped(wiki.ref("a"), "nothing to do"))

        skips.readText() shouldContain "nothing to do"
    }

    @Test
    fun `a trial is read wherever it comes and needs saving and a limit`() {
        CommonOptions.parse(listOf("--trial", "--limit", "50", "--save")).first shouldBe
            CommonOptions(save = true, limit = 50, trial = true)

        listOf(listOf("--trial"), listOf("--trial", "--save"), listOf("--trial", "--limit", "50")).forEach {
            assertFailsWith<IllegalArgumentException> { CommonOptions.parse(it) }.message shouldBe
                "--trial needs --save and --limit, how many pages to try"
        }
    }

    @Test
    fun `the usage names every flag`() {
        listOf(
                "--save",
                "--limit",
                "--diff-log",
                "--skip-log",
                "--stop-page",
                "--resume",
                "--state",
                "--trial",
            )
            .forEach {
                CommonOptions.usage shouldContain it
            }
    }
}
