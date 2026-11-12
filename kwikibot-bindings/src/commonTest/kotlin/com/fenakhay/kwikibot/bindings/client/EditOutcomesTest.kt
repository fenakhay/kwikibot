package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.bindings.openWiki
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/** Every answer to an edit, as `EditResult` documents it in `kwikibot.d.ts`. */
class EditOutcomesTest {

    private val ref = PageRef(WikiId("testwiki"), Title.Local(Namespace.MAIN, "Volcano"))
    private val seven = RevisionId(7)

    private val table: List<Pair<EditOutcome, EditAnswer>> =
        listOf(
            EditOutcome.Saved(ref, seven, RevisionId(6)) to EditAnswer("saved", seven, RevisionId(6)),
            EditOutcome.Saved(ref, seven, null) to EditAnswer("saved", seven),
            EditOutcome.NoChange(ref, seven) to EditAnswer("unchanged", seven),
            EditOutcome.NoChange(ref, null) to EditAnswer("unchanged"),
            refused(EditOutcome.Conflict(ref, "conflict", seven), "conflict", true) {
                it.copy(currentRevision = seven)
            },
            refused(EditOutcome.Protected(ref, "protected", "sysop", true), "protected") {
                it.copy(protectionLevel = "sysop", isCascadingProtection = true)
            },
            refused(EditOutcome.PermissionDenied(ref, "denied"), "permission-denied") { it },
            refused(EditOutcome.RateLimited(ref, "slow down", 30.seconds), "rate-limited", true) {
                it.copy(retryAfter = 30.seconds)
            },
            refused(EditOutcome.Filtered(ref, "filtered", "spam"), "filtered") { it.copy(filter = "spam") },
            refused(EditOutcome.CaptchaRequired(ref, "captcha", "c1"), "captcha") {
                it.copy(captchaId = "c1")
            },
            refused(EditOutcome.PageStateChanged(ref, "gone", true), "page-changed", null) {
                it.copy(wasDeleted = true)
            },
            refused(EditOutcome.Rejected(ref, "no", "spamblacklist"), "rejected") {
                it.copy(code = "spamblacklist")
            },
        )

    private fun refused(
        outcome: EditOutcome.Refused,
        status: String,
        retryable: Boolean? = false,
        fields: (EditAnswer) -> EditAnswer,
    ): Pair<EditOutcome, EditAnswer> {
        val answer =
            EditAnswer(
                status,
                detail = outcome.detail,
                refused = outcome,
                isRetryable = retryable ?: outcome.isRetryable,
            )
        return outcome to fields(answer)
    }

    @Test
    fun `every answer to an edit maps to its status and its own fields`() {
        table.forEach { (outcome, answer) -> answerOf(outcome) shouldBe answer }
    }

    @Test
    fun `every refusal is spelled as the definitions spell it`() {
        table.map { it.first }.filterIsInstance<EditOutcome.Refused>().map(::refusalOf) shouldBe
            listOf(
                "conflict",
                "protected",
                "permission-denied",
                "rate-limited",
                "filtered",
                "captcha",
                "page-changed",
                "rejected",
            )
    }

    @Test
    fun `a rate limit the client raises is a refusal like any other`() = runTest {
        val limited =
            refusingRateLimits(ref) { throw WikiError.Transport.RateLimited(5.seconds) }
                as EditOutcome.RateLimited
        limited.ref shouldBe ref
        limited.retryAfter shouldBe 5.seconds
        refusingRateLimits(ref) { EditOutcome.NoChange(ref, null) } shouldBe EditOutcome.NoChange(ref, null)
    }

    @Test
    fun `an edit the wiki rate-limits answers with a refusal`() = runTest {
        val wiki = RecordingWiki(openWiki(), editing = { throw WikiError.Transport.RateLimited(null) })
        val outcome = wiki.editWithDefaults("Volcano", text = "lava")
        answerOf(outcome).status shouldBe "rate-limited"
    }
}
