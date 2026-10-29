import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.wikibase.EntityEncoder
import com.fenakhay.kwikibot.wikibase.entity.Entity as ParsedEntity
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Snak
import com.fenakhay.kwikibot.wikibase.value.Statement
import com.fenakhay.kwikibot.wikibase.wikibase
import kotlinx.serialization.json.JsonObject

/** The Wikibase entity [id] on [wiki], or `null` when there is none. */
internal suspend fun entityOf(wiki: ClientWiki, id: String): Entity? =
    wiki.wikibase().entity(EntityId(id))?.let(::Entity)

/** A Wikibase entity: an item, a property, a lexeme or a file's structured data. */
@JsExport
public class Entity internal constructor(private val entity: ParsedEntity) {

    /** The entity's id, such as `"Q42"`. */
    public val id: String
        get() = entity.id.value

    /** `"item"`, `"property"`, `"lexeme"`, `"form"`, `"sense"`, `"mediainfo"`, or `"unknown"`. */
    public val type: String
        get() =
            when (entity.id.kind) {
                EntityId.Kind.ITEM -> "item"
                EntityId.Kind.PROPERTY -> "property"
                EntityId.Kind.LEXEME -> "lexeme"
                EntityId.Kind.FORM -> "form"
                EntityId.Kind.SENSE -> "sense"
                EntityId.Kind.MEDIA_INFO -> "mediainfo"
                EntityId.Kind.UNKNOWN -> "unknown"
            }

    /** The revision the entity was read at, or `null` when the wiki did not say. */
    public val lastRevision: Double?
        get() = entity.lastRevisionId?.toDouble()

    /** The properties the entity has statements for. */
    public val properties: Array<String>
        get() = entity.statements.keys.map { it.value }.toTypedArray()

    /** The label in [language], or `null` when it has none there. */
    public fun label(language: String): String? = entity.label(language)

    /** The description in [language], or `null`. */
    public fun description(language: String): String? = entity.description(language)

    /** The aliases in [language]. */
    public fun aliases(language: String): Array<String> =
        entity.aliases[language].orEmpty().map { it.value }.toTypedArray()

    /** The statements for [property], such as `"P31"`, in the entity's order. */
    public fun claims(property: String): Array<Claim> = entity[EntityId(property)].map(::Claim).toTypedArray()

    /**
     * The statement for [property] a reader would be shown: a preferred one, else a normal one, else `null`.
     */
    public fun bestClaim(property: String): Claim? = entity.best(EntityId(property))?.let(::Claim)
}

/** One statement of an entity. */
@JsExport
public class Claim internal constructor(private val statement: Statement) {

    /** The statement's id, or `null` for one not saved yet. */
    public val id: String?
        get() = statement.id

    /** The property it is about, such as `"P31"`. */
    public val property: String
        get() = statement.property.value

    /** `"preferred"`, `"normal"` or `"deprecated"`. */
    public val rank: String
        get() = statement.rank.name.lowercase()

    /** `"value"`, or `"somevalue"` for an unknown value, or `"novalue"` for a stated absence. */
    public val snakType: String
        get() =
            when (statement.mainSnak) {
                is Snak.Value -> "value"
                is Snak.SomeValue -> "somevalue"
                is Snak.NoValue -> "novalue"
            }

    /** The property's data type, such as `"wikibase-item"`, when the wiki gave it. */
    public val dataType: String?
        get() = (statement.mainSnak as? Snak.Value)?.dataType

    /** The value as Wikibase writes it, `{ type, value }`, or `null` for `somevalue` and `novalue`. */
    public val value: dynamic
        get() = (statement.mainSnak as? Snak.Value)?.let { toJs(EntityEncoder.encodeValue(it.value)) }

    /** The whole statement as Wikibase writes it, qualifiers and references included. */
    public fun toJSON(): dynamic = toJs(EntityEncoder.encodeStatement(statement))
}

private fun toJs(json: JsonObject): dynamic = JSON.parse(json.toString())
