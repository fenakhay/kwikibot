package com.fenakhay.kwikibot.model

/** Which way a language is written. */
public enum class TextDirection {
    /** Written left to right, which most languages are. */
    LEFT_TO_RIGHT,

    /** Written right to left: Arabic, Hebrew, Persian. */
    RIGHT_TO_LEFT;

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
 * The wiki is the authority here rather than the JVM's own locale data: MediaWiki carries language codes the
 * JVM has never heard of, and its [fallbacks] are the chain its own interface follows, which is what a bot
 * has to follow too when it picks a message to leave someone.
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
