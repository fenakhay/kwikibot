package com.fenakhay.kwikibot.net.cache

import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.long
import com.fenakhay.kwikibot.net.obj
import com.fenakhay.kwikibot.net.transport.ApiRequest
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.kotlincrypto.hash.sha2.SHA256

/**
 * Remembers responses so a repeated read does not repeat the request.
 *
 * This is for developing a bot, not for running one: the second run of a script that reads three thousand
 * pages should not cost the wiki three thousand requests to fix a typo in the summary. Nothing that changes a
 * wiki is ever cached, and neither is anything carrying a credential.
 */
public interface ResponseCache {

    /** The stored response for [request], or `null` if there is none or it has expired. */
    public suspend fun get(request: ApiRequest): JsonObject?

    /** Stores [response] for [request]. */
    public suspend fun put(request: ApiRequest, response: JsonObject)

    /** The cache to use when caching is not wanted. */
    public companion object {
        /** A cache that stores nothing, which is the default. */
        public val NONE: ResponseCache =
            object : ResponseCache {
                override suspend fun get(request: ApiRequest): JsonObject? = null

                override suspend fun put(request: ApiRequest, response: JsonObject): Unit = Unit
            }

        /**
         * Whether a request may be cached at all.
         *
         * Writes never are: replaying a stored `action=edit` response would report a save that did not
         * happen. Neither is anything carrying a token or a password, which would put a credential on disk,
         * nor a token request, whose whole purpose is to be fresh, nor a read its caller marked as needing
         * the wiki's current answer.
         */
        public fun isCacheable(request: ApiRequest): Boolean =
            when {
                !request.cacheable -> false
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
 * This cache, keeping what it holds for [scope] apart from everything else in it.
 *
 * The same request means something else on another wiki or to another account, and a cache on disk outlives
 * and is shared between the clients that open it. So a scope is added to the request the cache sees, and only
 * the cache sees it. A view of a view is in both scopes.
 *
 * @param scope what the answers belong to. Written into cache entries, so never a secret.
 */
public fun ResponseCache.scopedTo(scope: String): ResponseCache {
    if (this === ResponseCache.NONE) return this
    val inner = this
    return object : ResponseCache {
        override suspend fun get(request: ApiRequest): JsonObject? = inner.get(request.inScope(scope))

        override suspend fun put(request: ApiRequest, response: JsonObject) =
            inner.put(request.inScope(scope), response)
    }
}

/** This cache, keeping what it holds for the account [credentials] log in as apart. */
public fun ResponseCache.scopedTo(credentials: Credentials): ResponseCache =
    scopedTo(
        when (credentials) {
            is Credentials.Anonymous -> "anonymous"
            is Credentials.BotPassword -> credentials.loginName
            is Credentials.OAuth2 ->
                credentials.username
                    ?: SHA256().digest(credentials.accessToken.encodeToByteArray()).toHexString()
        }
    )

private fun ApiRequest.inScope(scope: String): ApiRequest =
    copy(params = params + (SCOPE_PARAMETER to (params[SCOPE_PARAMETER]?.let { "$it|$scope" } ?: scope)))

private const val SCOPE_PARAMETER = "kwikibot-scope"

/**
 * A [ResponseCache] on disk, one file per request.
 *
 * The key is a hash of the request parameters; the value is the response and the time it was stored. An entry
 * older than [ttl] is ignored and deleted. Stored as plain JSON so a cached response can be read directly
 * when diagnosing what a wiki returned.
 *
 * A cache that cannot be read or written is not an error: the request simply goes to the wiki. A corrupt or
 * unreadable entry must never be the reason a bot run fails.
 */
public class DiskCache(
    private val root: Path,
    private val ttl: Duration = DEFAULT_TTL,
    /**
     * Wall-clock time, not a monotonic one.
     *
     * A disk cache outlives the process that wrote it, and a monotonic reading means nothing to the next
     * process: the entry would look as though it were stored in the future.
     */
    private val clock: Clock = Clock.System,
) : ResponseCache {

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalAtomicApi::class) private val lastSweep = AtomicLong(Long.MIN_VALUE)

    override suspend fun get(request: ApiRequest): JsonObject? {
        if (!ResponseCache.isCacheable(request)) return null

        val file = fileFor(request)
        if (!SystemFileSystem.exists(file)) return null

        val entry = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
        val storedAt = entry?.long("storedAt")
        val response = entry?.obj("response")

        // A file that cannot be read, does not say when it was written or what was answered, or is older
        // than the time to live, is a miss. It is deleted so the next run does not read it again.
        if (storedAt == null || response == null || !isFresh(storedAt)) {
            file.deleteQuietly()
            return null
        }

        return response
    }

    /**
     * Whether an entry written at [storedAt] may still be served.
     *
     * A negative age means the clock moved backwards, or the file came from another machine; either way its
     * age is meaningless and it is not to be trusted.
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

        val _ = runCatching {
            val file = fileFor(request)
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            file.writeText(entry.toString())
        }
        sweepIfDue()
    }

    /**
     * Sweeps on the first write of this cache, then at most once per [ttl].
     *
     * An entry is otherwise deleted only when its request comes round again, and most never do, so the
     * directory would grow for as long as it is used.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private fun sweepIfDue() {
        val now = clock.now().toEpochMilliseconds()
        val last = lastSweep.load()
        if (last != Long.MIN_VALUE && now - last < ttl.inWholeMilliseconds) return
        if (!lastSweep.compareAndSet(last, now)) return

        val _ = runCatching { sweep(root) }
    }

    /**
     * Deletes every entry below [directory] that is expired or unreadable.
     *
     * Reads only the start of each file, since `storedAt` is written first and a response can be large.
     */
    private fun sweep(directory: Path) {
        for (child in SystemFileSystem.list(directory)) {
            if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) {
                sweep(child)
            } else {
                val storedAt = STORED_AT.find(child.readStart())?.groupValues?.get(1)?.toLongOrNull()
                if (storedAt == null || !isFresh(storedAt)) child.deleteQuietly()
            }
        }
    }

    private fun Path.readStart(): String =
        SystemFileSystem.source(this).use { source ->
            val start = Buffer()
            val _ = source.readAtMostTo(start, ENTRY_START_BYTES)
            start.readString()
        }

    /** Removes every entry. */
    public fun clear() {
        val _ = runCatching { if (SystemFileSystem.exists(root)) deleteBelow(root) }
    }

    /** Deletes everything inside [directory], leaving the directory itself. */
    private fun deleteBelow(directory: Path) {
        for (child in SystemFileSystem.list(directory)) {
            if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) deleteBelow(child)
            SystemFileSystem.delete(child, mustExist = false)
        }
    }

    /**
     * The file one request maps to.
     *
     * Sharded by the first two characters of the hash, because a flat directory of a hundred thousand files
     * is slow on every filesystem that matters.
     */
    private fun fileFor(request: ApiRequest): Path {
        val key = keyOf(request)
        return Path(root, key.take(2), "$key.json")
    }

    private fun keyOf(request: ApiRequest): String {
        // Sorted, so two requests differing only in the order their parameters were built in
        // share one entry.
        val canonical =
            request.params.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }

        return SHA256().digest(canonical.encodeToByteArray()).toHexString().take(KEY_LENGTH)
    }

    /** How long ago an entry was stored. */
    private fun age(storedAt: Long): Duration = (clock.now().toEpochMilliseconds() - storedAt).milliseconds

    private fun Path.deleteQuietly() {
        val _ = runCatching { SystemFileSystem.delete(this, mustExist = false) }
    }

    private fun Path.readText(): String = SystemFileSystem.source(this).buffered().use { it.readString() }

    private fun Path.writeText(text: String) {
        SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
    }

    /** How long an entry stays usable, and how its file is named. */
    public companion object {
        /** Long enough for a day of development, short enough that a wiki is not stale. */
        public val DEFAULT_TTL: Duration = 12.hours

        /** Hex characters of the hash used as the file name. 128 bits is plenty. */
        private const val ENTRY_START_BYTES = 64L
        private val STORED_AT = Regex("""^\{"storedAt":(\d+)""")
        private const val KEY_LENGTH = 32
    }
}
