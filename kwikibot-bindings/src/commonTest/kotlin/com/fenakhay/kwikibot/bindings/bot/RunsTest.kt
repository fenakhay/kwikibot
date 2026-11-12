package com.fenakhay.kwikibot.bindings.bot

import com.fenakhay.kwikibot.bindings.common.Setting
import com.fenakhay.kwikibot.bot.BotConfig
import com.fenakhay.kwikibot.bot.BotPolicy
import com.fenakhay.kwikibot.bot.run.Edit
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.bot.run.RunState
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.bot.source.PageSource
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/** A binding's two ways of running a bot: what each does with nothing given, and with everything given. */
class RunsTest {

    private fun wiki(): Pair<FakeWiki, FakePageService> {
        val pages =
            FakePageService(
                mapOf(
                    "A" to "alpha",
                    "B" to "{{nobots}} beta",
                    "C" to "gamma",
                    "User:TestBot/Stop" to "false",
                )
            )
        return FakeWiki(pages = pages) to pages
    }

    private fun source(vararg titles: String): PageSource = PageSource { wiki ->
        titles.asList().asFlow().map { wiki.ref(it) }
    }

    @Test
    fun `with nothing given a run is a dry run that honours nobots as this account`() = runTest {
        val (wiki, pages) = wiki()
        val report =
            wiki.botRunWithDefaults(
                transform = { page -> Edit(page.text + "!", "s") },
                source = source("A", "B"),
            )
        report.processed shouldBe 2
        report.pending shouldBe 1
        report.skipped shouldBe 1
        report.saved shouldBe 0
        pages.text("A") shouldBe "alpha"
    }

    @Test
    fun `with no source a run is refused as the core refuses it`() = runTest {
        val wiki = wiki().first
        assertFailsWith<IllegalArgumentException> { wiki.botRunWithDefaults(transform = { null }) }
    }

    @Test
    fun `every setting given reaches the run`() = runTest {
        val (wiki, pages) = wiki()
        val outcomes = mutableListOf<PageOutcome>()
        val directory = Path(SystemTemporaryDirectory, "kwikibot-bindings-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(directory)
        val report =
            wiki.botRunWithDefaults(
                transform = { page ->
                    if (page.text == "gamma") skip("not this one") else Edit(page.text + "!", "s")
                },
                source = source("A", "B", "C", "D"),
                createMissing = { Edit("delta", "created", createOnly = true) },
                dryRun = false,
                readConcurrency = 1,
                readBatch = 1,
                writeConcurrency = 1,
                limit = 4,
                maxEdits = 10,
                stopPolicy = StopPolicy.NONE,
                exclusionPolicy = Setting.Off,
                onOutcome = { outcomes += it },
                state = RunState(directory),
                resume = false,
                config = BotConfig(BotConfig.BotIdentity("TestBot", contact = "test@example.org")),
            )
        report.saved shouldBe 3
        report.skipped shouldBe 1
        pages.text("A") shouldBe "alpha!"
        pages.text("B") shouldBe "{{nobots}} beta!"
        pages.text("D") shouldBe "delta"
        outcomes.size shouldBe 4
    }

    @Test
    fun `what is given here comes after the common flags and an exclusion policy names the bot`() = runTest {
        val (wiki, pages) = wiki()
        val report =
            wiki.botRunWithDefaults(
                transform = { page -> Edit(page.text + "!", "s") },
                source = source("A", "B", "C"),
                dryRun = false,
                limit = 2,
                exclusionPolicy = Setting.Value(BotPolicy("OtherBot")),
                common = commonOptionsOf(save = false, limit = 1),
            )
        report.processed shouldBe 2
        report.saved shouldBe 1
        pages.text("A") shouldBe "alpha!"
        pages.text("B") shouldBe "{{nobots}} beta"
    }

    @Test
    fun `a stop page in the common flags is a stop policy`() = runTest {
        val (wiki, pages) = wiki()
        val report =
            wiki.botRunWithDefaults(
                transform = { page -> Edit(page.text + "!", "s") },
                source = source("A"),
                dryRun = false,
                common = commonOptionsOf(save = true, stopPage = "User:TestBot/Stop"),
            )
        report.saved shouldBe 1
        pages.text("A") shouldBe "alpha!"
    }

    @Test
    fun `a plain run computes its changes ignoring nobots and saves them only when told`() = runTest {
        val (wiki, pages) = wiki()
        val changes = mutableListOf<RunChange>()
        val heard = mutableListOf<PageOutcome>()
        val report =
            wiki.runWithDefaults(
                listOf("A", "B", "C"),
                "s",
                { text, title -> if (title == "C") null else "$text!" },
                onChange = { changes += it },
                onOutcome = { heard += it },
            )
        report.pending shouldBe 2
        changes.map { it.title }.toSet() shouldBe setOf("A", "B")
        changes.all { it.revision == null } shouldBe true
        heard.size shouldBe 3
        pages.text("A") shouldBe "alpha"

        val saved =
            wiki.runWithDefaults(
                listOf("A"),
                "s",
                { text, _ -> "$text!" },
                save = true,
                stopPage = "User:TestBot/Stop",
            )
        saved.saved shouldBe 1
        pages.text("A") shouldBe "alpha!"
    }

    @Test
    fun `a plain run stops after as many edits as it may save under the stop policy given`() = runTest {
        val (wiki, pages) = wiki()
        val report =
            wiki.runWithDefaults(
                listOf("A", "C"),
                "s",
                { text, _ -> "$text!" },
                save = true,
                stopPolicy = StopPolicy.NONE,
                maxEdits = 1,
            )
        report.saved shouldBe 1
        report.stopped shouldBe true
        wiki.runWithDefaults(listOf("C"), "s", { text, _ -> "$text!" }, save = true).saved shouldBe 1
    }
}
