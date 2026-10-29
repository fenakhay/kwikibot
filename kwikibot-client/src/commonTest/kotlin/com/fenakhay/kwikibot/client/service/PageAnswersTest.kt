package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiPageService
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.ActionChecks
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.page.CategoryInfo
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.model.user.Contributor
import com.fenakhay.kwikibot.model.user.Contributors
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.testkit.MockTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/** The options a page service sends only when chosen, and what it makes of answers that say little. */
class PageAnswersTest {

    private val wiki = WikiId("enwiktionary")
    private val volcano = ref("volcano")
    private val lava = ref("lava")

    private fun ref(text: String, namespace: Namespace = Namespace.MAIN) =
        PageRef(wiki, Title.Local(namespace, text))

    @Test
    fun `a move sends what to leave behind and what to take along only as chosen`() = runTest {
        val transport = answering("""{"move":{}}""")
        val service = pages(transport)

        service.move(volcano, lava)
        service.move(volcano, lava, leaveRedirect = false, moveTalk = false, moveSubpages = true)

        val (plain, chosen) = transport.requests.filter { it.action == "move" }
        plain.params["movetalk"] shouldBe "1"
        plain.params["noredirect"].shouldBeNull()
        plain.params["movesubpages"].shouldBeNull()
        chosen.params["movetalk"].shouldBeNull()
        chosen.params["noredirect"] shouldBe "1"
        chosen.params["movesubpages"] shouldBe "1"
    }

    @Test
    fun `a deletion takes the talk page and a protection cascades only when asked`() = runTest {
        val transport = answering("""{"delete":{},"protect":{}}""")
        val service = pages(transport)

        service.delete(volcano, deleteTalk = true)
        service.delete(volcano)
        service.protect(volcano, listOf(Protection("edit", "sysop")), cascade = true)
        service.protect(volcano, emptyList())

        val writes = transport.requests.filter { it.isWrite }
        writes[0].params["deletetalk"] shouldBe "1"
        writes[1].params["deletetalk"].shouldBeNull()
        writes[2].params["cascade"] shouldBe "1"
        writes[2].params["protections"] shouldBe "edit=sysop"
        writes[3].params["cascade"].shouldBeNull()
        writes[3].params["protections"] shouldBe ""
    }

    @Test
    fun `a history merge names the last revision to move only when given one`() = runTest {
        val transport = answering("""{"mergehistory":{}}""")
        val upTo = Instant.parse("2026-09-30T12:00:00Z")

        pages(transport).mergeHistory(volcano, lava, upTo = upTo)
        pages(transport).mergeHistory(volcano, lava)

        val (bounded, whole) = transport.requests.filter { it.action == "mergehistory" }
        bounded.params["timestamp"] shouldBe MwTimestamp.format(upTo)
        whole.params["timestamp"].shouldBeNull()
    }

    @Test
    fun `a rollback sends a summary and the bot flag only when set`() = runTest {
        val transport = answering("""{"rollback":{"revid":10,"old_revid":9}}""", token = "rollbacktoken")
        val service = pages(transport)

        service.rollback(volcano, user = "Vandal", summary = "revert", markBot = false) shouldBe
            EditOutcome.Saved(volcano, RevisionId(10), RevisionId(9))
        service.rollback(volcano, user = "Vandal")

        val (chosen, plain) = transport.requests.filter { it.action == "rollback" }
        chosen.params["summary"] shouldBe "revert"
        chosen.params["markbot"].shouldBeNull()
        plain.params["summary"].shouldBeNull()
        plain.params["markbot"] shouldBe "1"
    }

    @Test
    fun `a rollback that names no earlier revision has none`() = runTest {
        val saved = pages(answering("""{"rollback":{"revid":10}}""", token = "rollbacktoken"))
        val unchanged = pages(answering("""{"rollback":{}}""", token = "rollbacktoken"))

        saved.rollback(volcano, user = "Vandal") shouldBe EditOutcome.Saved(volcano, RevisionId(10), null)
        unchanged.rollback(volcano, user = "Vandal") shouldBe EditOutcome.NoChange(volcano, null)
    }

    @Test
    fun `a refusal that is not about the edit is an error from a rollback or an undo`() = runTest {
        val refusal = """{"error":{"code":"readonly","info":"The wiki is read-only."}}"""
        val service = pages(answering(refusal, token = "rollbacktoken"))

        shouldThrow<WikiError> { service.rollback(volcano, user = "Vandal") }
        shouldThrow<WikiError> { pages(answering(refusal)).undo(volcano, RevisionId(10)) }
    }

    @Test
    fun `an undo of a range names its older end`() = runTest {
        val transport = answering("""{"edit":{"result":"Success","newrevid":12,"oldrevid":11}}""")

        pages(transport).undo(volcano, RevisionId(11), through = RevisionId(8)) shouldBe
            EditOutcome.Saved(volcano, RevisionId(12), RevisionId(11))

        transport.requests.single { it.action == "edit" }.params["undoafter"] shouldBe "8"
    }

    @Test
    fun `an edit that names no result is rejected and one with no earlier revision has none`() = runTest {
        val edit: EditBuilder.() -> Unit = { text = "x" }

        pages(answering("""{"edit":{}}""")).edit(volcano, edit) shouldBe
            EditOutcome.Rejected(volcano, "edit returned ", "")
        pages(answering("""{"edit":{"result":"Success","oldrevid":0}}""")).edit(volcano, edit) shouldBe
            EditOutcome.NoChange(volcano, null)
        pages(answering("""{"edit":{"result":"Success","newrevid":5}}""")).edit(volcano, edit) shouldBe
            EditOutcome.Saved(volcano, RevisionId(5), null)
        pages(answering("""{"edit":{"result":"Success","nochange":"","oldrevid":7}}"""))
            .edit(volcano, edit) shouldBe EditOutcome.NoChange(volcano, RevisionId(7))
    }

    @Test
    fun `a purge of nothing asks nothing and one that updates links says so`() = runTest {
        val transport = answering("""{"purge":[]}""")
        val service = pages(transport)

        service.purge(emptyList())
        service.purge(listOf(volcano), forceLinkUpdate = true)
        service.purge(listOf(volcano))

        val (forced, plain) = transport.requests
        forced.params["forcelinkupdate"] shouldBe "1"
        plain.params["forcelinkupdate"].shouldBeNull()
    }

    @Test
    fun `asking about no pages or no actions asks the wiki nothing`() = runTest {
        val transport = answering("{}")
        val service = pages(transport)

        service.contents(emptyList()) shouldBe emptyMap()
        service.protections(emptyList()) shouldBe emptyMap()
        service.testActions(listOf(volcano), actions = emptySet()) shouldBe emptyMap()
        service.contributors(emptyList()) shouldBe emptyMap()
        service.backlinksOf(emptyList()) shouldBe emptyMap()
        transport.requests shouldBe emptyList()
    }

    @Test
    fun `redirects can be limited to namespaces`() = runTest {
        val transport = answering("""{"query":{"pages":[]}}""")

        pages(transport).redirectsTo(listOf(volcano), setOf(Namespace.MAIN, Namespace.TEMPLATE))
        pages(transport).redirectsTo(listOf(volcano))

        val (limited, every) = transport.requests
        limited.params["rdnamespace"] shouldBe "0|10"
        every.params["rdnamespace"].shouldBeNull()
    }

    @Test
    fun `a protection with no type is skipped and one with nothing else is at no level and never ends`() =
        runTest {
            val service =
                pages(
                    answering(
                        """{"query":{"pages":[
                           {"ns":0,"title":"volcano","protection":[{},{"type":"edit"},
                             {"type":"move","level":"sysop","expiry":"2026-12-01T00:00:00Z"}]},
                           {"pageid":3,"protection":[{"type":"move"}]},
                           {"ns":0,"title":"ash","protection":[]}]}}"""
                    )
                )

            service.protections(listOf(volcano, lava)) shouldBe
                mapOf(
                    volcano to
                        listOf(
                            Protection("edit", "", Expiry.Never, cascading = false),
                            Protection(
                                "move",
                                "sysop",
                                Expiry.At(Instant.parse("2026-12-01T00:00:00Z")),
                                cascading = false,
                            ),
                        )
                )
        }

    @Test
    fun `a page with no tested actions is absent and actions with no refusal list have no refusals`() =
        runTest {
            val service =
                pages(
                    answering(
                        """{"query":{"pages":[{"ns":0,"title":"volcano","actions":{"edit":{},"move":[{}]}},
                           {"ns":0,"title":"lava"}]}}"""
                    )
                )

            service.testActions(listOf(volcano, lava), setOf("edit", "move")) shouldBe
                mapOf(volcano to ActionChecks(mapOf("edit" to emptyList(), "move" to emptyList())))
        }

    @Test
    fun `contributors without a name are skipped and a page with no one counted is absent`() = runTest {
        val service =
            pages(
                answering(
                    """{"query":{"pages":[
                       {"ns":0,"title":"volcano","contributors":[{"userid":1},{"name":"Alice"}]},
                       {"ns":0,"title":"lava","anoncontributors":3},
                       {"ns":0,"title":"ash","contributors":[]},
                       {"title":"pumice","contributors":[{"name":"Bob"}]}]}}"""
                )
            )
        val ash = ref("ash")

        service.contributors(listOf(volcano, lava, ash)) shouldBe
            mapOf(
                volcano to Contributors(listOf(Contributor("Alice", 0)), anonymous = 0),
                lava to Contributors(emptyList(), anonymous = 3),
            )
    }

    @Test
    fun `a category that leaves out its counts counts nothing`() = runTest {
        val category = ref("English nouns", Namespace.CATEGORY)
        val service =
            pages(
                answering(
                    """{"query":{"pages":[{"ns":14,"title":"Category:English nouns","categoryinfo":{}},
                       {"pageid":3,"categoryinfo":{"size":1}}]}}"""
                )
            )

        service.categoryInfo(listOf(category, volcano)) shouldBe
            mapOf(category to CategoryInfo(size = 0, pages = 0, files = 0, subcategories = 0, hidden = false))
    }

    @Test
    fun `an entry the wiki gives no title is not read as a page`() = runTest {
        val service =
            pages(answering("""{"query":{"pages":[{"pageid":1,"linkshere":[{"ns":0,"title":"lava"}]}]}}"""))

        service.backlinksOf(listOf(volcano)) shouldBe emptyMap()
    }

    @Test
    fun `texts past one request's worth by number or length are expanded in another and no texts in none`() =
        runTest {
            val transport = MockTransport { request ->
                MockTransport.json(
                    """{"expandtemplates":{"wikitext":${JsonPrimitive(request.params.getValue("text"))}}}"""
                )
            }
            val texts = (1..EXPANSION_BATCH + 1).map { "text $it" }

            val long = listOf("a".repeat(EXPANSION_CHARACTERS), "b")

            pages(transport).expandTexts(emptyList()) shouldBe emptyList()
            pages(transport).expandTexts(texts).map { it.text } shouldBe texts
            pages(transport).expandTexts(long).map { it.text } shouldBe long

            transport.requests.size shouldBe 4
        }

    @Test
    fun `an expansion with no expansion block is an error and a category with no name is skipped`() =
        runTest {
            shouldThrow<WikiError.Api> { pages(answering("{}")).expandText("{{x}}") }.code shouldBe
                "noexpansion"

            val categorised =
                pages(
                    answering("""{"expandtemplates":{"wikitext":"x","categories":[{},{"category":"A_b"}]}}""")
                )
            categorised.expandTexts(listOf("{{x}}"), categories = true).single().categories shouldBe
                listOf("A b")
        }

    private fun pages(transport: MockTransport): PageService =
        ApiPageService(
            transport = transport,
            tokens = TokenStore(transport),
            decoder = PageDecoder(wiki, NamespaceMap.CANONICAL),
            namespaces = NamespaceMap.CANONICAL,
        )

    /** A wiki that hands out a [token] and answers every other request with [answer]. */
    private fun answering(answer: String, token: String = "csrftoken") = MockTransport { request ->
        if (request.params["meta"] == "tokens") {
            MockTransport.json("""{"query":{"tokens":{"$token":"TOKEN"}}}""")
        } else {
            MockTransport.json(answer)
        }
    }

    private companion object {
        /** The most texts the service puts in one expansion request. */
        const val EXPANSION_BATCH = 50

        /** The most characters of text the service puts in one expansion request. */
        const val EXPANSION_CHARACTERS = 1_000_000
    }
}
