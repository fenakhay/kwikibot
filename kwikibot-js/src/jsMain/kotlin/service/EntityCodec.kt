import com.fenakhay.kwikibot.wikibase.EntityDecoder
import com.fenakhay.kwikibot.wikibase.EntityEncoder

/** Reads one entity as `wbgetentities` serialises it. */
@JsExport
public fun decodeEntity(json: Record<String, JsonValue>): Entity =
    Entity(EntityDecoder.decode(jsonObjectOf(json)))

/** Reads a whole `wbgetentities` response, by entity id. Entries that are not entities are left out. */
@JsExport
public fun decodeEntities(response: Record<String, JsonValue>): Record<String, Entity> =
    EntityDecoder.decodeAll(jsonObjectOf(response))
        .entries
        .associate { (id, entity) -> id.value to entity }
        .toRecord(::Entity)

/** The entity a `wbgetentities` entry redirects to, or `null` when it is not a redirect. */
@JsExport
public fun entityRedirectTarget(json: Record<String, JsonValue>): String? =
    EntityDecoder.redirectTarget(jsonObjectOf(json))?.value

/** Reads one statement as Wikibase serialises it. */
@JsExport
public fun decodeClaim(json: Record<String, JsonValue>): Claim =
    Claim(EntityDecoder.decodeStatement(jsonObjectOf(json)))

/** Reads one snak as Wikibase serialises it. */
@JsExport
public fun decodeSnak(json: Record<String, JsonValue>): Snak =
    Snak(EntityDecoder.decodeSnak(jsonObjectOf(json)))

/** Reads one value as Wikibase serialises it, `{ type, value }`. */
@JsExport
public fun decodeDataValue(json: Record<String, JsonValue>): DataValue =
    DataValue(EntityDecoder.decodeValue(jsonObjectOf(json)))

/** [statements], by property, as a `claims` block of `wbeditentity`'s data. */
@JsExport
public fun encodeClaims(statements: Record<String, Array<Claim>>): Record<String, JsonValue> =
    plainOf(EntityEncoder.encodeStatements(statementsOf(statements)))

/** [values], by language, as a `labels` or `descriptions` block of `wbeditentity`'s data. */
@JsExport
public fun encodeLanguageValues(values: Record<String, LanguageValue>): Record<String, JsonValue> =
    plainOf(EntityEncoder.encodeLanguageValues(values.toMap().mapValues { it.value.parsed() }))

/** [values], by language, as an `aliases` block of `wbeditentity`'s data. */
@JsExport
public fun encodeAliases(values: Record<String, Array<LanguageValue>>): Record<String, JsonValue> =
    plainOf(EntityEncoder.encodeAliases(values.toMap().mapValues { (_, list) -> list.map { it.parsed() } }))

/** [links], by wiki database name, as a `sitelinks` block of `wbeditentity`'s data. */
@JsExport
public fun encodeSiteLinks(links: Record<String, EntitySiteLink>): Record<String, JsonValue> =
    plainOf(EntityEncoder.encodeSiteLinks(links.toMap().mapValues { it.value.parsed() }))
