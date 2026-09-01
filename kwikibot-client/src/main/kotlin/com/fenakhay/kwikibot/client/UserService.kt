package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.BlockRecord
import com.fenakhay.kwikibot.model.Contribution
import com.fenakhay.kwikibot.model.Expiry
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.Namespace
import com.fenakhay.kwikibot.model.UserInfo
import com.fenakhay.kwikibot.net.ApiRequest
import com.fenakhay.kwikibot.net.MediaWikiTransport
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.protocol.ActivityDecoder
import com.fenakhay.kwikibot.protocol.Continuation
import com.fenakhay.kwikibot.protocol.OptionSet
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.serialization.json.jsonObject

/**
 * The people editing a wiki.
 *
 * Reading is what a bot needs most: whether an account is blocked, what rights it holds, what it
 * has edited. The two write operations are here because they are user actions rather than page
 * actions, and both need rights an ordinary bot does not have.
 */
public interface UserService {

    /**
     * What the wiki knows about accounts, keyed by the name asked for.
     *
     * A name with no account behind it comes back with [UserInfo.isMissing] set rather than
     * being absent, since "no such user" is an answer a caller usually wants to see.
     */
    public suspend fun info(names: Collection<String>): Map<String, UserInfo>

    /**
     * The account this session is logged in as.
     *
     * The rights reported are this session's own, which is the only way to find out whether a
     * bot may do something before it tries.
     */
    public suspend fun current(): UserInfo

    /**
     * The edits a user has made, newest first.
     *
     * @param user the account or address to read.
     * @param namespaces only edits in these namespaces. Empty means all.
     * @param start where in time to begin.
     * @param end where in time to stop.
     * @param show filters applied by the wiki: `minor`, `new`, `top`, `patrolled` and
     *   `autopatrolled`, each requirable or excludable. Filtering here rather than after
     *   collection is the difference between fetching a user's page creations and fetching every
     *   edit they have made in order to discard most of them.
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
     * @param anonymousOnly block only logged-out edits from this address, which is what makes a
     *   shared IP usable by its account holders.
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
     * [UserInfo.block] answers whether one known account is blocked; this enumerates the list, so
     * a bot can find every blocked range or every block placed in the last hour without knowing
     * the targets in advance.
     *
     * @param users restrict to these targets, which is cheaper than listing and filtering.
     * @param show which kinds to include: `account`, `ip`, `range` and `temp`, each requirable
     *   or excludable.
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
     * @param withEditsOnly skip accounts that have never edited, which on a large wiki is
     *   most of them.
     * @param excludeTemporary skip the auto-created accounts a wiki gives logged-out
     *   editors.
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
     * @param expiry how long the added memberships last, as MediaWiki spells durations.
     *   `null` makes them permanent, which is what the wiki does unasked.
     */
    public suspend fun changeRights(
        user: String,
        add: Set<String> = emptySet(),
        remove: Set<String> = emptySet(),
        reason: String = "",
        expiry: String? = null,
    )

}

internal class ApiUserService(
    private val transport: MediaWikiTransport,
    private val tokens: TokenStore,
    private val activity: ActivityDecoder,
    private val batchSize: Int = DEFAULT_BATCH,
) : UserService {

    private val continuation = Continuation(transport)

    override suspend fun info(names: Collection<String>): Map<String, UserInfo> {
        if (names.isEmpty()) return emptyMap()

        val found = mutableMapOf<String, UserInfo>()
        for (batch in names.distinct().chunked(batchSize)) {
            continuation.list(
                ApiRequest.of(
                    "query",
                    "list" to "users",
                    "ususers" to batch.joinToString("|"),
                    "usprop" to USER_PROPS,
                ),
                "users",
            ).collect { entry ->
                val user = activity.decodeUser(entry)
                if (user.name.isNotEmpty()) found[user.name] = user
            }
        }

        // Keyed by what was asked for: the wiki capitalizes names, and a caller should not have
        // to guess which spelling came back.
        return names.mapNotNull { name ->
            val user = found[name] ?: found.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            user?.let { name to it }
        }.toMap()
    }

    override suspend fun current(): UserInfo {
        val response = transport.call(
            ApiRequest.of(
                "query",
                "meta" to "userinfo",
                "uiprop" to "groups|rights|editcount|registrationdate|blockinfo",
            ),
        ).throwOnError()

        val info = response["query"]?.jsonObject?.get("userinfo")?.jsonObject
            ?: return UserInfo(name = "", isAnonymous = true)
        return activity.decodeCurrentUser(info)
    }

    override fun allUsers(
        from: String?,
        to: String?,
        prefix: String?,
        group: Set<String>,
        withEditsOnly: Boolean,
        excludeTemporary: Boolean,
        limit: Int?,
    ): Flow<UserInfo> = continuation
        .list(
            ApiRequest.of(
                "query",
                "list" to "allusers",
                "aufrom" to from,
                "auto" to to,
                "auprefix" to prefix,
                "augroup" to group.takeIf { it.isNotEmpty() }?.joinToString("|"),
                "auwitheditsonly" to if (withEditsOnly) "1" else null,
                "auexcludetemp" to if (excludeTemporary) "1" else null,
                "auprop" to USER_PROPS,
                "aulimit" to apiLimit(limit),
            ),
            "allusers",
        )
        .map { activity.decodeUser(it) }
        .let { if (limit == null) it else it.take(limit) }

    override fun contributions(
        user: String,
        namespaces: Set<Namespace>,
        start: Instant?,
        end: Instant?,
        show: OptionSet,
        tag: String?,
        limit: Int?,
    ): Flow<Contribution> {
        val flow = continuation.list(
            ApiRequest.of(
                "query",
                "list" to "usercontribs",
                "ucuser" to user,
                "ucprop" to "ids|title|timestamp|comment|size|sizediff|flags|tags",
                "ucnamespace" to namespaces.takeIf { it.isNotEmpty() }
                    ?.joinToString("|") { it.id.toString() },
                "ucstart" to start?.let { MwTimestamp.format(it) },
                "ucend" to end?.let { MwTimestamp.format(it) },
                "ucshow" to show.toParam(),
                "uctag" to tag,
                "uclimit" to apiLimit(limit),
            ),
            "usercontribs",
        ).mapNotNull { activity.decodeContribution(it) }

        return if (limit == null) flow else flow.take(limit)
    }

    override suspend fun changeRights(
        user: String,
        add: Set<String>,
        remove: Set<String>,
        reason: String,
        expiry: String?,
    ) {
        require(add.isNotEmpty() || remove.isNotEmpty()) { "changeRights must add or remove a group" }
        require((add intersect remove).isEmpty()) { "cannot add and remove the same group" }

        // Group membership has its own token type. A CSRF token is refused here.
        tokens.withFreshToken(TokenStore.USER_RIGHTS) { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "userrights")
                        put("user", user)
                        if (add.isNotEmpty()) put("add", add.joinToString("|"))
                        if (remove.isNotEmpty()) put("remove", remove.joinToString("|"))
                        expiry?.let { put("expiry", it) }
                        put("reason", reason)
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).also { it.raiseBadToken(TokenStore.USER_RIGHTS) }.throwOnError()
        }
    }

    override suspend fun block(
        user: String,
        reason: String,
        expiry: Expiry,
        anonymousOnly: Boolean,
        preventAccountCreation: Boolean,
        autoBlock: Boolean,
    ) {
        tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    buildMap {
                        put("action", "block")
                        put("user", user)
                        put("reason", reason)
                        put("expiry", expiry.toString())
                        // Sent either way: the default differs between MediaWiki versions, and
                        // the difference is whether named accounts on a shared IP keep editing.
                        put("anononly", if (anonymousOnly) "1" else "0")
                        if (preventAccountCreation) put("nocreate", "1")
                        if (autoBlock) put("autoblock", "1")
                        put("assert", "user")
                        put("token", token)
                    },
                    RequestKind.WRITE,
                ),
            ).throwOnError()
        }
    }

    override suspend fun unblock(user: String, reason: String) {
        tokens.withFreshToken { token ->
            transport.call(
                ApiRequest(
                    mapOf(
                        "action" to "unblock",
                        "user" to user,
                        "reason" to reason,
                        "assert" to "user",
                        "token" to token,
                    ),
                    RequestKind.WRITE,
                ),
            ).throwOnError()
        }
    }

    override fun blocks(
        users: Collection<String>,
        show: OptionSet,
        start: Instant?,
        end: Instant?,
        limit: Int?,
    ): Flow<BlockRecord> {
        val flow = continuation.list(
            ApiRequest.of(
                "query",
                "list" to "blocks",
                "bkusers" to users.takeIf { it.isNotEmpty() }?.joinToString("|"),
                "bkshow" to show.toParam(),
                "bkstart" to start?.let { MwTimestamp.format(it) },
                "bkend" to end?.let { MwTimestamp.format(it) },
                "bkprop" to BLOCK_PROPS,
                "bklimit" to apiLimit(limit),
            ),
            "blocks",
        ).map { activity.decodeBlockRecord(it) }

        return if (limit == null) flow else flow.take(limit)
    }

    private fun apiLimit(limit: Int?): String =
        if (limit != null && limit < MAX_BATCH) limit.toString() else "max"

    private companion object {
        const val DEFAULT_BATCH = 50
        const val MAX_BATCH = 500
        const val USER_PROPS = "blockinfo|groups|rights|editcount|registration|emailable|gender"
        const val BLOCK_PROPS = "id|user|by|timestamp|expiry|reason|flags"
    }
}
