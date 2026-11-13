import com.fenakhay.kwikibot.model.RevisionId
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** This plain object as a map, in the object's own order. */
internal fun <V> Record<String, V>.toMap(): Map<String, V> {
    val record: dynamic = this
    val keys = js("Object.keys")(record).unsafeCast<Array<String>>()
    return keys.associateWith { record[it].unsafeCast<V>() }
}

/** [element] as the plain JavaScript value `JSON.parse` reads it as. */
internal fun plainOf(element: JsonElement): JsonValue = JSON.parse(element.toString())

/** [element] as the plain JavaScript object `JSON.parse` reads it as. */
internal fun plainOf(element: JsonObject): Record<String, JsonValue> = JSON.parse(element.toString())

/** [value], a plain JavaScript value, as JSON. */
internal fun jsonElementOf(value: Any?): JsonElement = Json.parseToJsonElement(JSON.stringify(value))

/** [value], a plain JavaScript object, as a JSON object. */
internal fun jsonObjectOf(value: Any?): JsonObject = jsonElementOf(value).jsonObject

/** Revision [id] as this library's own id. */
internal fun revisionIdOf(id: Double): RevisionId = RevisionId(id.toLong())

/**
 * A promise of [block] that resolves to `undefined`, as TypeScript's `Promise<void>` says: a coroutine that
 * ends in `Unit` would resolve to Kotlin's `Unit` object.
 */
internal fun CoroutineScope.promiseVoid(block: suspend CoroutineScope.() -> Unit): Promise<Unit> =
    promise<Any?> {
            block()
            undefined
        }
        .unsafeCast<Promise<Unit>>()

/**
 * Fails unless there is a filesystem to do [action] with.
 *
 * @throws IllegalStateException in a browser, which has none.
 */
internal fun requireFileSystem(action: String, available: Boolean = hasFileSystem()) {
    check(available) { "$action needs a filesystem, which a browser does not have: it works in Node only" }
}

/** Whether this runs in Node, which has a filesystem, rather than in a browser. */
internal fun hasFileSystem(): Boolean =
    js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null")
        .unsafeCast<Boolean>()
