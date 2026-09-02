package com.fenakhay.kwikibot.net.auth

/**
 * How this client identifies itself to a wiki.
 *
 * Passwords and tokens are held as plain strings because they must be sent as such; keep them out of source
 * control and read them from configuration or the environment.
 */
public sealed interface Credentials {

    /** The account name the wiki will attribute edits to, or `null` when anonymous. */
    public val username: String?

    /** No credentials: reads only, and subject to the tightest rate limits. */
    public data object Anonymous : Credentials {
        override val username: String?
            get() = null
    }

    /**
     * A bot password — the credential type Special:BotPasswords issues.
     *
     * The wiki expects the account and the bot-password name joined by `@`, which is what [loginName]
     * produces. Bot passwords are preferred over an account's real password because they carry only the
     * rights granted to that specific bot.
     *
     * @param account the account name, without the bot suffix (`FenaBot`).
     * @param botName the bot password name (`compounds`).
     * @param password the generated secret, which is not the account's own password.
     */
    public data class BotPassword(
        val account: String,
        val botName: String,
        val password: String,
    ) : Credentials {
        init {
            require(account.isNotBlank()) { "account must not be blank" }
            require(botName.isNotBlank()) { "bot password name must not be blank" }
            require(password.isNotBlank()) { "password must not be blank" }
            require('@' !in account) {
                "pass the account and bot name separately, not as '$account'"
            }
        }

        override val username: String
            get() = account

        /** The `lgname` the API expects: `Account@botname`. */
        val loginName: String
            get() = "$account@$botName"

        override fun toString(): String = "BotPassword($loginName, password=***)"
    }

    /**
     * An OAuth 2.0 owner-only access token.
     *
     * Sent as a bearer header on every request; there is no login round trip and no session cookie to keep
     * alive.
     */
    public data class OAuth2(
        /** The bearer token, sent on every request. */
        val accessToken: String,
        override val username: String? = null,
    ) : Credentials {
        init {
            require(accessToken.isNotBlank()) { "access token must not be blank" }
        }

        override fun toString(): String = "OAuth2(username=$username, token=***)"
    }
}
