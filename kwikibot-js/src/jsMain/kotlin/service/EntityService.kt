import com.fenakhay.kwikibot.bindings.common.argumentName
import com.fenakhay.kwikibot.bindings.common.enumArgument
import com.fenakhay.kwikibot.bindings.wikibase.mergeItemsWithDefaults
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.wikibase.EntityEdit
import com.fenakhay.kwikibot.wikibase.EntityMatch as ClientEntityMatch
import com.fenakhay.kwikibot.wikibase.EntityService as ClientEntityService
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.wikibase
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise

/** What `wbgetentities` accepts in one request. */
@JsExport public const val ENTITY_BATCH_SIZE: Int = ClientEntityService.BATCH_SIZE

/** How many hits `wiki.entities.search` asks for unless told otherwise. */
@JsExport public const val ENTITY_SEARCH_LIMIT: Int = ClientEntityService.SEARCH_LIMIT

/**
 * The parts of an entity an edit sets. Everything is optional and what is left out is left alone, because
 * `wbeditentity` merges, unless [clear] says otherwise.
 */
@JsExport
public external interface EntityEditOptions {
    /** Labels to set, by language code. */
    public val labels: Record<String, String>?

    /** Descriptions to set, by language code. */
    public val descriptions: Record<String, String>?

    /** Aliases to set, by language code. Replaces the language entirely. */
    public val aliases: Record<String, Array<String>>?

    /** Sitelinks to set, by wiki database name. */
    public val siteLinks: Record<String, EntitySiteLink>?

    /** Statements to add or replace, by property. */
    public val statements: Record<String, Array<Claim>>?

    /** The edit summary. Wikibase prepends its own to it. */
    public val summary: String?

    /** Whether to flag the edit as a bot's; `true` unless given. */
    public val bot: Boolean?

    /** The revision the edit was computed from, so the repository can detect a conflict. */
    public val baseRevision: Double?

    /** Replace the entity with what this edit sets, deleting everything else on it. `false` unless given. */
    public val clear: Boolean?
}

/** Reading and writing a Wikibase repository's entities. Reached as `wiki.entities`. */
@JsExport
public class EntityService
internal constructor(private val wiki: ClientWiki, private val scope: CoroutineScope) {

    private val entities: ClientEntityService
        get() = wiki.wikibase()

    /** The entity [id], such as `"Q42"`, or `null` when there is none. */
    public fun entity(id: String): Promise<Entity?> = scope.promise {
        entities.entity(EntityId(id))?.let(::Entity)
    }

    /**
     * The entities [ids] names, by id, in as few requests as the wiki allows. An id that does not exist is
     * left out.
     */
    public fun entities(ids: Array<String>): Promise<Record<String, Entity>> = scope.promise {
        entities
            .entities(ids.map(::EntityId))
            .entries
            .associate { (id, entity) -> id.value to entity }
            .toRecord(::Entity)
    }

    /**
     * The entities about [titles] on the wiki with database name [site], each with the title asked for. A
     * title no entity is about is left out.
     */
    public fun forPages(site: String, titles: Array<String>): Promise<Array<EntityOfPage>> = scope.promise {
        entities
            .forPages(site, titles.toList())
            .map { (title, entity) -> EntityOfPage(title, Entity(entity)) }
            .toTypedArray()
    }

    /**
     * Searches entities by label and alias in [language]. [kind] is `"item"`, `"property"`, `"lexeme"`,
     * `"form"` or `"sense"`; the wiki does not search `"mediainfo"`, and asking for it or `"unknown"`
     * rejects.
     */
    public fun search(
        query: String,
        language: String = "en",
        kind: EntitySearchKind = "item".unsafeCast<EntitySearchKind>(),
        limit: Int = ENTITY_SEARCH_LIMIT,
    ): Promise<Array<EntityMatch>> = scope.promise {
        entities
            .search(query, language, entityKindOf(kind.unsafeCast<String>()), limit)
            .map(::EntityMatch)
            .toTypedArray()
    }

    /** Applies [options] to the existing entity [id], and answers with it as saved. */
    public fun edit(id: String, options: EntityEditOptions): Promise<Entity> = scope.promise {
        Entity(entities.edit(EntityId(id)) { apply(options) })
    }

    /**
     * Creates an entity of [kind] with what [options] sets, and answers with it. [kind] is `"item"`,
     * `"property"`, `"lexeme"`, `"form"`, `"sense"` or `"mediainfo"`.
     */
    public fun create(kind: NewEntityKind, options: EntityEditOptions): Promise<Entity> = scope.promise {
        Entity(entities.create(entityKindOf(kind.unsafeCast<String>())) { apply(options) })
    }

    /**
     * Adds [statement] to the entity [target], or replaces the one whose id it carries, and answers with it
     * as saved. [baseRevision] is the revision it was computed from, so the repository can detect a conflict.
     */
    public fun setStatement(
        target: String,
        statement: Claim,
        summary: String = "",
        baseRevision: Double? = null,
    ): Promise<Claim> = scope.promise {
        Claim(entities.setStatement(EntityId(target), statement.statement, summary, baseRevision?.toLong()))
    }

    /** Removes the statements with the ids [statementIds]. */
    public fun removeStatements(
        statementIds: Array<String>,
        summary: String = "",
        baseRevision: Double? = null,
    ): Promise<Unit> = scope.promiseVoid {
        entities.removeStatements(statementIds.toList(), summary, baseRevision?.toLong())
    }

    /**
     * Merges the item [from] into the item [into], leaving the first as a redirect. [ignoreConflicts] says
     * which conflicting parts to overwrite rather than refuse on: `"description"`, `"sitelink"`,
     * `"statement"`.
     */
    public fun mergeItems(
        from: String,
        into: String,
        ignoreConflicts: Array<MergeConflict>? = null,
        summary: String = "",
    ): Promise<Unit> = scope.promiseVoid {
        entities.mergeItemsWithDefaults(from, into, ignoreConflicts?.map { it.unsafeCast<String>() }, summary)
    }

    /** Merges the lexeme [from] into the lexeme [into]. */
    public fun mergeLexemes(from: String, into: String, summary: String = ""): Promise<Unit> =
        scope.promiseVoid {
            entities.mergeLexemes(EntityId(from), EntityId(into), summary)
        }

    /** Points the empty entity [from] at the entity [to] as a redirect. */
    public fun redirect(from: String, to: String): Promise<Unit> = scope.promiseVoid {
        entities.redirect(EntityId(from), EntityId(to))
    }

    /**
     * Parses [values] the way Wikibase would, without saving anything. [dataType] is the Wikibase type name:
     * `"time"`, `"globe-coordinate"`, `"quantity"`.
     */
    public fun parseValues(dataType: String, values: Array<String>): Promise<Array<DataValue>> =
        scope.promise {
            entities.parseValues(dataType, values.toList()).map(::DataValue).toTypedArray()
        }
}

/** One entity found for a page, with the title it was asked for. */
@JsExport
public class EntityOfPage
internal constructor(
    /** The title as it was asked for. */
    public val title: String,
    /** The entity about it. */
    public val entity: Entity,
)

/** One hit from an entity search: enough to choose between candidates. */
@JsExport
public class EntityMatch internal constructor(private val match: ClientEntityMatch) {

    /** The entity found. */
    public val id: String
        get() = match.id.value

    /** Its label in the language searched, or `null`. */
    public val label: String?
        get() = match.label

    /** Its description, which is what usually separates two candidates, or `null`. */
    public val description: String?
        get() = match.description
}

/** What kind of entity [id] is from its prefix, as `Entity.type` says it. */
@JsExport
public fun entityKind(id: String): EntityKind = EntityId(id).kind.argumentName().unsafeCast<EntityKind>()

/** The kind [name] spells, as `Entity.type` spells it. */
private fun entityKindOf(name: String): EntityId.Kind = enumArgument("kind", name)

private fun EntityEdit.apply(options: EntityEditOptions) {
    options.labels?.let { labels = it.toMap() }
    options.descriptions?.let { descriptions = it.toMap() }
    options.aliases?.let { aliases = it.toMap().mapValues { (_, values) -> values.toList() } }
    options.siteLinks?.let { siteLinks = it.toMap().mapValues { (_, link) -> link.parsed() } }
    options.statements?.let { statements = statementsOf(it) }
    options.summary?.let { summary = it }
    options.bot?.let { bot = it }
    options.baseRevision?.let { baseRevision = it.toLong() }
    options.clear?.let { clear = it }
}
