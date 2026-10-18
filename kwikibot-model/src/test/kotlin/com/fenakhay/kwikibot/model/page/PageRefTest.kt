package com.fenakhay.kwikibot.model.page

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class PageRefTest {
    private val wiki = WikiId("enwiktionary")
    private val ref = PageRef(wiki, Title.Local(Namespace.CATEGORY, "English lemmas"))

    @Test
    fun `a reference prints as its title and the wiki it is on`() {
        wiki.toString() shouldBe "enwiktionary"
        ref.toString() shouldBe "Category:English lemmas@enwiktionary"
        ref.namespace shouldBe Namespace.CATEGORY
    }

    @Test
    fun `a wiki needs a name`() {
        assertFailsWith<IllegalArgumentException> { WikiId(" ") }
    }

    @Test
    fun `revision deletion shows as a missing author or summary`() {
        val time = Instant.parse("2026-08-31T21:43:26Z")
        val open = Revision(RevisionId(2), timestamp = time, user = "Someone", comment = "fix")
        val hidden = Revision(RevisionId(2), timestamp = time)

        open.isUserHidden shouldBe false
        open.isCommentHidden shouldBe false
        hidden.isUserHidden shouldBe true
        hidden.isCommentHidden shouldBe true
    }

    @Test
    fun `a page is a redirect exactly when it names a target`() {
        val target = Title.Local(Namespace.MAIN, "Volcano")

        PageContent(ref, RevisionId(1), "#REDIRECT [[Volcano]]", redirectTarget = target).isRedirect shouldBe
            true
        PageContent(ref, RevisionId(1), "text").isRedirect shouldBe false
        PageContent(ref, RevisionId(1), "text").title shouldBe ref.title
    }
}
