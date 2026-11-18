package com.fenakhay.kwikibot.client.service

import com.fenakhay.kwikibot.client.internal.wire.MediaWikiParameter
import com.fenakhay.kwikibot.model.WikiError
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

    /**
     * This account's preferences, by name, as the wiki holds them.
     *
     * Every value is text: a number as its digits, and a box as `1` when ticked and `0` when not, whether the
     * wiki keeps it as a number or as a boolean. A preference with no value reads as the empty string. A
     * session that is not logged in reads the wiki's defaults. Asked anew each time, never from a cache, so a
     * read after [setPreferences] sees what it saved.
     *
     * @throws UnsupportedOperationException from an implementation that cannot read preferences.
     */
    public suspend fun preferences(): Map<String, String> =
        throw UnsupportedOperationException("this UserService cannot read preferences")

    /**
     * Changes this account's preferences. Needs the `editmyoptions` right, which a bot password has only with
     * its `editmyoptions` grant.
     *
     * The changes go as one write, or as one per fifty when there are more, fifty being as many as MediaWiki
     * takes from every account. A value holding `|` is sent the way MediaWiki takes it, behind the unit
     * separator U+001F rather than split at the bar.
     *
     * @param changes the new value of each preference, by name. A `null` value puts that preference back to
     *   the wiki's default. Empty asks the wiki nothing.
     * @return the preferences the wiki refused, by name, with what it said about each: a name it does not
     *   know, a value it does not take, or one only its own preferences page may set. MediaWiki saves the
     *   rest regardless and says so only in a warning, which this reads by finding the name in it. Empty when
     *   it took every change.
     * @throws IllegalArgumentException for a name that is empty or holds `=`, or a name or value that holds
     *   U+001F, none of which MediaWiki can be sent.
     * @throws WikiError.Auth.PermissionDenied when the account may not change its preferences.
     * @throws UnsupportedOperationException from an implementation that cannot change preferences.
     */
    public suspend fun setPreferences(changes: Map<String, String?>): Map<String, String> =
        throw UnsupportedOperationException("this UserService cannot change preferences")

    /**
     * Puts this account's preferences back to the wiki's defaults. Needs the `editmyoptions` right.
     *
     * @param kinds which kinds of preference to reset. Empty, the default, resets every kind, the values user
     *   scripts keep under `userjs-` included.
     * @throws UnsupportedOperationException from an implementation that cannot reset preferences.
     */
    public suspend fun resetPreferences(kinds: Set<PreferenceKind> = emptySet()): Unit =
        throw UnsupportedOperationException("this UserService cannot reset preferences")

    /**
     * Emails a user through the wiki, from this account.
     *
     * This sends a real email to a real person, from this account, with the address the account has confirmed
     * on the wiki given for replies. Nothing about it is a dry run, and it cannot be taken back. Wikis
     * rate-limit it: MediaWiki's own default is twenty a day for an account and five for a new one, and a
     * wiki may set fewer. It is a write, paced as every other write is, and a bot should email nobody in a
     * loop without a person having decided it.
     *
     * @param target the account to write to.
     * @param subject the subject line.
     * @param text the body, as plain text. The wiki adds a footer naming this account.
     * @param ccMe whether to send this account a copy as well.
     * @return [EmailOutcome.Sent] once the wiki has handed the message to its mail server, or
     *   [EmailOutcome.Undeliverable] when [target] has no account, no confirmed address, or takes no email
     *   from this account. Nothing was sent then.
     * @throws WikiError.Api with the code `usermaildisabled` from a wiki that sends no email between users,
     *   `cantsend` when this account is not logged in or has no confirmed address of its own, or the wiki's
     *   code for it when its mail server would not take the message.
     * @throws WikiError.Auth.AccountBlocked when the account is blocked from sending email.
     * @throws WikiError.Auth.MissingRight naming `sendemail` when the session lacks that right, which a bot
     *   password has only with its `sendemail` grant. The wiki itself says only `permissiondenied`, so the
     *   session's rights are asked for to tell this apart.
     * @throws WikiError.Auth.PermissionDenied when the wiki refuses it for another reason.
     * @throws WikiError.Transport.RateLimited once the wiki's email rate limit is reached.
     * @throws UnsupportedOperationException from an implementation that cannot send email.
     */
    public suspend fun email(
        target: String,
        subject: String,
        text: String,
        ccMe: Boolean = false,
    ): EmailOutcome = throw UnsupportedOperationException("this UserService cannot send email")
}

/** A kind of preference, as [UserService.resetPreferences] resets them. */
@MediaWikiParameter("options", "resetkinds")
public enum class PreferenceKind(internal val apiValue: String) {
    /** A preference the wiki's preferences page shows and validates. */
    REGISTERED("registered"),

    /** One box of a preference that is a set of boxes, such as which namespaces to search. */
    REGISTERED_MULTISELECT("registered-multiselect"),

    /** One cell of a preference that is a grid of boxes, such as which notifications go where. */
    REGISTERED_CHECKMATRIX("registered-checkmatrix"),

    /** A value a user script keeps, named `userjs-` and something. */
    USERJS("userjs"),

    /**
     * A preference the wiki keeps but only its own pages set, such as the date of the last password change.
     */
    SPECIAL("special"),

    /** A value nothing on the wiki reads any longer. */
    UNUSED("unused"),
}

/** How the wiki answered [UserService.email]. */
public sealed interface EmailOutcome {

    /** The wiki took the message and handed it to its mail server. */
    public data object Sent : EmailOutcome

    /** The message could not go to that account, and nothing was sent. */
    public data class Undeliverable(
        /** Why, as far as this library can tell from the wiki's code. */
        val reason: UndeliverableReason,
        /** The wiki's code for the refusal, which differs between MediaWiki releases. */
        val code: String,
        /** Its text for a person. */
        val detail: String,
    ) : EmailOutcome
}

/** Why [UserService.email] could not reach an account. */
public enum class UndeliverableReason {
    /**
     * There is no account by that name, or the name cannot be one: `emailnotarget` or `baduser_target`, and
     * `notarget` on older releases.
     */
    NO_SUCH_USER,

    /** The account has no confirmed address: `noemailtext`, and `noemail` on older releases. */
    NO_ADDRESS,

    /**
     * The account takes no email from other users, or none from this one or from new accounts:
     * `nowikiemailtext`, and `nowikiemail` on older releases.
     */
    NOT_ACCEPTED,
}
