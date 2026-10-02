package com.fenakhay.kwikibot.testkit

import com.fenakhay.kwikibot.client.service.EditBuilder
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.client.service.WatchMode
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.ActionChecks
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.page.CategoryInfo
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.title.Title
import com.fenakhay.kwikibot.model.user.Contributors
import kotlin.time.Instant

/**
 * A wiki's pages held in memory, for testing bots without a wiki.
 *
 * Edits are applied to the in-memory text and recorded in [edits], so a test can assert both what the bot
 * decided and what the page ended up saying. Refusals and failures are injectable, because the paths worth
 * testing in a bot are the ones where the wiki says no.
 *
 * The fake refuses an edit for reasons a wiki has: a stale `baseRevision` is a conflict, though a wiki first
 * tries to merge the two edits and refuses only if it cannot; `createOnly` on a page that exists and
 * `noCreate` on one that does not are refused; and a contradictory builder is rejected before anything
 * changes. Each page has its own revision, which every saved edit advances, so a bot that reads, waits and
 * saves can be tested against a page that changed in between.
 *
 * Titles are seeded as a wiki writes them, prefix included: `"Template:foo"` is a page in the Template
 * namespace, and a different page from `"foo"`.
 *
 * ```
 * val pages = FakePageService("volcano" to "==English==")
 * botRun(pages) { … }
 * pages.text("volcano") shouldBe "…"
 * ```
 *
 * @param texts the pages the fake starts with, by title.
 * @param wiki the wiki the refs it hands out belong to.
 * @param refuse a refusal to return instead of applying an edit, or `null` to apply it.
 * @param failWith an error to throw from every edit, for testing a bot's failure path.
 * @param expander what [expandText] makes of wikitext. The identity by default: a fake has no templates to
 *   expand, and a test that cares says what expansion should produce.
 */
public class FakePageService(
    texts: Map<String, String> = emptyMap(),
    private val wiki: WikiId = WikiId("testwiki"),
    private val refuse: (PageRef) -> EditOutcome.Refused? = { null },
    private val failWith: (() -> WikiError)? = null,
    private val expander: (String, PageRef?) -> String = { text, _ -> text },
) : PageService {

    public constructor(vararg texts: Pair<String, String>) : this(texts.toMap())

    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        texts: Map<String, String> = emptyMap(),
        wiki: WikiId = WikiId("testwiki"),
        refuse: (PageRef) -> EditOutcome.Refused? = { null },
        failWith: (() -> WikiError)? = null,
    ) : this(texts, wiki, refuse, failWith, { text, _ -> text })

    private val texts: MutableMap<Key, String> = texts.mapKeys { key(it.key) }.toMutableMap()

    /** What each page said before anything edited it, so a rollback has something to go back to. */
    private val originals: Map<Key, String> = this.texts.toMap()
    private val revisions: MutableMap<Key, Long> =
        this.texts.keys.associateWith { INITIAL_REVISION }.toMutableMap()
    private val protections: MutableMap<Key, List<Protection>> = mutableMapOf()
    private val deleted: MutableMap<Key, String> = mutableMapOf()
    private val watched: MutableSet<Key> = mutableSetOf()
    private var lastRevision = INITIAL_REVISION

    /** Every edit that was applied, in order, with the builder the bot filled in. */
    public val edits: MutableList<Pair<PageRef, EditBuilder>> = mutableListOf()

    /** The current text of a page, or `null` if it does not exist. */
    public fun text(title: String): String? = texts[key(title)]

    /** The revision a page is at, or `null` if it does not exist. */
    public fun revision(title: String): RevisionId? {
        val key = key(title)
        return if (key in texts) revisions[key]?.let(::RevisionId) else null
    }

    /** A reference to a page on this fake wiki. */
    public fun ref(title: String, namespace: Namespace = Namespace.MAIN): PageRef =
        PageRef(wiki, Title.Local(namespace, title))

    override suspend fun content(ref: PageRef): PageContent? {
        val key = key(ref)
        val text = texts[key] ?: return null
        return PageContent(ref, RevisionId(revisions.getValue(key)), text)
    }

    override suspend fun contents(refs: Collection<PageRef>): Map<PageRef, PageContent> =
        refs.mapNotNull { ref -> content(ref)?.let { ref to it } }.toMap()

    override suspend fun exists(ref: PageRef): Boolean = key(ref) in texts

    override suspend fun edit(ref: PageRef, block: EditBuilder.() -> Unit): EditOutcome {
        failWith?.let { throw it() }
        refuse(ref)?.let {
            return it
        }

        val builder = EditBuilder().apply(block)
        builder.validate()

        val key = key(ref)
        val current = texts[key]
        refusal(ref, builder, current, revisions[key])?.let {
            return it
        }

        val updated = updated(current, builder)
        if (updated == current) return EditOutcome.NoChange(ref, revisions[key]?.let(::RevisionId))

        val previous = revisions[key]?.takeIf { current != null }?.let(::RevisionId)
        edits += ref to builder
        texts[key] = updated
        return EditOutcome.Saved(ref, advance(key), previous)
    }

    /** What a wiki would refuse this edit for, given what the page is now. */
    private fun refusal(
        ref: PageRef,
        builder: EditBuilder,
        current: String?,
        revision: Long?,
    ): EditOutcome.Refused? {
        val base = builder.baseRevision
        return when {
            current == null && builder.noCreate ->
                EditOutcome.Rejected(ref, "The page you specified doesn't exist.", "missingtitle")

            current != null && builder.createOnly ->
                EditOutcome.PageStateChanged(
                    ref,
                    "The page you tried to create has been created already.",
                    false,
                )

            // A page deleted since it was read is recreated, as a wiki does, unless the edit says noCreate.
            current != null && base != null && base.value != revision ->
                EditOutcome.Conflict(ref, "Edit conflict.", revision?.let(::RevisionId))

            else -> null
        }
    }

    private fun updated(current: String?, builder: EditBuilder): String {
        val text = builder.text
        return when {
            builder.section == NEW_SECTION -> {
                // Without a section title, a wiki uses the summary as the heading.
                val title = builder.sectionTitle ?: builder.summary
                val heading = if (title.isEmpty()) "" else "== $title ==\n"
                val body = heading + (text ?: builder.appendText.orEmpty())
                if (current.isNullOrEmpty()) body else "$current\n\n$body"
            }

            builder.section != null ->
                throw NotImplementedError(
                    "FakePageService does not edit numbered sections; replace the whole text instead"
                )

            text != null -> text
            else -> builder.prependText.orEmpty() + current.orEmpty() + builder.appendText.orEmpty()
        }
    }

    /** Gives a page the next revision and returns it. */
    private fun advance(key: Key): RevisionId {
        val revision = ++lastRevision
        revisions[key] = revision
        return RevisionId(revision)
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
        texts.remove(key(from))?.let {
            texts[key(to)] = it
            advance(key(to))
        }
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
     * They exist so a bot that calls one compiles and runs against it; a fake wiki has no history to merge
     * and no content models to change between.
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
    ): Map<PageRef, ActionChecks> = refs.associateWith { ActionChecks(actions.associateWith { emptyList() }) }

    /**
     * Nothing points at anything in the fake, and no category holds anything.
     *
     * A bot under test decides what to do from the page text it was given; these answer so that a code path
     * reading them runs, not so that it finds something.
     */
    override suspend fun contributors(refs: Collection<PageRef>): Map<PageRef, Contributors> = emptyMap()

    override suspend fun categoryInfo(refs: Collection<PageRef>): Map<PageRef, CategoryInfo> = emptyMap()

    override suspend fun backlinksOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>> = emptyMap()

    override suspend fun transclusionsOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>> = emptyMap()

    override suspend fun fileUsageOf(refs: Collection<PageRef>): Map<PageRef, List<PageRef>> = emptyMap()

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
     * A real rollback undoes only the top run of edits by one user; there is one editor here, so the two
     * amount to the same thing.
     */
    override suspend fun rollback(
        ref: PageRef,
        user: String,
        summary: String,
        markBot: Boolean,
        watchlist: WatchMode,
    ): EditOutcome {
        val key = key(ref)
        val current = revisions[key]?.let(::RevisionId)
        val original = originals[key] ?: return EditOutcome.NoChange(ref, current)
        if (texts[key] == original) return EditOutcome.NoChange(ref, current)

        texts[key] = original
        return EditOutcome.Saved(ref, advance(key), current)
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
     * What the fake's expander makes of the text: the text itself unless the test said otherwise.
     *
     * Expanding a template means running the wiki's parser, which a fake cannot do and must not pretend to: a
     * test that needs expansion either says what it should produce, through `expander`, or needs a wiki.
     */
    override suspend fun expandText(wikitext: String, title: PageRef?): String = expander(wikitext, title)

    /** The pages whose text redirects to each of [refs], read from what the fake holds. */
    override suspend fun redirectsTo(
        refs: Collection<PageRef>,
        namespaces: Set<Namespace>,
    ): Map<PageRef, List<PageRef>> {
        val redirects = texts.mapNotNull { (key, text) ->
            val target = REDIRECT.find(text)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val from = PageRef(wiki, Title.Local(Namespace(key.namespace), key.text))
            key(target) to from
        }
        return refs
            .associateWith { ref ->
                redirects
                    .filter { (target, from) ->
                        target == key(ref) && (namespaces.isEmpty() || from.title.namespace in namespaces)
                    }
                    .map { it.second }
            }
            .filterValues { it.isNotEmpty() }
    }

    /** Undoing has no meaning without a history, so it reverts the same way a rollback does. */
    override suspend fun undo(
        ref: PageRef,
        revision: RevisionId,
        summary: String,
        through: RevisionId?,
    ): EditOutcome = rollback(ref, user = "")

    /** Where a page is kept: its namespace and its text, so `Template:X` and `X` are different pages. */
    private data class Key(val namespace: Int, val text: String)

    /**
     * The key a title is stored under, read the way a wiki reads one.
     *
     * A wiki capitalises the first letter of a title, and [com.fenakhay.kwikibot.client.Wiki.ref] does the
     * same before this fake ever sees it. Storing what the test typed would leave a page seeded as "volcano"
     * unreachable through `wiki.ref("volcano")`, which reads as a bot finding nothing.
     */
    private fun key(title: String): Key =
        when (val parsed = Title.parse(title)) {
            is Title.Local -> key(parsed.namespace, parsed.text)
            else -> key(Namespace.MAIN, title)
        }

    private fun key(ref: PageRef): Key = key(ref.title.namespace, ref.title.text)

    private fun key(namespace: Namespace, text: String): Key =
        Key(namespace.id, text.replaceFirstChar { it.uppercaseChar() })

    /** Values a test can compare against. */
    public companion object {
        /** The revision every seeded page is at before anything edits it. */
        public const val INITIAL_REVISION: Long = 1L

        private const val NEW_SECTION = "new"

        /** A redirect in any language: `#` and a magic word, then the target. */
        private val REDIRECT = Regex("""^\s*#[^\s\[]+\s*:?\s*\[\[([^\]|#\n]+)""")
    }
}
