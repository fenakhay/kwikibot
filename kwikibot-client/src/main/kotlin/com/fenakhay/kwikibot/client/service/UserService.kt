package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.user.BlockRecord
import com.fenakhay.kwikibot.model.user.Contribution
import com.fenakhay.kwikibot.model.user.UserInfo
import com.fenakhay.kwikibot.protocol.decode.OptionSet
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * The people editing a wiki.
 *
 * Reading is what a bot needs most: whether an account is blocked, what rights it holds, what it has edited.
 * The two write operations are here because they are user actions rather than page actions, and both need
 * rights an ordinary bot does not have.
 */
public interface UserService {

    /**
     * What the wiki knows about accounts, keyed by the name asked for.
     *
     * A name with no account behind it comes back with [UserInfo.isMissing] set rather than being absent,
     * since "no such user" is an answer a caller usually wants to see.
     */
    public suspend fun info(names: Collection<String>): Map<String, UserInfo>

    /**
     * The account this session is logged in as.
     *
     * The rights reported are this session's own, which is the only way to find out whether a bot may do
     * something before it tries.
     */
    public suspend fun current(): UserInfo

    /**
     * The edits a user has made, newest first.
     *
     * @param user the account or address to read.
     * @param namespaces only edits in these namespaces. Empty means all.
     * @param start where in time to begin.
     * @param end where in time to stop.
     * @param show filters applied by the wiki: `minor`, `new`, `top`, `patrolled` and `autopatrolled`, each
     *   requirable or excludable. Filtering here rather than after collection is the difference between
     *   fetching a user's page creations and fetching every edit they have made in order to discard most of
     *   them.
     * @param tag only edits carrying this change tag.
     * @param limit how many to emit before stopping.
     */
    public fun contributions(
        user: String,
        namespaces: Set<Namespace> = emptySet(),
        start: Instant? = null,
        end: Instant? = null,
        show: OptionSet = OptionSet(),
        tag: String? = null,
        limit: Int? = null,
    ): Flow<Contribution>

    /**
     * Blocks a user. Needs the `block` right.
     *
     * @param user the account or address to block.
     * @param reason the block summary, which the blocked user sees.
     * @param expiry when the block ends; [Expiry.Never] for an indefinite one.
     * @param anonymousOnly block only logged-out edits from this address, which is what makes a shared IP
     *   usable by its account holders.
     * @param preventAccountCreation stop new accounts being made from it.
     * @param autoBlock extend the block to addresses the account then uses.
     */
    public suspend fun block(
        user: String,
        reason: String,
        expiry: Expiry = Expiry.Never,
        anonymousOnly: Boolean = false,
        preventAccountCreation: Boolean = true,
        autoBlock: Boolean = true,
    )

    /** Lifts a block. Needs the `block` right. */
    public suspend fun unblock(user: String, reason: String = "")

    /**
     * The blocks currently in force, newest first.
     *
     * [UserInfo.block] answers whether one known account is blocked; this enumerates the list, so a bot can
     * find every blocked range or every block placed in the last hour without knowing the targets in advance.
     *
     * @param users restrict to these targets, which is cheaper than listing and filtering.
     * @param show which kinds to include: `account`, `ip`, `range` and `temp`, each requirable or excludable.
     * @param start where in time to begin.
     * @param end where in time to stop.
     * @param limit how many to emit before stopping.
     */
    public fun blocks(
        users: Collection<String> = emptyList(),
        show: OptionSet = OptionSet(),
        start: Instant? = null,
        end: Instant? = null,
        limit: Int? = null,
    ): Flow<BlockRecord>

    /**
     * Every account on the wiki, in name order.
     *
     * @param from the account name to start at.
     * @param to the account name to stop at.
     * @param prefix only accounts beginning with this.
     * @param group only accounts in these groups.
     * @param withEditsOnly skip accounts that have never edited, which on a large wiki is most of them.
     * @param excludeTemporary skip the auto-created accounts a wiki gives logged-out editors.
     * @param limit how many to emit before stopping.
     */
    public fun allUsers(
        from: String? = null,
        to: String? = null,
        prefix: String? = null,
        group: Set<String> = emptySet(),
        withEditsOnly: Boolean = false,
        excludeTemporary: Boolean = false,
        limit: Int? = null,
    ): Flow<UserInfo>

    /**
     * Adds a user to groups, removes them from others, or both. Needs the `userrights` right.
     *
     * Untested against a live wiki: this account does not hold the right.
     *
     * @param user the account to change.
     * @param add the groups to add.
     * @param remove the groups to take away.
     * @param reason the log summary.
     * @param expiry how long the added memberships last, as MediaWiki spells durations. `null` makes them
     *   permanent, which is what the wiki does unasked.
     */
    public suspend fun changeRights(
        user: String,
        add: Set<String> = emptySet(),
        remove: Set<String> = emptySet(),
        reason: String = "",
        expiry: String? = null,
    )
}
