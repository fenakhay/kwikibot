import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.wikibase.EntityEncoder
import com.fenakhay.kwikibot.wikibase.entity.Entity as ParsedEntity
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Snak as ParsedSnak
import com.fenakhay.kwikibot.wikibase.value.Statement
import com.fenakhay.kwikibot.wikibase.wikibase

/** The Wikibase entity [id] on [wiki], or `null` when there is none. */
internal suspend fun entityOf(wiki: ClientWiki, id: String): Entity? =
    wiki.wikibase().entity(EntityId(id))?.let(::Entity)

/** A Wikibase entity: an item, a property, a lexeme or a file's structured data. */
@JsExport
public class Entity internal constructor(internal val entity: ParsedEntity) {

    /** The entity's id, such as `"Q42"`. */
    public val id: String
        get() = entity.id.value

    /** `"item"`, `"property"`, `"lexeme"`, `"form"`, `"sense"`, `"mediainfo"`, or `"unknown"`. */
    public val type: EntityKind
        get() = entity.id.kind.argumentName().unsafeCast<EntityKind>()

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

    /** The labels by language code; a lexeme's are its lemmas and a file's its captions. */
    public val labels: Record<String, LanguageValue>
        get() = entity.labels.toRecord(::languageValueOf)

    /** The descriptions by language code. */
    public val descriptions: Record<String, LanguageValue>
        get() = entity.descriptions.toRecord(::languageValueOf)

    /** The aliases by language code. */
    public val allAliases: Record<String, Array<LanguageValue>>
        get() = entity.aliases.toRecord { values -> values.map(::languageValueOf).toTypedArray() }

    /** The statements by the property they are about. */
    public val statements: Record<String, Array<Claim>>
        get() = claimsByProperty(entity.statements)

    /** The label in [language] only, without falling back on the label every language shares. */
    public fun labelIn(language: String): String? = entity.labelIn(language)

    /** An item's pages by wiki database name; `null` unless it is an item. */
    public val siteLinks: Record<String, EntitySiteLink>?
        get() = (entity as? ParsedEntity.Item)?.siteLinks?.toRecord(::entitySiteLinkOf)

    /** An item's page on the wiki with database name [site], or `null` when it has none. */
    public fun siteLink(site: String): EntitySiteLink? =
        (entity as? ParsedEntity.Item)?.siteLink(site)?.let(::entitySiteLinkOf)

    /** What kind of value a property's statements hold, as Wikibase names it; `null` unless a property. */
    public val dataType: String?
        get() = (entity as? ParsedEntity.Property)?.dataType

    /** A lexeme's spellings by language; `null` unless it is a lexeme. */
    public val lemmas: Record<String, LanguageValue>?
        get() = (entity as? ParsedEntity.Lexeme)?.lemmas?.toRecord(::languageValueOf)

    /** A lexeme's spelling in [language], or `null`. */
    public fun lemma(language: String): String? = (entity as? ParsedEntity.Lexeme)?.lemma(language)

    /** The item for a lexeme's language, or `null`. */
    public val language: String?
        get() = (entity as? ParsedEntity.Lexeme)?.language?.value

    /** The item for a lexeme's part of speech, or `null`. */
    public val lexicalCategory: String?
        get() = (entity as? ParsedEntity.Lexeme)?.lexicalCategory?.value

    /** A lexeme's inflected forms; `null` unless it is a lexeme. */
    public val forms: Array<LexemeForm>?
        get() = (entity as? ParsedEntity.Lexeme)?.forms?.map(::LexemeForm)?.toTypedArray()

    /** A lexeme's meanings; `null` unless it is a lexeme. */
    public val senses: Array<LexemeSense>?
        get() = (entity as? ParsedEntity.Lexeme)?.senses?.map(::LexemeSense)?.toTypedArray()

    /** A file's caption in [language], or `null` unless this is a file's structured data. */
    public fun caption(language: String): String? = (entity as? ParsedEntity.MediaInfo)?.caption(language)
}

/** One statement of an entity. */
@JsExport
public class Claim internal constructor(internal val statement: Statement) {

    /** The statement's id, or `null` for one not saved yet. */
    public val id: String?
        get() = statement.id

    /** The property it is about, such as `"P31"`. */
    public val property: String
        get() = statement.property.value

    /** `"preferred"`, `"normal"` or `"deprecated"`. */
    public val rank: StatementRank
        get() = statement.rank.name.lowercase().unsafeCast<StatementRank>()

    /** `"value"`, or `"somevalue"` for an unknown value, or `"novalue"` for a stated absence. */
    public val snakType: SnakKind
        get() =
            when (statement.mainSnak) {
                is ParsedSnak.Value -> "value"
                is ParsedSnak.SomeValue -> "somevalue"
                is ParsedSnak.NoValue -> "novalue"
            }.unsafeCast<SnakKind>()

    /** The property's data type, such as `"wikibase-item"`, when the wiki gave it. */
    public val dataType: String?
        get() = (statement.mainSnak as? ParsedSnak.Value)?.dataType

    /** The value as Wikibase writes it, `{ type, value }`, or `null` for `somevalue` and `novalue`. */
    public val value: Record<String, JsonValue>?
        get() =
            (statement.mainSnak as? ParsedSnak.Value)?.let { plainOf(EntityEncoder.encodeValue(it.value)) }

    /** The whole statement as Wikibase writes it, qualifiers and references included. */
    public fun toJSON(): Record<String, JsonValue> = plainOf(EntityEncoder.encodeStatement(statement))

    /** What the statement asserts. */
    public val mainSnak: Snak
        get() = Snak(statement.mainSnak)

    /** The value as its own type, or `null` for `somevalue` and `novalue`. */
    public val dataValue: DataValue?
        get() = statement.value?.let(::DataValue)

    /** The conditions on the assertion. */
    public val qualifiers: Array<Snak>
        get() = statement.qualifiers.map(::Snak).toTypedArray()

    /** The sources cited for it. */
    public val references: Array<Reference>
        get() = statement.references.map(::referenceOf).toTypedArray()

    /** The qualifiers for [property]. */
    public fun qualifiersFor(property: String): Array<Snak> =
        statement.qualifiers(EntityId(property)).map(::Snak).toTypedArray()

    /** This statement with what [options] gives in place of what it had. */
    public fun copy(options: ClaimOptions): Claim = Claim(statementOf(statement.mainSnak, options, statement))

    /** This statement asserting [snak] instead. */
    public fun withMainSnak(snak: Snak): Claim = Claim(statement.copy(mainSnak = snak.snak))
}
