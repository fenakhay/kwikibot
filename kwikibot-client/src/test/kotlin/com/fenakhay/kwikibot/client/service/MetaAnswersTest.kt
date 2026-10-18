package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.ApiMetaService
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.LanguageInfo
import com.fenakhay.kwikibot.model.TextDirection
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.testkit.MockTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** What the meta service makes of site information, messages and languages that say little. */
class MetaAnswersTest {

    @Test
    fun `an answer with no query has no property, no messages and no languages`() = runTest {
        val service = meta("{}")

        service.property("general").shouldBeNull()
        service.messages(listOf("edit")) shouldBe emptyMap()
        service.languages() shouldBe emptyMap()
        meta("""{"query":{}}""").languages() shouldBe emptyMap()
    }

    @Test
    fun `asking for no messages asks the wiki nothing`() = runTest {
        val transport = transport("{}")

        ApiMetaService(transport, TokenStore(transport)).messages(emptyList()) shouldBe emptyMap()
        transport.requests shouldBe emptyList()
    }

    @Test
    fun `a message with no name or no content is not one`() = runTest {
        val service =
            meta(
                """{"query":{"allmessages":[
                   {"content":"Edit"},{"name":"talk"},{"name":"save","content":"Save"}]}}"""
            )

        service.messages(listOf("edit", "talk", "save")) shouldBe mapOf("save" to "Save")
    }

    @Test
    fun `magic words that are not a list are none, and a word with no name is filed under an empty one`() =
        runTest {
            meta("""{"query":{"magicwords":{"redirect":["#REDIRECT"]}}}""").magicWords() shouldBe emptyMap()
            meta("""{"query":{"magicwords":[{"aliases":["#X"]}]}}""").magicWords() shouldBe
                mapOf("" to listOf("#X"))
        }

    @Test
    fun `statistics that are not counters are none, and a counter that is not a number is left out`() =
        runTest {
            meta("""{"query":{"statistics":[1,2]}}""").statistics() shouldBe emptyMap()
            meta("""{"query":{}}""").statistics() shouldBe emptyMap()
            meta("""{"query":{"statistics":{"pages":12,"edits":"many","users":"3"}}}""").statistics() shouldBe
                mapOf("pages" to 12L, "users" to 3L)
        }

    @Test
    fun `a language described by nothing is skipped, and one that says little reads as unknown`() = runTest {
        val service = meta("""{"query":{"languageinfo":{"xx":"unknown","fr":{"bcp47":""}}}}""")

        service.languages(listOf(LangCode("fr"), LangCode("xx"))) shouldBe
            mapOf(LangCode("fr") to LanguageInfo(LangCode("fr"), "", "", TextDirection.LEFT_TO_RIGHT))
    }

    @Test
    fun `tags are removed without being added, but not changed without either`() = runTest {
        val transport = transport("""{"tag":[]}""")
        val service = ApiMetaService(transport, TokenStore(transport))

        service.applyTags(remove = setOf("mobile edit"), revisions = listOf(9001))
        shouldThrow<IllegalArgumentException> { service.applyTags(revisions = listOf(9001)) }

        val sent = transport.requests.single { it.action == "tag" }.params
        sent["remove"] shouldBe "mobile edit"
        sent["add"].shouldBeNull()
    }

    private fun meta(answer: String): MetaService {
        val transport = transport(answer)
        return ApiMetaService(transport, TokenStore(transport))
    }

    private fun transport(answer: String) = MockTransport { request ->
        if (request.params["meta"] == "tokens") {
            MockTransport.json("""{"query":{"tokens":{"csrftoken":"TOKEN"}}}""")
        } else {
            MockTransport.json(answer)
        }
    }
}
