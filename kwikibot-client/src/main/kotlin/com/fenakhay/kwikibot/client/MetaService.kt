package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.LanguageInfo
import com.fenakhay.kwikibot.model.TextDirection
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What a wiki says about itself beyond what a session needs to start.
 *
 * [SiteInfo] is fetched once at login because titles cannot be parsed without it. Everything
 * else — interface messages, magic words, statistics, the rest of `siprop` — is fetched when it
 * is first asked for and kept for the life of the session, because it changes when the wiki is
 * reconfigured rather than during a run.
 */
public interface MetaService {

    /**
     * One `siprop` block, fetched on first use.
     *
     * The raw JSON, because the blocks have nothing in common and wrapping each in a type would
     * be a type per block for no gain. `null` means the wiki does not have that property.
     */
    public suspend fun property(name: String): JsonElement?

    /**
     * Interface messages, by key.
     *
     * A bot that has to recognise what a template produced, or write a summary in the wiki's own
     * words, needs the wiki's own strings rather than a guess at them.
     *
     * @param keys the message keys to fetch.
     * @param language the language to fetch them in. `null` uses the wiki's own.
     */
    public suspend fun messages(
        keys: Collection<String>,
        language: String? = null,
    ): Map<String, String>

    /** One interface message, or `null` if the wiki has no such message. */
    public suspend fun message(key: String, language: String? = null): String?

    /**
     * The magic words this wiki knows, by name, with every alias it accepts.
     *
     * `redirect` is `#REDIRECT` on en and `#REDIRECCIÓN` on es, and a bot that recognises only
     * the English spelling does not recognise redirects on most wikis.
     */
    public suspend fun magicWords(): Map<String, List<String>>

    /** The wiki's own counters: `pages`, `articles`, `edits`, `users`, `activeusers`. */
    public suspend fun statistics(): Map<String, Long>

    /**
     * What the wiki knows about languages, by code.
     *
     * The wiki is the authority rather than the JVM's locale data: it carries codes the JVM has
     * never heard of, and its fallback chain is the one a bot has to follow when choosing which
     * language to address someone in.
     *
     * @param codes the languages to ask about. Empty asks about every one the wiki knows, which
     *   is several hundred and worth asking for only once.
     */
    public suspend fun languages(codes: Collection<LangCode> = emptyList()): Map<LangCode, LanguageInfo>

    /**
     * Adds or removes change tags on revisions, recent changes or log entries.
     *
     * Untested against a live wiki: needs the `changetags` right, and `applychangetags` for the
     * tags themselves.
     *
     * @param add the tags to apply.
     * @param remove the tags to take off.
     * @param revisions revision ids to tag. At least one of these, [recentChanges] or
     *   [logEntries] is required.
     * @param recentChanges recent-changes ids to tag.
     * @param logEntries log ids to tag.
     * @param reason why, which is recorded in the tag log.
     */
    public suspend fun applyTags(
        add: Set<String> = emptySet(),
        remove: Set<String> = emptySet(),
        revisions: Collection<Long> = emptyList(),
        recentChanges: Collection<Long> = emptyList(),
        logEntries: Collection<Long> = emptyList(),
        reason: String = "",
    )

    /**
     * Defines, retires or removes a change tag. Needs the `managechangetags` right.
     *
     * Untested against a live wiki. Distinct from [applyTags], which puts an existing tag on an
     * edit: this decides which tags exist at all.
     */
    public suspend fun manageTag(tag: String, operation: TagOperation, reason: String = "")

}

/** What [MetaService.manageTag] should do to a tag definition. */
public enum class TagOperation(internal val apiValue: String) {
    /** Define a new tag, which users may then apply. */
    CREATE("create"),

    /** Delete a tag and remove it from everything carrying it. */
    DELETE("delete"),

    /** Allow the tag to be applied again. */
    ACTIVATE("activate"),

    /** Stop the tag being applied, without touching what already carries it. */
    DEACTIVATE("deactivate"),
}

internal class ApiMetaService(
    private val transport: MediaWikiTransport,
    private val tokens: TokenStore,
) : MetaService {

    private val mutex = Mutex()
    private val properties = mutableMapOf<String, JsonElement?>()
    private val messageCache = mutableMapOf<String, String>()

    override suspend fun property(name: String): JsonElement? {
        if (name in properties) return properties[name]

        return mutex.withLock {
            if (name in properties) return@withLock properties[name]

            val response = transport.call(
                ApiRequest.of("query", "meta" to "siteinfo", "siprop" to name),
            ).throwOnError()

            response["query"]?.jsonObject?.get(name).also { properties[name] = it }
        }
    }

    override suspend fun messages(
        keys: Collection<String>,
        language: String?,
    ): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()

        // Only the ones not already held: a bot asking for the same message per page would
        // otherwise ask the wiki per page.
        val wanted = keys.distinct()
        val missing = wanted.filter { it !in messageCache }

        if (missing.isNotEmpty()) {
            val response = transport.call(
                ApiRequest.of(
                    "query",
                    "meta" to "allmessages",
                    "ammessages" to missing.joinToString("|"),
                    "amlang" to language,
                ),
            ).throwOnError()

            response["query"]?.jsonObject?.get("allmessages")?.jsonArray.orEmpty().forEach { entry ->
                val message = entry.jsonObject
                val name = message["name"]?.jsonPrimitive?.content ?: return@forEach
                // A message the wiki does not have comes back flagged rather than omitted.
                if (message.containsKey("missing")) return@forEach
                message["content"]?.jsonPrimitive?.content?.let { messageCache[name] = it }
            }
        }

        return wanted.mapNotNull { key -> messageCache[key]?.let { key to it } }.toMap()
    }

    override suspend fun message(key: String, language: String?): String? =
        messages(listOf(key), language)[key]

    override suspend fun magicWords(): Map<String, List<String>> =
        property("magicwords")?.jsonArray.orEmpty().associate { entry ->
            val word = entry.jsonObject
            word["name"]?.jsonPrimitive?.content.orEmpty() to
                word["aliases"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        }

    override suspend fun languages(codes: Collection<LangCode>): Map<LangCode, LanguageInfo> {
        val response = transport.call(
            ApiRequest.of(
                "query",
                "meta" to "languageinfo",
                "licode" to codes.takeIf { it.isNotEmpty() }?.joinToString("|") { it.code },
                "liprop" to "code|name|autonym|dir|fallbacks|bcp47",
            ),
        ).throwOnError()

        // An object keyed by code, not the array every other list module answers with.
        val block = response["query"]?.jsonObject?.get("languageinfo")?.jsonObject ?: return emptyMap()

        return block.mapNotNull { (code, described) ->
            val entry = described.jsonObject
            LangCode(code) to LanguageInfo(
                code = LangCode(code),
                name = entry.text("name"),
                autonym = entry.text("autonym"),
                direction = TextDirection.of(entry.text("dir")),
                fallbacks = (entry["fallbacks"] as? JsonArray).orEmpty()
                    .mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    .map { LangCode(it) },
                bcp47 = entry.text("bcp47").takeIf { it.isNotEmpty() },
            )
        }.toMap()
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

    override suspend fun statistics(): Map<String, Long> =
        (property("statistics") as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            value.jsonPrimitive.longOrNull?.let { key to it }
        }.toMap()

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    override suspend fun applyTags(
        add: Set<String>,
        remove: Set<String>,
        revisions: Collection<Long>,
        recentChanges: Collection<Long>,
        logEntries: Collection<Long>,
        reason: String,
    ) {
        require(add.isNotEmpty() || remove.isNotEmpty()) { "applyTags must add or remove a tag" }
        require(revisions.isNotEmpty() || recentChanges.isNotEmpty() || logEntries.isNotEmpty()) {
            "applyTags needs revisions, recentChanges or logEntries to tag"
        }

        write {
            put("action", "tag")
            if (add.isNotEmpty()) put("add", add.joinToString("|"))
            if (remove.isNotEmpty()) put("remove", remove.joinToString("|"))
            if (revisions.isNotEmpty()) put("revid", revisions.joinToString("|"))
            if (recentChanges.isNotEmpty()) put("rcid", recentChanges.joinToString("|"))
            if (logEntries.isNotEmpty()) put("logid", logEntries.joinToString("|"))
            put("reason", reason)
        }
    }

    override suspend fun manageTag(tag: String, operation: TagOperation, reason: String) {
        write {
            put("action", "managetags")
            put("operation", operation.apiValue)
            put("tag", tag)
            put("reason", reason)
        }
    }

    private suspend fun write(params: MutableMap<String, String>.() -> Unit) {
        tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        params()
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).also { it.raiseBadToken() }.throwOnError()
        }
    }
}
