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

    /** Reading credentials from somewhere other than code. */
    public companion object {
        /**
         * The credentials the environment holds under [prefix]: an OAuth 2.0 token in `KWIKIBOT_OAUTH_TOKEN`,
         * or a bot password in `KWIKIBOT_ACCOUNT`, `KWIKIBOT_BOT_NAME` and `KWIKIBOT_PASSWORD`. [Anonymous]
         * when neither is set, which a bot that must log in can check for.
         *
         * @param prefix what the variable names start with, for running two bots from one environment.
         * @param environment where to read them; the process environment by default.
         * @throws IllegalStateException if a bot password is only partly set, which is a mistake rather than
         *   a choice to run anonymously.
         */
        public fun fromEnvironment(
            prefix: String = "KWIKIBOT",
            environment: (String) -> String? = System::getenv,
        ): Credentials {
            fun read(name: String) = environment("${prefix}_$name")?.takeIf { it.isNotBlank() }

            val token = read("OAUTH_TOKEN")
            if (token != null) return OAuth2(token, read("ACCOUNT"))

            val account = read("ACCOUNT")
            val botName = read("BOT_NAME")
            val password = read("PASSWORD")
            val given = listOf(account, botName, password).count { it != null }
            if (given == 0) return Anonymous
            check(account != null && botName != null && password != null) {
                "${prefix}_ACCOUNT, ${prefix}_BOT_NAME and ${prefix}_PASSWORD are set only in part"
            }
            return BotPassword(account, botName, password)
        }
    }
}
