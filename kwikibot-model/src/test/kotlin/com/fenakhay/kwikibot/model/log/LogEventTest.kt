package com.fenakhay.kwikibot.model.log

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.test.Test
import kotlin.time.Instant

class LogEventTest {
    private val ref = PageRef(WikiId("enwiktionary"), Title.Local(Namespace.MAIN, "volcano"))
    private val time = Instant.parse("2026-08-31T21:43:26Z")

    private fun entry(page: PageRef? = ref, user: String? = "Someone", comment: String? = "spam") =
        LogEvent(1, "delete", "delete", page, user, time, comment)

    @Test
    fun `an entry with any field hidden is suppressed, and one with none hidden is not`() {
        entry().isSuppressed shouldBe false
        entry(page = null).isSuppressed shouldBe true
        entry(user = null).isSuppressed shouldBe true
        entry(comment = null).isSuppressed shouldBe true
    }

    @Test
    fun `a block with no expiry never ends, and one with an expiry does`() {
        LogDetails.Block(duration = "infinite").isInfinite shouldBe true
        LogDetails.Block(duration = "1 week", expiry = time).isInfinite shouldBe false
    }

    @Test
    fun `a rights change lists the groups gained and lost, and nothing it was not told`() {
        val promoted = LogDetails.Rights(added = listOf("sysop"), removed = listOf("rollbacker"))

        promoted.added shouldBe listOf("sysop")
        promoted.removed shouldBe listOf("rollbacker")
        LogDetails.Rights() shouldBe LogDetails.Rights(added = emptyList(), removed = emptyList())
    }

    @Test
    fun `a patrol is a person's, with no previous revision, unless the log says otherwise`() {
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

    @Test
    fun `code compiled against the 1_1 constructor still links, and leaves patrolled unknown`() {
        val oneOne =
            RecentChange::class.java.getConstructor(*oneOneParameters, DefaultConstructorMarker::class.java)

        val change =
            oneOne.newInstance(
                7L,
                "edit",
                ref,
                "Someone",
                time,
                "fix",
                RevisionId(2),
                RevisionId(1),
                12,
                true,
                false,
                false,
                true,
                null,
                listOf("typo"),
                null,
            )

        change shouldBe
            RecentChange(
                7,
                "edit",
                ref,
                "Someone",
                time,
                "fix",
                revisionId = RevisionId(2),
                previousRevisionId = RevisionId(1),
                sizeChange = 12,
                isMinor = true,
                isPatrolled = true,
                tags = listOf("typo"),
            )
        change.patrolled shouldBe null
    }

    @Test
    fun `a 1_1 call that left the optional arguments out still links`() {
        // Such a call passes the same parameters, then one bit for each argument left to its default.
        val defaulted = (6..14).sumOf { 1 shl it }
        val withDefaults =
            RecentChange::class
                .java
                .getConstructor(*oneOneParameters, Int::class.java, DefaultConstructorMarker::class.java)

        val change =
            withDefaults.newInstance(
                7L,
                "edit",
                ref,
                "Someone",
                time,
                "fix",
                null,
                null,
                0,
                false,
                false,
                false,
                false,
                null,
                null,
                defaulted,
                null,
            )

        change shouldBe RecentChange(7, "edit", ref, "Someone", time, "fix")
    }

    /**
     * The parameters of the constructor 1.1 callers were compiled against, as the JVM sees them. RevisionId
     * is a value class, so Kotlin also gives every constructor taking one a trailing marker parameter.
     */
    private val oneOneParameters: Array<Class<*>> =
        arrayOf(
            Long::class.java,
            String::class.java,
            PageRef::class.java,
            String::class.java,
            Instant::class.java,
            String::class.java,
            RevisionId::class.java,
            RevisionId::class.java,
            Int::class.java,
            Boolean::class.java,
            Boolean::class.java,
            Boolean::class.java,
            Boolean::class.java,
            LogEvent::class.java,
            List::class.java,
        )
}
