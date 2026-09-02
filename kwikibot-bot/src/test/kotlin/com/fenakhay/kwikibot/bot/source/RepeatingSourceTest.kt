package com.fenakhay.kwikibot.bot.source

import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class RepeatingSourceTest {

    private val wiki = WikiId("testwiki")

    private fun ref(text: String) = PageRef(wiki, Title.Local(Namespace.MAIN, text))

    private val fake = FakeWiki(pages = FakePageService(emptyMap(), wiki), id = wiki)

    private fun source(vararg titles: String) = PageSource {
        titles.map { ref(it) }.asFlow()
    }

    @Test
    fun `a page already seen is not emitted again on the next round`() = runTest {
        val repeating = source("a", "b").repeating()

        val emitted = repeating.pages(fake).take(2).toList()

        emitted.map { it.title.text } shouldBe listOf("a", "b")
    }

    @Test
    fun `the memory is bounded, so a long run does not grow without limit`() = runTest {
        val repeating = source("a", "b", "c").repeating(remember = 2)

        val emitted = repeating.pages(fake).take(4).toList()

        emitted.map { it.title.text } shouldBe listOf("a", "b", "c", "a")
    }
}
