package com.fenakhay.kwikibot.client.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take

/** The most results a bot account gets from one request to a list module. */
internal const val MAX_BATCH = 500

/**
 * How many results to ask for per request, for a caller that wants at most [limit].
 *
 * `max` lets the wiki decide, which is 500 for a bot account and 50 for anyone else. A small explicit limit
 * avoids fetching five hundred results to use three.
 */
internal fun limitParam(limit: Int?): String =
    if (limit != null && limit < MAX_BATCH) limit.toString() else "max"

/** The first [limit] items, or all of them when there is no limit. */
internal fun <T> Flow<T>.limitedTo(limit: Int?): Flow<T> = if (limit == null) this else take(limit)
