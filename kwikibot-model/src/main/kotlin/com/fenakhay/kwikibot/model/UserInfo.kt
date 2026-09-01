package com.fenakhay.kwikibot.model

import kotlin.time.Instant

/**
 * What a wiki knows about one account.
 *
 * A snapshot, like everything else in this module: rights change, blocks expire, and a value
 * read an hour ago is a record of what was true then.
 */
public data class UserInfo(
    /** The account name, spelled as the wiki spells it rather than as it was asked for. */
    val name: String,
    /** The account id, or `null` for an anonymous editor, who has none. */
    val id: Long? = null,
    /** Groups the account belongs to. Rights come from these, so read [rights] to decide. */
    val groups: List<String> = emptyList(),
    /** What the account may do. The direct answer to whether an action will be allowed. */
    val rights: List<String> = emptyList(),
    /** How many edits the account has made, across all namespaces. */
    val editCount: Long = 0,
    /** When the account was created, absent for accounts older than the wiki's records. */
    val registration: Instant? = null,
    /** The block in force, or `null` if the account is not blocked. */
    val block: BlockInfo? = null,
    /** Whether the name is an IP address rather than an account. */
    val isAnonymous: Boolean = false,
    /**
     * Whether this is a temporary account.
     *
     * A wiki with temporary accounts enabled auto-creates one for an editor who is not logged
     * in, in place of recording an IP address. It has an id, an edit count and a talk page, so
     * it is neither anonymous nor a person's account: it belongs to a browser session and its
     * holder will most likely never see a message left on it.
     */
    val isTemporary: Boolean = false,
    /** Whether there is no such account. */
    val isMissing: Boolean = false,
    /** The grammatical gender the account chose, which some interface messages need. */
    val gender: String? = null,
    /** Whether this user can be sent email through the wiki. */
    val emailable: Boolean = false,
) {
    /** Whether the account is currently blocked. */
    val isBlocked: Boolean get() = block != null

    /** Whether the user holds a right. */
    public fun hasRight(right: String): Boolean = right in rights

    /** Whether the user is in a group. Prefer [hasRight]: groups are how rights are granted. */
    public fun inGroup(group: String): Boolean = group in groups

    /**
     * Whether the editor is a person's own account.
     *
     * False for an IP address and for a temporary account alike, which is the distinction a bot
     * needs before treating an edit history as one person's.
     */
    val isRegistered: Boolean get() = !isAnonymous && !isTemporary && !isMissing
}

/**
 * Whether a wiki auto-creates temporary accounts, and how their names are shaped.
 *
 * Reported by `siprop=autocreatetempuser`. The patterns are the wiki's own — `~2$1` on Wikimedia
 * wikis, where `$1` stands for the rest of the name — so a bare username from a recent change or
 * a signature can be classified without hardcoding a shape that differs per wiki.
 */
public data class TempAccountConfig(
    /** Whether the wiki auto-creates temporary accounts at all. */
    val enabled: Boolean = false,
    /** Name patterns, each with `$1` marking where the variable part goes. */
    val matchPatterns: List<String> = emptyList(),
) {

    /** Whether [name] is a temporary account on this wiki. */
    public fun matches(name: String): Boolean =
        enabled && matchPatterns.any { pattern -> name.fitsPattern(pattern) }

    private fun String.fitsPattern(pattern: String): Boolean {
        val marker = pattern.indexOf(PLACEHOLDER)
        if (marker < 0) return this == pattern

        val prefix = pattern.take(marker)
        val suffix = pattern.drop(marker + PLACEHOLDER.length)

        // The variable part must not be empty: the prefix alone is somebody else's account name.
        return length > prefix.length + suffix.length &&
            startsWith(prefix) &&
            endsWith(suffix)
    }

    /** The configuration a wiki without temporary accounts has. */
    public companion object {
        private const val PLACEHOLDER = "$1"

        /** A wiki that does not auto-create temporary accounts. */
        public val DISABLED: TempAccountConfig = TempAccountConfig()
    }
}

/** The block in force on an account. */
public data class BlockInfo(
    /** The block's own id, which is what an unblock names. */
    val id: Long? = null,
    /** Who imposed it. */
    val by: String? = null,
    /** The reason given, which readers of a talk page will see. */
    val reason: String? = null,
    /** When it started. */
    val since: Instant? = null,
    /** When the block ends. `null` means it does not. */
    val expiry: Instant? = null,
    /** Whether the block covers only some pages or actions rather than the whole wiki. */
    val isPartial: Boolean = false,
) {
    /** Whether the block never expires. */
    val isInfinite: Boolean get() = expiry == null
}

/**
 * One entry in a wiki's block list.
 *
 * Distinct from [BlockInfo], which describes the block found *on* a known user and therefore
 * needs no target. An entry in the list has to name who or what is blocked, and that is not
 * always an account: a block can cover a single address or a whole range.
 */
public data class BlockRecord(
    /** The block itself, with its reason and expiry. */
    val info: BlockInfo,
    /** The account, address or range blocked, or `null` if the target is hidden. */
    val target: String?,
    /** Whether the wiki applied this itself, following an address used by a blocked account. */
    val isAutomatic: Boolean = false,
) {
    /** Whether the target is an address range rather than one address or an account. */
    val isRange: Boolean get() = target?.contains('/') == true
}

/** One edit by a user, as the contributions list reports it. */
public data class Contribution(
    /** The page edited. */
    val page: PageRef,
    /** The revision the edit produced, carrying its author, size and summary. */
    val revision: Revision,
    /** Whether this edit created the page. */
    val isNew: Boolean = false,
    /** Whether it is still the page's newest revision. */
    val isTop: Boolean = false,
    /** How many bytes the page gained, negative for a removal. */
    val sizeChange: Int = 0,
)
