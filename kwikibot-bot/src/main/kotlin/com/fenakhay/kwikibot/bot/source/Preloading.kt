package com.fenakhay.kwikibot.bot.source

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

/**
 * Fetching page text for a stream of pages, and filtering on what it says.
 *
 * Batched because the arithmetic demands it: fetching fifty pages one at a time is fifty round trips, and the
 * API returns all fifty in one.
 */

/**
 * Fetches the pages, in batches, as the stream is collected.
 *
 * The reason to batch is arithmetic: fetching fifty pages one at a time is fifty round trips, and the API
 * will hand over all fifty in one. Pages that do not exist are dropped, since there is nothing to give a
 * caller for them.
 *
 * The batch is filled from the stream before any request goes out, so a source that pages through a category
 * still only holds [batch] pages at a time.
 */
public fun Flow<PageRef>.withContent(
    pages: PageService,
    batch: Int = DEFAULT_BATCH,
): Flow<PageContent> {
    require(batch > 0) { "batch must be positive" }
    val refs = this

    return flow {
        val pending = mutableListOf<PageRef>()

        refs.collect { ref ->
            pending += ref
            if (pending.size >= batch) {
                emitBatch(pages, pending)
                pending.clear()
            }
        }
        if (pending.isNotEmpty()) emitBatch(pages, pending)
    }
}

/** Fetches the pages of a wiki, in batches, as the stream is collected. */
public fun Flow<PageRef>.withContent(wiki: Wiki, batch: Int = DEFAULT_BATCH): Flow<PageContent> =
    withContent(wiki.pages, batch)

/**
 * Fetches one batch and emits it in the order it was asked for.
 *
 * Not the order the API returned it in: a bot that logs its work should log it in the order it was given, and
 * pages that do not exist simply do not appear.
 */
private suspend fun FlowCollector<PageContent>.emitBatch(pages: PageService, refs: List<PageRef>) {
    val contents = pages.contents(refs)
    refs.forEach { ref -> contents[ref]?.let { emit(it) } }
}

/** Only pages whose text matches [pattern]. */
public fun Flow<PageContent>.textMatching(pattern: Regex): Flow<PageContent> = filter {
    pattern.containsMatchIn(it.text)
}

/** Only pages whose text does not match [pattern]. */
public fun Flow<PageContent>.textNotMatching(pattern: Regex): Flow<PageContent> = filter {
    !pattern.containsMatchIn(it.text)
}

/** Only pages that are redirects, or only pages that are not. */
public fun Flow<PageContent>.redirects(keep: Boolean = true): Flow<PageContent> = filter {
    it.isRedirect == keep
}

/** How many pages one request fetches: what the API allows a bot account. */
private const val DEFAULT_BATCH = 50
