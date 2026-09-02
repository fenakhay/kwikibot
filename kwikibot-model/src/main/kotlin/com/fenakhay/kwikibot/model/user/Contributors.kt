package com.fenakhay.kwikibot.model.user

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
 * The logged-out editors are a count rather than a list: the API reports them that way, and a bot notifying
 * contributors has nobody to notify for them.
 */
public data class Contributors(
    /** The named accounts that have edited the page. */
    val users: List<Contributor> = emptyList(),
    /** How many edits came from addresses rather than accounts. */
    val anonymous: Int = 0,
)
