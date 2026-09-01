package com.fenakhay.kwikibot.testkit

import com.fenakhay.kwikibot.client.WatchMode
import com.fenakhay.kwikibot.client.EditBuilder
import com.fenakhay.kwikibot.client.PageService
import com.fenakhay.kwikibot.model.ActionChecks
import com.fenakhay.kwikibot.model.CategoryInfo
import com.fenakhay.kwikibot.model.Contributors
import com.fenakhay.kwikibot.model.EditOutcome
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.PageContent
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.PageRef
import com.fenakhay.kwikibot.model.Protection
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.Title
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.WikiId
import kotlin.time.Instant

/**
 * A wiki's pages held in memory, for testing bots without a wiki.
 *
 * Edits are applied to the in-memory text and recorded in [edits], so a test can assert both
 * what the bot decided and what the page ended up saying. Refusals and failures are injectable,
 * because the paths worth testing in a bot are the ones where the wiki says no.
 *
 * ```
 * val pages = FakePageService("volcano" to "==English==")
 * botRun(pages) { … }
 * pages.text("volcano") shouldBe "…"
 * ```
 */
public class FakePageService(
    texts: Map<String, String> = emptyMap(),
    private val wiki: WikiId = WikiId("testwiki"),
    private val refuse: (PageRef) -> EditOutcome.Refused? = { null },
    private val failWith: (() -> WikiError)? = null,
) : PageService {

    public constructor(vararg texts: Pair<String, String>) : this(texts.toMap())

    private val texts: MutableMap<String, String> = texts.mapKeys { key(it.key) }.toMutableMap()

    /** What each page said before anything edited it, so a rollback has something to go back to. */
    private val originals: Map<String, String> = texts.mapKeys { key(it.key) }
    private val protections: MutableMap<String, List<Protection>> = mutableMapOf()
    private val deleted: MutableMap<String, String> = mutableMapOf()
    private val watched: MutableSet<String> = mutableSetOf()
    private var nextRevision = INITIAL_REVISION

    /** Every edit that was applied, in order, with the builder the bot filled in. */
    public val edits: MutableList<Pair<PageRef, EditBuilder>> = mutableListOf()

    /** The current text of a page, or `null` if it does not exist. */
    public fun text(title: String): String? = texts[key(title)]

    /** A reference to a page on this fake wiki. */
    public fun ref(title: String, namespace: Namespace = Namespace.MAIN): PageRef =
        PageRef(wiki, Title.Local(namespace, title))

    override suspend fun content(ref: PageRef): PageContent? {
        val text = texts[key(ref)] ?: return null
        return PageContent(ref, RevisionId(INITIAL_REVISION), text)
    }

    override suspend fun contents(refs: Collection<PageRef>): Map<PageRef, PageContent> =
        refs.mapNotNull { ref -> content(ref)?.let { ref to it } }.toMap()

    override suspend fun exists(ref: PageRef): Boolean = key(ref) in texts

    override suspend fun edit(ref: PageRef, block: EditBuilder.() -> Unit): EditOutcome {
        failWith?.let { throw it() }
        refuse(ref)?.let { return it }

        val builder = EditBuilder().apply(block)
        val updated = builder.text ?: (texts[key(ref)].orEmpty() + builder.appendText.orEmpty())

        if (updated == texts[key(ref)]) return EditOutcome.NoChange(ref, RevisionId(nextRevision))

        edits += ref to builder
        texts[key(ref)] = updated
        return EditOutcome.Saved(ref, RevisionId(++nextRevision), RevisionId(nextRevision - 1))
    }

    override suspend fun move(
        from: PageRef,
        to: PageRef,
        reason: String,
        leaveRedirect: Boolean,
        moveTalk: Boolean,
        moveSubpages: Boolean,
        watchlist: WatchMode,
    ): PageRef {
        texts.remove(key(from))?.let { texts[key(to)] = it }
        return to
    }

    override suspend fun delete(
        ref: PageRef,
        reason: String,
        deleteTalk: Boolean,
        watchlist: WatchMode,
    ) {
        texts.remove(key(ref))?.let { deleted[key(ref)] = it }
    }

    override suspend fun purge(refs: Collection<PageRef>, forceLinkUpdate: Boolean): Unit = Unit

    /**
     * The page-level administrative actions do nothing in the fake.
     *
     * They exist so a bot that calls one compiles and runs against it; a fake wiki has no history
     * to merge and no content models to change between.
     */
    override suspend fun mergeHistory(
        from: PageRef,
        to: PageRef,
        upTo: Instant?,
        reason: String,
    ): Unit = Unit

    override suspend fun importPage(
        source: String,
        page: String,
        fullHistory: Boolean,
        includeTemplates: Boolean,
        rootPage: String?,
        summary: String,
    ): Unit = Unit

    override suspend fun setLanguage(ref: PageRef, language: LangCode, reason: String): Unit = Unit

    override suspend fun changeContentModel(
        ref: PageRef,
        model: String,
        summary: String,
    ): Unit = Unit

    /** The fake refuses nothing: a bot under test is not being tested on its permissions. */
    override suspend fun testActions(
        refs: Collection<PageRef>,
        actions: Set<String>,
    ): Map<PageRef, ActionChecks> =
        refs.associateWith { ActionChecks(actions.associateWith { emptyList() }) }

    /**
     * Nothing points at anything in the fake, and no category holds anything.
     *
     * A bot under test decides what to do from the page text it was given; these answer so that
     * a code path reading them runs, not so that it finds something.
     */
    override suspend fun contributors(refs: Collection<PageRef>): Map<PageRef, Contributors> =
        emptyMap()

    override suspend fun categoryInfo(refs: Collection<PageRef>): Map<PageRef, CategoryInfo> =
        emptyMap()

    override suspend fun backlinksOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>> =
        emptyMap()

    override suspend fun transclusionsOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>> =
        emptyMap()

    override suspend fun fileUsageOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>> =
        emptyMap()

    override suspend fun protections(refs: Collection<PageRef>): Map<PageRef, List<Protection>> =
        refs.mapNotNull { ref -> protections[key(ref)]?.let { ref to it } }.toMap()

    override suspend fun protect(
        ref: PageRef,
        protections: List<Protection>,
        reason: String,
        cascade: Boolean,
        watchlist: WatchMode,
    ) {
        this.protections[key(ref)] = protections
    }

    /**
     * Reverts the page to the text it had before this fake applied any edit.
     *
     * A real rollback undoes only the top run of edits by one user; there is one editor here, so
     * the two amount to the same thing.
     */
    override suspend fun rollback(
        ref: PageRef,
        user: String,
        summary: String,
        markBot: Boolean,
        watchlist: WatchMode,
    ): EditOutcome {
        val original = originals[key(ref)]
            ?: return EditOutcome.NoChange(ref, RevisionId(nextRevision))
        if (texts[key(ref)] == original) {
            return EditOutcome.NoChange(ref, RevisionId(nextRevision))
        }

        texts[key(ref)] = original
        return EditOutcome.Saved(ref, RevisionId(++nextRevision), RevisionId(nextRevision - 1))
    }

    /** Deleted pages are kept, so undeleting one puts it back where it was. */
    override suspend fun undelete(
        ref: PageRef,
        reason: String,
        undeleteTalk: Boolean,
        watchlist: WatchMode,
    ) {
        deleted.remove(key(ref))?.let { texts[key(ref)] = it }
    }

    /** Watching changes nothing about a page, so the fake records it and moves on. */
    override suspend fun watch(refs: Collection<PageRef>, watch: Boolean, expiry: String?) {
        refs.forEach { if (watch) watched += key(it) else watched -= key(it) }
    }

    /** Whether a page is on the fake watchlist. */
    public fun isWatched(title: String): Boolean = key(title) in watched

    /**
     * Returns the text unchanged.
     *
     * Expanding a template means running the wiki's parser, which a fake cannot do and must not
     * pretend to: a test that needs expansion needs a wiki.
     */
    override suspend fun expandText(wikitext: String, title: PageRef?): String = wikitext

    /** Undoing has no meaning without a history, so it reverts the same way a rollback does. */
    override suspend fun undo(
        ref: PageRef,
        revision: RevisionId,
        summary: String,
        through: RevisionId?,
    ): EditOutcome = rollback(ref, user = "")

    /**
     * The key a title is stored under.
     *
     * A wiki capitalises the first letter of a title, and [Wiki.ref] does the same before this
     * fake ever sees it. Storing what the test typed would leave a page seeded as "volcano"
     * unreachable through `wiki.ref("volcano")`, which reads as a bot finding nothing.
     */
    private fun key(title: String): String = title.replaceFirstChar { it.uppercaseChar() }

    private fun key(ref: PageRef): String = key(ref.title.text)

    private companion object {
        const val INITIAL_REVISION = 1L
    }
}
