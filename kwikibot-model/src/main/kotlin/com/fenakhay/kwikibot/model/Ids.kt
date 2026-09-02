package com.fenakhay.kwikibot.model

/**
 * A MediaWiki language/site code, such as `en` or `zh-min-nan`.
 *
 * Wrapped so it cannot be confused with a family name, a project name or a raw title.
 */
@JvmInline
public value class LangCode(
    /** The code as the wiki's interwiki map spells it, not always an ISO tag. */
    public val code: String
) {
    init {
        require(code.isNotBlank()) { "language code must not be blank" }
    }

    override fun toString(): String = code
}

/** A page id, unique within one wiki. */
@JvmInline
public value class PageId(
    /** The wiki's own id, stable across renames where a title is not. */
    public val value: Long
) {
    override fun toString(): String = value.toString()
}

/** A revision id, unique within one wiki. */
@JvmInline
public value class RevisionId(
    /** The revision's own id, which is never reused. */
    public val value: Long
) {
    override fun toString(): String = value.toString()
}
