package com.fenakhay.kwikibot.wikitext

import kotlin.test.Test
import kotlin.test.fail

class PathologicalInputTest {

    @Test
    fun `unclosed style markup inside templates does not blow up`() {
        parseWithin("{{outer|" + "{{#ifeq:{{pagename}}|a|'''}}".repeat(400) + "}}")
    }

    @Test
    fun `runs of unclosed constructs do not blow up`() {
        parseWithin("{{a|".repeat(400))
        parseWithin("[[a|".repeat(400))
        parseWithin("<ref>".repeat(400))
        parseWithin("{{{a|".repeat(400))
        parseWithin("[http://example.org ".repeat(400))
    }

    @Test
    fun `deeply nested constructs do not blow up`() {
        parseWithin("{{a|".repeat(200) + "x" + "}}".repeat(200))
        parseWithin("[[a|".repeat(200) + "x" + "]]".repeat(200))
    }

    private fun parseWithin(wikitext: String) {
        var thrown: Throwable? = null

        val worker = Thread { runCatching { Wikitext.parse(wikitext).serialize() }.onFailure { thrown = it } }
        worker.isDaemon = true
        worker.start()
        worker.join(DEADLINE_MILLIS)

        if (worker.isAlive) {
            fail("parsing ${wikitext.length} characters did not finish within ${DEADLINE_MILLIS}ms")
        }
        thrown?.let { throw it }
    }

    private companion object {
        const val DEADLINE_MILLIS = 20_000L
    }
}
