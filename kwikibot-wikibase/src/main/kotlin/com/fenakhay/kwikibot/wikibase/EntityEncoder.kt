package com.fenakhay.kwikibot.wikibase

import com.fenakhay.kwikibot.wikibase.entity.LanguageValue
import com.fenakhay.kwikibot.wikibase.entity.SiteLink
import com.fenakhay.kwikibot.wikibase.value.DataValue
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Rank
import com.fenakhay.kwikibot.wikibase.value.Snak
import com.fenakhay.kwikibot.wikibase.value.Statement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Writes entities back into the JSON a Wikibase accepts.
 *
 * The inverse of [EntityDecoder], and deliberately its mirror image: `wbeditentity` and `wbsetclaim` take the
 * same serialization the API hands out, so anything the decoder reads must be writable again unchanged. That
 * is what the round-trip test asserts, and it is the only way a bot can read a statement, change one
 * qualifier and save it without quietly dropping the parts it did not understand — including a
 * [DataValue.Unknown], which is written back verbatim.
 */
public object EntityEncoder {

    /** Encodes one statement, ready for `wbsetclaim` or a `claims` block. */
    public fun encodeStatement(statement: Statement): JsonObject = buildJsonObject {
        statement.id?.let { put("id", it) }
        put("type", "statement")
        put("mainsnak", encodeSnak(statement.mainSnak))
        put("rank", statement.rank.wire)

        if (statement.qualifiers.isNotEmpty()) {
            val groups = statement.qualifiers.groupBy { it.property }
            put("qualifiers", encodeSnakGroups(groups))
            // Wikibase keeps the order qualifiers were added in a separate list; without it the
            // API reorders them, which shows up as a spurious diff on every save.
            putJsonArray("qualifiers-order") { groups.keys.forEach { add(it.value) } }
        }

        if (statement.references.isNotEmpty()) {
            putJsonArray("references") {
                statement.references.forEach { reference ->
                    val groups = reference.snaks.groupBy { it.property }
                    add(
                        buildJsonObject {
                            put("snaks", encodeSnakGroups(groups))
                            putJsonArray("snaks-order") { groups.keys.forEach { add(it.value) } }
                        }
                    )
                }
            }
        }
    }

    /** Encodes one snak, keeping the difference between no value and an unknown value. */
    public fun encodeSnak(snak: Snak): JsonObject = buildJsonObject {
        put("property", snak.property.value)
        when (snak) {
            is Snak.NoValue -> put("snaktype", "novalue")
            is Snak.SomeValue -> put("snaktype", "somevalue")
            is Snak.Value -> {
                put("snaktype", "value")
                snak.dataType?.let { put("datatype", it) }
                put("datavalue", encodeValue(snak.value))
            }
        }
    }

    /** Encodes one data value. A type this library does not model is written back as it came. */
    public fun encodeValue(value: DataValue): JsonObject =
        when (value) {
            is DataValue.Unknown -> value.json.jsonObject

            is DataValue.Text ->
                buildJsonObject {
                    put("type", "string")
                    put("value", value.value)
                }

            is DataValue.EntityRef ->
                buildJsonObject {
                    put("type", "wikibase-entityid")
                    putJsonObject("value") {
                        put("entity-type", value.id.entityType)
                        // Older Wikibase installs key entity references by number rather than by id;
                        // both are sent so either kind of reader is satisfied.
                        value.id.numericId?.let { put("numeric-id", it) }
                        put("id", value.id.value)
                    }
                }

            is DataValue.Time ->
                buildJsonObject {
                    put("type", "time")
                    putJsonObject("value") {
                        put("time", value.time)
                        put("timezone", value.timezone)
                        put("before", value.before)
                        put("after", value.after)
                        put("precision", value.precision)
                        put("calendarmodel", value.calendarModel)
                    }
                }

            is DataValue.Quantity ->
                buildJsonObject {
                    put("type", "quantity")
                    putJsonObject("value") {
                        put("amount", value.amount)
                        put("unit", value.unit)
                        value.upperBound?.let { put("upperBound", it) }
                        value.lowerBound?.let { put("lowerBound", it) }
                    }
                }

            is DataValue.GlobeCoordinate ->
                buildJsonObject {
                    put("type", "globecoordinate")
                    putJsonObject("value") {
                        put("latitude", value.latitude)
                        put("longitude", value.longitude)
                        put("precision", value.precision)
                        put("globe", value.globe)
                    }
                }

            is DataValue.Monolingual ->
                buildJsonObject {
                    put("type", "monolingualtext")
                    putJsonObject("value") {
                        put("text", value.text)
                        put("language", value.language)
                    }
                }
        }

    /** Encodes statements grouped by property, as an entity `claims` block. */
    public fun encodeStatements(statements: Map<EntityId, List<Statement>>): JsonObject = buildJsonObject {
        statements.forEach { (property, group) ->
            putJsonArray(property.value) { group.forEach { add(encodeStatement(it)) } }
        }
    }

    /** Encodes labels, descriptions or lemmas. */
    public fun encodeLanguageValues(values: Map<String, LanguageValue>): JsonObject = buildJsonObject {
        values.forEach { (language, entry) ->
            putJsonObject(language) {
                put("language", entry.language.ifEmpty { language })
                put("value", entry.value)
            }
        }
    }

    /** Encodes aliases, which unlike labels are a list per language. */
    public fun encodeAliases(values: Map<String, List<LanguageValue>>): JsonObject = buildJsonObject {
        values.forEach { (language, entries) ->
            putJsonArray(language) {
                entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("language", entry.language.ifEmpty { language })
                            put("value", entry.value)
                        }
                    )
                }
            }
        }
    }

    /** Encodes sitelinks, badges included. */
    public fun encodeSiteLinks(links: Map<String, SiteLink>): JsonObject = buildJsonObject {
        links.forEach { (site, link) ->
            putJsonObject(site) {
                put("site", link.site.ifEmpty { site })
                put("title", link.title)
                putJsonArray("badges") { link.badges.forEach { add(it.value) } }
            }
        }
    }

    private fun encodeSnakGroups(groups: Map<EntityId, List<Snak>>): JsonObject = buildJsonObject {
        groups.forEach { (property, snaks) ->
            putJsonArray(property.value) { snaks.forEach { add(encodeSnak(it)) } }
        }
    }

    private val Rank.wire: String
        get() =
            when (this) {
                Rank.PREFERRED -> "preferred"
                Rank.NORMAL -> "normal"
                Rank.DEPRECATED -> "deprecated"
            }
}

/** What Wikibase calls this kind of entity on the wire. */
internal val EntityId.entityType: String
    get() =
        when (kind) {
            EntityId.Kind.ITEM -> "item"
            EntityId.Kind.PROPERTY -> "property"
            EntityId.Kind.LEXEME -> "lexeme"
            EntityId.Kind.FORM -> "form"
            EntityId.Kind.SENSE -> "sense"
            EntityId.Kind.MEDIA_INFO -> "mediainfo"
            EntityId.Kind.UNKNOWN -> "unknown"
        }

/**
 * The number in an id, for the older serialization that keys entities by it.
 *
 * Forms and senses have no numeric id of their own — `L1-F1` is not a number — so they have none here either.
 */
internal val EntityId.numericId: Int?
    get() =
        when (kind) {
            EntityId.Kind.ITEM,
            EntityId.Kind.PROPERTY,
            EntityId.Kind.LEXEME -> value.drop(1).toIntOrNull()

            else -> null
        }
