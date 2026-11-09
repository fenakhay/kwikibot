package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.bot.BotPolicy
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/** How a run is started: what each entry point chooses for a bot that says nothing more. */
class BotRunEntryPointsTest {

    private val pages = FakePageService("open" to "text", "closed" to "{{nobots}}")

    private fun BotRunBuilder.editing(title: String, wiki: FakeWiki) {
        source(flowOf(wiki.ref(title)))
        transform { Edit("new", "s") }
        dryRun = false
    }

    @Test
    fun `a run on a wiki honours exclusions as its account unless told not to`() = runTest {
        val wiki = FakeWiki(pages)

        wiki.botRun(StopPolicy.NONE) { editing("closed", wiki) }.saved shouldBe 0
        wiki
            .botRun(StopPolicy.NONE) {
                editing("closed", wiki)
                exclusionPolicy = null
            }
            .saved shouldBe 1
    }

    @Test
    fun `the stop policy given is the one checked and a block can replace it`() = runTest {
        val wiki = FakeWiki(pages)

        wiki.botRun(StopPolicy { false }) { editing("open", wiki) }.stopReason shouldBe StopReason.POLICY
        wiki
            .botRun(StopPolicy { false }) {
                editing("open", wiki)
                stopPolicy = StopPolicy.NONE
            }
            .saved shouldBe 1
    }

    @Test
    fun `a run on bare pages takes the policies it is given`() = runTest {
        val wiki = FakeWiki(pages)

        botRun(pages, StopPolicy.NONE, BotPolicy("Bot")) { editing("closed", wiki) }.saved shouldBe 0
        botRun(pages, StopPolicy.NONE, null) { editing("closed", wiki) }.saved shouldBe 1
    }

    @Test
    fun `a stop page lets a bot run only while it says false`() = runTest {
        val open = FakePageService("User:Bot/Stop" to "false", "page" to "text")
        val wiki = FakeWiki(open)

        wiki.botRun(wiki.stopPage("User:Bot/Stop")) { editing("page", wiki) }.saved shouldBe 1
        wiki.botRun(wiki.stopPage()) { editing("page", wiki) }.stopReason shouldBe StopReason.POLICY
    }

    @Test
    fun `a cap on edits holds with pages saved side by side and stops the run`() = runTest {
        val many = FakePageService((1..20).associate { "p$it" to "text" })
        val report =
            botRun(many, StopPolicy.NONE, null) {
                source((1..20).map { many.ref("p$it") }.asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                writeConcurrency = 8
                readConcurrency = 8
                maxEdits = 3
            }

        report.saved shouldBe 3
        report.stopReason shouldBe StopReason.POLICY
    }

    @Test
    fun `reaching the cap does not drop the edits already allowed`() = runTest {
        val many = FakePageService("a" to "text", "b" to "text", "c" to "text")
        val capReached = CompletableDeferred<Unit>()
        var startChecked = false

        val report =
            botRun(many, StopPolicy.NONE, null) {
                source(listOf("a", "b", "c").map { many.ref(it) }.asFlow())
                transform { Edit("new", "s") }
                dryRun = false
                readBatch = 3
                writeConcurrency = 1
                maxEdits = 2
                stopPolicy = StopPolicy {
                    if (startChecked) capReached.await()
                    startChecked = true
                    true
                }
                onOutcome = { if (it is PageOutcome.NotAttempted) capReached.complete(Unit) }
            }

        report.saved shouldBe 2
        report.notAttempted shouldBe 1
    }

    @Test
    fun `a cap on edits counts creations and leaves a dry run alone`() = runTest {
        val empty = FakePageService()
        val created =
            botRun(empty, StopPolicy.NONE, null) {
                source(listOf("a", "b").map { empty.ref(it) }.asFlow())
                transform { null }
                createMissing { Edit("new", "s") }
                dryRun = false
                readConcurrency = 1
                maxEdits = 1
            }
        created.saved shouldBe 1

        val dry =
            botRun(empty, StopPolicy.NONE, null) {
                source(listOf("c", "d").map { empty.ref(it) }.asFlow())
                transform { null }
                createMissing { Edit("new", "s") }
                maxEdits = 1
            }
        dry.pending shouldBe 2
    }

    @Test
    fun `all of several policies have to let a bot go on`() = runTest {
        StopPolicy.allOf(StopPolicy.NONE, StopPolicy.NONE).mayContinue() shouldBe true
        StopPolicy.allOf(StopPolicy.NONE, StopPolicy { false }).mayContinue() shouldBe false
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the old entry points save with no stop check and ignore exclusions as they always did`() = runTest {
        val pages = FakePageService("open" to "text", "closed" to "{{nobots}}", "shut" to "{{nobots}}")
        val wiki = FakeWiki(pages)

        wiki.botRun { editing("closed", wiki) }.saved shouldBe 1
        botRun(pages) { editing("shut", wiki) }.saved shouldBe 1
        wiki
            .botRun {
                editing("open", wiki)
                stopPolicy = StopPolicy { false }
            }
            .stopReason shouldBe StopReason.POLICY
        wiki
            .botRun {
                source(flowOf(wiki.ref("open")))
                transform { Edit("new", "s") }
            }
            .pending shouldBe 1
    }
}
