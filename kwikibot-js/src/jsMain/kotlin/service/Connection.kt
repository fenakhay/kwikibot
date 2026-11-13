import com.fenakhay.kwikibot.bindings.connect.endpointOf
import com.fenakhay.kwikibot.client.Wiki as ClientWiki
import com.fenakhay.kwikibot.client.WikiClient as ClientWikiClient
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import io.ktor.client.engine.HttpClientEngine
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise

/**
 * Opens the wiki at [server] as `connect` does, with every setting [options] gives. [contact] is a URL or
 * email address the wiki's operators can reach you at.
 */
@JsExport
public fun connectWith(server: String, contact: String, options: ConnectOptions? = null): Promise<Wiki> =
    connectWith(server, contact, options, engine = null)

/** [connectWith], through [engine], or the platform's own when it is `null`. */
internal fun connectWith(
    server: String,
    contact: String,
    options: ConnectOptions?,
    engine: HttpClientEngine?,
): Promise<Wiki> = open(contact, options, engine) { it.wiki(endpointFor(server, options)) }

/**
 * Opens the wiki of [family] in [language], such as `"en"` and `FAMILY_WIKTIONARY`, with every setting
 * [options] gives, `scriptPath` and `secure` over the family's own. [language] is ignored by a family with
 * one wiki.
 */
@JsExport
public fun connectFamily(
    language: String,
    family: Family,
    contact: String,
    options: ConnectOptions? = null,
): Promise<Wiki> = connectFamily(language, family, contact, options, engine = null)

/** [connectFamily], through [engine], or the platform's own when it is `null`. */
internal fun connectFamily(
    language: String,
    family: Family,
    contact: String,
    options: ConnectOptions?,
    engine: HttpClientEngine?,
): Promise<Wiki> = open(contact, options, engine) { it.wiki(endpointFor(language, family, options)) }

@OptIn(DelicateCoroutinesApi::class)
private fun open(
    contact: String,
    options: ConnectOptions?,
    engine: HttpClientEngine?,
    wiki: suspend (ClientWikiClient) -> ClientWiki,
): Promise<Wiki> = GlobalScope.promise {
    withoutLoggingBanner()
    val client = clientOf(contact, options, engine)
    val opened = runCatching { wiki(client) }.onFailure { client.close() }
    Wiki(opened.getOrThrow(), CoroutineScope(SupervisorJob()), client::close)
}

/**
 * Makes a client whose wikis share one set of connections and one throttle, so that a pause one wiki asks for
 * holds back requests to the others. Its `scriptPath` and `secure` are what its wikis open with unless told
 * otherwise. Close it when done.
 */
@JsExport
public fun createClient(contact: String, options: ConnectOptions? = null): WikiClient =
    createClient(contact, options, engine = null)

/** [createClient], through [engine], or the platform's own when it is `null`. */
internal fun createClient(contact: String, options: ConnectOptions?, engine: HttpClientEngine?): WikiClient {
    withoutLoggingBanner()
    return WikiClient(clientOf(contact, options, engine), options)
}

/** Opens wikis that share one set of connections and one throttle. */
@JsExport
public class WikiClient
internal constructor(internal val client: ClientWikiClient, private val options: ConnectOptions?) {

    /** Opens the wiki at [server]. Closing it leaves the client and its other wikis open. */
    @OptIn(DelicateCoroutinesApi::class)
    public fun wiki(server: String): Promise<Wiki> = GlobalScope.promise {
        Wiki(client.wiki(endpointFor(server, options)), CoroutineScope(SupervisorJob())) {}
    }

    /**
     * Opens the wiki of [family] in [language], with the client's `scriptPath` and `secure` over the family's
     * own. Closing it leaves the client and its other wikis open.
     */
    @OptIn(DelicateCoroutinesApi::class)
    public fun wikiOf(language: String, family: Family): Promise<Wiki> = GlobalScope.promise {
        Wiki(client.wiki(endpointFor(language, family, options)), CoroutineScope(SupervisorJob())) {}
    }

    /** Releases the connections of every wiki opened here. */
    public fun close() {
        client.close()
    }
}

private fun endpointFor(server: String, options: ConnectOptions?): ApiEndpoint =
    endpointOf(server, options?.scriptPath, options?.secure)

private fun endpointFor(language: String, family: Family, options: ConnectOptions?): ApiEndpoint =
    endpointOf(language, family.family, options?.scriptPath, options?.secure)

private fun clientOf(contact: String, options: ConnectOptions?, engine: HttpClientEngine?): ClientWikiClient =
    ClientWikiClient(
        configOf(contact, options),
        options?.login?.credentials ?: Credentials.Anonymous,
        engine = engine,
    )
