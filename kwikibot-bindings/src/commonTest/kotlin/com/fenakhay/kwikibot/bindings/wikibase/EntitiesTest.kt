package com.fenakhay.kwikibot.bindings.wikibase

import com.fenakhay.kwikibot.bindings.Calls
import com.fenakhay.kwikibot.bindings.openWiki
import com.fenakhay.kwikibot.wikibase.EntityService
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.wikibase
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** The Wikibase operations with defaults: what each sends with nothing given, and with everything given. */
class EntitiesTest {

    private class Recording(base: EntityService, val calls: Calls) : EntityService by base {
        override suspend fun mergeItems(
            from: EntityId,
            into: EntityId,
            ignoreConflicts: Set<String>,
            summary: String,
        ) {
            calls.record("mergeItems", from, into, ignoreConflicts, summary)
        }
    }

    @Test
    fun `each operation sends npm's defaults or what it was given`() = runTest {
        val calls = Calls()
        val entities = Recording(openWiki().wikibase(), calls)
        val q1 = EntityId("Q1")
        val q2 = EntityId("Q2")
        val cases: List<Pair<suspend () -> Any?, List<Any?>>> =
            listOf(
                suspend { entities.mergeItemsWithDefaults("Q1", "Q2") } to
                    listOf("mergeItems", q1, q2, emptySet<String>(), ""),
                suspend { entities.mergeItemsWithDefaults("Q1", "Q2", listOf("sitelink"), "s") } to
                    listOf("mergeItems", q1, q2, setOf("sitelink"), "s"),
            )
        cases.forEach { (call, expected) ->
            calls.clear()
            val _ = runCatching { call() }
            calls.only shouldBe expected
        }
    }
}
