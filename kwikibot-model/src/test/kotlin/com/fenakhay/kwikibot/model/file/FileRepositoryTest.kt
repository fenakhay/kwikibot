package com.fenakhay.kwikibot.model.file

import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FileRepositoryTest {
    private val title = Title.Local(Namespace.MAIN, "volcano")
    private val ref = PageRef(WikiId("enwiktionary"), title)

    @Test
    fun `a shared repository is not local, which is what decides where an edit goes`() {
        val commons =
            FileRepository(
                name = "shared",
                displayName = "Wikimedia Commons",
                isLocal = false,
                url = "https://commons.wikimedia.org/wiki/File:$1",
                rootUrl = "https://upload.wikimedia.org",
            )

        commons.isLocal shouldBe false
        commons.displayName shouldBe "Wikimedia Commons"

        FileRepository(name = "local", displayName = "Test Wiki", isLocal = true).isLocal shouldBe true
    }
}
