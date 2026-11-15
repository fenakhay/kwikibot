package com.fenakhay.kwikibot.net.auth

import com.fenakhay.kwikibot.net.environmentVariable

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
            concealFields(this)
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
     * Sent as a bearer header on every request, with no login round trip. The cookies the wiki sets in its
     * answers are kept and sent back alongside the token, which Wikimedia asks of owner-only tokens so that
     * the account's own rate limit applies. Cookies are kept per HTTP client, so a token for another account
     * needs a client of its own.
     */
    public data class OAuth2(
        /** The bearer token, sent on every request. */
        val accessToken: String,
        override val username: String? = null,
    ) : Credentials {
        init {
            require(accessToken.isNotBlank()) { "access token must not be blank" }
            concealFields(this)
        }

        override fun toString(): String = "OAuth2(username=$username, token=***)"
    }

    /**
     * An OAuth 1.0a owner-only consumer: its key and secret, and the access token and secret the wiki issued
     * with it.
     *
     * Every request is signed with HMAC-SHA1, as RFC 5849 says, and the signature is sent in an
     * `Authorization: OAuth` header, with no login round trip. Each attempt at a request is signed afresh,
     * with a nonce and timestamp of its own, so a retry is never sent with a signature the wiki has seen. The
     * cookies the wiki sets are kept and sent back, as for [OAuth2].
     *
     * The consumer key and the access token are sent with every request; the two secrets never are, since
     * they only key the signature.
     *
     * @param consumerKey the consumer's key, sent as `oauth_consumer_key`.
     * @param consumerSecret the consumer's secret, which never leaves the client.
     * @param accessToken the access token, sent as `oauth_token`.
     * @param accessSecret the access token's secret, which never leaves the client.
     * @param username the account the token belongs to, for the logs; the wiki is asked who it is anyway.
     */
    public data class OAuth1(
        val consumerKey: String,
        val consumerSecret: String,
        val accessToken: String,
        val accessSecret: String,
        override val username: String? = null,
    ) : Credentials {
        init {
            require(consumerKey.isNotBlank()) { "consumer key must not be blank" }
            require(consumerSecret.isNotBlank()) { "consumer secret must not be blank" }
            require(accessToken.isNotBlank()) { "access token must not be blank" }
            require(accessSecret.isNotBlank()) { "access secret must not be blank" }
            concealFields(this)
        }

        /** Names the consumer, and hides the access token and both secrets. */
        override fun toString(): String =
            "OAuth1(username=$username, consumerKey=$consumerKey, accessToken=***, secrets=***)"
    }

    /** Reading credentials from somewhere other than code. */
    public companion object {
        /**
         * The credentials the environment holds under [prefix]: an OAuth 2.0 token in `KWIKIBOT_OAUTH_TOKEN`;
         * an OAuth 1.0a consumer in `KWIKIBOT_OAUTH_CONSUMER_KEY`, `KWIKIBOT_OAUTH_CONSUMER_SECRET`,
         * `KWIKIBOT_OAUTH_ACCESS_TOKEN` and `KWIKIBOT_OAUTH_ACCESS_SECRET`; or a bot password in
         * `KWIKIBOT_ACCOUNT`, `KWIKIBOT_BOT_NAME` and `KWIKIBOT_PASSWORD`. For either kind of OAuth,
         * `KWIKIBOT_ACCOUNT` is the username, if set. [Anonymous] when none is set, which a bot that must log
         * in can check for.
         *
         * An OAuth login is used in place of a bot password set beside it. An OAuth 2.0 token and an OAuth
         * 1.0a consumer set together are refused rather than one chosen, since nothing says which was meant.
         *
         * @param prefix what the variable names start with, for running two bots from one environment.
         * @param environment where to read them; the process environment by default.
         * @throws IllegalStateException if a bot password or an OAuth 1.0a consumer is only partly set, which
         *   is a mistake rather than a choice to run anonymously, or if both kinds of OAuth are set. The
         *   message names the variables, never what they hold.
         */
        public fun fromEnvironment(
            prefix: String = "KWIKIBOT",
            environment: (String) -> String? = ::environmentVariable,
        ): Credentials {
            fun read(name: String) = environment("${prefix}_$name")?.takeIf { it.isNotBlank() }

            val token = read("OAUTH_TOKEN")
            val consumer = OAUTH1_VARIABLES.map { read(it) }
            val consumerNames = OAUTH1_VARIABLES.joinToString(", ") { "${prefix}_$it" }
            check(token == null || consumer.all { it == null }) {
                "${prefix}_OAUTH_TOKEN and the OAuth 1.0a variables ($consumerNames) are both set; set one"
            }
            if (token != null) return OAuth2(token, read("ACCOUNT"))

            if (consumer.any { it != null }) {
                val (key, secret) = consumer.take(2)
                val (access, accessSecret) = consumer.drop(2)
                check(key != null && secret != null && access != null && accessSecret != null) {
                    "$consumerNames are set only in part"
                }
                return OAuth1(key, secret, access, accessSecret, read("ACCOUNT"))
            }

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

        private val OAUTH1_VARIABLES =
            listOf("OAUTH_CONSUMER_KEY", "OAUTH_CONSUMER_SECRET", "OAUTH_ACCESS_TOKEN", "OAUTH_ACCESS_SECRET")
    }
}
