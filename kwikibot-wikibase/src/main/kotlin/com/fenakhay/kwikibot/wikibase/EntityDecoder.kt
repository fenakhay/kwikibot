package com.fenakhay.kwikibot.wikibase

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.protocol.decode.array
import com.fenakhay.kwikibot.protocol.decode.double
import com.fenakhay.kwikibot.protocol.decode.int
import com.fenakhay.kwikibot.protocol.decode.long
import com.fenakhay.kwikibot.protocol.decode.obj
import com.fenakhay.kwikibot.protocol.decode.objects
import com.fenakhay.kwikibot.protocol.decode.string
import com.fenakhay.kwikibot.protocol.decode.strings
import com.fenakhay.kwikibot.wikibase.entity.Entity
import com.fenakhay.kwikibot.wikibase.entity.LanguageValue
import com.fenakhay.kwikibot.wikibase.entity.SiteLink
import com.fenakhay.kwikibot.wikibase.value.DataValue
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Rank
import com.fenakhay.kwikibot.wikibase.value.Reference
import com.fenakhay.kwikibot.wikibase.value.Snak
import com.fenakhay.kwikibot.wikibase.value.Statement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Reads the JSON a Wikibase serves into entities.
 *
 * Written by hand rather than generated: entity JSON is the most irregular thing MediaWiki serves — six kinds
 * of data value, three snak types, statements nesting qualifiers and references — and a generated decoder
 * would either reject the parts it did not expect or quietly drop them. Unknown value types survive as
 * [DataValue.Unknown] instead.
 */
public object EntityDecoder {

    /** Decodes one entity from a `wbgetentities` response. */
    public fun decode(json: JsonObject): Entity {
        val id = EntityId(json.string("id") ?: throw missing("id"))
        val revision = json.long("lastrevid")

        return when (val type = json.string("type")) {
            "item" ->
                Entity.Item(
                    id = id,
                    labels = json.languageValues("labels"),
                    descriptions = json.languageValues("descriptions"),
                    aliases = json.aliasLists(),
                    statements = json.statements(),
                    siteLinks = json.siteLinks(),
                    lastRevisionId = revision,
                )

            "property" ->
                Entity.Property(
                    id = id,
                    dataType = json.string("datatype").orEmpty(),
                    labels = json.languageValues("labels"),
                    descriptions = json.languageValues("descriptions"),
                    aliases = json.aliasLists(),
                    statements = json.statements(),
                    lastRevisionId = revision,
                )

            "lexeme" ->
                Entity.Lexeme(
                    id = id,
                    lemmas = json.languageValues("lemmas"),
                    language = json.string("language")?.let { EntityId(it) },
                    lexicalCategory = json.string("lexicalCategory")?.let { EntityId(it) },
                    forms = json.array("forms").objects().map { decodeForm(it) },
                    senses = json.array("senses").objects().map { decodeSense(it) },
                    statements = json.statements(),
                    lastRevisionId = revision,
                )

            "mediainfo" ->
                Entity.MediaInfo(
                    id = id,
                    labels = json.languageValues("labels"),
                    statements = json.statements(),
                    lastRevisionId = revision,
                )

            else ->
                throw WikiError.Api(
                    "unknownentitytype",
                    "this library does not model entities of type '$type'",
                    "wbgetentities",
                )
        }
    }

    /** Decodes every entity in a `wbgetentities` response, keyed by id. */
    public fun decodeAll(response: JsonObject): Map<EntityId, Entity> {
        val entities = response.obj("entities") ?: return emptyMap()
        return entities.values
            .map { it.jsonObject }
            // A requested id that does not exist comes back flagged rather than omitted. So does
            // one that was merged away: it has a redirect and no content, and decoding it as an
            // entity would produce an empty item that overwrites the real one on save.
            .filterNot { it.containsKey("missing") || it.containsKey("redirects") }
            .associate {
                val entity = decode(it)
                entity.id to entity
            }
    }

    /**
     * Where an id redirects, or `null` if it does not.
     *
     * A merged item stays addressable and points at the one it was merged into. Following it is the caller's
     * decision: a bot that recorded `Q42` last year should usually notice that it moved rather than silently
     * act on a different item.
     */
    public fun redirectTarget(json: JsonObject): EntityId? =
        json.obj("redirects")?.string("to")?.let { EntityId(it) }

    private fun decodeForm(json: JsonObject) =
        Entity.Form(
            id = EntityId(json.string("id") ?: throw missing("form id")),
            representations = json.languageValues("representations"),
            grammaticalFeatures = json.array("grammaticalFeatures").strings().map { EntityId(it) },
            statements = json.statements(),
        )

    private fun decodeSense(json: JsonObject) =
        Entity.Sense(
            id = EntityId(json.string("id") ?: throw missing("sense id")),
            glosses = json.languageValues("glosses"),
            statements = json.statements(),
        )

    /** Decodes one statement, with its qualifiers, references and rank. */
    public fun decodeStatement(json: JsonObject): Statement =
        Statement(
            mainSnak = decodeSnak(json.obj("mainsnak") ?: throw missing("mainsnak")),
            id = json.string("id"),
            rank =
                when (json.string("rank")) {
                    "preferred" -> Rank.PREFERRED
                    "deprecated" -> Rank.DEPRECATED
                    else -> Rank.NORMAL
                },
            qualifiers = json.obj("qualifiers")?.snakGroups().orEmpty(),
            references =
                json.array("references").objects().map { reference ->
                    Reference(reference.obj("snaks")?.snakGroups().orEmpty())
                },
        )

    /** Decodes one snak, keeping the difference between no value and an unknown value. */
    public fun decodeSnak(json: JsonObject): Snak {
        val property = EntityId(json.string("property") ?: throw missing("snak property"))

        return when (json.string("snaktype")) {
            "novalue" -> Snak.NoValue(property)
            "somevalue" -> Snak.SomeValue(property)
            else ->
                Snak.Value(
                    property = property,
                    value = decodeValue(json.obj("datavalue") ?: throw missing("datavalue")),
                    dataType = json.string("datatype"),
                )
        }
    }

    /**
     * Decodes one data value. An unmodelled type, or a value not in the shape its type takes, is kept whole
     * as [DataValue.Unknown].
     */
    public fun decodeValue(json: JsonObject): DataValue {
        val type = json.string("type").orEmpty()
        val text = json.string("value")
        if (type == "string" && text != null) return DataValue.Text(text)
        val value = json.obj("value") ?: return DataValue.Unknown(type, json)

        return when (type) {
            "wikibase-entityid" -> decodeEntityRef(value, json)
            "time" -> decodeTime(value)
            "quantity" -> decodeQuantity(value)
            "globecoordinate" -> decodeCoordinate(value)
            "monolingualtext" ->
                DataValue.Monolingual(
                    text = value.string("text").orEmpty(),
                    language = value.string("language").orEmpty(),
                )

            else -> DataValue.Unknown(type, json)
        }
    }

    /**
     * Reads an entity reference.
     *
     * Modern responses carry the id directly; older ones carry only an entity type and a numeric id, which
     * has to be reassembled.
     */
    private fun decodeEntityRef(value: JsonObject, whole: JsonObject): DataValue {
        value.string("id")?.let {
            return DataValue.EntityRef(EntityId(it))
        }

        val numeric = value.int("numeric-id") ?: return DataValue.Unknown("wikibase-entityid", whole)
        val prefix =
            when (value.string("entity-type")) {
                "item" -> "Q"
                "property" -> "P"
                "lexeme" -> "L"
                else -> return DataValue.Unknown("wikibase-entityid", whole)
            }
        return DataValue.EntityRef(EntityId("$prefix$numeric"))
    }

    private fun decodeTime(value: JsonObject) =
        DataValue.Time(
            time = value.string("time").orEmpty(),
            precision = value.int("precision") ?: DataValue.Time.DAY,
            calendarModel = value.string("calendarmodel").orEmpty(),
            before = value.int("before") ?: 0,
            after = value.int("after") ?: 0,
            timezone = value.int("timezone") ?: 0,
        )

    private fun decodeQuantity(value: JsonObject) =
        DataValue.Quantity(
            amount = value.string("amount").orEmpty(),
            unit = value.string("unit") ?: DataValue.Quantity.UNITLESS,
            upperBound = value.string("upperBound"),
            lowerBound = value.string("lowerBound"),
        )

    private fun decodeCoordinate(value: JsonObject) =
        DataValue.GlobeCoordinate(
            latitude = value.double("latitude") ?: 0.0,
            longitude = value.double("longitude") ?: 0.0,
            precision = value.double("precision"),
            globe = value.string("globe").orEmpty(),
        )

    // ---------------------------------------------------------------- shapes

    private fun JsonObject.statements(): Map<EntityId, List<Statement>> {
        // Items call them "claims"; the newer serialization calls them "statements".
        val groups = obj("claims") ?: obj("statements") ?: return emptyMap()
        return groups.keys.associate { property ->
            EntityId(property) to groups.array(property).objects().map { decodeStatement(it) }
        }
    }

    private fun JsonObject.snakGroups(): List<Snak> = keys.flatMap { property ->
        array(property).objects().map { decodeSnak(it) }
    }

    private fun JsonObject.languageValues(key: String): Map<String, LanguageValue> {
        val values = obj(key) ?: return emptyMap()
        return values.mapValues { (language, entry) ->
            LanguageValue(language, entry.jsonObject.string("value").orEmpty())
        }
    }

    private fun JsonObject.aliasLists(): Map<String, List<LanguageValue>> {
        val values = obj("aliases") ?: return emptyMap()
        return values.keys.associateWith { language ->
            values.array(language).objects().map { LanguageValue(language, it.string("value").orEmpty()) }
        }
    }

    private fun JsonObject.siteLinks(): Map<String, SiteLink> {
        val links = obj("sitelinks") ?: return emptyMap()
        return links.mapValues { (site, link) ->
            val entry = link.jsonObject
            SiteLink(
                site = entry.string("site") ?: site,
                title = entry.string("title").orEmpty(),
                badges = entry.array("badges").strings().map { EntityId(it) },
            )
        }
    }

    private fun missing(what: String) =
        WikiError.Api("malformedentity", "entity JSON has no $what", "wbgetentities")
}
