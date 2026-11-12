import com.fenakhay.kwikibot.wikibase.EntityEncoder
import com.fenakhay.kwikibot.wikibase.entity.MULTIPLE_LANGUAGES as PARSED_MULTIPLE_LANGUAGES
import com.fenakhay.kwikibot.wikibase.value.DataValue as ParsedDataValue
import com.fenakhay.kwikibot.wikibase.value.EntityId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The pseudo-language Wikibase stores a label under when it applies to every language: `"mul"`. */
@JsExport public const val MULTIPLE_LANGUAGES: String = PARSED_MULTIPLE_LANGUAGES

/** A [DataValue] time's precision: to a billion years. */
@JsExport public const val TIME_PRECISION_BILLION_YEARS: Int = ParsedDataValue.Time.BILLION_YEARS

/** A [DataValue] time's precision: to a century. */
@JsExport public const val TIME_PRECISION_CENTURY: Int = ParsedDataValue.Time.CENTURY

/** A [DataValue] time's precision: to a decade. */
@JsExport public const val TIME_PRECISION_DECADE: Int = ParsedDataValue.Time.DECADE

/** A [DataValue] time's precision: to a year, the commonest historical one. */
@JsExport public const val TIME_PRECISION_YEAR: Int = ParsedDataValue.Time.YEAR

/** A [DataValue] time's precision: to a month. */
@JsExport public const val TIME_PRECISION_MONTH: Int = ParsedDataValue.Time.MONTH

/** A [DataValue] time's precision: to a day, the finest Wikibase records. */
@JsExport public const val TIME_PRECISION_DAY: Int = ParsedDataValue.Time.DAY

/** What Wikibase writes as the unit of a quantity with none: `"1"`. */
@JsExport public const val UNITLESS: String = ParsedDataValue.Quantity.UNITLESS

/**
 * A value a statement can carry. [kind] says which it is, and so which fields are set; a value of a type this
 * library does not model is kept whole as an `"unknown"`, and written back unchanged.
 */
@JsExport
public class DataValue internal constructor(internal val data: ParsedDataValue) {

    /**
     * `"text"`, `"entity-ref"`, `"time"`, `"quantity"`, `"globe-coordinate"`, `"monolingual"` or `"unknown"`.
     */
    public val kind: DataValueKind
        get() =
            when (data) {
                is ParsedDataValue.Text -> "text"
                is ParsedDataValue.EntityRef -> "entity-ref"
                is ParsedDataValue.Time -> "time"
                is ParsedDataValue.Quantity -> "quantity"
                is ParsedDataValue.GlobeCoordinate -> "globe-coordinate"
                is ParsedDataValue.Monolingual -> "monolingual"
                is ParsedDataValue.Unknown -> "unknown"
            }.unsafeCast<DataValueKind>()

    /** A `"text"`'s string. */
    public val value: String?
        get() = (data as? ParsedDataValue.Text)?.value

    /** An `"entity-ref"`'s entity. */
    public val id: String?
        get() = (data as? ParsedDataValue.EntityRef)?.id?.value

    /** A `"time"`'s timestamp, as Wikibase writes it with a sign and a padded year. */
    public val time: String?
        get() = (data as? ParsedDataValue.Time)?.time

    /**
     * A `"time"`'s precision, one of the `TIME_PRECISION_` numbers, or a `"globe-coordinate"`'s in degrees.
     */
    public val precision: Double?
        get() =
            when (data) {
                is ParsedDataValue.Time -> data.precision.toDouble()
                is ParsedDataValue.GlobeCoordinate -> data.precision
                else -> null
            }

    /** A `"time"`'s calendar, as an entity URI. */
    public val calendarModel: String?
        get() = (data as? ParsedDataValue.Time)?.calendarModel

    /** How many units earlier a `"time"` might be. */
    public val before: Int?
        get() = (data as? ParsedDataValue.Time)?.before

    /** How many units later a `"time"` might be. */
    public val after: Int?
        get() = (data as? ParsedDataValue.Time)?.after

    /** The offset in minutes a `"time"` was stated in. */
    public val timezone: Int?
        get() = (data as? ParsedDataValue.Time)?.timezone

    /** A `"quantity"`'s number as written, kept as text so its precision survives. */
    public val amount: String?
        get() = (data as? ParsedDataValue.Quantity)?.amount

    /** A `"quantity"`'s unit as an entity URI, or `UNITLESS`. */
    public val unit: String?
        get() = (data as? ParsedDataValue.Quantity)?.unit

    /** The top of a `"quantity"`'s stated tolerance. */
    public val upperBound: String?
        get() = (data as? ParsedDataValue.Quantity)?.upperBound

    /** The bottom of a `"quantity"`'s stated tolerance. */
    public val lowerBound: String?
        get() = (data as? ParsedDataValue.Quantity)?.lowerBound

    /** A `"globe-coordinate"`'s degrees north, negative for south. */
    public val latitude: Double?
        get() = (data as? ParsedDataValue.GlobeCoordinate)?.latitude

    /** A `"globe-coordinate"`'s degrees east, negative for west. */
    public val longitude: Double?
        get() = (data as? ParsedDataValue.GlobeCoordinate)?.longitude

    /** A `"globe-coordinate"`'s globe, as an entity URI. */
    public val globe: String?
        get() = (data as? ParsedDataValue.GlobeCoordinate)?.globe

    /** A `"monolingual"`'s text. */
    public val text: String?
        get() = (data as? ParsedDataValue.Monolingual)?.text

    /** A `"monolingual"`'s language, as a Wikimedia language code. */
    public val language: String?
        get() = (data as? ParsedDataValue.Monolingual)?.language

    /** The type Wikibase named for an `"unknown"`. */
    public val type: String?
        get() = (data as? ParsedDataValue.Unknown)?.type

    /** An `"unknown"`'s value as it arrived. */
    public val json: JsonValue?
        get() = (data as? ParsedDataValue.Unknown)?.json?.jsonObject?.get("value")?.let(::plainOf)

    /** The value as Wikibase writes it, `{ type, value }`. */
    public fun toJSON(): Record<String, JsonValue> = plainOf(EntityEncoder.encodeValue(data))
}

/** A plain string value: an identifier, a URL, a formatter pattern. */
@JsExport public fun textValue(value: String): DataValue = DataValue(ParsedDataValue.Text(value))

/** A value pointing at the entity [id], such as `"Q42"`. */
@JsExport public fun entityValue(id: String): DataValue = DataValue(ParsedDataValue.EntityRef(EntityId(id)))

/**
 * A point in time. [time] is as Wikibase writes it, such as `"+1952-00-00T00:00:00Z"`; [precision] is one of
 * the `TIME_PRECISION_` numbers, and [calendarModel] the calendar as an entity URI.
 */
@JsExport
public fun timeValue(
    time: String,
    precision: Int,
    calendarModel: String,
    before: Int = 0,
    after: Int = 0,
    timezone: Int = 0,
): DataValue = DataValue(ParsedDataValue.Time(time, precision, calendarModel, before, after, timezone))

/** An amount, kept as text so its precision survives, with a unit as an entity URI and a tolerance. */
@JsExport
public fun quantityValue(
    amount: String,
    unit: String = UNITLESS,
    upperBound: String? = null,
    lowerBound: String? = null,
): DataValue = DataValue(ParsedDataValue.Quantity(amount, unit, upperBound, lowerBound))

/** A point on [globe], an entity URI, with its [precision] in degrees when it has one. */
@JsExport
public fun coordinateValue(
    latitude: Double,
    longitude: Double,
    precision: Double?,
    globe: String,
): DataValue = DataValue(ParsedDataValue.GlobeCoordinate(latitude, longitude, precision, globe))

/** Text in a stated language: a name, a title, a motto. */
@JsExport
public fun monolingualValue(text: String, language: String): DataValue =
    DataValue(ParsedDataValue.Monolingual(text, language))

/** A value of the type Wikibase calls [type], which this library does not model, holding [value]. */
@JsExport
public fun unknownValue(type: String, value: JsonValue): DataValue =
    DataValue(
        ParsedDataValue.Unknown(
            type,
            JsonObject(mapOf("type" to JsonPrimitive(type), "value" to jsonElementOf(value))),
        )
    )
