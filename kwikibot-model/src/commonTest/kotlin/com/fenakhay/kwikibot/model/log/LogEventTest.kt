package com.fenakhay.kwikibot.model.log

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

class LogEventTest {
    private val ref = PageRef(WikiId("enwiktionary"), Title.Local(Namespace.MAIN, "volcano"))
    private val time = Instant.parse("2026-08-31T21:43:26Z")

    private fun entry(page: PageRef? = ref, user: String? = "Someone", comment: String? = "spam") =
        LogEvent(1, "delete", "delete", page, user, time, comment)

    @Test
    fun `an entry with any field hidden is suppressed and one with none hidden is not`() {
        entry().isSuppressed shouldBe false
        entry(page = null).isSuppressed shouldBe true
        entry(user = null).isSuppressed shouldBe true
        entry(comment = null).isSuppressed shouldBe true
    }

    @Test
    fun `a block with no expiry never ends and one with an expiry does`() {
        LogDetails.Block(duration = "infinite").isInfinite shouldBe true
        LogDetails.Block(duration = "1 week", expiry = time).isInfinite shouldBe false
    }

    @Test
    fun `a rights change lists the groups gained and lost and nothing it was not told`() {
        val promoted = LogDetails.Rights(added = listOf("sysop"), removed = listOf("rollbacker"))

        promoted.added shouldBe listOf("sysop")
        promoted.removed shouldBe listOf("rollbacker")
        LogDetails.Rights() shouldBe LogDetails.Rights(added = emptyList(), removed = emptyList())
    }

    @Test
    fun `a patrol is a person's with no previous revision unless the log says otherwise`() {
        val patrol = LogDetails.Patrol(RevisionId(42))

        patrol.revision shouldBe RevisionId(42)
        patrol.previous shouldBe null
        patrol.automatic shouldBe false
        LogDetails.Patrol(RevisionId(42), previous = RevisionId(41), automatic = true).automatic shouldBe true
    }

    @Test
    fun `a recent change carrying a log entry is a log action rather than an edit`() {
        val logged = RecentChange(7, "log", ref, "Someone", time, "spam", logEvent = entry())
        val edited = RecentChange(8, "edit", ref, "Someone", time, "fix", revisionId = RevisionId(2))

        logged.isLogEntry shouldBe true
        edited.isLogEntry shouldBe false
        edited.patrolled shouldBe null
    }
}
