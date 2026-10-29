package com.fenakhay.kwikibot.protocol.decode

import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.PageId
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.page.ContentModel
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.Revision
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.model.title.Title
import kotlinx.serialization.json.JsonObject

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
        val ref: PageRef
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
 * The awkward parts of the API shape are handled here so no caller has to: a title arrives with its namespace
 * prefix already attached, content sits two levels down inside a revision slot, and `missing`, `redirect` and
 * `minor` are flags the wiki leaves out rather than sends as `false`.
 */
public class PageDecoder(
    private val wiki: WikiId,
    private val namespaces: NamespaceMap,
) {

    /** Decodes one page entry. */
    public fun decode(page: JsonObject): PageResult {
        val raw = page.string("title").orEmpty()

        if (page.flag("invalid")) {
            val reason = page.string("invalidreason") ?: "MediaWiki rejected the title"
            return PageResult.Invalid(raw, reason)
        }

        val ref = refOf(page) ?: return PageResult.Invalid(raw, "title could not be read")

        if (page.flag("missing")) return PageResult.Missing(ref)

        val revision = page.array("revisions").objects().firstOrNull()
        val contentModel = page.string("contentmodel")?.let { ContentModel(it) } ?: ContentModel.WIKITEXT

        return PageResult.Existing(
            ref = ref,
            contentModel = contentModel,
            isRedirect = page.flag("redirect"),
            latestRevision = revision?.let { decodeRevision(it) },
            content = revision?.let { content(ref, it, contentModel, page.flag("redirect")) },
        )
    }

    /**
     * The text at each revision one page entry carries, which `prop=revisions&revids=…` gives several of when
     * several of the revisions asked for belong to the same page. A revision without text, hidden or not
     * asked for, is left out; so is everything when the entry is not an existing page.
     *
     * The entry says whether the page is a redirect now, which says nothing of an older revision, so whether
     * each revision's wikitext is one is up to [isRedirect]: the wiki's own redirect magic word starting it.
     */
    public fun decodeContents(page: JsonObject, isRedirect: (String) -> Boolean): List<PageContent> {
        val ref = refOf(page) ?: return emptyList()
        if (page.flag("missing") || page.flag("invalid")) return emptyList()
        val contentModel = page.string("contentmodel")?.let { ContentModel(it) } ?: ContentModel.WIKITEXT

        return page.array("revisions").objects().mapNotNull { revision ->
            val text = revision.slotContent() ?: return@mapNotNull null
            content(ref, revision, contentModel, contentModel == ContentModel.WIKITEXT && isRedirect(text))
        }
    }

    /** Decodes one revision entry, from `prop=revisions` or from a log or history query. */
    public fun decodeRevision(revision: JsonObject): Revision =
        Revision(
            id = RevisionId(revision.long("revid") ?: 0L),
            parentId = revision.long("parentid")?.takeIf { it != 0L }?.let { RevisionId(it) },
            timestamp = revision.time("timestamp") ?: MwTimestamp.parse(EPOCH),
            // Absent rather than empty when revision-deleted, which is how hiding is reported.
            user = revision.string("user"),
            comment = revision.string("comment"),
            isMinor = revision.flag("minor"),
            isBot = revision.flag("bot"),
            size = revision.int("size") ?: 0,
            sha1 = revision.string("sha1"),
            tags = revision.array("tags").strings(),
        )

    /**
     * The reference an entry names, or `null` if it carries no usable title.
     *
     * Works for any result that reports `ns` and `title`, which every list module does, so list results and
     * page results decode through the same rules.
     *
     * The API returns titles already normalised and prefixed, so the namespace number decides where the
     * prefix ends — which is also why a main-space title containing a colon, like `Nineteen Eighty-Four: A
     * Novel`, survives intact.
     */
    public fun refOf(page: JsonObject): PageRef? {
        val full = page.string("title") ?: return null
        return refOf(full, page.int("ns") ?: 0, page.long("pageid"))
    }

    /**
     * The reference a prefixed title and a namespace number name.
     *
     * The same rule as [refOf], for the places the API reports a title outside a page entry: a move log entry
     * names its target as a title and a namespace side by side.
     */
    public fun refOf(fullTitle: String, namespaceId: Int, pageId: Long? = null): PageRef? {
        val namespace = Namespace(namespaceId)
        val text = if (namespace == Namespace.MAIN) fullTitle else fullTitle.substringAfter(':', fullTitle)
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
            timestamp = revision.time("timestamp"),
            redirectTarget = if (isRedirect) redirectTarget(text) else null,
        )
    }

    /** Reads the wikitext out of a revision, from either the slot or the flat shape. */
    private fun JsonObject.slotContent(): String? =
        obj("slots")?.obj("main")?.string("content")
            // Without rvslots the content sits directly on the revision.
            ?: string("content")

    /**
     * The target of a redirect, read from the page text.
     *
     * The API can be asked to resolve redirects instead, but a bot that is about to rewrite a page needs to
     * know what the text itself says.
     *
     * Only called for a page the wiki has already marked as a redirect, so any magic word will do: English
     * writes `#REDIRECT`, German `#WEITERLEITUNG` and Arabic `#تحويل`. The target is the link that follows
     * the magic word, with nothing but whitespace, line breaks included, and at most one colon between them.
     */
    private fun redirectTarget(text: String): Title? =
        REDIRECT.find(text)?.groupValues?.get(1)?.let { Title.parse(it, namespaces) }

    private companion object {
        const val EPOCH = "1970-01-01T00:00:00Z"

        val REDIRECT = Regex("""^\s*#[^\s\[]+\s*:?\s*\[\[([^\]|#\n]+)""")
    }
}
