package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.raiseBadToken
import com.fenakhay.kwikibot.client.service.MetaService
import com.fenakhay.kwikibot.client.service.TagOperation
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.LanguageInfo
import com.fenakhay.kwikibot.model.TextDirection
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.decode.array
import com.fenakhay.kwikibot.protocol.decode.long
import com.fenakhay.kwikibot.protocol.decode.obj
import com.fenakhay.kwikibot.protocol.decode.objects
import com.fenakhay.kwikibot.protocol.decode.string
import com.fenakhay.kwikibot.protocol.decode.strings
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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

            val response =
                transport.call(ApiRequest.of("query", "meta" to "siteinfo", "siprop" to name)).throwOnError()

            response.obj("query")?.get(name).also { properties[name] = it }
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
            val response =
                transport
                    .call(
                        ApiRequest.of(
                            "query",
                            "meta" to "allmessages",
                            "ammessages" to missing.joinToString("|"),
                            "amlang" to language,
                        )
                    )
                    .throwOnError()

            response.obj("query")?.array("allmessages").objects().forEach { message ->
                val name = message.string("name") ?: return@forEach
                // A message the wiki does not have comes back flagged rather than omitted.
                if (message.containsKey("missing")) return@forEach
                message.string("content")?.let { messageCache[name] = it }
            }
        }

        return wanted.mapNotNull { key -> messageCache[key]?.let { key to it } }.toMap()
    }

    override suspend fun message(key: String, language: String?): String? =
        messages(listOf(key), language)[key]

    override suspend fun magicWords(): Map<String, List<String>> =
        (property("magicwords") as? JsonArray).objects().associate { word ->
            word.string("name").orEmpty() to word.array("aliases").strings()
        }

    override suspend fun languages(codes: Collection<LangCode>): Map<LangCode, LanguageInfo> {
        val response =
            transport
                .call(
                    ApiRequest.of(
                        "query",
                        "meta" to "languageinfo",
                        "licode" to codes.takeIf { it.isNotEmpty() }?.joinToString("|") { it.code },
                        "liprop" to "code|name|autonym|dir|fallbacks|bcp47",
                    )
                )
                .throwOnError()

        // An object keyed by code, not the array every other list module answers with.
        val block = response.obj("query")?.obj("languageinfo") ?: return emptyMap()

        return block.keys
            .mapNotNull { code ->
                val entry = block.obj(code) ?: return@mapNotNull null
                LangCode(code) to
                    LanguageInfo(
                        code = LangCode(code),
                        name = entry.string("name").orEmpty(),
                        autonym = entry.string("autonym").orEmpty(),
                        direction = TextDirection.of(entry.string("dir")),
                        fallbacks = entry.array("fallbacks").strings().map { LangCode(it) },
                        bcp47 = entry.string("bcp47")?.takeIf { it.isNotEmpty() },
                    )
            }
            .toMap()
    }

    override suspend fun statistics(): Map<String, Long> {
        val counts = property("statistics") as? JsonObject ?: return emptyMap()
        return counts.keys.mapNotNull { key -> counts.long(key)?.let { key to it } }.toMap()
    }

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
            transport
                .call(
                    ApiRequest(
                        buildMap {
                            params()
                            put("assert", "user")
                            put("token", token)
                        },
                        RequestKind.WRITE,
                    )
                )
                .also { it.raiseBadToken() }
                .throwOnError()
        }
    }
}
