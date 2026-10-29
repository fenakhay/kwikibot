/**
 * Reading one field of an answer, by the same rule as the public accessors in `kwikibot-protocol`'s
 * `com.fenakhay.kwikibot.protocol.decode`: a field that is absent, JSON `null`, or of another JSON type reads
 * as absent, and nothing here throws.
 *
 * A copy of the few this module needs, because `kwikibot-protocol` depends on this module and not the other
 * way round.
 */
package com.fenakhay.kwikibot.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The text of [key] when it holds a string, a number or a boolean. */
internal fun JsonObject.string(key: String): String? {
    val value = this[key]
    return if (value is JsonPrimitive && value !is JsonNull) value.content else null
}

/** [key] as a whole number, whether the wiki sent it as a number or as a numeric string. */
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

/** [key] when it holds an object. */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** [key] when it holds an array. */
internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** The text of each string, number and boolean in the array, skipping `null`, objects and arrays. */
internal fun JsonArray?.strings(): List<String> =
    orEmpty().mapNotNull { element ->
        if (element is JsonPrimitive && element !is JsonNull) element.content else null
    }

/** The objects in the array, skipping everything else. */
internal fun JsonArray?.objects(): List<JsonObject> = orEmpty().filterIsInstance<JsonObject>()
