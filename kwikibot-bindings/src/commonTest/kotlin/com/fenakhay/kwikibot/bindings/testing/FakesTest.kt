package com.fenakhay.kwikibot.bindings.testing

import com.fenakhay.kwikibot.bindings.client.answerOf
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.net.auth.Identity
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/** What a fake wiki is unless told, and the refusals a fake page service answers with. */
class FakesTest {

    private val ref = PageRef(WikiId("testwiki"), Title.Local(Namespace.MAIN, "Volcano"))

    @Test
    fun `a fake wiki is testwiki with a bot logged in unless told`() {
        fakeWikiIdOf() shouldBe WikiId("testwiki")
        fakeWikiIdOf("frwiki") shouldBe WikiId("frwiki")
        DEFAULT_FAKE_IDENTITY shouldBe Identity(name = "TestBot", id = 1, groups = setOf("bot"))
        fakeIdentityOf("Alice") shouldBe
            Identity(name = "Alice", id = 1, groups = emptySet(), rights = emptySet())
        fakeIdentityOf("Alice", 7, listOf("sysop"), listOf("delete")) shouldBe
            Identity(name = "Alice", id = 7, groups = setOf("sysop"), rights = setOf("delete"))
    }

    @Test
    fun `with nothing given a refusal says nothing and is neither cascading nor a deletion`() {
        listOf(
                "conflict",
                "protected",
                "permission-denied",
                "rate-limited",
                "filtered",
                "captcha",
                "page-changed",
            )
            .map { refusalNamed(ref, it) } shouldBe
            listOf(
                EditOutcome.Conflict(ref, ""),
                EditOutcome.Protected(ref, "", null, false),
                EditOutcome.PermissionDenied(ref, ""),
                EditOutcome.RateLimited(ref, ""),
                EditOutcome.Filtered(ref, ""),
                EditOutcome.CaptchaRequired(ref, ""),
                EditOutcome.PageStateChanged(ref, "", false),
            )
        refusalNamed(ref, "rejected") shouldBe EditOutcome.Rejected(ref, "", "")
    }

    @Test
    fun `every refusal takes its own fields and reads back as it was named`() {
        val names =
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
        val refusals = names.map {
            refusalNamed(ref, it, "said", RevisionId(3), "sysop", true, 5.seconds, "spam", "c1", true, "code")
        }
        refusals.map { answerOf(it).status } shouldBe names
        refusals shouldBe
            listOf(
                EditOutcome.Conflict(ref, "said", RevisionId(3)),
                EditOutcome.Protected(ref, "said", "sysop", true),
                EditOutcome.PermissionDenied(ref, "said"),
                EditOutcome.RateLimited(ref, "said", 5.seconds),
                EditOutcome.Filtered(ref, "said", "spam"),
                EditOutcome.CaptchaRequired(ref, "said", "c1"),
                EditOutcome.PageStateChanged(ref, "said", true),
                EditOutcome.Rejected(ref, "said", "code"),
            )
    }

    @Test
    fun `a status that names no refusal is refused with every one it could have been`() {
        assertFailsWith<IllegalArgumentException> { refusalNamed(ref, "saved") }.message shouldBe
            "a refusal's status cannot be 'saved': it is one of 'conflict', 'protected', " +
                "'permission-denied', 'rate-limited', 'filtered', 'captcha', 'page-changed', 'rejected'"
    }
}
