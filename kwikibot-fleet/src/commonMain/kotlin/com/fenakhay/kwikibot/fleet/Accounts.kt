package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.environmentVariable
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The wikis a fleet edits through, one session per account and wiki.
 *
 * A session is opened the first time it is asked for and kept afterwards, so bots on one account share one
 * login and one budget: each account has its own client and its own throttle, made from the pace in [config],
 * so bots on one account hold each other back and bots on different accounts do not. A second login over a
 * bot password session is not a fresh start but an error, which is why sharing is not only an economy.
 * Opening is also where a wrong password shows up, so a bad account fails the bot pointed at it, not the
 * fleet, and a slow login holds back only the bots on that account.
 *
 * @param config the transport settings every account shares.
 * @param credentials the credentials of an account by name; `null` is the default account. Reads the
 *   environment by default, under `KWIKIBOT_` and, through [namedCredentials], `KWIKIBOT_<NAME>_`.
 * @param clients how to make a client, which a test replaces.
 */
@OptIn(ExperimentalAtomicApi::class)
public class Accounts(
    private val config: WikiConfig,
    private val credentials: (String?) -> Credentials = ::fromEnvironment,
    private val clients: (WikiConfig, Credentials) -> WikiClient = { config, login ->
        WikiClient(config, login)
    },
) : AutoCloseable {

    /** What is open for one account. [wikis] and the making of [client] are under [lock]. */
    private class Session {
        val lock = Mutex()
        val wikis = mutableMapOf<ApiEndpoint, Wiki>()

        @Volatile var client: WikiClient? = null
    }

    private val sessions = AtomicReference<Map<String?, Session>>(emptyMap())

    /**
     * The wiki at [endpoint], as [account], logging in if this is the first time it is wanted.
     *
     * @param endpoint which wiki.
     * @param account which account, or `null` for the default one.
     */
    public suspend fun wiki(endpoint: ApiEndpoint, account: String? = null): Wiki {
        val session = sessionOf(account)
        return session.lock.withLock {
            session.wikis[endpoint]
                ?: (session.client ?: newClient(account).also { session.client = it }).wiki(endpoint).also {
                    session.wikis[endpoint] = it
                }
        }
    }

    private fun newClient(account: String?): WikiClient {
        val paced = Throttle(config.throttle.read, config.throttle.write)
        return clients(config.copy(throttle = paced), credentials(account))
    }

    private fun sessionOf(account: String?): Session {
        while (true) {
            val known = sessions.load()
            known[account]?.let {
                return it
            }
            val session = Session()
            if (sessions.compareAndSet(known, known + (account to session))) return session
        }
    }

    /** Closes every client. */
    override fun close() {
        sessions.exchange(emptyMap()).values.forEach { it.client?.close() }
    }

    /** Where credentials are read from. */
    public companion object {
        /**
         * The credentials of the account called [account], from [environment] under `KWIKIBOT_<ACCOUNT>_`: an
         * OAuth 2.0 token in `OAUTH_TOKEN`, or a bot password in `ACCOUNT`, `BOT_NAME` and `PASSWORD`.
         *
         * An account that is named has to be there. Going on as somebody else or as nobody would put its
         * edits under the wrong name.
         *
         * @throws IllegalStateException naming the variables, if they are not set or are set only in part.
         */
        public fun namedCredentials(
            account: String,
            environment: (String) -> String? = ::environmentVariable,
        ): Credentials {
            val prefix = "KWIKIBOT_${account.uppercase()}"
            val credentials = Credentials.fromEnvironment(prefix, environment)
            check(credentials != Credentials.Anonymous) {
                "the account '$account' has no credentials: set ${prefix}_OAUTH_TOKEN, or " +
                    "${prefix}_ACCOUNT, ${prefix}_BOT_NAME and ${prefix}_PASSWORD"
            }
            return credentials
        }

        private fun fromEnvironment(account: String?): Credentials =
            if (account == null) Credentials.fromEnvironment("KWIKIBOT", ::environmentVariable)
            else namedCredentials(account)
    }
}
