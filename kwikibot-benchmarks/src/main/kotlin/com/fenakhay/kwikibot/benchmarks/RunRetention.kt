package com.fenakhay.kwikibot.benchmarks

import com.fenakhay.kwikibot.bot.run.BotReport
import com.fenakhay.kwikibot.bot.run.Edit
import com.fenakhay.kwikibot.bot.run.botRun
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.testkit.FakePageService
import java.util.Locale
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking

/**
 * How much a finished run still holds, which the allocation harness cannot say.
 *
 * The heap is read with the report held and again with it dropped; the gap is what the report costs.
 */
public fun main() {
    measureRetention(PAGES, SENTENCES, System.out, ::heapInUse)
}

/**
 * What [main] does: a dry run over [pages] pages of [sentences] sentences each, printing to [out] what the
 * report it returns holds, as the difference between two readings of [heapInUse].
 */
internal fun measureRetention(pages: Int, sentences: Int, out: Appendable, heapInUse: () -> Long) {
    val wiki = WikiId("testwiki")
    val text = "the text of a page, long enough to be worth counting. ".repeat(sentences)

    val service = FakePageService((1..pages).associate { "page$it" to text })
    val refs = (1..pages).map { PageRef(wiki, Title.Local(Namespace.MAIN, "page$it")) }

    val megabytes = pages.toLong() * text.length / KB / KB
    out.appendLine("pages: $pages, ${text.length} chars each, $megabytes MB of text")

    // A dry run, which is the default: a `Pending` carries the new text and the old.
    var report: BotReport? = runBlocking {
        botRun(service) {
            source(refs.asFlow())
            transform { Edit(it.text + " and one more sentence.", "adding") }
        }
    }

    out.appendLine("run: $report")

    val holding = heapInUse()
    report = null
    val released = heapInUse()

    out.appendLine()
    out.appendLine("heap with the report held:    ${kilobytes(holding)} KB")
    out.appendLine("heap with the report dropped: ${kilobytes(released)} KB")
    out.appendLine("the report was holding:       ${kilobytes((holding - released).coerceAtLeast(0))} KB")
}

/** [bytes] in whole kilobytes, grouped in thousands the same way on every machine. */
private fun kilobytes(bytes: Long): String = "%,d".format(Locale.ROOT, bytes / KB)

/** Heap in use, after requesting a collection [GC_ROUNDS] times with [GC_PAUSE] ms between each. */
internal fun heapInUse(): Long {
    val runtime = Runtime.getRuntime()
    repeat(GC_ROUNDS) {
        System.gc()
        Thread.sleep(GC_PAUSE)
    }
    return runtime.totalMemory() - runtime.freeMemory()
}

/** How many pages the run walks. */
private const val PAGES = 20_000

/** Sentences per page, which makes each page a couple of kilobytes. */
private const val SENTENCES = 40

/** Bytes in a kilobyte, for reporting. */
private const val KB = 1024

private const val GC_ROUNDS = 4

private const val GC_PAUSE = 50L
