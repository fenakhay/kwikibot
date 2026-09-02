package com.fenakhay.kwikibot.wikibase.value

import kotlinx.serialization.json.JsonElement

/**
 * The identifier of a Wikibase entity: `Q42`, `P31`, `L1`, `L1-F1`, `L1-S1`.
 *
 * Wrapped so an item id cannot be passed where a property id is meant, which is the mistake that makes a
 * statement assert something unintended.
 */
@JvmInline
public value class EntityId(
    /** The id as Wikibase writes it, prefix included: `Q42`, `P31`, `L1-F2`. */
    public val value: String
) {

    init {
        require(value.isNotBlank()) { "entity id must not be blank" }
    }

    /** What kind of entity this identifies, read from its prefix. */
    public val kind: Kind
        get() =
            when {
                value.contains("-F") -> Kind.FORM
                value.contains("-S") -> Kind.SENSE
                value.startsWith("Q") -> Kind.ITEM
                value.startsWith("P") -> Kind.PROPERTY
                value.startsWith("L") -> Kind.LEXEME
                value.startsWith("M") -> Kind.MEDIA_INFO
                else -> Kind.UNKNOWN
            }

    override fun toString(): String = value

    /** The kinds of entity a Wikibase holds. */
    public enum class Kind {
        /** A thing the world contains, `Q42`. */
        ITEM,

        /** Something that can be said about an item, `P31`. */
        PROPERTY,

        /** A word, `L1`. */
        LEXEME,

        /** One inflected form of a lexeme, `L1-F2`. */
        FORM,

        /** One meaning of a lexeme, `L1-S1`. */
        SENSE,

        /** Structured data about a file on Commons, `M123`. */
        MEDIA_INFO,

        /** A prefix this library does not recognise, kept rather than refused. */
        UNKNOWN,
    }
}

/**
 * A value a statement can carry.
 *
 * Sealed, so handling a statement means handling every kind of value it might hold — and so a value type this
 * library does not model yet arrives as [Unknown] rather than as a crash or a silent empty string.
 */
public sealed interface DataValue {

    /** A plain string: an identifier, a URL, a formatter pattern. */
    public data class Text(
        /** The string itself, exactly as Wikibase stored it. */
        val value: String
    ) : DataValue

    /** A reference to another entity. */
    public data class EntityRef(
        /** The entity pointed at. */
        val id: EntityId
    ) : DataValue

    /**
     * A point in time, with the precision it was stated at.
     *
     * Precision matters more than the timestamp: `+1952-00-00T00:00:00Z` at precision 9 means "in 1952";
     * reading it as the first of January invents a day the source did not state.
     */
    public data class Time(
        /** The timestamp as Wikibase writes it, with a sign and a padded year. */
        val time: String,
        /** How much of it is meant, from [BILLION_YEARS] to [DAY]. */
        val precision: Int,
        /** Which calendar the date is stated in, as an entity URI. */
        val calendarModel: String,
        /** How many units earlier the true value might be. */
        val before: Int = 0,
        /** How many units later the true value might be. */
        val after: Int = 0,
        /** The offset in minutes the date was stated in. */
        val timezone: Int = 0,
    ) : DataValue {
        /**
         * The precisions Wikibase uses, as the numbers it stores.
         *
         * Reading a value more precisely than its precision allows is how a source that said "in 1952"
         * becomes a claim about the first of January.
         */
        public companion object {
            /** Precise to a billion years. */
            public const val BILLION_YEARS: Int = 0

            /** Precise to a century. */
            public const val CENTURY: Int = 7

            /** Precise to a decade. */
            public const val DECADE: Int = 8

            /** Precise to a year, which is the commonest historical precision. */
            public const val YEAR: Int = 9

            /** Precise to a month. */
            public const val MONTH: Int = 10

            /** Precise to a day, the finest Wikibase records. */
            public const val DAY: Int = 11
        }
    }

    /**
     * An amount, optionally with a unit and a stated tolerance.
     *
     * The amount is kept as written rather than parsed into a number: Wikibase states arbitrary precision,
     * and rounding a stated measurement to fit a Double would lose that.
     */
    public data class Quantity(
        /** The number as written, kept as text so its precision survives. */
        val amount: String,
        /** The unit as an entity URI, or [UNITLESS] for a bare number. */
        val unit: String = UNITLESS,
        /** The top of the stated tolerance, when one was given. */
        val upperBound: String? = null,
        /** The bottom of the stated tolerance, when one was given. */
        val lowerBound: String? = null,
    ) : DataValue {
        /** The unit a quantity carries when it has none. */
        public companion object {
            /** What Wikibase writes for a quantity with no unit. */
            public const val UNITLESS: String = "1"
        }
    }

    /** A point on a globe, which is not always Earth. */
    public data class GlobeCoordinate(
        /** Degrees north, negative for south. */
        val latitude: Double,
        /** Degrees east, negative for west. */
        val longitude: Double,
        /** How precisely the point is stated, in degrees. */
        val precision: Double?,
        /** Which globe, as an entity URI. Earth is the usual one, not the only one. */
        val globe: String,
    ) : DataValue

    /** Text in a stated language: a name, a title, a motto. */
    public data class Monolingual(
        /** The text itself. */
        val text: String,
        /** The language it is in, as a Wikimedia language code. */
        val language: String,
    ) : DataValue

    /**
     * A value of a type this library does not model.
     *
     * Kept whole rather than dropped, so a statement carrying one still round-trips and a caller can read it
     * if it knows how.
     */
    public data class Unknown(
        /** The type Wikibase named, which this library has no case for. */
        val type: String,
        /** The value as it arrived, so it round-trips unchanged. */
        val json: JsonElement,
    ) : DataValue
}

/**
 * One property-value assertion.
 *
 * Wikibase distinguishes three things a statement can say about a property: a value, that there is no value,
 * and that there is a value but it is unknown. Collapsing the last two into `null` loses the difference
 * between "has no children" and "children not recorded".
 */
public sealed interface Snak {

    /** The property this snak is about. */
    public val property: EntityId

    /** The property has this value. */
    public data class Value(
        /** The property being asserted. */
        override val property: EntityId,
        /** What it is asserted to be. */
        val value: DataValue,
        /** The property's declared datatype, when the response carried it. */
        val dataType: String? = null,
    ) : Snak

    /** The property has no value, stated deliberately. */
    public data class NoValue(override val property: EntityId) : Snak

    /** The property has a value, but it is not known. */
    public data class SomeValue(override val property: EntityId) : Snak
}

/** How much weight a statement carries relative to others for the same property. */
public enum class Rank {
    /** The statement to use when only one is wanted. */
    PREFERRED,

    /** The default. */
    NORMAL,

    /** Known to be wrong or outdated, kept for the record. */
    DEPRECATED,
}

/** A source cited for a statement. */
public data class Reference(
    /** The snaks that make up the citation, such as a URL and a retrieval date. */
    val snaks: List<Snak>
) {
    /** The snaks for one property of this reference. */
    public operator fun get(property: EntityId): List<Snak> = snaks.filter { it.property == property }
}

/** One statement on an entity: what it says, how strongly, with what qualifications and sources. */
public data class Statement(
    /** What the statement asserts. */
    val mainSnak: Snak,
    /** Wikibase's id for it, absent for a statement not yet saved. */
    val id: String? = null,
    /** How much weight it carries against others for the same property. */
    val rank: Rank = Rank.NORMAL,
    /** Conditions on the assertion: a point in time, a place, a determination method. */
    val qualifiers: List<Snak> = emptyList(),
    /** The sources cited for it. */
    val references: List<Reference> = emptyList(),
) {
    /** The property this statement is about. */
    val property: EntityId
        get() = mainSnak.property

    /** The value, or `null` when the statement says there is none or that it is unknown. */
    val value: DataValue?
        get() = (mainSnak as? Snak.Value)?.value

    /** The qualifiers for one property. */
    public fun qualifiers(property: EntityId): List<Snak> = qualifiers.filter { it.property == property }
}
