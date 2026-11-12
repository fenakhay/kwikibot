package com.fenakhay.kwikibot.bindings.bot

import com.fenakhay.kwikibot.bot.run.Edit
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.bot.run.StopReason
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/** What each outcome of a page in a run shows, as `PageOutcome` documents it in `kwikibot.d.ts`. */
class OutcomesTest {

    private val ref = PageRef(WikiId("testwiki"), Title.Local(Namespace.MAIN, "Volcano"))
    private val edit = Edit("lava!", "s")
    private val refusal = EditOutcome.Conflict(ref, "conflict")
    private val error = IllegalStateException("broken")
    private val namespaces = NamespaceMap.CANONICAL

    private val missing = PageOutcome.Missing(ref)
    private val skipped = PageOutcome.Skipped(ref, "why")
    private val unchanged = PageOutcome.Unchanged(ref)
    private val pending = PageOutcome.Pending(ref, edit, "lava", RevisionId(6))
    private val saved = PageOutcome.Saved(ref, RevisionId(7), RevisionId(6), edit, "lava")
    private val savedBare = PageOutcome.Saved(ref, RevisionId(7))
    private val refused = PageOutcome.Refused(ref, refusal)
    private val failed = PageOutcome.Failed(ref, error)
    private val notAttempted = PageOutcome.NotAttempted(ref, StopReason.POLICY_UNREACHABLE)

    private val every = listOf(missing, skipped, unchanged, pending, saved, refused, failed, notAttempted)

    @Test
    fun `every outcome is spelled as the definitions spell it`() {
        every.map(::kindOf) shouldBe
            listOf(
                "missing",
                "skipped",
                "unchanged",
                "pending",
                "saved",
                "refused",
                "failed",
                "not-attempted",
            )
    }

    @Test
    fun `each outcome shows only its own fields`() {
        every.map(::reasonOf) shouldBe listOf(null, "why", null, null, null, null, null, "policy-unreachable")
        every.map(::editOf) shouldBe listOf(null, null, null, edit, edit, null, null, null)
        every.map(::beforeOf) shouldBe listOf(null, null, null, "lava", "lava", null, null, null)
        every.map(::baseRevisionOf) shouldBe listOf(null, null, null, RevisionId(6), null, null, null, null)
        every.map(::revisionOf) shouldBe listOf(null, null, null, null, RevisionId(7), null, null, null)
        every.map(::previousRevisionOf) shouldBe
            listOf(null, null, null, null, RevisionId(6), null, null, null)
        every.map(::refusalOf) shouldBe listOf(null, null, null, null, null, refusal, null, null)
        every.map(::errorOf) shouldBe listOf(null, null, null, null, null, null, error, null)
    }

    @Test
    fun `a pending or saved page is a change with its diff when the run kept the texts`() {
        val changes = (every + savedBare).map { changeOf(it, namespaces) }
        changes.filterNotNull().map { it.revision } shouldBe listOf(null, RevisionId(7), RevisionId(7))
        changes[3]!!.title shouldBe "Volcano"
        changes[3]!!.diff shouldContain "+lava!"
        changes[4]!!.diff shouldContain "+lava!"
        changes.last()!!.diff shouldBe ""
        changes.count { it == null } shouldBe 6
    }

    @Test
    fun `a refused or failed page is a problem`() {
        every.mapNotNull { problemOf(it, namespaces) } shouldBe
            listOf(RunProblem("Volcano", "refused", "conflict"), RunProblem("Volcano", "failed", "broken"))
        problemOf(PageOutcome.Failed(ref, IllegalStateException()), namespaces)!!.detail shouldBe
            IllegalStateException().toString()
    }
}
