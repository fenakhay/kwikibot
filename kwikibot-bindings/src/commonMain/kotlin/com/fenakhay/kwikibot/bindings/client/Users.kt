package com.fenakhay.kwikibot.bindings.client

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.user.BlockRecord
import com.fenakhay.kwikibot.model.user.Contribution
import com.fenakhay.kwikibot.model.user.UserInfo
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/** The edits [user] has made, in every namespace and with no filter unless told. */
@Suppress("LongParameterList")
public fun Wiki.contributionsWithDefaults(
    user: String,
    namespaces: Collection<Int>? = null,
    start: Instant? = null,
    end: Instant? = null,
    show: Collection<String>? = null,
    tag: String? = null,
    limit: Int? = null,
): Flow<Contribution> =
    users.contributions(user, namespacesOf(namespaces), start, end, optionSetOf(show), tag, limit)

/**
 * Blocks [user] with [reason]: for ever, accounts as well as addresses, account creation prevented and
 * autoblocking unless told.
 */
@Suppress("LongParameterList")
public suspend fun Wiki.blockWithDefaults(
    user: String,
    reason: String,
    expiry: Instant? = null,
    anonymousOnly: Boolean? = null,
    preventAccountCreation: Boolean? = null,
    autoBlock: Boolean? = null,
) {
    users.block(
        user,
        reason,
        expiry?.let(Expiry::At) ?: Expiry.Never,
        anonymousOnly ?: false,
        preventAccountCreation ?: true,
        autoBlock ?: true,
    )
}

/** The blocks in force: on everyone, with no filter unless told. */
public fun Wiki.blocksWithDefaults(
    users: Collection<String>? = null,
    show: Collection<String>? = null,
    start: Instant? = null,
    end: Instant? = null,
    limit: Int? = null,
): Flow<BlockRecord> = this.users.blocks(users?.toList().orEmpty(), optionSetOf(show), start, end, limit)

/** Every account, in every group, with or without edits, temporary ones included unless told. */
@Suppress("LongParameterList")
public fun Wiki.allUsersWithDefaults(
    from: String? = null,
    to: String? = null,
    prefix: String? = null,
    groups: Collection<String>? = null,
    withEditsOnly: Boolean? = null,
    excludeTemporary: Boolean? = null,
    limit: Int? = null,
): Flow<UserInfo> =
    users.allUsers(
        from,
        to,
        prefix,
        groups?.toSet().orEmpty(),
        withEditsOnly ?: false,
        excludeTemporary ?: false,
        limit,
    )

/** Adds [user] to the groups [add] and takes them from [remove], each none unless given. */
public suspend fun Wiki.changeRightsWithDefaults(
    user: String,
    add: Collection<String>? = null,
    remove: Collection<String>? = null,
    reason: String? = null,
    expiry: String? = null,
) {
    users.changeRights(user, add?.toSet().orEmpty(), remove?.toSet().orEmpty(), reason.orEmpty(), expiry)
}
