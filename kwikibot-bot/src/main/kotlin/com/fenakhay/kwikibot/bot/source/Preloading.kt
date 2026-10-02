package com.fenakhay.kwikibot.bot.source

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Fetches the pages, in batches, as the stream is collected.
 *
 * The reason to batch is arithmetic: fetching fifty pages one at a time is fifty round trips, and the API
 * will hand over all fifty in one. Pages that do not exist are dropped, since there is nothing to give a
 * caller for them.
 *
 * The batch is filled from the stream before any request goes out, so a source that pages through a category
 * still only holds [batch] pages at a time.
 *
 * @param pages where the text is read from.
 * @param batch how many pages one request names. The wiki refuses more than the account may ask for.
 * @param concurrency how many batches are fetched at once. Pages still come out in the order they went in,
 *   and at most this many batches are in flight or waiting to be emitted. At one, nothing is fetched ahead of
 *   what the collector has asked for.
 */
public fun Flow<PageRef>.withContent(
    pages: PageService,
    batch: Int = DEFAULT_BATCH,
    concurrency: Int = 1,
): Flow<PageContent> {
    require(batch > 0) { "batch must be positive" }
    require(concurrency > 0) { "concurrency must be positive" }
    val batches = chunked(batch)

    if (concurrency == 1) {
        return flow { batches.collect { refs -> emitBatch(refs, pages.contents(refs)) } }
    }

    return flow {
        coroutineScope {
            // A permit is held from when a batch is asked for until its pages are emitted, so a slow
            // collector pauses the fetching instead of letting pages pile up.
            val permits = Semaphore(concurrency)
            val fetches = Channel<Pair<List<PageRef>, Deferred<Map<PageRef, PageContent>>>>(Channel.UNLIMITED)

            launch {
                batches.collect { refs ->
                    permits.acquire()
                    fetches.send(refs to async { pages.contents(refs) })
                }
                fetches.close()
            }

            for ((refs, fetch) in fetches) {
                emitBatch(refs, fetch.await())
                permits.release()
            }
        }
    }
}

/**
 * Fetches the pages of a wiki, in batches, as the stream is collected.
 *
 * @param wiki the wiki whose pages are read.
 * @param batch how many pages one request names. The default is as many as the account may ask for: 500 with
 *   `apihighlimits`, which bots hold, and 50 otherwise.
 * @param concurrency how many batches are fetched at once; see the [PageService] overload.
 */
public fun Flow<PageRef>.withContent(
    wiki: Wiki,
    batch: Int = wiki.identity.batchLimit,
    concurrency: Int = 1,
): Flow<PageContent> = withContent(wiki.pages, batch, concurrency)

/** The 1.1 signature, kept so code compiled against it still links. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public fun Flow<PageRef>.withContent(pages: PageService, batch: Int = DEFAULT_BATCH): Flow<PageContent> =
    withContent(pages, batch, concurrency = 1)

/** The 1.1 signature, kept so code compiled against it still links. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public fun Flow<PageRef>.withContent(wiki: Wiki, batch: Int = DEFAULT_BATCH): Flow<PageContent> =
    withContent(wiki.pages, batch, concurrency = 1)

/**
 * Emits one batch in the order it was asked for.
 *
 * Not the order the API returned it in: a bot that logs its work should log it in the order it was given, and
 * pages that do not exist simply do not appear.
 */
private suspend fun FlowCollector<PageContent>.emitBatch(
    refs: List<PageRef>,
    contents: Map<PageRef, PageContent>,
) {
    refs.forEach { ref -> contents[ref]?.let { emit(it) } }
}

/**
 * This flow in lists of at most [size], the last one shorter if the flow ends mid-batch.
 *
 * A size of one emits singletons, which keeps the caller's shape the same whether or not batching was asked
 * for.
 */
internal fun <T> Flow<T>.chunked(size: Int): Flow<List<T>> {
    require(size >= 1) { "a batch holds at least one item" }

    return flow {
        val batch = ArrayList<T>(size)

        collect { item ->
            batch += item
            if (batch.size == size) {
                emit(batch.toList())
                batch.clear()
            }
        }

        if (batch.isNotEmpty()) emit(batch.toList())
    }
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

/** How many pages one request fetches when nothing says the account may ask for more. */
private const val DEFAULT_BATCH = 50
