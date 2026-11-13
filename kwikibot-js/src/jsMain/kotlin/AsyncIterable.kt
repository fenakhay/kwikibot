@file:Suppress("TooGenericExceptionCaught")

import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise

/** What `for await` walks: TypeScript's own `AsyncIterable`, which every listing returns. */
public external interface AsyncIterable<out T>

/**
 * This flow as an async iterable, each walk of which collects the flow afresh, one item ahead of the walk.
 * Ending a walk early, with `break` or `return`, stops the collection, so no page is fetched that is not
 * read. A failure of the flow, however it comes, rejects the walk.
 */
internal fun <T, R> Flow<T>.toAsyncIterable(scope: CoroutineScope, convert: (T) -> R): AsyncIterable<R> =
    asyncIterable(this, scope, false, convert)

/**
 * This flow as [toAsyncIterable] makes it, a walk of which cancelling [scope] ends rather than rejects: for
 * what never ends of itself, so that closing it is how a walk is meant to finish.
 */
internal fun <T, R> Flow<T>.toAsyncIterableEndingWith(
    scope: CoroutineScope,
    convert: (T) -> R,
): AsyncIterable<R> = asyncIterable(this, scope, true, convert)

private fun <T, R> asyncIterable(
    flow: Flow<T>,
    scope: CoroutineScope,
    endsWithScope: Boolean,
    convert: (T) -> R,
): AsyncIterable<R> {
    val iterable: dynamic = js("({})")
    iterable[js("Symbol.asyncIterator")] = { FlowIterator(flow, scope, endsWithScope, convert).toJs() }
    return iterable.unsafeCast<AsyncIterable<R>>()
}

/**
 * The flow [listing] makes, or when what it asks of is left unimplemented, as a service of `createFakeWiki`
 * is, a flow failing with that: a listing rejects as it is walked, as everything else on a fake wiki rejects.
 */
internal inline fun <T> listing(listing: () -> Flow<T>): Flow<T> =
    try {
        listing()
    } catch (unimplemented: NotImplementedError) {
        flow { throw unimplemented }
    }

/** One walk of a flow, as JavaScript's async iterator protocol asks for it. */
private class FlowIterator<T, R>(
    flow: Flow<T>,
    private val scope: CoroutineScope,
    private val endsWithScope: Boolean,
    private val convert: (T) -> R,
) {
    private val items = Channel<T>()

    private val collector =
        scope.launch(start = CoroutineStart.LAZY) {
            try {
                flow.collect { items.send(it) }
                items.close()
            } catch (failure: Throwable) {
                val _ = items.close(failure)
                if (failure is CancellationException && !isActive) throw failure
            }
        }

    private var stopped = false

    fun next(): Promise<dynamic> =
        when {
            stopped -> Promise.resolve(iteratorResult(null, done = true))
            endsWithScope -> nextOrEnd()
            else -> scope.promise { receive() }
        }

    @OptIn(DelicateCoroutinesApi::class)
    private fun nextOrEnd(): Promise<dynamic> = GlobalScope.promise {
        try {
            scope.async { receive() }.await()
        } catch (_: CancellationException) {
            iteratorResult(null, done = true)
        }
    }

    private suspend fun receive(): dynamic {
        collector.start()
        val received = items.receiveCatching()
        received.exceptionOrNull()?.let { throw it }
        return if (received.isClosed) iteratorResult(null, done = true)
        else iteratorResult(convert(received.getOrThrow()), done = false)
    }

    /**
     * Ends this walk, having stopped the collection, whatever has become of [scope], since a loop leaving
     * after a close is not at fault.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun stop(): Promise<dynamic> {
        stopped = true
        return GlobalScope.promise {
            collector.cancelAndJoin()
            items.cancel()
            iteratorResult(null, done = true)
        }
    }

    fun toJs(): dynamic {
        val iterator: dynamic = js("({})")
        iterator.next = { next() }
        iterator["return"] = { stop() }
        return iterator
    }
}

private fun iteratorResult(value: Any?, done: Boolean): dynamic {
    val result: dynamic = js("({})")
    result.value = value
    result.done = done
    return result
}
