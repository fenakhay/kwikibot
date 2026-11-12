import com.fenakhay.kwikibot.client.model.MatrixWiki as ClientMatrixWiki
import com.fenakhay.kwikibot.client.model.SiteMatrix as ClientSiteMatrix
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.page.WikiId

/** Every Wikimedia wiki, from `wiki.siteMatrix()`. */
@JsExport
public class SiteMatrix internal constructor(private val matrix: ClientSiteMatrix) {

    /** Every wiki the matrix lists, in its order. */
    public val wikis: Array<MatrixWiki>
        get() = matrix.wikis.map(::MatrixWiki).toTypedArray()

    /** The wikis of one [project], such as `"wiktionary"`. */
    public fun of(project: String): Array<MatrixWiki> = matrix.of(project).map(::MatrixWiki).toTypedArray()

    /** The wiki of [project] in [language], or `null` when there is none. */
    public fun get(language: String, project: String): MatrixWiki? =
        matrix[LangCode(language), project]?.let(::MatrixWiki)

    /** The wiki with the database name [id], or `null`. */
    public fun byId(id: String): MatrixWiki? = matrix[WikiId(id)]?.let(::MatrixWiki)

    /** The wikis a bot can actually edit: not closed, not private. */
    public fun open(): Array<MatrixWiki> = matrix.open().map(::MatrixWiki).toTypedArray()

    /** The language codes [project] exists in. */
    public fun languagesOf(project: String): Array<String> =
        matrix.languagesOf(project).map { it.code }.toTypedArray()
}

/** Reads a recorded `action=sitematrix` response. */
@JsExport
public fun decodeSiteMatrix(response: Record<String, JsonValue>): SiteMatrix =
    SiteMatrix(ClientSiteMatrix.decode(jsonObjectOf(response)))

/** One wiki in the Wikimedia site matrix. */
@JsExport
public class MatrixWiki internal constructor(internal val wiki: ClientMatrixWiki) {

    /** The wiki's database name. */
    public val id: String
        get() = wiki.id.dbName

    /** Its canonical URL. */
    public val url: String
        get() = wiki.url

    /** The language code, or `null` for a wiki with no language of its own. */
    public val language: String?
        get() = wiki.language?.code

    /** The project, such as `"wiktionary"`, `"wikipedia"` or `"commons"`. */
    public val project: String?
        get() = wiki.project

    /** Whether it is closed to editing but still readable. */
    public val isClosed: Boolean
        get() = wiki.isClosed

    /** Whether it is private, so that a bot without an account cannot read it. */
    public val isPrivate: Boolean
        get() = wiki.isPrivate

    /** Where its API is, assuming Wikimedia's layout. */
    public val endpoint: Endpoint
        get() = Endpoint(wiki.endpoint)
}
