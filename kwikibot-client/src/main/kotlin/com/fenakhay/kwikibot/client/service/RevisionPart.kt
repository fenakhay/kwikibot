package com.fenakhay.kwikibot.client.service

/**
 * A part of a revision that can be hidden from readers independently of the others.
 *
 * Hiding the content leaves the fact of the edit visible; hiding the user leaves the edit attributable to
 * nobody. They are separate because the reasons for hiding them are: a defamatory summary and a copyright
 * violation in the text call for different answers.
 */
public enum class RevisionPart(internal val apiValue: String) {
    /** The wikitext of the revision. */
    CONTENT("content"),

    /** The edit summary. */
    COMMENT("comment"),

    /** Who made the edit. */
    USER("user"),
}
