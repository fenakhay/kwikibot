package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.net.ApiEndpoint
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One wiki in the Wikimedia site matrix. */
public data class MatrixWiki(
    /** The wiki's database name. */
    val id: WikiId,
    /** Its canonical URL. */
    val url: String,
    /** The language code, or `null` for a wiki that has no language of its own. */
    val language: LangCode? = null,
    /** The project: `wiktionary`, `wikipedia`, `commons`. */
    val project: String? = null,
    /** Whether the wiki is closed to editing but still readable. */
    val isClosed: Boolean = false,
    /** Whether it is private, in which case a bot without an account cannot read it. */
    val isPrivate: Boolean = false,
) {
    /** The endpoint of this wiki, assuming the Wikimedia layout. */
    public val endpoint: ApiEndpoint
        get() = ApiEndpoint(
            server = url.substringAfter("://").trimEnd('/'),
            scriptPath = "/w",
        )
}

/**
 * Every Wikimedia wiki, from the wiki that lists them.
 *
 * Queried rather than shipped as a table. A list of projects and their language codes goes stale
 * as wikis are created, so the answer is read from the wiki that maintains it.
 *
 * ```
 * val matrix = SiteMatrix.fetch(transport)
 * matrix.of("wiktionary").map { it.language }
 * ```
 */
public class SiteMatrix(
    /** Every wiki the matrix lists, in the order it listed them. */
    public val wikis: List<MatrixWiki>,
) {

    /** The wikis of one project. */
    public fun of(project: String): List<MatrixWiki> =
        wikis.filter { it.project.equals(project, ignoreCase = true) }

    /** The wiki of a project in a language, or `null` if there is none. */
    public operator fun get(language: LangCode, project: String): MatrixWiki? =
        of(project).firstOrNull { it.language == language }

    /** The wiki with this database name. */
    public operator fun get(id: WikiId): MatrixWiki? = wikis.firstOrNull { it.id == id }

    /** The wikis a bot can actually edit: not closed, not private. */
    public fun open(): List<MatrixWiki> = wikis.filterNot { it.isClosed || it.isPrivate }

    /** The language codes a project exists in. */
    public fun languagesOf(project: String): List<LangCode> = of(project).mapNotNull { it.language }

    /** Fetching the matrix, which is a read of the wiki fleet rather than of one wiki. */
    public companion object {

        /**
         * Reads the site matrix from a wiki that publishes one.
         *
         * Any Wikimedia wiki serves it, since the extension answers for the whole farm; meta is
         * conventional and nothing depends on it.
         */
        public suspend fun fetch(transport: MediaWikiTransport): SiteMatrix =
            decode(transport.call(ApiRequest.of("sitematrix")).throwOnError())

        /** Reads a recorded `action=sitematrix` response. */
        public fun decode(response: JsonObject): SiteMatrix {
            val matrix = response["sitematrix"]?.jsonObject ?: return SiteMatrix(emptyList())

            val byLanguage = matrix.entries
                // The block is keyed by number per language, plus "count" and "specials", which
                // are not languages and have to be told apart by shape rather than by name.
                .mapNotNull { (_, value) -> (value as? JsonObject) }
                .flatMap { group ->
                    val language = group["code"]?.jsonPrimitive?.content?.let { LangCode(it) }
                    group["site"]?.jsonArray.orEmpty().map { it.jsonObject.toWiki(language) }
                }

            val specials = matrix["specials"]?.jsonArray.orEmpty()
                .map { it.jsonObject.toWiki(language = null) }

            return SiteMatrix(byLanguage + specials)
        }

        private fun JsonObject.toWiki(language: LangCode?) = MatrixWiki(
            id = WikiId(this["dbname"]?.jsonPrimitive?.content.orEmpty().ifEmpty { "unknown" }),
            url = this["url"]?.jsonPrimitive?.content.orEmpty(),
            language = language,
            project = this["code"]?.jsonPrimitive?.content,
            // Both arrive as presence flags rather than booleans.
            isClosed = containsKey("closed"),
            isPrivate = containsKey("private"),
        )
    }
}
