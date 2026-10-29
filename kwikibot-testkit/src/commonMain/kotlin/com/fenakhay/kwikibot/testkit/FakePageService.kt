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
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
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
 * The fake can be called from several threads at once, as a bot run calls it. Each change replaces what the
 * fake holds in one step, so a reader sees all of an edit or none of it, and of two edits made at the same
 * time neither is lost.
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
@OptIn(ExperimentalAtomicApi::class)
public class FakePageService(
    texts: Map<String, String> = emptyMap(),
    private val wiki: WikiId = WikiId("testwiki"),
    private val refuse: (PageRef) -> EditOutcome.Refused? = { null },
    private val failWith: (() -> WikiError)? = null,
    private val expander: (String, PageRef?) -> String = { text, _ -> text },
) : PageService {

    public constructor(vararg texts: Pair<String, String>) : this(texts.toMap())

    /** What each page said before anything edited it, so a rollback has something to go back to. */
    private val originals: Map<Key, String> = texts.mapKeys { key(it.key) }

    private val state = AtomicReference(Pages(originals, originals.mapValues { INITIAL_REVISION }))

    /**
     * Every edit that was applied, in order, with the builder the bot filled in.
     *
     * Each read gives the edits applied so far, and the list it gives does not change: one read before a run
     * does not grow as the run edits.
     */
    public val edits: List<Pair<PageRef, EditBuilder>>
        get() = state.load().edits

    /** The current text of a page, or `null` if it does not exist. */
    public fun text(title: String): String? = state.load().texts[key(title)]

    /** The revision a page is at, or `null` if it does not exist. */
    public fun revision(title: String): RevisionId? {
        val key = key(title)
        val pages = state.load()
        return if (key in pages.texts) RevisionId(pages.revisions.getValue(key)) else null
    }

    /** A reference to a page on this fake wiki. */
    public fun ref(title: String, namespace: Namespace = Namespace.MAIN): PageRef =
        PageRef(wiki, Title.Local(namespace, title))

    override suspend fun content(ref: PageRef): PageContent? = state.load().contentOf(ref)

    override suspend fun contents(refs: Collection<PageRef>): Map<PageRef, PageContent> {
        val pages = state.load()
        return refs.mapNotNull { ref -> pages.contentOf(ref)?.let { ref to it } }.toMap()
    }

    override suspend fun exists(ref: PageRef): Boolean = key(ref) in state.load().texts

    /** What [ref] says in these pages, at the revision it is at, or `null` if it does not exist. */
    private fun Pages.contentOf(ref: PageRef): PageContent? {
        val key = key(ref)
        val text = texts[key] ?: return null
        return PageContent(ref, RevisionId(revisions.getValue(key)), text)
    }

    override suspend fun edit(ref: PageRef, block: EditBuilder.() -> Unit): EditOutcome {
        failWith?.let { throw it() }
        refuse(ref)?.let {
            return it
        }

        val builder = EditBuilder().apply(block)
        builder.validate()

        val key = key(ref)
        return change { pages ->
            val current = pages.texts[key]
            val revision = pages.revisions[key]
            refusal(ref, builder, current, revision)?.let {
                return@change pages to it
            }

            val updated = updated(current, builder)
            if (updated == current) {
                return@change pages to EditOutcome.NoChange(ref, RevisionId(pages.revisions.getValue(key)))
            }

            val previous = revision?.takeIf { current != null }?.let(::RevisionId)
            val saved = pages.saving(key, updated)
            saved.copy(edits = pages.edits + (ref to builder)) to
                EditOutcome.Saved(ref, RevisionId(saved.lastRevision), previous)
        }
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
                val body = heading + (text ?: builder.prependText.orEmpty() + builder.appendText.orEmpty())
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

    /**
     * Applies [transition] to the pages as they are, and returns what it says came of it.
     *
     * If another thread changed the pages while [transition] ran, it runs again on what they became, so no
     * change is lost. A transition that hands back the pages it was given only read them, and nothing is
     * written.
     */
    private inline fun <T> change(transition: (Pages) -> Pair<Pages, T>): T {
        while (true) {
            val before = state.load()
            val (after, result) = transition(before)
            if (after === before || state.compareAndSet(before, after)) return result
        }
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
        state.update { pages ->
            val text = pages.texts[key(from)] ?: return@update pages
            pages.copy(texts = pages.texts - key(from)).saving(key(to), text)
        }
        return to
    }

    override suspend fun delete(
        ref: PageRef,
        reason: String,
        deleteTalk: Boolean,
        watchlist: WatchMode,
    ) {
        val key = key(ref)
        state.update { pages ->
            val text = pages.texts[key] ?: return@update pages
            pages.copy(texts = pages.texts - key, deleted = pages.deleted + (key to text))
        }
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

    override suspend fun protections(refs: Collection<PageRef>): Map<PageRef, List<Protection>> {
        val protections = state.load().protections
        return refs.mapNotNull { ref -> protections[key(ref)]?.let { ref to it } }.toMap()
    }

    override suspend fun protect(
        ref: PageRef,
        protections: List<Protection>,
        reason: String,
        cascade: Boolean,
        watchlist: WatchMode,
    ) {
        val key = key(ref)
        state.update { it.copy(protections = it.protections + (key to protections)) }
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
        val original = originals[key]
        return change { pages ->
            val current = pages.revisions[key]?.let(::RevisionId)
            if (original == null || pages.texts[key] == original) {
                return@change pages to EditOutcome.NoChange(ref, current)
            }

            val saved = pages.saving(key, original)
            saved to EditOutcome.Saved(ref, RevisionId(saved.lastRevision), current)
        }
    }

    /** Deleted pages are kept, so undeleting one puts it back where it was. */
    override suspend fun undelete(
        ref: PageRef,
        reason: String,
        undeleteTalk: Boolean,
        watchlist: WatchMode,
    ) {
        val key = key(ref)
        state.update { pages ->
            val text = pages.deleted[key] ?: return@update pages
            pages.copy(texts = pages.texts + (key to text), deleted = pages.deleted - key)
        }
    }

    /** Watching changes nothing about a page, so the fake records it and moves on. */
    override suspend fun watch(refs: Collection<PageRef>, watch: Boolean, expiry: String?) {
        val keys = refs.map { key(it) }
        state.update { it.copy(watched = if (watch) it.watched + keys else it.watched - keys) }
    }

    /** Whether a page is on the fake watchlist. */
    public fun isWatched(title: String): Boolean = key(title) in state.load().watched

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
        val redirects =
            state.load().texts.mapNotNull { (key, text) ->
                val match = REDIRECT.find(text) ?: return@mapNotNull null
                val target = match.groupValues[1].trim()
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
     * Everything the fake holds, as one value that is replaced and never changed, so a thread reading it
     * cannot catch another halfway through an edit.
     */
    private data class Pages(
        val texts: Map<Key, String>,
        val revisions: Map<Key, Long>,
        val lastRevision: Long = INITIAL_REVISION,
        val protections: Map<Key, List<Protection>> = emptyMap(),
        val deleted: Map<Key, String> = emptyMap(),
        val watched: Set<Key> = emptySet(),
        val edits: List<Pair<PageRef, EditBuilder>> = emptyList(),
    ) {
        /** These pages with [key] saying [text], at the next revision. */
        fun saving(key: Key, text: String): Pages {
            val revision = lastRevision + 1
            return copy(
                texts = texts + (key to text),
                revisions = revisions + (key to revision),
                lastRevision = revision,
            )
        }
    }

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
