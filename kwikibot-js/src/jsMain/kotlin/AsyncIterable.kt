import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise

/** What `for await` walks: TypeScript's own `AsyncIterable`, which every listing returns. */
public external interface AsyncIterable<out T>

/**
 * This flow as an async iterable, each walk of which collects the flow afresh, one item ahead of the walk.
 * Ending a walk early, with `break` or `return`, stops the collection, so no page is fetched that is not
 * read.
 */
internal fun <T, R> Flow<T>.toAsyncIterable(scope: CoroutineScope, convert: (T) -> R): AsyncIterable<R> {
    val flow = this
    val iterable: dynamic = js("({})")
    iterable[js("Symbol.asyncIterator")] = { FlowIterator(flow, scope, convert).toJs() }
    return iterable.unsafeCast<AsyncIterable<R>>()
}

/** One walk of a flow, as JavaScript's async iterator protocol asks for it. */
private class FlowIterator<T, R>(
    flow: Flow<T>,
    private val scope: CoroutineScope,
    private val convert: (T) -> R,
) {
    private val items = Channel<T>()

    private val collector =
        scope.launch(start = CoroutineStart.LAZY) {
            flow
                .onCompletion { cause -> if (cause == null) items.close() }
                .catch { failure -> items.close(failure) }
                .collect { items.send(it) }
        }

    fun next(): Promise<dynamic> = scope.promise {
        collector.start()
        val received = items.receiveCatching()
        received.exceptionOrNull()?.let { throw it }
        if (received.isClosed) iteratorResult(null, done = true)
        else iteratorResult(convert(received.getOrThrow()), done = false)
    }

    fun stop(): Promise<dynamic> = scope.promise {
        collector.cancel()
        items.cancel()
        iteratorResult(null, done = true)
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
