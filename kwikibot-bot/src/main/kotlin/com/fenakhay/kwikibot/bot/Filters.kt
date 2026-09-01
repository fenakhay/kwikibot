package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.PageRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/**
 * Narrowing a stream of pages before anything is fetched.
 *
 * `Flow.filter` covers the simple cases. These are the operators with a rule worth implementing
 * once: deduplication, subpage depth, and the two-stream operations.
 */

/** Only pages in one of [namespaces]. Every namespace passes when the set is empty. */
public fun Flow<PageRef>.inNamespaces(namespaces: Set<Namespace>): Flow<PageRef> =
    if (namespaces.isEmpty()) this else filter { it.namespace in namespaces }

/** Only pages whose title matches [pattern]. */
public fun Flow<PageRef>.titleMatching(pattern: Regex): Flow<PageRef> =
    filter { pattern.containsMatchIn(it.title.text) }

/** Only pages whose title does not match [pattern]. */
public fun Flow<PageRef>.titleNotMatching(pattern: Regex): Flow<PageRef> =
    filter { !pattern.containsMatchIn(it.title.text) }

/**
 * Drops pages already seen.
 *
 * Two sources overlap more often than not — a category and a template both name the same page —
 * and editing it twice in one run means the second edit reverts nothing and logs a confusing
 * no-change. The seen set grows with the stream, which is the price of the guarantee.
 */
public fun Flow<PageRef>.distinctPages(): Flow<PageRef> = flow {
    val seen = mutableSetOf<PageRef>()
    collect { if (seen.add(it)) emit(it) }
}

/**
 * Only pages no deeper than [maxDepth] slashes below their base page.
 *
 * `0` keeps base pages only, which is how a bot avoids archives and sandboxes without listing
 * them.
 */
public fun Flow<PageRef>.subpageDepthAtMost(maxDepth: Int): Flow<PageRef> =
    filter { it.title.text.count { character -> character == '/' } <= maxDepth }

/**
 * Only pages that [other] also names.
 *
 * [other] is collected in full first, because an intersection cannot be decided page by page.
 * That makes it the one operator here that is not lazy, so put the smaller stream second.
 */
public fun Flow<PageRef>.intersect(other: Flow<PageRef>): Flow<PageRef> = flow {
    val keep = other.toList().toSet()
    collect { if (it in keep) emit(it) }
}

/** Only pages [other] does not name. Collects [other] in full first, as [intersect] does. */
public fun Flow<PageRef>.excluding(other: Flow<PageRef>): Flow<PageRef> = flow {
    val skip = other.toList().toSet()
    collect { if (it !in skip) emit(it) }
}
