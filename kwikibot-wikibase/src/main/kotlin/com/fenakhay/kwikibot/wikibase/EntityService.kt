package com.fenakhay.kwikibot.wikibase

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.KwikibotDsl
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ApiFailure
import com.fenakhay.kwikibot.protocol.throwOnError
import com.fenakhay.kwikibot.wikibase.entity.Entity
import com.fenakhay.kwikibot.wikibase.entity.LanguageValue
import com.fenakhay.kwikibot.wikibase.entity.SiteLink
import com.fenakhay.kwikibot.wikibase.value.DataValue
import com.fenakhay.kwikibot.wikibase.value.EntityId
import com.fenakhay.kwikibot.wikibase.value.Statement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One hit from an entity search: enough to choose between candidates, not the whole entity. */
public data class EntityMatch(
    /** The entity found. */
    val id: EntityId,
    /** Its label in the language searched, absent where it has none. */
    val label: String?,
    /** Its description, which is what usually separates two candidates. */
    val description: String?,
)

/**
 * Reading and writing Wikibase entities.
 *
 * A repository is a wiki of its own — Wikidata is not en.wiktionary — so this service is reached from the
 * repository handle rather than from the wiki a bot happens to be editing. Opening it is the caller's job
 * precisely so that the two wikis, their throttles and their sessions stay distinct.
 */
public interface EntityService {

    /** Fetches one entity, or `null` if there is no such entity. */
    public suspend fun entity(id: EntityId): Entity?

    /**
     * Fetches many entities in as few requests as the wiki allows.
     *
     * Ids that do not exist are absent from the result rather than present and empty.
     */
    public suspend fun entities(ids: Collection<EntityId>): Map<EntityId, Entity>

    /**
     * Fetches the entities about pages on one wiki, keyed by the title asked for.
     *
     * The wiki normalizes titles, so a request for `douglas adams` comes back under the title it was asked
     * for, matched to the entity through its sitelink.
     */
    public suspend fun forPages(site: String, titles: Collection<String>): Map<String, Entity>

    /** Searches entities by label and alias. */
    public suspend fun search(
        query: String,
        language: String = "en",
        kind: EntityId.Kind = EntityId.Kind.ITEM,
        limit: Int = SEARCH_LIMIT,
    ): List<EntityMatch>

    /**
     * Applies an edit to an existing entity and returns it as saved.
     *
     * Only what the block sets is touched; everything else on the entity is left alone unless
     * [EntityEdit.clear] says otherwise.
     */
    public suspend fun edit(id: EntityId, block: EntityEdit.() -> Unit): Entity

    /** Creates a new entity of [kind] and returns it. */
    public suspend fun create(kind: EntityId.Kind, block: EntityEdit.() -> Unit): Entity

    /**
     * Adds a statement, or replaces the one whose [Statement.id] it carries.
     *
     * @param target the entity to change.
     * @param statement the statement to add or replace.
     * @param summary the edit summary.
     * @param baseRevision the revision the statement was computed from, so the repository can detect a
     *   conflicting edit. Leaving it unset means "save regardless".
     */
    public suspend fun setStatement(
        target: EntityId,
        statement: Statement,
        summary: String = "",
        baseRevision: Long? = null,
    ): Statement

    /** Removes statements by their GUIDs. */
    public suspend fun removeStatements(
        statementIds: Collection<String>,
        summary: String = "",
        baseRevision: Long? = null,
    )

    /**
     * Merges one item into another, leaving the first as a redirect.
     *
     * @param from the item that becomes a redirect.
     * @param into the item that keeps everything.
     * @param ignoreConflicts which conflicting parts to overwrite rather than refuse on: `description`,
     *   `sitelink`, `statement`. Empty means refuse on any conflict, which is the right default, since a
     *   merge that silently drops a description cannot be reviewed.
     * @param summary the edit summary.
     */
    public suspend fun mergeItems(
        from: EntityId,
        into: EntityId,
        ignoreConflicts: Set<String> = emptySet(),
        summary: String = "",
    )

    /** Merges one lexeme into another. Lexemes merge by their own rules and their own module. */
    public suspend fun mergeLexemes(from: EntityId, into: EntityId, summary: String = "")

    /**
     * Points a spare entity at another as a redirect.
     *
     * Only an empty entity can be redirected; Wikibase refuses otherwise, which is what stops a redirect from
     * concealing existing content.
     */
    public suspend fun redirect(from: EntityId, to: EntityId)

    /**
     * Parses values the way Wikibase would, without saving anything.
     *
     * The only way to know whether a date string or a coordinate is one Wikibase accepts is to ask it. A bot
     * building statements from scraped text should ask before it saves rather than after it is reverted.
     *
     * @param dataType the Wikibase type name: `time`, `globe-coordinate`, `quantity`.
     * @param values the strings to parse.
     */
    public suspend fun parseValues(dataType: String, values: List<String>): List<DataValue>

    /** The batch size the repository enforces. */
    public companion object {
        /** What `wbgetentities` accepts in one request. */
        public const val BATCH_SIZE: Int = 50

        /** A search page, matching the API default. */
        public const val SEARCH_LIMIT: Int = 7
    }
}

/**
 * The parts of an entity an edit sets.
 *
 * Everything is optional and anything left unset is left alone, because `wbeditentity` merges by default:
 * sending only a label does not blank the statements. [clear] switches that off, and is kept explicit and
 * loud for that reason.
 */
@KwikibotDsl
public class EntityEdit {

    /** Labels to set, by language code. */
    public var labels: Map<String, String> = emptyMap()

    /** Descriptions to set, by language code. */
    public var descriptions: Map<String, String> = emptyMap()

    /** Aliases to set, by language code. Replaces the language entirely. */
    public var aliases: Map<String, List<String>> = emptyMap()

    /** Sitelinks to set, by wiki database name. */
    public var siteLinks: Map<String, SiteLink> = emptyMap()

    /** Statements to add or replace, grouped by property. */
    public var statements: Map<EntityId, List<Statement>> = emptyMap()

    /** The edit summary. Wikibase prepends its own auto-summary to it. */
    public var summary: String = ""

    /** Whether to flag the edit as a bot edit. */
    public var bot: Boolean = true

    /** The revision the edit was computed from, so the repository can detect a conflict. */
    public var baseRevision: Long? = null

    /**
     * Replace the entity with what this edit sets, deleting everything else on it.
     *
     * Almost never what a bot wants: it discards work by editors the bot has never seen.
     */
    public var clear: Boolean = false

    /** The `data` parameter, which is where `wbeditentity` takes the whole edit. */
    internal fun data(): JsonObject = buildJsonObject {
        if (labels.isNotEmpty()) {
            put("labels", EntityEncoder.encodeLanguageValues(labels.toLanguageValues()))
        }
        if (descriptions.isNotEmpty()) {
            put(
                "descriptions",
                EntityEncoder.encodeLanguageValues(descriptions.toLanguageValues()),
            )
        }
        if (aliases.isNotEmpty()) {
            val values = aliases.mapValues { (language, list) ->
                list.map { LanguageValue(language, it) }
            }
            put("aliases", EntityEncoder.encodeAliases(values))
        }
        if (siteLinks.isNotEmpty()) {
            put("sitelinks", EntityEncoder.encodeSiteLinks(siteLinks))
        }
        if (statements.isNotEmpty()) {
            put("claims", EntityEncoder.encodeStatements(statements))
        }
    }

    private fun Map<String, String>.toLanguageValues(): Map<String, LanguageValue> =
        mapValues { (language, value) ->
            LanguageValue(language, value)
        }
}

/**
 * The Wikibase repository reached through this wiki.
 *
 * Constructed per call rather than cached on [Wiki], since the service holds nothing but the transport it was
 * given.
 */
public fun Wiki.wikibase(): EntityService = ApiEntityService(transport, tokens)

internal class ApiEntityService(
    private val transport: MediaWikiTransport,
    private val tokens: TokenStore,
) : EntityService {

    override suspend fun entity(id: EntityId): Entity? = entities(listOf(id))[id]

    override suspend fun entities(ids: Collection<EntityId>): Map<EntityId, Entity> {
        if (ids.isEmpty()) return emptyMap()

        val found = mutableMapOf<EntityId, Entity>()
        for (batch in ids.distinct().chunked(EntityService.BATCH_SIZE)) {
            val response =
                transport
                    .call(
                        ApiRequest.of(
                            "wbgetentities",
                            "ids" to batch.joinToString("|") { it.value },
                        )
                    )
                    .throwOnError()
            found += EntityDecoder.decodeAll(response)
        }
        return found
    }

    override suspend fun forPages(site: String, titles: Collection<String>): Map<String, Entity> {
        if (titles.isEmpty()) return emptyMap()

        val byTitle = mutableMapOf<String, Entity>()
        for (batch in titles.distinct().chunked(EntityService.BATCH_SIZE)) {
            val response =
                transport
                    .call(
                        ApiRequest.of(
                            "wbgetentities",
                            "sites" to site,
                            "titles" to batch.joinToString("|"),
                        )
                    )
                    .throwOnError()

            // The response says nothing about which requested title produced which entity, so
            // the mapping is rebuilt from the sitelinks — the same relation, read back.
            val linked =
                EntityDecoder.decodeAll(response)
                    .values
                    .filterIsInstance<Entity.Item>()
                    .mapNotNull { item -> item.siteLink(site)?.let { it.title to item } }
                    .toMap()

            for (title in batch) {
                val entity =
                    linked[title]
                        // A title differing only in the case of its first letter is the same page:
                        // MediaWiki capitalizes it, and the caller should not have to.
                        ?: linked.entries.firstOrNull { it.key.equals(title, ignoreCase = true) }?.value
                if (entity != null) byTitle[title] = entity
            }
        }
        return byTitle
    }

    override suspend fun search(
        query: String,
        language: String,
        kind: EntityId.Kind,
        limit: Int,
    ): List<EntityMatch> {
        val response =
            transport
                .call(
                    ApiRequest.of(
                        "wbsearchentities",
                        "search" to query,
                        "language" to language,
                        "uselang" to language,
                        "type" to EntityId("${kind.samplePrefix}1").entityType,
                        "limit" to limit.toString(),
                    )
                )
                .throwOnError()

        return response["search"]?.jsonArray.orEmpty().map { hit ->
            val entry = hit.jsonObject
            EntityMatch(
                id = EntityId(entry["id"]?.jsonPrimitive?.content.orEmpty()),
                label = entry["label"]?.jsonPrimitive?.content,
                description = entry["description"]?.jsonPrimitive?.content,
            )
        }
    }

    override suspend fun edit(id: EntityId, block: EntityEdit.() -> Unit): Entity {
        val builder = EntityEdit().apply(block)
        return editEntity(builder) { put("id", id.value) }
    }

    override suspend fun create(kind: EntityId.Kind, block: EntityEdit.() -> Unit): Entity {
        val builder = EntityEdit().apply(block)
        val type = EntityId("${kind.samplePrefix}1").entityType
        require(type != "unknown") { "cannot create an entity of kind $kind" }
        return editEntity(builder) { put("new", type) }
    }

    override suspend fun setStatement(
        target: EntityId,
        statement: Statement,
        summary: String,
        baseRevision: Long?,
    ): Statement {
        val response =
            write("wbsetclaim") {
                put("claim", EntityEncoder.encodeStatement(statement).toString())
                put("summary", summary)
                put("bot", "1")
                baseRevision?.let { put("baserevid", it.toString()) }
            }

        val saved =
            response["claim"]?.jsonObject
                ?: throw WikiError.Api(
                    "noclaim",
                    "wbsetclaim returned no claim for ${target.value}",
                    "wbsetclaim",
                )
        return EntityDecoder.decodeStatement(saved)
    }

    override suspend fun removeStatements(
        statementIds: Collection<String>,
        summary: String,
        baseRevision: Long?,
    ) {
        if (statementIds.isEmpty()) return

        write("wbremoveclaims") {
            put("claim", statementIds.joinToString("|"))
            put("summary", summary)
            put("bot", "1")
            baseRevision?.let { put("baserevid", it.toString()) }
        }
    }

    override suspend fun mergeItems(
        from: EntityId,
        into: EntityId,
        ignoreConflicts: Set<String>,
        summary: String,
    ) {
        write("wbmergeitems") {
            put("fromid", from.value)
            put("toid", into.value)
            if (ignoreConflicts.isNotEmpty()) {
                put("ignoreconflicts", ignoreConflicts.joinToString("|"))
            }
            put("summary", summary)
            put("bot", "1")
        }
    }

    override suspend fun mergeLexemes(from: EntityId, into: EntityId, summary: String) {
        write("wblmergelexemes") {
            put("source", from.value)
            put("target", into.value)
            put("summary", summary)
            put("bot", "1")
        }
    }

    override suspend fun redirect(from: EntityId, to: EntityId) {
        write("wbcreateredirect") {
            put("from", from.value)
            put("to", to.value)
            put("bot", "1")
        }
    }

    override suspend fun parseValues(dataType: String, values: List<String>): List<DataValue> {
        if (values.isEmpty()) return emptyList()

        val response =
            transport
                .call(
                    ApiRequest.of(
                        "wbparsevalue",
                        "datatype" to dataType,
                        "values" to values.joinToString("|"),
                    )
                )
                .throwOnError()

        return response["results"]?.jsonArray.orEmpty().map { result ->
            EntityDecoder.decodeValue(result.jsonObject)
        }
    }

    private suspend fun editEntity(
        edit: EntityEdit,
        target: MutableMap<String, String>.() -> Unit,
    ): Entity {
        val response =
            write("wbeditentity") {
                target()
                put("data", edit.data().toString())
                put("summary", edit.summary)
                if (edit.bot) put("bot", "1")
                if (edit.clear) put("clear", "1")
                edit.baseRevision?.let { put("baserevid", it.toString()) }
            }

        val entity =
            response["entity"]?.jsonObject
                ?: throw WikiError.Api(
                    "noentity",
                    "wbeditentity returned no entity",
                    "wbeditentity",
                )
        return EntityDecoder.decode(entity)
    }

    /** Runs one write, refreshing the token if the repository rejects it. */
    private suspend fun write(
        action: String,
        params: MutableMap<String, String>.() -> Unit,
    ): JsonObject = tokens.withFreshToken { token ->
        val response =
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", action)
                        params()
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                )
            )

        // The transport hands error blocks back rather than throwing, so a stale token has to be
        // raised here for withFreshToken to see it and retry.
        ApiFailure.from(response)?.let { failure ->
            if (failure.code == BAD_TOKEN) throw WikiError.Auth.BadToken(TokenStore.CSRF)
            throw failure.toWikiError()
        }
        response
    }

    private companion object {
        const val BAD_TOKEN = "badtoken"
    }
}

/**
 * A representative id of this kind, for the places the API wants an entity type rather than an id — searching
 * and creating.
 */
private val EntityId.Kind.samplePrefix: String
    get() =
        when (this) {
            EntityId.Kind.ITEM -> "Q"
            EntityId.Kind.PROPERTY -> "P"
            EntityId.Kind.LEXEME -> "L"
            EntityId.Kind.FORM -> "L1-F"
            EntityId.Kind.SENSE -> "L1-S"
            EntityId.Kind.MEDIA_INFO -> "M"
            EntityId.Kind.UNKNOWN -> "?"
        }
