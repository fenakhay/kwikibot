package com.fenakhay.kwikibot.net.cache

import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.filesUnder
import com.fenakhay.kwikibot.net.temporaryDirectory
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.KtorTransport
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val SHARDS = 256

class ResponseCacheTest {

    private class FixedClock(var now: Instant = Instant.parse("2026-09-01T12:00:00Z")) : Clock {
        override fun now(): Instant = now

        fun advance(by: Duration) {
            now += by
        }
    }

    private fun body(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val read = ApiRequest.of("query", "titles" to "volcano")

    @Test
    fun `a stored response is served instead of a request`() = runTest {
        val cache = DiskCache(tempRoot())

        cache.put(read, body("""{"query":{"pages":[{"title":"volcano"}]}}"""))

        cache.get(read) shouldBe body("""{"query":{"pages":[{"title":"volcano"}]}}""")
    }

    @Test
    fun `parameter order does not change the entry`() = runTest {
        val cache = DiskCache(tempRoot())
        val one = ApiRequest(mapOf("action" to "query", "a" to "1", "b" to "2"))
        val other = ApiRequest(mapOf("b" to "2", "action" to "query", "a" to "1"))

        cache.put(one, body("""{"ok":1}"""))

        cache.get(other) shouldBe body("""{"ok":1}""")
    }

    @Test
    fun `a different parameter is a different entry`() = runTest {
        val cache = DiskCache(tempRoot())

        cache.put(read, body("""{"ok":1}"""))

        cache.get(ApiRequest.of("query", "titles" to "mountain")).shouldBeNull()
    }

    @Test
    fun `an entry past its time is not served`() = runTest {
        val clock = FixedClock()
        val cache = DiskCache(tempRoot(), ttl = 1.hours, clock = clock)

        cache.put(read, body("""{"ok":1}"""))
        clock.advance(2.hours)

        cache.get(read).shouldBeNull()
    }

    @Test
    fun `an entry is served for a day of development by default and not after`() = runTest {
        val clock = FixedClock()
        val cache = DiskCache(tempRoot(), clock = clock)

        cache.put(read, body("""{"ok":1}"""))
        clock.advance(DiskCache.DEFAULT_TTL)
        cache.get(read) shouldBe body("""{"ok":1}""")
        clock.advance(1.hours)

        cache.get(read).shouldBeNull()
        DiskCache.DEFAULT_TTL shouldBe 12.hours
    }

    @Test
    fun `an entry from the future is not trusted`() = runTest {
        val clock = FixedClock()
        val cache = DiskCache(tempRoot(), ttl = 12.hours, clock = clock)

        cache.put(read, body("""{"ok":1}"""))
        clock.advance(-1.hours)

        cache.get(read).shouldBeNull()
    }

    @Test
    fun `a write is never cached`() = runTest {
        val cache = DiskCache(tempRoot())
        val edit =
            ApiRequest(
                mapOf("action" to "edit", "title" to "volcano", "text" to "x", "token" to "T"),
                RequestKind.WRITE,
            )

        cache.put(edit, body("""{"edit":{"result":"Success"}}"""))

        cache.get(edit).shouldBeNull()
    }

    @Test
    fun `nothing carrying a credential is written to disk`() = runTest {
        val root = tempRoot()
        val cache = DiskCache(root)
        val login = ApiRequest(mapOf("action" to "login", "lgname" to "Bot", "lgpassword" to "hunter2"))

        cache.put(login, body("""{"login":{"result":"Success"}}"""))

        cache.get(login).shouldBeNull()
        entries(root) shouldBe 0
    }

    @Test
    fun `a scope keeps entries apart and scopes combine`() = runTest {
        val cache = DiskCache(tempRoot())
        val answer = body("""{"n":1}""")

        cache.scopedTo("a").put(read, answer)

        cache.scopedTo("a").get(read) shouldBe answer
        cache.scopedTo("b").get(read).shouldBeNull()
        cache.get(read).shouldBeNull()
        cache.scopedTo("a").scopedTo("b").get(read).shouldBeNull()
        ResponseCache.NONE.scopedTo("a") shouldBe ResponseCache.NONE
    }

    @Test
    fun `two wikis sharing a cache do not read each other's answers`() = runTest {
        val cache = DiskCache(tempRoot())
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        """{"from":"${it.url.host}"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            )
        val userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot")

        val answers =
            listOf("a.example.org", "b.example.org").map { server ->
                KtorTransport(client, ApiEndpoint(server), userAgent, Throttle.unpaced(), cache = cache)
                    .call(read)["from"]
                    .toString()
            }

        answers shouldBe listOf("\"a.example.org\"", "\"b.example.org\"")
    }

    @Test
    fun `accounts keep entries apart and no secret reaches the disk`() = runTest {
        val root = tempRoot()
        val cache = DiskCache(root)
        val answer = body("""{"n":1}""")
        val accounts =
            listOf(
                Credentials.Anonymous,
                Credentials.BotPassword("Bot", "one", "hunter2"),
                Credentials.BotPassword("Bot", "two", "hunter2"),
                Credentials.OAuth2("sekrit-token"),
                Credentials.OAuth2("sekrit-too", "Bot"),
            )

        accounts.forEach { cache.scopedTo(it).put(read, answer) }

        entries(root) shouldBe accounts.size
        accounts.forEach { cache.scopedTo(it).get(read) shouldBe answer }
        val written =
            filesUnder(root).joinToString { file ->
                SystemFileSystem.source(file).buffered().use { it.readString() }
            }
        written shouldNotContain "hunter2"
        written shouldNotContain "sekrit"
    }

    @Test
    fun `two logins sharing a username keep entries apart`() = runTest {
        val cache = DiskCache(tempRoot())

        cache.scopedTo(Credentials.OAuth2("token-one", "Bot")).put(read, body("""{"n":1}"""))

        cache.scopedTo(Credentials.OAuth2("token-two", "Bot")).get(read).shouldBeNull()
    }

    @Test
    fun `writes sweep out what has expired a shard at a time and only once per time to live`() = runTest {
        val root = tempRoot()
        val clock = FixedClock()
        val cache = DiskCache(root, ttl = 1.hours, clock = clock)
        val answer = body("""{"n":1}""")
        val old = ApiRequest.of("query", "titles" to "old")
        val unreadable = entryIn(root, "ab", "not json")

        cache.put(old, answer)

        clock.advance(2.hours)
        repeat(SHARDS) { cache.put(ApiRequest.of("query", "titles" to "new $it"), answer) }

        SystemFileSystem.exists(unreadable) shouldBe false
        cache.get(old).shouldBeNull()
        entries(root) shouldBe SHARDS

        val later = entryIn(root, "cd", """{"storedAt":0,"response":{}}""")
        cache.put(ApiRequest.of("query", "titles" to "one more"), answer)
        SystemFileSystem.exists(later) shouldBe true

        clock.advance(1.hours)
        repeat(SHARDS) { cache.put(ApiRequest.of("query", "titles" to "again $it"), answer) }
        SystemFileSystem.exists(later) shouldBe false
    }

    @Test
    fun `a sweep touches nothing outside the cache's own layout whatever it holds`() = runTest {
        val root = tempRoot()
        val clock = FixedClock()
        val cache = DiskCache(root, ttl = 1.hours, clock = clock)
        val hash = "ab".padEnd(32, '0')
        val expired = """{"storedAt":0,"response":{}}"""
        val foreign =
            listOf(
                Path(root, "kwikibot.toml"),
                Path(root, "$hash.json"),
                Path(root, "ab", "notes.txt"),
                Path(root, "ab", "$hash.txt"),
                Path(root, "ab", "garbage.json"),
                Path(root, "ab", "cd".padEnd(32, '0') + ".json"),
                Path(root, "ab", "deep", "$hash.json"),
                Path(root, "abc", "$hash.json"),
                Path(root, "state", "ab", "$hash.json"),
            )
        foreign.forEach {
            SystemFileSystem.createDirectories(it.parent!!)
            it.write(expired)
        }

        clock.advance(2.hours)
        repeat(SHARDS + 1) { cache.put(ApiRequest.of("query", "titles" to "t$it"), body("{}")) }

        foreign.forEach { SystemFileSystem.exists(it) shouldBe true }
    }

    @Test
    fun `one write sweeps at most one shard`() = runTest {
        val root = tempRoot()
        val shards = (0 until SHARDS).map { it.toByte().toHexString() }
        val expired = shards.map { entryIn(root, it, """{"storedAt":0,"response":{}}""") }
        val cache = DiskCache(root, ttl = 1.hours, clock = FixedClock())

        cache.put(read, body("{}"))

        expired.count { SystemFileSystem.exists(it) } shouldBeGreaterThanOrEqual SHARDS - 1
    }

    @Test
    fun `no time to live walks nothing`() = runTest {
        val root = tempRoot()
        val expired =
            (0 until SHARDS).map {
                entryIn(root, it.toByte().toHexString(), """{"storedAt":0,"response":{}}""")
            }

        DiskCache(root, ttl = Duration.ZERO, clock = FixedClock()).put(read, body("{}"))

        expired.count { SystemFileSystem.exists(it) } shouldBe SHARDS
    }

    @Test
    fun `a token request is never cached since its whole point is to be fresh`() = runTest {
        val cache = DiskCache(tempRoot())
        val tokens = ApiRequest.of("query", "meta" to "tokens", "type" to "csrf")

        cache.put(tokens, body("""{"query":{"tokens":{"csrftoken":"T"}}}"""))

        cache.get(tokens).shouldBeNull()
    }

    @Test
    fun `a session action is never cached even with no secret in it`() {
        for (action in listOf("login", "logout", "clientlogin", "createaccount")) {
            ResponseCache.isCacheable(ApiRequest.of(action)) shouldBe false
        }
        ResponseCache.isCacheable(read) shouldBe true
    }

    @Test
    fun `an error response is not stored`() = runTest {
        val cache = DiskCache(tempRoot())

        cache.put(read, body("""{"errors":[{"code":"maxlag","text":"lagged"}]}"""))
        cache.put(read, body("""{"error":{"code":"readonly","info":"The wiki is read-only."}}"""))

        cache.get(read).shouldBeNull()
    }

    @Test
    fun `a corrupt entry is a miss not a crash`() = runTest {
        val root = tempRoot()
        val cache = DiskCache(root)
        cache.put(read, body("""{"ok":1}"""))

        filesUnder(root).filter { it.name.endsWith(".json") }.forEach { it.write("{not json") }

        cache.get(read).shouldBeNull()
    }

    @Test
    fun `an entry that is JSON of the wrong shape is a miss rather than a crash and is removed`() = runTest {
        val clock = FixedClock()
        val storedAt = clock.now.toEpochMilliseconds()
        val shapes =
            listOf(
                """{"storedAt":$storedAt,"response":[]}""",
                """{"storedAt":$storedAt,"response":null}""",
                """{"storedAt":$storedAt}""",
                """{"storedAt":{},"response":{"ok":1}}""",
                """{"storedAt":null,"response":{"ok":1}}""",
            )

        for (shape in shapes) {
            val root = tempRoot()
            val cache = DiskCache(root, clock = clock)
            cache.put(read, body("""{"ok":1}"""))
            filesUnder(root).filter { it.name.endsWith(".json") }.forEach { it.write(shape) }

            cache.get(read).shouldBeNull()
            entries(root) shouldBe 0
        }
    }

    @Test
    fun `clearing removes every entry and leaves the directory in place`() = runTest {
        val root = tempRoot()
        val cache = DiskCache(root)
        cache.put(read, body("""{"query":{"pages":[]}}"""))
        cache.put(ApiRequest.of("query", "titles" to "lava"), body("""{"query":{"pages":[]}}"""))
        entries(root) shouldBe 2

        cache.clear()

        entries(root) shouldBe 0
        SystemFileSystem.exists(root) shouldBe true
        cache.get(read).shouldBeNull()
    }

    @Test
    fun `the cache that stores nothing answers nothing`() = runTest {
        ResponseCache.NONE.put(read, body("""{"ok":1}"""))

        ResponseCache.NONE.get(read).shouldBeNull()
    }

    private fun tempRoot(): Path = temporaryDirectory("kwikibot-cache-test")

    /** A file named as an entry of [shard] is, holding [text]. */
    private fun entryIn(root: Path, shard: String, text: String): Path {
        SystemFileSystem.createDirectories(Path(root, shard))
        return Path(root, shard, shard.padEnd(32, '0') + ".json").also { it.write(text) }
    }

    private fun entries(root: Path): Int = filesUnder(root).count { it.name.endsWith(".json") }

    private fun Path.write(text: String) {
        SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
    }
}
