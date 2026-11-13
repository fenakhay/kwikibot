import com.fenakhay.kwikibot.bot.source.ExternalSources
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlin.js.Promise
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.promise

/**
 * The pages a saved PetScan query returns.
 *
 * @param id the PSID of the saved query.
 * @param contact a URL or email address the tool's operators can reach you at.
 * @param agent who to say is asking, in place of the library's name with the contact.
 */
@JsExport
public fun petScanSavedSource(id: String, contact: String, agent: UserAgent? = null): PageSource =
    petScanSavedSource(id, contact, agent, engine = null)

/** [petScanSavedSource], through [engine], or the platform's own when it is `null`. */
internal fun petScanSavedSource(
    id: String,
    contact: String,
    agent: UserAgent?,
    engine: HttpClientEngine?,
): PageSource = fetched(engine) { http -> ExternalSources.petScan(id, http, agent.orContact(contact)) }

/**
 * The pages a PetScan query returns, given the query itself.
 *
 * @param query PetScan's own parameters; `format` and `doit` are supplied.
 * @param contact a URL or email address the tool's operators can reach you at.
 * @param agent who to say is asking, in place of the library's name with the contact.
 */
@JsExport
public fun petScanSource(
    query: Record<String, String>,
    contact: String,
    agent: UserAgent? = null,
): PageSource = petScanSource(query, contact, agent, engine = null)

/** [petScanSource], through [engine], or the platform's own when it is `null`. */
internal fun petScanSource(
    query: Record<String, String>,
    contact: String,
    agent: UserAgent?,
    engine: HttpClientEngine?,
): PageSource =
    fetched(engine) { http -> ExternalSources.petScan(query.toMap(), http, agent.orContact(contact)) }

/**
 * The titles a PetScan query returns, as written, without resolving them against a wiki.
 *
 * @param query PetScan's own parameters; `format` and `doit` are supplied.
 * @param contact a URL or email address the tool's operators can reach you at.
 * @param agent who to say is asking, in place of the library's name with the contact.
 */
@JsExport
public fun petScanTitles(
    query: Record<String, String>,
    contact: String,
    agent: UserAgent? = null,
): Promise<Array<String>> = petScanTitles(query, contact, agent, engine = null)

/** [petScanTitles], through [engine], or the platform's own when it is `null`. */
@OptIn(DelicateCoroutinesApi::class)
internal fun petScanTitles(
    query: Record<String, String>,
    contact: String,
    agent: UserAgent?,
    engine: HttpClientEngine?,
): Promise<Array<String>> = GlobalScope.promise {
    WikiHttpClient.create(engine = engine).use { http ->
        ExternalSources.petScanTitles(query.toMap(), http, agent.orContact(contact)).toTypedArray()
    }
}

/**
 * Gives [use] the titles a PetScan query returns, one at a time, spooled to a temporary file as they arrive
 * rather than held in memory, and answers with what it answers. The titles can be walked once, and a walk
 * begun after [use] has settled finds none.
 *
 * @param T what [use] answers with.
 * @param query PetScan's own parameters; `format` and `doit` are supplied.
 * @param contact a URL or email address the tool's operators can reach you at.
 * @param use what to do with the titles, which may return a promise.
 * @param agent who to say is asking, in place of the library's name with the contact.
 */
@JsExport
public fun <T> withPetScanTitles(
    query: Record<String, String>,
    contact: String,
    use: (titles: AsyncIterable<String>) -> Awaitable<T>,
    agent: UserAgent? = null,
): Promise<T> = withPetScanTitles(query, contact, use, agent, engine = null)

/** [withPetScanTitles], through [engine], or the platform's own when it is `null`. */
@OptIn(DelicateCoroutinesApi::class)
internal fun <T> withPetScanTitles(
    query: Record<String, String>,
    contact: String,
    use: (titles: AsyncIterable<String>) -> Awaitable<T>,
    agent: UserAgent?,
    engine: HttpClientEngine?,
): Promise<T> = GlobalScope.promise {
    WikiHttpClient.create(engine = engine).use { http ->
        ExternalSources.withPetScanTitles(query.toMap(), http, agent.orContact(contact)) { titles ->
            settled { use(titles.asFlow().toAsyncIterable(GlobalScope) { it }) }
        }
    }
}
