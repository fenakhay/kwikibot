package com.fenakhay.kwikibot.fleet

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.WikiClient
import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.environmentVariable
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The wikis a fleet edits through, one session per account and wiki.
 *
 * A session is opened the first time it is asked for and kept afterwards, so bots on one account share one
 * login and one budget: requests are paced per client, and there is one client per account, which is a real
 * ceiling. A second login over a bot password session is not a fresh start but an error, which is why sharing
 * is not only an economy. Opening is also where a wrong password shows up, so a bad account fails the bot
 * pointed at it, not the fleet.
 *
 * @param config the transport settings every account shares.
 * @param credentials the credentials of an account by name; `null` is the default account. Reads the
 *   environment by default, under `KWIKIBOT_` and `KWIKIBOT_<NAME>_`.
 * @param clients how to make a client, which a test replaces.
 */
public class Accounts(
    private val config: WikiConfig,
    private val credentials: (String?) -> Credentials = ::fromEnvironment,
    private val clients: (WikiConfig, Credentials) -> WikiClient = { config, login ->
        WikiClient(config, login)
    },
) : AutoCloseable {

    private val open = mutableMapOf<String?, WikiClient>()
    private val sessions = mutableMapOf<Pair<String?, ApiEndpoint>, Wiki>()
    private val opening = Mutex()

    /**
     * The wiki at [endpoint], as [account], logging in if this is the first time it is wanted.
     *
     * @param endpoint which wiki.
     * @param account which account, or `null` for the default one.
     */
    public suspend fun wiki(endpoint: ApiEndpoint, account: String? = null): Wiki = opening.withLock {
        sessions.getOrPut(account to endpoint) {
            open.getOrPut(account) { clients(config, credentials(account)) }.wiki(endpoint)
        }
    }

    /** Closes every client. */
    override fun close() {
        open.values.forEach { it.close() }
        open.clear()
        sessions.clear()
    }

    private companion object {
        fun fromEnvironment(account: String?): Credentials =
            Credentials.fromEnvironment(
                if (account == null) "KWIKIBOT" else "KWIKIBOT_${account.uppercase()}",
                ::environmentVariable,
            )
    }
}
