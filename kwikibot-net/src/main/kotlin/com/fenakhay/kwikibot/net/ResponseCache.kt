package com.fenakhay.kwikibot.net

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Remembers responses so a repeated read does not repeat the request.
 *
 * This is for developing a bot, not for running one: the second run of a script that reads three
 * thousand pages should not cost the wiki three thousand requests to fix a typo in the summary.
 * Nothing that changes a wiki is ever cached, and neither is anything carrying a credential.
 */
public interface ResponseCache {

    /** The stored response for [request], or `null` if there is none or it has expired. */
    public suspend fun get(request: ApiRequest): JsonObject?

    /** Stores [response] for [request]. */
    public suspend fun put(request: ApiRequest, response: JsonObject)

    /** The cache to use when caching is not wanted. */
    public companion object {
        /** A cache that stores nothing, which is the default. */
        public val NONE: ResponseCache = object : ResponseCache {
            override suspend fun get(request: ApiRequest): JsonObject? = null
            override suspend fun put(request: ApiRequest, response: JsonObject): Unit = Unit
        }

        /**
         * Whether a request may be cached at all.
         *
         * Writes never are: replaying a stored `action=edit` response would report a save that
         * did not happen. Neither is anything carrying a token or a password, which would put a
         * credential on disk, nor a token request, whose whole purpose is to be fresh.
         */
        public fun isCacheable(request: ApiRequest): Boolean = when {
            request.isWrite -> false
            request.requiresPost -> false
            request.params["meta"] == "tokens" -> false
            request.action in UNCACHEABLE_ACTIONS -> false
            else -> true
        }

        private val UNCACHEABLE_ACTIONS = setOf("login", "logout", "clientlogin", "createaccount")
    }
}

/**
 * A [ResponseCache] on disk, one file per request.
 *
 * The key is a hash of the request parameters; the value is the response and the time it was
 * stored. An entry older than [ttl] is ignored and deleted. Stored as plain JSON so a cached
 * response can be read directly when diagnosing what a wiki returned.
 *
 * A cache that cannot be read or written is not an error: the request simply goes to the wiki.
 * A corrupt or unreadable entry must never be the reason a bot run fails.
 */
public class DiskCache(
    private val root: Path,
    private val ttl: Duration = DEFAULT_TTL,
    /**
     * Wall-clock time, not a monotonic one.
     *
     * A disk cache outlives the process that wrote it, and a monotonic reading means nothing to
     * the next process: the entry would look as though it were stored in the future.
     */
    private val clock: Clock = Clock.System,
) : ResponseCache {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun get(request: ApiRequest): JsonObject? {
        if (!ResponseCache.isCacheable(request)) return null

        val file = fileFor(request)
        if (!file.exists()) return null

        val entry = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
        val storedAt = entry?.get("storedAt")?.jsonPrimitive?.longOrNull

        // A file that cannot be read, or does not say when it was written, or is older than the
        // time to live, is a miss — and is deleted rather than left to be re-read every run.
        if (storedAt == null || !isFresh(storedAt)) {
            file.deleteQuietly()
            return null
        }

        return entry["response"]?.jsonObject
    }

    /**
     * Whether an entry written at [storedAt] may still be served.
     *
     * A negative age means the clock moved backwards, or the file came from another machine;
     * either way its age is meaningless and it is not to be trusted.
     */
    private fun isFresh(storedAt: Long): Boolean {
        val age = age(storedAt)
        return age >= Duration.ZERO && age <= ttl
    }

    override suspend fun put(request: ApiRequest, response: JsonObject) {
        if (!ResponseCache.isCacheable(request)) return
        // An error is not an answer: storing one would serve the same failure for hours.
        if (response.containsKey("errors") || response.containsKey("error")) return

        val entry = buildJsonObject {
            put("storedAt", clock.now().toEpochMilliseconds())
            put("request", request.params.entries.joinToString("&") { "${it.key}=${it.value}" })
            put("response", response)
        }

        runCatching {
            val file = fileFor(request)
            file.createParentDirectories()
            file.writeText(entry.toString())
        }
    }

    /** Removes every entry. */
    public fun clear() {
        runCatching {
            root.toFile().walkBottomUp().forEach { if (it != root.toFile()) it.delete() }
        }
    }

    /**
     * The file one request maps to.
     *
     * Sharded by the first two characters of the hash, because a flat directory of a hundred
     * thousand files is slow on every filesystem that matters.
     */
    private fun fileFor(request: ApiRequest): Path {
        val key = keyOf(request)
        return root.resolve(key.take(2)).resolve("$key.json")
    }

    private fun keyOf(request: ApiRequest): String {
        // Sorted, so two requests differing only in the order their parameters were built in
        // share one entry.
        val canonical = request.params.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(KEY_LENGTH)
    }

    /** How long ago an entry was stored. */
    private fun age(storedAt: Long): Duration =
        (clock.now().toEpochMilliseconds() - storedAt).milliseconds

    private fun Path.deleteQuietly() {
        runCatching { deleteIfExists() }
    }

    /** How long an entry stays usable, and how its file is named. */
    public companion object {
        /** Long enough for a day of development, short enough that a wiki is not stale. */
        public val DEFAULT_TTL: Duration = 12.hours

        /** Hex characters of the hash used as the file name. 128 bits is plenty. */
        private const val KEY_LENGTH = 32
    }
}
