package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.auth.LoginManager
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ApiFailure
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

private val log = KotlinLogging.logger {}

/**
 * Keeps a logged-in session logged in.
 *
 * MediaWiki keeps a session for `$wgObjectCacheSessionExpiry` and renews it on use once half of that has
 * passed. Wikimedia sets a day, so a session there ends 12 to 24 hours after its last request. A bot-password
 * session also ends when the bot password is reset, changed or deleted, when its IP restrictions fail, when
 * the account's token changes (a new password, logging out everywhere), or when the session store loses it.
 *
 * Without this, every read after that is made anonymously, at anonymous limits, and the first write fails the
 * run. So every request asserts the account it was logged in as. A lost session then becomes an error the
 * wiki reports instead of a silent change of who is asking, and that error is answered by logging in again
 * and repeating the request once.
 *
 * Concurrent requests that find the session gone share one login. Logins are capped at [relogins] in any
 * [window], so credentials the wiki has stopped accepting stop the run instead of looping; past the cap the
 * error goes back to the caller unchanged.
 *
 * A write repeated after a login carries a token from the old session. The wiki answers `badtoken`, and the
 * token store answers that by fetching a new token, so no write is lost.
 *
 * An anonymous session has nothing to assert or restore, and passes through untouched.
 *
 * @param delegate the transport that sends the requests.
 * @param login the login the session came from, and how it is restored.
 * @param identity who the session was logged in as.
 * @param relogins how many logins [window] allows. Zero turns re-login off.
 * @param window how far back logins are counted towards [relogins].
 * @param onRelogin told of each login this makes.
 * @param timeSource the clock [window] is measured on.
 */
internal class SessionTransport(
    private val delegate: MediaWikiTransport,
    private val login: LoginManager,
    identity: Identity,
    private val relogins: Int = WikiConfig.DEFAULT_RELOGINS,
    private val window: Duration = DEFAULT_WINDOW,
    private val onRelogin: (Identity) -> Unit = {},
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : MediaWikiTransport {

    override val endpoint: ApiEndpoint
        get() = delegate.endpoint

    private val anonymous = identity.isAnonymous

    // assert=bot checks the `bot` right, not the group: a bot password without the high-volume grant logs
    // a bot-group account in without it. So the right decides, as read at login.
    private val assertion = if (BOT_RIGHT in identity.rights) "bot" else "user"

    private val mutex = Mutex()
    private val recent = ArrayDeque<TimeMark>()

    @Volatile private var generation = 0L

    override suspend fun call(request: ApiRequest): JsonObject {
        if (anonymous) return delegate.call(request)

        val seen = generation
        val response = delegate.call(asserted(request))
        if (ApiFailure.from(response)?.code !in SESSION_LOST) return response

        return if (restore(seen)) delegate.call(asserted(request)) else response
    }

    /** The request with this session's assertion, unless the caller made one of its own. */
    private fun asserted(request: ApiRequest): ApiRequest =
        if (ASSERT in request.params) request
        else request.copy(params = request.params + (ASSERT to assertion))

    /** Logs in again unless another request did since [seen], and says whether to repeat the request. */
    private suspend fun restore(seen: Long): Boolean = mutex.withLock {
        if (generation != seen) return@withLock true

        while (recent.isNotEmpty() && recent.first().elapsedNow() > window) recent.removeFirst()
        if (recent.size >= relogins) {
            log.warn {
                "the session on ${endpoint.server} was lost again; not logging in a ${relogins + 1}th time"
            }
            return@withLock false
        }

        log.info { "the session on ${endpoint.server} was lost; logging in again" }
        val identity = login.relogin()
        recent.addLast(timeSource.markNow())
        generation++
        onRelogin(identity)
        true
    }

    companion object {
        /** How far back logins are counted. */
        val DEFAULT_WINDOW: Duration = 10.minutes

        private const val ASSERT = "assert"

        private const val BOT_RIGHT = "bot"

        /** The codes a wiki answers with when the session a request relied on is gone. */
        private val SESSION_LOST = setOf("assertuserfailed", "assertbotfailed", "notloggedin")
    }
}
