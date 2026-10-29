package com.fenakhay.kwikibot.wikibase.entity

import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Rank
import com.fenakhay.kwikibot.wikibase.value.Statement

/** Text in one language, as labels, descriptions and lemmas are stored. */
public data class LanguageValue(
    /** The language code, or [MULTIPLE_LANGUAGES] for a value that applies to all. */
    val language: String,
    /** The text itself. */
    val value: String,
)

/**
 * The pseudo-language Wikibase uses for a label that applies to every language.
 *
 * A name spelled the same everywhere is stored once under `mul` rather than repeated in three hundred
 * languages, which is why an entity can have no `en` label and still have an English one.
 */
public const val MULTIPLE_LANGUAGES: String = "mul"

/** A page on a wiki that an item is about. */
public data class SiteLink(
    /** The wiki's database name, `enwiktionary`. */
    val site: String,
    /** The page's title on that wiki. */
    val title: String,
    /** Marks on the link, such as the badge for a featured article. */
    val badges: List<EntityId> = emptyList(),
)

/**
 * A Wikibase entity.
 *
 * Sealed over the kinds this library models, so code that handles entities has to say what it does with a
 * lexeme as well as an item — the two are different enough that treating them alike is usually a bug.
 */
public sealed interface Entity {

    /** The entity's own id, which is how everything else refers to it. */
    public val id: EntityId

    /** Labels by language code. */
    public val labels: Map<String, LanguageValue>

    /** Descriptions by language code. */
    public val descriptions: Map<String, LanguageValue>

    /** Aliases by language code. */
    public val aliases: Map<String, List<LanguageValue>>

    /** Statements grouped by the property they are about. */
    public val statements: Map<EntityId, List<Statement>>

    /** The revision this entity was read at, for detecting an edit conflict. */
    public val lastRevisionId: Long?

    /**
     * The label in [language], falling back to the `mul` pseudo-language.
     *
     * The fallback is not a nicety. Wikidata has moved many labels to `mul`, the label that applies to every
     * language at once, and Q42 has no `en` label at all — so reading `labels["en"]` for Douglas Adams
     * returns nothing while the entity plainly has an English label. Anything that asks for one language must
     * look there too.
     */
    public fun label(language: String): String? = labels[language]?.value ?: labels[MULTIPLE_LANGUAGES]?.value

    /**
     * The description in [language], or `null`.
     *
     * No `mul` fallback: descriptions distinguish entities *within* a language, so one shared across all of
     * them would defeat the purpose, and Wikibase does not store them that way.
     */
    public fun description(language: String): String? = descriptions[language]?.value

    /** The label in [language] only, with no fallback. */
    public fun labelIn(language: String): String? = labels[language]?.value

    /** Every statement for one property. */
    public operator fun get(property: EntityId): List<Statement> = statements[property].orEmpty()

    /**
     * The statement to use when only one is wanted.
     *
     * Preferred statements win; deprecated ones are never chosen, since they are kept precisely because they
     * are wrong.
     */
    public fun best(property: EntityId): Statement? {
        val candidates = get(property).filter { it.rank != Rank.DEPRECATED }
        return candidates.firstOrNull { it.rank == Rank.PREFERRED } ?: candidates.firstOrNull()
    }

    /** An ordinary item: `Q42`. */
    public data class Item(
        override val id: EntityId,
        override val labels: Map<String, LanguageValue> = emptyMap(),
        override val descriptions: Map<String, LanguageValue> = emptyMap(),
        override val aliases: Map<String, List<LanguageValue>> = emptyMap(),
        override val statements: Map<EntityId, List<Statement>> = emptyMap(),
        /** The pages this item is about, by wiki database name. */
        val siteLinks: Map<String, SiteLink> = emptyMap(),
        override val lastRevisionId: Long? = null,
    ) : Entity {
        /** The page this item is about on one wiki, by database name. */
        public fun siteLink(site: String): SiteLink? = siteLinks[site]
    }

    /** A property: `P31`. Its data type decides what its statements may hold. */
    public data class Property(
        override val id: EntityId,
        /** What kind of value its statements may hold, as Wikibase names it. */
        val dataType: String,
        override val labels: Map<String, LanguageValue> = emptyMap(),
        override val descriptions: Map<String, LanguageValue> = emptyMap(),
        override val aliases: Map<String, List<LanguageValue>> = emptyMap(),
        override val statements: Map<EntityId, List<Statement>> = emptyMap(),
        override val lastRevisionId: Long? = null,
    ) : Entity

    /**
     * A lexeme: `L1`, a word rather than a concept.
     *
     * Wiktionary's own data lives here, which is why it is modelled rather than deferred: a lexeme has
     * lemmas, a language, a lexical category, forms and senses, none of which an item has.
     */
    public data class Lexeme(
        override val id: EntityId,
        /** The word as written, by language. A lexeme has these where an item has labels. */
        val lemmas: Map<String, LanguageValue> = emptyMap(),
        /** The language the word belongs to, as an item id. */
        val language: EntityId? = null,
        /** Its part of speech, as an item id. */
        val lexicalCategory: EntityId? = null,
        /** Its inflected forms. */
        val forms: List<Form> = emptyList(),
        /** Its meanings. */
        val senses: List<Sense> = emptyList(),
        override val statements: Map<EntityId, List<Statement>> = emptyMap(),
        override val lastRevisionId: Long? = null,
    ) : Entity {
        // A lexeme has lemmas rather than labels; these keep the interface honest.
        override val labels: Map<String, LanguageValue>
            get() = lemmas

        override val descriptions: Map<String, LanguageValue>
            get() = emptyMap()

        override val aliases: Map<String, List<LanguageValue>>
            get() = emptyMap()

        /** The lemma in [language], or `null`. */
        public fun lemma(language: String): String? = lemmas[language]?.value
    }

    /**
     * The structured data of a file: `M12345`, on Commons.
     *
     * The id is the page id of the file description page, not an id of its own, which is why a MediaInfo
     * entity cannot exist without the file it describes.
     */
    public data class MediaInfo(
        override val id: EntityId,
        override val labels: Map<String, LanguageValue> = emptyMap(),
        override val statements: Map<EntityId, List<Statement>> = emptyMap(),
        override val lastRevisionId: Long? = null,
    ) : Entity {
        // MediaInfo has captions, which are labels, and nothing else of the kind.
        override val descriptions: Map<String, LanguageValue>
            get() = emptyMap()

        override val aliases: Map<String, List<LanguageValue>>
            get() = emptyMap()

        /** The caption in [language], which is what a label means for a file. */
        public fun caption(language: String): String? = label(language)
    }

    /** One inflected form of a lexeme. */
    public data class Form(
        /** The form's id, `L1-F2`. */
        val id: EntityId,
        /** How the form is spelled, by language. */
        val representations: Map<String, LanguageValue> = emptyMap(),
        /** What makes it this form: plural, genitive, past participle. */
        val grammaticalFeatures: List<EntityId> = emptyList(),
        /** Statements about the form itself rather than about the word. */
        val statements: Map<EntityId, List<Statement>> = emptyMap(),
    )

    /** One sense of a lexeme. */
    public data class Sense(
        /** The sense's id, `L1-S1`. */
        val id: EntityId,
        /** What the sense means, by language. */
        val glosses: Map<String, LanguageValue> = emptyMap(),
        /** Statements about this meaning rather than about the word. */
        val statements: Map<EntityId, List<Statement>> = emptyMap(),
    )
}
