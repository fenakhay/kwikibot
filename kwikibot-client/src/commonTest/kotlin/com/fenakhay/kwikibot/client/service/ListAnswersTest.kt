package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiListService
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.page.InterwikiLink
import com.fenakhay.kwikibot.model.page.LanguageLink
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.testkit.MockTransport
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/** The options a list service sends only when chosen, and what it makes of entries that say little. */
class ListAnswersTest {

    private val wiki = WikiId("enwiktionary")

    private fun ref(text: String, namespace: Namespace = Namespace.MAIN) =
        PageRef(wiki, Title.Local(namespace, text))

    @Test
    fun `backlinks leave out redirects unless asked and an entry with no title is not a page`() = runTest {
        val transport =
            answer(
                """{"query":{"backlinks":[{"pageid":1},
                   {"ns":0,"title":"lava","redirlinks":[{"pageid":3},{"ns":0,"title":"ash"}]}]}}"""
            )

        lists(transport).backlinks(ref("volcano"), includeRedirects = false).toList() shouldBe
            listOf(ref("lava"), ref("ash"))

        transport.requests.single().params["blredirect"].shouldBeNull()
    }

    @Test
    fun `a language link with no language is skipped rather than ending the list`() = runTest {
        val service =
            lists(
                answer(
                    """{"query":{"pages":[{"ns":0,"title":"volcano",
                       "langlinks":[{},{"lang":" "},{"lang":"fr"}]}]}}"""
                )
            )

        service.languageLinksOn(ref("volcano")).toList() shouldBe listOf(LanguageLink(LangCode("fr"), ""))
    }

    @Test
    fun `an interwiki or external link that says nothing reads as empty`() = runTest {
        val service =
            lists(
                answer("""{"query":{"pages":[{"ns":0,"title":"volcano","iwlinks":[{}],"extlinks":[{}]}]}}""")
            )

        service.interwikiLinksOn(ref("volcano")).toList() shouldBe listOf(InterwikiLink("", ""))
        service.externalLinksOn(ref("volcano")).toList() shouldBe listOf("")
    }

    @Test
    fun `a category enumeration sends its size bounds only when given and skips an entry with no name`() =
        runTest {
            val transport =
                answer("""{"query":{"allcategories":[{},{"category":""},{"category":"Lava_flows"}]}}""")

            lists(transport).allCategories(min = 1, max = 10).toList() shouldBe
                listOf(ref("Lava flows", Namespace.CATEGORY))
            lists(transport).allCategories().toList()

            val (bounded, unbounded) = transport.requests
            bounded.params["acmin"] shouldBe "1"
            bounded.params["acmax"] shouldBe "10"
            unbounded.params["acmin"].shouldBeNull()
            unbounded.params["acmax"].shouldBeNull()
        }

    @Test
    fun `a prefix search can be limited to namespaces`() = runTest {
        val transport = answer("""{"query":{"prefixsearch":[]}}""")

        lists(transport).prefixSearch("vol", namespaces = setOf(Namespace.MAIN, Namespace.TEMPLATE)).toList()
        lists(transport).prefixSearch("vol", namespaces = emptySet()).toList()

        transport.requests.map { it.params["psnamespace"] } shouldBe listOf("0|10", null)
    }

    @Test
    fun `a special page answered with no query block or a result with no title lists nothing`() = runTest {
        lists(answer("{}")).specialPage("Lonelypages").toList() shouldBe emptyList()
        lists(answer("""{"query":{}}""")).specialPage("Lonelypages").toList() shouldBe emptyList()
        lists(answer("""{"query":{"querypage":{"results":[{"value":"0"}]}}}"""))
            .specialPage("Lonelypages")
            .toList() shouldBe emptyList()
    }

    @Test
    fun `random pages are asked for no more than ten at a time or fewer when fewer are wanted`() = runTest {
        val transport =
            answer(
                """{"query":{"random":[
                   {"ns":0,"title":"volcano"},{"ns":0,"title":"lava"},{"ns":0,"title":"ash"}]}}"""
            )

        lists(transport).randomPages(limit = 2).toList() shouldBe listOf(ref("volcano"), ref("lava"))
        lists(transport).randomPages(limit = 50).toList()

        transport.requests.map { it.params["rnlimit"] } shouldBe listOf("2", "10")
    }

    private fun lists(transport: MockTransport): ListService =
        ApiListService(
            transport = transport,
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
        )

    private fun answer(body: String) = MockTransport { MockTransport.json(body) }
}
