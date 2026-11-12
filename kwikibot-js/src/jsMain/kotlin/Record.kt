import com.fenakhay.kwikibot.bindings.common.enumArgumentOrNull
import kotlin.js.Date
import kotlin.time.Instant

/**
 * An object used as a table from [K] to [V]: TypeScript's own `Record`, which a table of results is typed as.
 */
public external interface Record<K, out V>

/** A value of [T] or a promise of one: what a callback handed to this package may answer with. */
public external interface Awaitable<out T>

/**
 * Any value JSON can write: what the wiki's API speaks, as the package's type of the same name spells out.
 */
public external interface JsonValue

/** What a parameter of `Wiki.call` may be, as the package's type of the same name spells out. */
public external interface ApiValue

/**
 * What a callback may answer when nothing reads its answer, so a one-line arrow function can return anything.
 */
public external interface Ignored

/** What a `botMain` block may answer: the exit status, or nothing for 0. */
public external interface ExitStatus

/** What fills a placeholder of a message: a string or a number. */
public external interface MessageValue

/** The platform's own signal that something was aborted, which `fetch` and the like take. */
public external interface AbortSignal

/**
 * The entry of [E] that [value], a union of string literals of this package's, names, or `null` when it was
 * not given.
 *
 * @throws IllegalArgumentException when it names none, saying which it could have been.
 */
internal inline fun <reified E : Enum<E>> enumOf(parameter: String, value: Any?): E? =
    enumArgumentOrNull<E>(parameter, value?.unsafeCast<String>())

/** [instant] as JavaScript's date. */
internal fun dateOf(instant: Instant): Date = Date(instant.toEpochMilliseconds().toDouble())

/**
 * [date] as an instant.
 *
 * @throws IllegalArgumentException when it is JavaScript's invalid date, which has no time to send.
 */
internal fun instantOf(date: Date): Instant {
    val milliseconds = date.getTime()
    require(!milliseconds.isNaN()) { "the date is invalid" }
    return Instant.fromEpochMilliseconds(milliseconds.toLong())
}

/** This map as a plain object, with [convert] applied to each value. */
internal fun <T, R> Map<String, T>.toRecord(convert: (T) -> R): Record<String, R> {
    val record: dynamic = js("({})")
    forEach { (key, value) -> record[key] = convert(value) }
    return record.unsafeCast<Record<String, R>>()
}
