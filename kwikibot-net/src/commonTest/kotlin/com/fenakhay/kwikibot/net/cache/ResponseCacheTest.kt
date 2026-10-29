package com.fenakhay.kwikibot.net.cache

import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.filesUnder
import com.fenakhay.kwikibot.net.temporaryDirectory
import com.fenakhay.kwikibot.net.transport.ApiRequest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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

    private fun entries(root: Path): Int = filesUnder(root).count { it.name.endsWith(".json") }

    private fun Path.write(text: String) {
        SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
    }
}
