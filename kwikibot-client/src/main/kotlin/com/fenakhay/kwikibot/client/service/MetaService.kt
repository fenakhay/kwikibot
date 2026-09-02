package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.LanguageInfo
import kotlinx.serialization.json.JsonElement

/**
 * What a wiki says about itself beyond what a session needs to start.
 *
 * [SiteInfo] is fetched once at login because titles cannot be parsed without it. Everything else — interface
 * messages, magic words, statistics, the rest of `siprop` — is fetched when it is first asked for and kept
 * for the life of the session, because it changes when the wiki is reconfigured rather than during a run.
 */
public interface MetaService {

    /**
     * One `siprop` block, fetched on first use.
     *
     * The raw JSON, because the blocks have nothing in common and wrapping each in a type would be a type per
     * block for no gain. `null` means the wiki does not have that property.
     */
    public suspend fun property(name: String): JsonElement?

    /**
     * Interface messages, by key.
     *
     * A bot that has to recognise what a template produced, or write a summary in the wiki's own words, needs
     * the wiki's own strings rather than a guess at them.
     *
     * @param keys the message keys to fetch.
     * @param language the language to fetch them in. `null` uses the wiki's own.
     */
    public suspend fun messages(
        keys: Collection<String>,
        language: String? = null,
    ): Map<String, String>

    /** One interface message, or `null` if the wiki has no such message. */
    public suspend fun message(key: String, language: String? = null): String?

    /**
     * The magic words this wiki knows, by name, with every alias it accepts.
     *
     * `redirect` is `#REDIRECT` on en and `#REDIRECCIÓN` on es, and a bot that recognises only the English
     * spelling does not recognise redirects on most wikis.
     */
    public suspend fun magicWords(): Map<String, List<String>>

    /** The wiki's own counters: `pages`, `articles`, `edits`, `users`, `activeusers`. */
    public suspend fun statistics(): Map<String, Long>

    /**
     * What the wiki knows about languages, by code.
     *
     * The wiki is the authority rather than the JVM's locale data: it carries codes the JVM has never heard
     * of, and its fallback chain is the one a bot has to follow when choosing which language to address
     * someone in.
     *
     * @param codes the languages to ask about. Empty asks about every one the wiki knows, which is several
     *   hundred and worth asking for only once.
     */
    public suspend fun languages(codes: Collection<LangCode> = emptyList()): Map<LangCode, LanguageInfo>

    /**
     * Adds or removes change tags on revisions, recent changes or log entries.
     *
     * Untested against a live wiki: needs the `changetags` right, and `applychangetags` for the tags
     * themselves.
     *
     * @param add the tags to apply.
     * @param remove the tags to take off.
     * @param revisions revision ids to tag. At least one of these, [recentChanges] or [logEntries] is
     *   required.
     * @param recentChanges recent-changes ids to tag.
     * @param logEntries log ids to tag.
     * @param reason why, which is recorded in the tag log.
     */
    public suspend fun applyTags(
        add: Set<String> = emptySet(),
        remove: Set<String> = emptySet(),
        revisions: Collection<Long> = emptyList(),
        recentChanges: Collection<Long> = emptyList(),
        logEntries: Collection<Long> = emptyList(),
        reason: String = "",
    )

    /**
     * Defines, retires or removes a change tag. Needs the `managechangetags` right.
     *
     * Untested against a live wiki. Distinct from [applyTags], which puts an existing tag on an edit: this
     * decides which tags exist at all.
     */
    public suspend fun manageTag(tag: String, operation: TagOperation, reason: String = "")
}

/** What [MetaService.manageTag] should do to a tag definition. */
public enum class TagOperation(internal val apiValue: String) {
    /** Define a new tag, which users may then apply. */
    CREATE("create"),

    /** Delete a tag and remove it from everything carrying it. */
    DELETE("delete"),

    /** Allow the tag to be applied again. */
    ACTIVATE("activate"),

    /** Stop the tag being applied, without touching what already carries it. */
    DEACTIVATE("deactivate"),
}
