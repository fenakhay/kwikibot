package com.fenakhay.kwikibot.bindings.titles

import com.fenakhay.kwikibot.bindings.bot.repeatingWithDefaults
import com.fenakhay.kwikibot.bindings.bot.withContentWithDefaults
import com.fenakhay.kwikibot.bindings.wikitext.tagOf
import com.fenakhay.kwikibot.bot.source.PageSource
import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceInfo
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.model.title.TitleCase
import com.fenakhay.kwikibot.testkit.FakeWiki
import com.fenakhay.kwikibot.wikitext.node.Tag
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/** Titles, tags and page sources as a binding makes them, with nothing given and with everything given. */
class TitlesTest {

    @Test
    fun `a namespace has no alias keeps its first letter and has no subpages unless told`() {
        namespaceInfoOf(100, "Portal", "Portail") shouldBe NamespaceInfo(Namespace(100), "Portal", "Portail")
        namespaceInfoOf(100, null, "Portail", listOf("P"), TitleCase.entries.last(), true) shouldBe
            NamespaceInfo(Namespace(100), null, "Portail", listOf("P"), TitleCase.entries.last(), true)
    }

    @Test
    fun `a title is read against MediaWiki's own namespaces in the main one unless told`() {
        parseTitleWithDefaults("Talk:Volcano") shouldBe Title.parse("Talk:Volcano")
        val namespaces = NamespaceMap.CANONICAL
        parseTitleWithDefaults("Volcano", namespaces, InterwikiMap.EMPTY, 10) shouldBe
            Title.parse("Template:Volcano")
    }

    @Test
    fun `a tag is plain unless told`() {
        tagOf("ref") shouldBe Tag("ref")
        tagOf("br", null, emptyList(), true, "<br/>", " ", true, "</br>", true) shouldBe
            Tag("br", null, emptyList(), true, "<br/>", " ", true, "</br>", true)
    }

    @Test
    fun `a source repeats every minute and pages come with their text as many to a request as allowed`() =
        runTest {
            val wiki = FakeWiki("Volcano" to "lava")
            val once = PageSource { flowOf(wiki.ref("Volcano")) }
            once.repeatingWithDefaults().pages(wiki).first() shouldBe wiki.ref("Volcano")
            once.repeatingWithDefaults(1.seconds, 1).pages(wiki).first() shouldBe wiki.ref("Volcano")
            flowOf(wiki.ref("Volcano")).withContentWithDefaults(wiki).toList().single().text shouldBe "lava"
            flowOf(wiki.ref("Volcano")).withContentWithDefaults(wiki, 1, 1).toList().single().text shouldBe
                "lava"
        }
}
