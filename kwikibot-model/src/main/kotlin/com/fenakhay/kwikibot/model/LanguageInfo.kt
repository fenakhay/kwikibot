package com.fenakhay.kwikibot.model

/** Which way a language is written. */
public enum class TextDirection {
    /** Written left to right, which most languages are. */
    LEFT_TO_RIGHT,

    /** Written right to left: Arabic, Hebrew, Persian. */
    RIGHT_TO_LEFT,
    ;

    /** Reading the direction a wiki reports. */
    public companion object {
        /** Reads the `dir` a wiki reports, defaulting to left-to-right when it says nothing. */
        public fun of(value: String?): TextDirection =
            if (value.equals("rtl", ignoreCase = true)) RIGHT_TO_LEFT else LEFT_TO_RIGHT
    }
}

/**
 * What a wiki knows about a language.
 *
 * The wiki is the authority here rather than the JVM's own locale data: MediaWiki carries language
 * codes the JVM has never heard of, and its [fallbacks] are the chain its own interface follows,
 * which is what a bot has to follow too when it picks a message to leave someone.
 */
public data class LanguageInfo(
    /** The wiki's own code for the language. */
    val code: LangCode,
    /** The name in the wiki's content language, `French`. */
    val name: String,
    /** The name in the language itself, `français`. */
    val autonym: String,
    /** Which way it is written, which decides how a bot should wrap text in it. */
    val direction: TextDirection,
    /** The languages the wiki falls back to when this one has no translation, in order. */
    val fallbacks: List<LangCode> = emptyList(),
    /** The IETF tag, which is not always the wiki's own code. */
    val bcp47: String? = null,
)

/**
 * Where a wiki's files come from.
 *
 * A wiki usually has two: its own, and a shared one. A file bot has to know which, since a file
 * held on Commons cannot be edited or deleted through the wiki displaying it.
 */
public data class FileRepository(
    /** The internal name, `local` or `shared`. */
    val name: String,
    /** The name meant for a reader, `Wikimedia Commons`. */
    val displayName: String,
    /** Whether the files live on this wiki. */
    val isLocal: Boolean,
    /** Where a file's own page lives, when the wiki said. */
    val url: String? = null,
    /** Where the files themselves are served from. */
    val rootUrl: String? = null,
    /** Whether this session may upload here. */
    val canUpload: Boolean = false,
)

/** Someone who has edited a page. */
public data class Contributor(
    /** The account name. */
    val name: String,
    /** The account's id, zero where the wiki did not report one. */
    val id: Long,
)

/**
 * Who has edited a page.
 *
 * The logged-out editors are a count rather than a list: the API reports them that way, and a bot
 * notifying contributors has nobody to notify for them.
 */
public data class Contributors(
    /** The named accounts that have edited the page. */
    val users: List<Contributor> = emptyList(),
    /** How many edits came from addresses rather than accounts. */
    val anonymous: Int = 0,
)
