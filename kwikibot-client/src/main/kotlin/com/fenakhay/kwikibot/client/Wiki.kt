package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.InterwikiMap
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.NamespaceMap
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.WikiId
import com.fenakhay.kwikibot.net.Identity
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.protocol.ParamInfo
import com.fenakhay.kwikibot.protocol.SiteInfo

/**
 * One wiki, ready to be worked with.
 *
 * Services hang off this handle — `wiki.pages`, `wiki.categories` — rather than being methods
 * on it, so each stays small and independently testable, and so IDE completion offers a short
 * list instead of the sixty methods a single site object accumulates.
 *
 * A `Wiki` is cheap to pass around and safe to share between coroutines.
 */
public interface Wiki {

    /** The wiki's database name, `enwiktionary`. */
    public val id: WikiId

    /** What the wiki says about itself: namespaces, interwiki prefixes, version. */
    public val info: SiteInfo

    /** Who the wiki thinks we are. */
    public val identity: Identity

    /** Reading and writing pages. */
    public val pages: PageService

    /** Listing pages: categories, backlinks, transclusions, search, special pages. */
    public val lists: ListService

    /** Page histories, revisions by id, and the diffs between them. */
    public val revisions: RevisionService

    /** Accounts: their rights, their blocks and their edits. */
    public val users: UserService

    /** What has happened on the wiki: its logs and its recent changes. */
    public val logs: LogService

    /** Files: what is known about them, where they are used, and how to add one. */
    public val files: FileService

    /** What a wiki can do only because an extension is installed. */
    public val extensions: ExtensionService

    /** Wikisource's scanned pages. Needs the ProofreadPage extension. */
    public val proofread: ProofreadService

    /** What the wiki says about itself beyond what a session needs to start. */
    /**
     * The wiki's own view of a page: what it renders, and what it resolves to.
     *
     * Named apart from [parse], which parses a title rather than wikitext.
     */
    public val renderer: RenderService

    /** What the wiki says about itself: its messages, its magic words, its counters. */
    public val meta: MetaService

    /**
     * What this wiki says its own API accepts.
     *
     * Worth asking rather than assuming: a query limit is 50 for one account and 500 for
     * another, and parameters come and go between MediaWiki versions.
     */
    public val paramInfo: ParamInfo

    /**
     * Resolves a raw title against this wiki's namespaces and interwiki prefixes.
     *
     * @throws WikiError.Page.BadTitle if the title is not one MediaWiki would accept.
     * @throws WikiError.Page.OffWiki if it names a page on another project — the check that
     *   stops a bot from editing the wrong wiki.
     */
    public fun ref(raw: String, defaultNamespace: Namespace = Namespace.MAIN): PageRef =
        when (val title = Title.parse(raw, namespaces, interwiki, defaultNamespace)) {
            is Title.Local -> PageRef(id, title)
            is Title.Interwiki -> throw WikiError.Page.OffWiki(title)
            is Title.Invalid -> throw WikiError.Page.BadTitle(title)
        }

    /** Resolves a raw title, returning the parse result instead of throwing. */
    public fun parse(raw: String, defaultNamespace: Namespace = Namespace.MAIN): Title =
        Title.parse(raw, namespaces, interwiki, defaultNamespace)

    /** A reference to a page that is already known to be local. */
    public fun ref(title: Title.Local): PageRef = PageRef(id, title)

    /** This wiki's namespaces. */
    public val namespaces: NamespaceMap get() = info.namespaces

    /** This wiki's interwiki prefixes. */
    public val interwiki: InterwikiMap get() = info.interwiki

    /** The transport, for actions this library does not model yet. */
    public val transport: MediaWikiTransport

    /** The token cache backing every write. */
    public val tokens: TokenStore
}
