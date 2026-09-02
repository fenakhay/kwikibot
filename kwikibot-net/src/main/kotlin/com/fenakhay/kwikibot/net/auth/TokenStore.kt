package com.fenakhay.kwikibot.net.auth

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches and caches the CSRF and action tokens MediaWiki requires for writes.
 *
 * Tokens are stable for the life of a session, so they are fetched once and reused. They do expire — on
 * logout, on session loss, and when a wiki rotates them — which is what [invalidate] and [withFreshToken] are
 * for.
 *
 * Concurrent callers asking for the same token produce one request, not one each.
 */
public class TokenStore(private val transport: MediaWikiTransport) {

    private val mutex = Mutex()
    private val cached = mutableMapOf<String, String>()

    /** The token of [type], fetched on first use and cached afterwards. */
    public suspend fun token(type: String = CSRF): String {
        cached[type]?.let {
            return it
        }
        return mutex.withLock {
            // Another coroutine may have fetched it while this one waited for the lock.
            cached[type] ?: fetch(type).also { cached[type] = it }
        }
    }

    /** Drops the cached token of [type], so the next request fetches a new one. */
    public suspend fun invalidate(type: String = CSRF) {
        mutex.withLock { cached.remove(type) }
    }

    /** Drops every cached token. Call after logging in or out. */
    public suspend fun clear() {
        mutex.withLock { cached.clear() }
    }

    /**
     * Runs [block] with a token, retrying once with a fresh one if the wiki rejects it.
     *
     * A `badtoken` is not a programming error: a long-running bot outlives its session. The single retry is
     * what turns that into a hiccup instead of a lost edit.
     */
    public suspend fun <T> withFreshToken(
        type: String = CSRF,
        block: suspend (String) -> T,
    ): T =
        try {
            block(token(type))
        } catch (e: WikiError.Auth.BadToken) {
            if (e.tokenType != type) throw e
            invalidate(type)
            block(token(type))
        }

    private suspend fun fetch(type: String): String {
        val response = transport.call(ApiRequest.of("query", "meta" to "tokens", "type" to type))

        val value =
            response["query"]
                ?.jsonObject
                ?.get("tokens")
                ?.jsonObject
                ?.get("${type}token")
                ?.jsonPrimitive
                ?.content
                ?: throw WikiError.Api(
                    code = "notoken",
                    info = "wiki returned no '$type' token",
                    module = "query+tokens",
                )

        // An anonymous session gets this placeholder instead of a real token.
        if (value == ANONYMOUS_TOKEN) throw WikiError.Auth.NotLoggedIn("a $type token")

        return value
    }

    /** The token types MediaWiki issues, which are not interchangeable. */
    public companion object {
        /** The token every ordinary write needs. */
        public const val CSRF: String = "csrf"

        /** The token needed to start a login. */
        public const val LOGIN: String = "login"

        /** The token needed to patrol a revision. */
        public const val PATROL: String = "patrol"

        /** The token needed to roll a page back. Separate from CSRF, and always has been. */
        public const val ROLLBACK: String = "rollback"

        /** The token for changing group membership, which the wiki keeps separate from CSRF. */
        public const val USER_RIGHTS: String = "userrights"

        /** The token needed to add or remove watchlist entries. */
        public const val WATCH: String = "watch"

        /** What MediaWiki hands out in place of a CSRF token when nobody is logged in. */
        private const val ANONYMOUS_TOKEN = "+\\"
    }
}
