import com.fenakhay.kwikibot.client.model.SparqlClient
import com.fenakhay.kwikibot.client.model.SparqlValue as Binding
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlin.js.Promise
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/** Wikidata's query service. */
@JsExport public const val WIKIDATA_QUERY_SERVICE: String = SparqlClient.WIKIDATA

/**
 * The rows a SPARQL `SELECT` [query] gives at [endpoint], Wikidata's query service unless another is named,
 * asked as the tool whose operator [contact] reaches.
 */
@JsExport
public fun sparql(
    query: String,
    contact: String,
    endpoint: String = SparqlClient.WIKIDATA,
): Promise<Array<SparqlRow>> = sparql(query, contact, endpoint, engine = null)

/** [sparql], through [engine], or the platform's own when it is `null`. */
@OptIn(DelicateCoroutinesApi::class)
internal fun sparql(
    query: String,
    contact: String,
    endpoint: String,
    engine: HttpClientEngine?,
): Promise<Array<SparqlRow>> = GlobalScope.promise {
    withoutLoggingBanner()
    WikiHttpClient.create(engine = engine).use { http ->
        SparqlClient(http, userAgent(contact), endpoint).select(query).map(::SparqlRow).toTypedArray()
    }
}

/** One row of a SPARQL result. */
@JsExport
public class SparqlRow internal constructor(private val bindings: Map<String, Binding>) {

    /** The variables bound in this row. */
    public val names: Array<String>
        get() = bindings.keys.toTypedArray()

    /** What variable [name], without its `?`, is bound to, or `null` when it is unbound in this row. */
    public fun get(name: String): SparqlValue? = bindings[name]?.let(::SparqlValue)
}

/** What a variable is bound to. */
@JsExport
public class SparqlValue internal constructor(private val binding: Binding) {

    /** `"uri"`, `"literal"` or `"bnode"`. */
    public val type: String
        get() = binding.type

    /** The URI, the literal's text, or the blank node's label. */
    public val value: String
        get() = binding.value

    /** The literal's language, or `null`. */
    public val language: String?
        get() = binding.language

    /** The literal's data type, or `null`. */
    public val dataType: String?
        get() = binding.dataType

    /** The Wikibase entity id when the value is one's URI, such as `"Q42"`, or `null`. */
    public val entityId: String?
        get() = binding.entityId
}
