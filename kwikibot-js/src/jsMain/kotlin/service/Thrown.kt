import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * What a callback threw or rejected with when that was not an `Error`, such as a string, `undefined` or a
 * plain object: a run or a walk rejects with one of these, and [value] is what was thrown.
 */
@JsExport
public class ThrownValue
internal constructor(
    /** Exactly what was thrown. */
    public val value: Thrown
) : Throwable("a callback threw something that is not an Error: ${describeThrown(value)}")

/** [value] as a person reads it, whatever it is. A value can refuse to be written out, so that is caught. */
private fun describeThrown(value: Any?): String =
    try {
        js("String")(value).unsafeCast<String>()
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Throwable) {
        "a value of type ${jsTypeOf(value)}"
    }

/**
 * [thrown] as something coroutines can carry: anything but an `Error` takes them down with the process, so
 * every value JavaScript throws or rejects with passes through here before it reaches one.
 */
internal fun throwableOf(thrown: Any?): Throwable =
    if (thrown is Throwable) thrown else ThrownValue(thrown.unsafeCast<Thrown>())

/** Runs a function and says how that went, which a Kotlin `catch` cannot for what is not an `Error`. */
private val catching: dynamic =
    js(
        """
        (function (call) {
            try {
                return { value: call() };
            } catch (thrown) {
                return { threw: true, thrown: thrown };
            }
        })
        """
    )

/** What [callback], JavaScript's, returns, with whatever it throws made safe by [throwableOf]. */
internal fun <T> fromJs(callback: () -> T): T {
    val outcome = catching(callback)
    if (outcome.threw == true) throw throwableOf(outcome.thrown)
    return outcome.value.unsafeCast<T>()
}

/** What [result] settles to, when it is a promise or anything else with a `then`, or [result] itself. */
internal suspend fun settledResult(result: Any?): Any? {
    val candidate: dynamic = result
    if (result == null || fromJs { jsTypeOf(candidate.then) } != "function") return result
    return suspendCancellableCoroutine { waiting ->
        val _ =
            Promise.resolve<Any?>(result)
                .then(
                    { value -> waiting.resume(value) },
                    { reason -> waiting.resumeWithException(throwableOf(reason)) },
                )
    }
}

/** What [callback], JavaScript's, settles to, whatever it throws or rejects with. */
internal suspend fun settledCall(callback: () -> Any?): Any? = settledResult(fromJs(callback))

/**
 * What [result] settles to, taken to be what its type says, for a value that goes back to JavaScript as it
 * is.
 */
internal suspend fun <T> settled(callback: () -> Awaitable<T>): T = settledCall(callback).unsafeCast<T>()

/**
 * Says on the console, once for each one made, when the callback called [name] threw or rejected. For a
 * callback that only listens: what goes wrong in one is no reason to stop what it is listening to.
 */
internal class ObserverReport(private val name: String) {
    private var reported = false

    /** Runs [callback], reporting anything it throws, or rejects with when it returns a promise. */
    fun observing(callback: () -> Any?) {
        try {
            reportRejection(fromJs(callback))
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            report(thrown)
        }
    }

    /** Reports the rejection of [result], when it is a promise, which nothing else waits for. */
    fun reportRejection(result: Any?) {
        val candidate: dynamic = result
        if (result != null && jsTypeOf(candidate.then) == "function") {
            val _ = Promise.resolve<Any?>(result).then(null, { reason -> report(reason) })
        }
    }

    /** Says what went wrong, the first time. */
    fun report(thrown: Any?) {
        if (reported) return
        reported = true
        console.error(
            "kwikibot: $name failed, and later failures of it will not be said: ${throwableOf(thrown)}"
        )
    }
}
