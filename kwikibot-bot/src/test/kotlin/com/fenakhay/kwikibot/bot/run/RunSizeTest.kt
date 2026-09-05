package com.fenakhay.kwikibot.bot.run

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.testkit.FakePageService
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest

/**
 * What a run keeps, as against what it gets through.
 *
 * A report used to hold one object per page, and a `Pending` holds the new text and the old, so a dry run
 * over a category kept two copies of every page it would have changed. A bot sweeping 350,000 entries could
 * not finish for that reason alone.
 *
 * These pin the property, not the implementation: what a run holds must not grow with how much it does.
 */
class RunSizeTest {

    private val wiki = WikiId("testwiki")

    private fun ref(text: String) = PageRef(wiki, Title.Local(Namespace.MAIN, text))

    private fun pages(count: Int) =
        FakePageService((1..count).associate { "page$it" to "the text of page $it, which is not short" })

    @Test
    fun `a dry run over many pages keeps none of their text`() = runTest {
        val many = 5_000

        val report =
            botRun(pages(many)) {
                source((1..many).map { ref("page$it") }.asFlow())
                transform { Edit(it.text + " and more", "adding") }
            }

        report.processed shouldBe many
        report.pending shouldBe many
        // Every one of them would have been edited, and none is retained.
        report.problems.shouldHaveSize(0)
    }

    @Test
    fun `what a report holds does not grow with the length of the run`() = runTest {
        suspend fun runOver(count: Int): BotReport =
            botRun(pages(count)) {
                source((1..count).map { ref("page$it") }.asFlow())
                transform { Edit(it.text + " and more", "adding") }
            }

        val small = runOver(100)
        val large = runOver(5_000)

        small.pending shouldBe 100
        large.pending shouldBe 5_000
        // Fifty times the work retains the same amount.
        large.problems.size shouldBe small.problems.size
    }

    @Test
    fun `the refusals it keeps are capped, and it says when there were more`() = runTest {
        val many = BotReport.PROBLEM_LIMIT * 3

        val report =
            botRun(pages(many)) {
                source((1..many).map { ref("page$it") }.asFlow())
                // An Api error is recorded against the page and the run carries on; an Auth or
                // ReadOnly one would stop it, and a plain exception is a bug in the transform and
                // is left to propagate.
                transform { throw WikiError.Api("badvalue", "this one went wrong", "edit") }
            }

        report.failed shouldBe many
        report.problems shouldHaveSize BotReport.PROBLEM_LIMIT
        report.problems.size shouldBeLessThan report.failed
        report.problemsTruncated shouldBe true
    }

    @Test
    fun `every outcome still reaches a caller that asks for them`() = runTest {
        val many = 500
        val seen = mutableListOf<PageOutcome>()

        val report =
            botRun(pages(many)) {
                source((1..many).map { ref("page$it") }.asFlow())
                transform { Edit(it.text + " and more", "adding") }
                onOutcome = { seen += it }
            }

        // The report counts them; the hook has them all.
        seen shouldHaveSize many
        report.processed shouldBe many
    }
}
