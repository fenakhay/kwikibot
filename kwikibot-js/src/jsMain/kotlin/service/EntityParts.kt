import com.fenakhay.kwikibot.wikibase.entity.Entity as ParsedEntity
import com.fenakhay.kwikibot.wikibase.entity.LanguageValue as ParsedLanguageValue
import com.fenakhay.kwikibot.wikibase.entity.SiteLink as ParsedSiteLink
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Statement

/** Text in one language, as labels, descriptions and lemmas are stored. */
@JsExport
public class LanguageValue(
    /** The language code, or `"mul"` for a value that applies to all. */
    public val language: String,
    /** The text. */
    public val value: String,
)

/** [value] as the facade reports it. */
internal fun languageValueOf(value: ParsedLanguageValue): LanguageValue =
    LanguageValue(value.language, value.value)

/** This value as the library's own. */
internal fun LanguageValue.parsed() = ParsedLanguageValue(language, value)

/** A page on a wiki that an item is about. */
@JsExport
public class EntitySiteLink(
    /** The wiki's database name, such as `"enwiktionary"`. */
    public val site: String,
    /** The page's title on that wiki. */
    public val title: String,
    /** Marks on the link, each an item id, such as the badge for a featured article. */
    public val badges: Array<String> = emptyArray(),
)

/** [link] as the facade reports it. */
internal fun entitySiteLinkOf(link: ParsedSiteLink): EntitySiteLink =
    EntitySiteLink(link.site, link.title, link.badges.map { it.value }.toTypedArray())

/** This link as the library's own. */
internal fun EntitySiteLink.parsed(): ParsedSiteLink = ParsedSiteLink(site, title, badges.map(::EntityId))

/** [statements] by property id, as a table. */
internal fun claimsByProperty(statements: Map<EntityId, List<Statement>>): Record<String, Array<Claim>> =
    statements.entries
        .associate { (property, claims) -> property.value to claims }
        .toRecord { claims -> claims.map(::Claim).toTypedArray() }

/** One inflected form of a lexeme. */
@JsExport
public class LexemeForm internal constructor(private val form: ParsedEntity.Form) {

    /** The form's id, such as `"L1-F2"`. */
    public val id: String
        get() = form.id.value

    /** How the form is spelled, by language. */
    public val representations: Record<String, LanguageValue>
        get() = form.representations.toRecord(::languageValueOf)

    /** What makes it this form, each an item id: plural, genitive, past participle. */
    public val grammaticalFeatures: Array<String>
        get() = form.grammaticalFeatures.map { it.value }.toTypedArray()

    /** Statements about the form itself rather than about the word, by property. */
    public val statements: Record<String, Array<Claim>>
        get() = claimsByProperty(form.statements)
}

/** One sense of a lexeme. */
@JsExport
public class LexemeSense internal constructor(private val sense: ParsedEntity.Sense) {

    /** The sense's id, such as `"L1-S1"`. */
    public val id: String
        get() = sense.id.value

    /** What the sense means, by language. */
    public val glosses: Record<String, LanguageValue>
        get() = sense.glosses.toRecord(::languageValueOf)

    /** Statements about this meaning rather than about the word, by property. */
    public val statements: Record<String, Array<Claim>>
        get() = claimsByProperty(sense.statements)
}

/** [statements], a table by property, as the library's own. */
internal fun statementsOf(statements: Record<String, Array<Claim>>): Map<EntityId, List<Statement>> =
    statements.toMap().entries.associate { (property, claims) ->
        EntityId(property) to claims.map { it.statement }
    }
