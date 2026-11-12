import com.fenakhay.kwikibot.wikibase.EntityEncoder
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Reference as ParsedReference
import com.fenakhay.kwikibot.wikibase.value.Snak as ParsedSnak
import com.fenakhay.kwikibot.wikibase.value.Statement

/**
 * One property-value assertion: that [property] has a value, that it has none, or that it has one not known.
 */
@JsExport
public class Snak internal constructor(internal val snak: ParsedSnak) {

    /** `"value"`, `"somevalue"` for an unknown value, or `"novalue"` for a stated absence. */
    public val kind: SnakKind
        get() =
            when (snak) {
                is ParsedSnak.Value -> "value"
                is ParsedSnak.SomeValue -> "somevalue"
                is ParsedSnak.NoValue -> "novalue"
            }.unsafeCast<SnakKind>()

    /** The property it is about, such as `"P31"`. */
    public val property: String
        get() = snak.property.value

    /** What the property is asserted to be, or `null` unless a `"value"`. */
    public val value: DataValue?
        get() = (snak as? ParsedSnak.Value)?.value?.let(::DataValue)

    /** The property's declared data type, on a `"value"` when the response carried it. */
    public val dataType: String?
        get() = (snak as? ParsedSnak.Value)?.dataType

    /** The snak as Wikibase writes it. */
    public fun toJSON(): Record<String, JsonValue> = plainOf(EntityEncoder.encodeSnak(snak))
}

/** That [property] has [value], of [dataType] when it is known. */
@JsExport
public fun valueSnak(property: String, value: DataValue, dataType: String? = null): Snak =
    Snak(ParsedSnak.Value(EntityId(property), value.data, dataType))

/** That [property] deliberately has no value. */
@JsExport public fun noValueSnak(property: String): Snak = Snak(ParsedSnak.NoValue(EntityId(property)))

/** That [property] has a value which is not known. */
@JsExport public fun someValueSnak(property: String): Snak = Snak(ParsedSnak.SomeValue(EntityId(property)))

/** A source cited for a statement. */
@JsExport
public class Reference(
    /** The snaks that make up the citation, such as a URL and a retrieval date. */
    public val snaks: Array<Snak>
) {
    internal val reference: ParsedReference = ParsedReference(snaks.map { it.snak })

    /** The snaks for [property] in this reference. */
    public fun get(property: String): Array<Snak> = reference[EntityId(property)].map(::Snak).toTypedArray()
}

/** [reference] as the facade reports it. */
internal fun referenceOf(reference: ParsedReference): Reference =
    Reference(reference.snaks.map(::Snak).toTypedArray())

/** What a statement says beyond its main snak. */
@JsExport
public external interface ClaimOptions {
    /**
     * Wikibase's id for the statement, which makes saving it replace the one with that id. In `Claim.copy`,
     * `null` here, or on [qualifiers] or [references], clears it, and leaving it out keeps it.
     */
    public val id: String?

    /** `"preferred"`, `"normal"` or `"deprecated"`; `"normal"` for a new statement unless given. */
    public val rank: StatementRank?

    /** Conditions on the assertion: a point in time, a place. */
    public val qualifiers: Array<Snak>?

    /** The sources cited for it. */
    public val references: Array<Reference>?
}

/**
 * A statement [mainSnak] makes, not saved yet: give it to `wiki.entities.setStatement`, or an edit's
 * `statements`.
 */
@JsExport
public fun claim(mainSnak: Snak, options: ClaimOptions? = null): Claim =
    Claim(statementOf(mainSnak.snak, options, Statement(mainSnak.snak)))

/** [base] with its [mainSnak] and what [options] gives. */
internal fun statementOf(mainSnak: ParsedSnak, options: ClaimOptions?, base: Statement): Statement =
    Statement(
        mainSnak = mainSnak,
        id = if (options == null || options.id === undefined) base.id else options.id,
        rank = enumOf("rank", options?.rank) ?: base.rank,
        qualifiers =
            if (options == null || options.qualifiers === undefined) base.qualifiers
            else options.qualifiers?.map { it.snak }.orEmpty(),
        references =
            if (options == null || options.references === undefined) base.references
            else options.references?.map { it.reference }.orEmpty(),
    )
