package com.fenakhay.kwikibot.net

import com.fenakhay.kwikibot.model.WikiError
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val log = KotlinLogging.logger {}

/** Who the wiki says we are. */
public data class Identity(
    /** The account name the wiki reports, which is the canonical spelling of it. */
    val name: String,
    /** The account's id. Zero for an anonymous session. */
    val id: Long,
    /** The groups the account is in, which is where its rights come from. */
    val groups: Set<String> = emptySet(),
    /** What the account may do. The direct answer to whether an action will be allowed. */
    val rights: Set<String> = emptySet(),
) {
    /** Whether the wiki has flagged this account as a bot. */
    val isBot: Boolean get() = "bot" in groups

    /** Whether the account is anonymous, in which case [name] is an IP address. */
    val isAnonymous: Boolean get() = id == 0L

    /** Whether the account holds [right]. */
    public operator fun contains(right: String): Boolean = right in rights
}

/**
 * Establishes and verifies a session.
 *
 * Bot passwords need a two-step login: fetch a login token, then post the credentials with it.
 * OAuth 2.0 needs no login at all — the bearer header is attached by the HTTP client — so for
 * those this only confirms who the wiki thinks we are.
 *
 * Logging in twice concurrently is prevented, so a pool of coroutines starting at once produces
 * one login, not one each.
 */
public class LoginManager(
    private val transport: MediaWikiTransport,
    private val credentials: Credentials,
    private val tokens: TokenStore = TokenStore(transport),
) {
    private val mutex = Mutex()

    private var identity: Identity? = null

    /** The identity established by the last successful [login], if any. */
    public val currentIdentity: Identity? get() = identity

    /**
     * Logs in if that has not happened yet, and returns who we are.
     *
     * @throws WikiError.Auth if the wiki refuses the credentials.
     */
    public suspend fun login(): Identity {
        identity?.let { return it }
        return mutex.withLock {
            identity ?: performLogin().also { identity = it }
        }
    }

    /** Forgets the session, so the next [login] starts a new one. */
    public suspend fun logout() {
        mutex.withLock {
            if (credentials !is Credentials.Anonymous) {
                runCatching {
                    transport.call(
                        ApiRequest(
                            mapOf("action" to "logout", "token" to tokens.token()),
                            RequestKind.WRITE,
                        ),
                    )
                }.onFailure { log.debug(it) { "logout call failed; dropping the session anyway" } }
            }
            tokens.clear()
            identity = null
        }
    }

    /** Asks the wiki who we are, without logging in. */
    public suspend fun whoAmI(): Identity {
        val response = transport.call(
            ApiRequest.of("query", "meta" to "userinfo", "uiprop" to "groups|rights"),
        )
        val info = response["query"]?.jsonObject?.get("userinfo")?.jsonObject
            ?: throw WikiError.Api("nouserinfo", "no userinfo in response", "query+userinfo")

        return Identity(
            name = info["name"]?.jsonPrimitive?.content.orEmpty(),
            id = info["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            groups = info.stringSet("groups"),
            rights = info.stringSet("rights"),
        )
    }

    private suspend fun performLogin(): Identity = when (credentials) {
        // Nothing to establish; the wiki will treat us as the requesting IP.
        is Credentials.Anonymous -> whoAmI()

        // The bearer header is attached by the HTTP client, so a session already exists.
        is Credentials.OAuth2 -> whoAmI().also { verifyNotAnonymous(it) }

        is Credentials.BotPassword -> {
            submitBotPassword(credentials)
            tokens.clear()
            whoAmI().also { verifyNotAnonymous(it) }
        }
    }

    private suspend fun submitBotPassword(botPassword: Credentials.BotPassword) {
        val response = transport.call(
            ApiRequest(
                mapOf(
                    "action" to "login",
                    "lgname" to botPassword.loginName,
                    "lgpassword" to botPassword.password,
                    "lgtoken" to tokens.token(TokenStore.LOGIN),
                ),
                RequestKind.READ,
            ),
        )

        val login = response["login"]?.jsonObject
            ?: throw WikiError.Auth.LoginFailed("no login block in the response")

        val result = login["result"]?.jsonPrimitive?.content.orEmpty()
        if (!result.equals("Success", ignoreCase = true)) {
            throw WikiError.Auth.LoginFailed(login.reasonText() ?: result.ifEmpty { "unknown" })
        }
    }

    /**
     * Why a login was refused, in either shape MediaWiki reports it.
     *
     * With `errorformat=plaintext` — which this library always sends — `reason` is an object
     * carrying a code and a message, not a string. Reading it as a string throws, which turns a
     * wrong password into a crash inside the library instead of "wrong password".
     */
    private fun JsonObject.reasonText(): String? = when (val reason = this["reason"]) {
        null -> null
        is JsonPrimitive -> reason.content
        is JsonObject -> reason["text"]?.jsonPrimitive?.content
            ?: reason["code"]?.jsonPrimitive?.content

        else -> reason.toString()
    }

    private fun verifyNotAnonymous(identity: Identity) {
        if (identity.isAnonymous) {
            throw WikiError.Auth.LoginFailed(
                "credentials were accepted but the wiki still sees an anonymous user",
            )
        }
        log.info { "logged in as ${identity.name}" + if (identity.isBot) " (bot)" else "" }
    }

    private fun JsonObject.stringSet(key: String): Set<String> =
        this[key]?.jsonArray?.mapTo(mutableSetOf()) { it.jsonPrimitive.content } ?: emptySet()
}
