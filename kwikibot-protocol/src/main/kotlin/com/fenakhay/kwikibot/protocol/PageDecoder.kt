package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.model.ContentModel
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageContent
import com.fenakhay.kwikibot.model.PageId
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Revision
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One entry from a `query` result's `pages` collection. */
public sealed interface PageResult {

    /** A page that exists. */
    public data class Existing(
        /** Which page this is. */
        val ref: PageRef,
        /** What kind of content it holds. */
        val contentModel: ContentModel,
        /** Whether it redirects elsewhere. */
        val isRedirect: Boolean,
        /** Its newest revision, absent when the query did not ask for revisions. */
        val latestRevision: Revision?,
        /** Its text, absent when the query asked for no content. */
        val content: PageContent?,
    ) : PageResult

    /** A well-formed title with no page behind it. */
    public data class Missing(
        /** The title that named no page. */
        val ref: PageRef,
    ) : PageResult

    /** A title MediaWiki refused, with the reason it gave. */
    public data class Invalid(
        /** The title as it was sent. */
        val raw: String,
        /** Why the wiki refused it. */
        val reason: String,
    ) : PageResult
}

/**
 * Turns `query` page entries into model objects.
 *
 * The awkward parts of the API shape are handled here so no caller has to: a title arrives with
 * its namespace prefix already attached, content sits two levels down inside a revision slot,
 * and `missing`, `redirect` and `minor` are presence flags whose representation changed with
 * `formatversion`.
 */
public class PageDecoder(
    private val wiki: WikiId,
    private val namespaces: NamespaceMap,
) {

    /** Decodes one page entry. */
    public fun decode(page: JsonObject): PageResult {
        val raw = page["title"]?.jsonPrimitive?.content.orEmpty()

        if (page.flag("invalid")) {
            val reason = page["invalidreason"]?.jsonPrimitive?.content
                ?: "MediaWiki rejected the title"
            return PageResult.Invalid(raw, reason)
        }

        val ref = refOf(page) ?: return PageResult.Invalid(raw, "title could not be read")

        if (page.flag("missing")) return PageResult.Missing(ref)

        val revision = page["revisions"]?.jsonArray?.firstOrNull()?.jsonObject
        val contentModel = page["contentmodel"]?.jsonPrimitive?.content
            ?.let { ContentModel(it) }
            ?: ContentModel.WIKITEXT

        return PageResult.Existing(
            ref = ref,
            contentModel = contentModel,
            isRedirect = page.flag("redirect"),
            latestRevision = revision?.let { decodeRevision(it) },
            content = revision?.let { content(ref, it, contentModel, page.flag("redirect")) },
        )
    }

    /** Decodes one revision entry, from `prop=revisions` or from a log or history query. */
    public fun decodeRevision(revision: JsonObject): Revision = Revision(
        id = RevisionId(revision.long("revid") ?: 0L),
        parentId = revision.long("parentid")?.takeIf { it != 0L }?.let { RevisionId(it) },
        timestamp = revision["timestamp"]?.jsonPrimitive?.content
            ?.let { MwTimestamp.parse(it) }
            ?: MwTimestamp.parse(EPOCH),
        // Absent rather than empty when revision-deleted, which is how hiding is reported.
        user = revision["user"]?.jsonPrimitive?.content,
        comment = revision["comment"]?.jsonPrimitive?.content,
        isMinor = revision.flag("minor"),
        isBot = revision.flag("bot"),
        size = revision["size"]?.jsonPrimitive?.intOrNull ?: 0,
        sha1 = revision["sha1"]?.jsonPrimitive?.content,
        tags = revision["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
    )

    /**
     * The reference an entry names, or `null` if it carries no usable title.
     *
     * Works for any result that reports `ns` and `title`, which every list module does, so list
     * results and page results decode through the same rules.
     *
     * The API returns titles already normalised and prefixed, so the namespace number decides
     * where the prefix ends — which is also why a main-space title containing a colon, like
     * `Nineteen Eighty-Four: A Novel`, survives intact.
     */
    public fun refOf(page: JsonObject): PageRef? {
        val full = page["title"]?.jsonPrimitive?.content ?: return null
        return refOf(full, page["ns"]?.jsonPrimitive?.intOrNull ?: 0, page.long("pageid"))
    }

    /**
     * The reference a prefixed title and a namespace number name.
     *
     * The same rule as [refOf], for the places the API reports a title outside a page entry: a
     * move log entry names its target as a title and a namespace side by side.
     */
    public fun refOf(fullTitle: String, namespaceId: Int, pageId: Long? = null): PageRef? {
        val namespace = Namespace(namespaceId)
        val text =
            if (namespace == Namespace.MAIN) fullTitle else fullTitle.substringAfter(':', fullTitle)
        if (text.isEmpty()) return null

        return PageRef(
            wiki = wiki,
            title = Title.Local(namespace, text),
            pageId = pageId?.let { PageId(it) },
        )
    }

    private fun content(
        ref: PageRef,
        revision: JsonObject,
        contentModel: ContentModel,
        isRedirect: Boolean,
    ): PageContent? {
        val text = revision.slotContent() ?: return null

        return PageContent(
            ref = ref,
            revisionId = RevisionId(revision.long("revid") ?: 0L),
            text = text,
            contentModel = contentModel,
            timestamp = revision["timestamp"]?.jsonPrimitive?.content?.let { MwTimestamp.parse(it) },
            redirectTarget = if (isRedirect) redirectTarget(text) else null,
        )
    }

    /** Reads the wikitext out of a revision, from either the slot or the flat shape. */
    private fun JsonObject.slotContent(): String? {
        val slot = this["slots"]?.jsonObject?.get("main")?.jsonObject
        return slot?.get("content")?.jsonPrimitive?.content
            // Without rvslots the content sits directly on the revision.
            ?: this["content"]?.jsonPrimitive?.content
            ?: this["*"]?.jsonPrimitive?.content
    }

    /**
     * The target of a redirect, read from the page text.
     *
     * The API can be asked to resolve redirects instead, but a bot that is about to rewrite a
     * page needs to know what the text itself says.
     */
    private fun redirectTarget(text: String): Title? =
        REDIRECT.find(text)?.groupValues?.get(1)?.let { Title.parse(it, namespaces) }

    /**
     * Whether a boolean flag is set.
     *
     * `formatversion=2` sends `true`, older responses send an empty string, and both mean the
     * same thing; only an explicit `false` counts as unset.
     */
    private fun JsonObject.flag(key: String): Boolean {
        val value: JsonElement = this[key] ?: return false
        val primitive = value as? JsonPrimitive ?: return true
        return primitive.booleanOrNull ?: true
    }

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private companion object {
        const val EPOCH = "1970-01-01T00:00:00Z"

        val REDIRECT = Regex("""^\s*#(?:REDIRECT|redirect)\s*:?\s*\[\[([^\]|#]+)""")
    }
}
