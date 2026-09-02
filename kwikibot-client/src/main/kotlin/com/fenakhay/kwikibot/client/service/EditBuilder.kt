package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.model.RevisionId

/**
 * The parameters of one edit.
 *
 * [baseRevision] is what lets the wiki detect an edit conflict; leaving it unset means "apply this regardless
 * of what the page says now", which is rarely what a bot wants.
 *
 * Normally reached through `pages.edit { }` rather than constructed directly; the constructor is public so
 * that [PageService] can be implemented outside this library, which is what test doubles need.
 */
@KwikibotDsl
public class EditBuilder {

    private companion object {
        const val NEW_SECTION = "new"
    }

    /** The full new wikitext. Exactly one of this, [appendText] or [prependText] is required. */
    public var text: String? = null

    /** Text to add at the end of the page instead of replacing it. */
    public var appendText: String? = null

    /** Text to add at the start of the page instead of replacing it. */
    public var prependText: String? = null

    /** The edit summary. */
    public var summary: String = ""

    /** Whether to mark the edit minor. */
    public var minor: Boolean = false

    /** Whether to flag the edit as a bot edit, hiding it from default recent-changes views. */
    public var bot: Boolean = true

    /** The revision the edit was computed from, so the wiki can detect a conflict. */
    public var baseRevision: RevisionId? = null

    /** Refuse to create the page if it does not exist. */
    public var noCreate: Boolean = false

    /** Refuse to edit the page if it already exists. */
    public var createOnly: Boolean = false

    /** Change tags to attach to the edit. */
    public var tags: List<String> = emptyList()

    /**
     * Which section to edit: a section number, or `"new"` to start one.
     *
     * `"new"` with a [sectionTitle] is how a message is left on a talk page. A number edits one section,
     * which also narrows what an edit conflict can be about.
     */
    public var section: String? = null

    /** The heading for a `section = "new"` edit. Meaningless on any other section. */
    public var sectionTitle: String? = null

    /** What the edit should do to the account's watchlist. */
    public var watchlist: WatchMode = WatchMode.PREFERENCES

    /** How long to watch the page for, when [watchlist] adds it. An expiry-less watch is forever. */
    public var watchlistExpiry: String? = null

    /**
     * Checks the builder describes an edit the wiki could accept.
     *
     * Called before a token is fetched: a contradictory edit is a mistake in the calling code, and finding it
     * should not cost a request.
     */
    internal fun validate() {
        require(body().isNotEmpty()) { "an edit needs text, appendText or prependText" }
        require(body().size == 1) { "set only one of text, appendText and prependText" }
        require(!(noCreate && createOnly)) { "noCreate and createOnly contradict each other" }
        require(sectionTitle == null || section == NEW_SECTION) {
            "sectionTitle applies only to section = \"$NEW_SECTION\""
        }
    }

    private fun body(): List<Pair<String, String>> =
        listOfNotNull(
            text?.let { "text" to it },
            appendText?.let { "appendtext" to it },
            prependText?.let { "prependtext" to it },
        )

    internal fun parameters(title: String, token: String): Map<String, String> {
        validate()

        return buildMap {
            put("action", "edit")
            put("title", title)
            putAll(body())
            put("summary", summary)
            putFlags()
            putPlacement()
            baseRevision?.let { put("baserevid", it.value.toString()) }
            // Catches a session that died between reading and saving, so the edit is not made
            // anonymously from the bot's IP.
            put("assert", "user")
            put("token", token)
        }
    }

    private fun MutableMap<String, String>.putFlags() {
        if (minor) put("minor", "1") else put("notminor", "1")
        if (bot) put("bot", "1")
        if (noCreate) put("nocreate", "1")
        if (createOnly) put("createonly", "1")
        if (tags.isNotEmpty()) put("tags", tags.joinToString("|"))
    }

    /** Where the text lands, and what that does to the watchlist. */
    private fun MutableMap<String, String>.putPlacement() {
        section?.let { put("section", it) }
        sectionTitle?.let { put("sectiontitle", it) }
        watchlist.applyTo(this)
        watchlistExpiry?.let { put("watchlistexpiry", it) }
    }
}
