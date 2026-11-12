import com.fenakhay.kwikibot.wikibase.EntityEncoder

/** [claim] as Wikibase serialises one statement. */
@JsExport
public fun encodeClaim(claim: Claim): Record<String, JsonValue> =
    plainOf(EntityEncoder.encodeStatement(claim.statement))

/** [snak] as Wikibase serialises one. */
@JsExport
public fun encodeSnak(snak: Snak): Record<String, JsonValue> = plainOf(EntityEncoder.encodeSnak(snak.snak))

/** [value] as Wikibase serialises one, `{ type, value }`. */
@JsExport
public fun encodeDataValue(value: DataValue): Record<String, JsonValue> =
    plainOf(EntityEncoder.encodeValue(value.data))
