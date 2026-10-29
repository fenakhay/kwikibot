/**
 * Reading one field of an API response.
 *
 * A field that is absent, JSON `null`, or of a JSON type other than the one asked for reads as absent:
 * `null`, `false` for a flag, an empty list for a list. Nothing here throws. A response the wiki sent in an
 * unexpected shape then decodes to less rather than failing the caller, and a read that must not tolerate
 * absence says so where it is made, with `getValue` or `!!`.
 */
package com.fenakhay.kwikibot.protocol.decode

import com.fenakhay.kwikibot.model.MwTimestamp
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The text of [key] when it holds a string, a number or a boolean. */
public fun JsonObject.string(key: String): String? {
    val value = this[key]
    return if (value is JsonPrimitive && value !is JsonNull) value.content else null
}

/** [key] as a whole number, whether the wiki sent it as a number or as a numeric string. */
public fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

/** [key] as a whole number that fits an [Int], whether sent as a number or as a numeric string. */
public fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** [key] as a number, whether sent as a number or as a numeric string. */
public fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** [key] as `true` or `false`, whether sent as a boolean or as the string `"true"` or `"false"`. */
public fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** Whether [key] is set to `true`; absent, `false` and anything else that is not a boolean are unset. */
public fun JsonObject.flag(key: String): Boolean = boolean(key) == true

/** [key] as a moment, in either [MwTimestamp] form; `infinity` and the like read as absent. */
public fun JsonObject.time(key: String): Instant? = string(key)?.let(MwTimestamp::parseOrNull)

/** [key] when it holds an object. */
public fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** [key] when it holds an array. */
public fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** The text of each string, number and boolean in the array, skipping `null`, objects and arrays. */
public fun JsonArray?.strings(): List<String> =
    orEmpty().mapNotNull { element ->
        if (element is JsonPrimitive && element !is JsonNull) element.content else null
    }

/** The objects in the array, skipping everything else. */
public fun JsonArray?.objects(): List<JsonObject> = orEmpty().filterIsInstance<JsonObject>()
