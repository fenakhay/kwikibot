package com.fenakhay.kwikibot.model

import kotlin.time.Instant

/**
 * Which wiki something belongs to, by database name (`enwiktionary`, `commonswiki`).
 *
 * Carried on every page reference so a value taken from one wiki cannot be handed to another —
 * the mistake that turns a Commons lookup into an edit on the local wiki.
 */
@JvmInline
public value class WikiId(
    /** The wiki's database name, which is its identity across the whole Wikimedia fleet. */
    public val dbName: String,
) {
    init {
        require(dbName.isNotBlank()) { "wiki id must not be blank" }
    }

    /** The database name itself, which is what logs and error messages should carry. */
    override fun toString(): String = dbName
}

/**
 * A page, named but not loaded.
 *
 * A pure value: constructing one performs no I/O and says nothing about whether the page
 * exists. Content is fetched explicitly, which is what keeps request counts visible.
 */
public data class PageRef(
    /** The wiki the page is on. Comparing references from two wikis is what this prevents. */
    val wiki: WikiId,
    /** The title, already parsed into a namespace and the text after it. */
    val title: Title.Local,
    /**
     * The wiki's own id for the page, when it is known.
     *
     * Absent for a reference built from a title alone, and stable across moves where a title is
     * not, so it is the safer thing to record in a log.
     */
    val pageId: PageId? = null,
) {
    /** The namespace the page lives in. */
    val namespace: Namespace get() = title.namespace

    /** The title and the wiki it is on, `volcano@enwiktionary`. */
    override fun toString(): String = "$title@$wiki"
}

/** The content model of a page, as MediaWiki names it. */
@JvmInline
public value class ContentModel(
    /** MediaWiki's own name for the model, which is case-sensitive. */
    public val id: String,
) {

    /** Whether this is wikitext, the only model the wikitext tooling can edit. */
    public val isWikitext: Boolean get() = id == WIKITEXT.id

    /** The model name as the API spells it. */
    override fun toString(): String = id

    /** The models a Wikimedia wiki carries out of the box. A wiki may define others. */
    public companion object {
        /** Ordinary page content, and the only model the wikitext tooling understands. */
        public val WIKITEXT: ContentModel = ContentModel("wikitext")
        /** Structured data pages, such as those the JsonConfig extension serves. */
        public val JSON: ContentModel = ContentModel("json")
        /** User and site stylesheets. */
        public val CSS: ContentModel = ContentModel("css")
        /** User and site scripts. */
        public val JAVASCRIPT: ContentModel = ContentModel("javascript")
        /** Lua modules, which templates call into. */
        public val SCRIBUNTO: ContentModel = ContentModel("Scribunto")
        /** TemplateStyles sheets, stripped of anything a page should not be able to do. */
        public val SANITIZED_CSS: ContentModel = ContentModel("sanitized-css")
        /** Plain text, stored and rendered without wiki markup. */
        public val TEXT: ContentModel = ContentModel("text")
    }
}

/**
 * A page as it was at one revision: an immutable snapshot, not a live handle.
 *
 * Editing takes [revisionId] back as the base revision, which is what lets the wiki detect an
 * edit conflict rather than silently overwriting a concurrent edit.
 */
public data class PageContent(
    /** Which page this is, and on which wiki. */
    val ref: PageRef,
    /**
     * The revision this text was read at.
     *
     * Pass it back as an edit's base revision: it is what lets the wiki refuse an edit computed
     * from text somebody has since changed.
     */
    val revisionId: RevisionId,
    /** The wikitext, byte for byte as the wiki stored it. */
    val text: String,
    /** What kind of content [text] is. Only wikitext can be given to the wikitext parser. */
    val contentModel: ContentModel = ContentModel.WIKITEXT,
    /** When the revision was made, absent when the query did not ask for it. */
    val timestamp: Instant? = null,
    /**
     * Where this page redirects, or `null` if it is not a redirect.
     *
     * A [Title] rather than a [Title.Local]: a redirect may point at another wiki.
     */
    val redirectTarget: Title? = null,
) {
    /** Whether this page is a redirect. */
    val isRedirect: Boolean get() = redirectTarget != null

    /** The page title, for convenience. */
    val title: Title.Local get() = ref.title
}

/** One revision of a page, without its content. */
public data class Revision(
    /** The revision's own id, which is stable and never reused. */
    val id: RevisionId,
    /** The revision this one was made from, absent for the page's first revision. */
    val parentId: RevisionId? = null,
    /** When the edit was saved. */
    val timestamp: Instant,
    /** Who made it, or `null` when revision deletion has hidden the author. */
    val user: String? = null,
    /** The edit summary, or `null` when revision deletion has hidden it. */
    val comment: String? = null,
    /** Whether the editor marked it minor, which is a claim rather than a measurement. */
    val isMinor: Boolean = false,
    /** Whether it was flagged as a bot edit, and so hidden from default recent-changes views. */
    val isBot: Boolean = false,
    /** The page size in bytes after the edit. Compare with the parent's to get the change. */
    val size: Int = 0,
    /** The content hash, which is how two revisions are known to be identical without reading them. */
    val sha1: String? = null,
    /** The change tags on the edit, such as `mw-reverted` or `mobile edit`. */
    val tags: List<String> = emptyList(),
) {
    /**
     * Whether the author was hidden by revision deletion.
     *
     * MediaWiki omits the field entirely in that case, so an absent user is the signal.
     */
    val isUserHidden: Boolean get() = user == null

    /** Whether the edit summary was hidden by revision deletion. */
    val isCommentHidden: Boolean get() = comment == null
}
