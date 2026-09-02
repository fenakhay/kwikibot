package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.raiseBadToken
import com.fenakhay.kwikibot.client.service.UserService
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.user.BlockRecord
import com.fenakhay.kwikibot.model.user.Contribution
import com.fenakhay.kwikibot.model.user.UserInfo
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.decode.ActivityDecoder
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.OptionSet
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.serialization.json.jsonObject

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
            continuation
                .list(
                    ApiRequest.of(
                        "query",
                        "list" to "users",
                        "ususers" to batch.joinToString("|"),
                        "usprop" to USER_PROPS,
                    ),
                    "users",
                )
                .collect { entry ->
                    val user = activity.decodeUser(entry)
                    if (user.name.isNotEmpty()) found[user.name] = user
                }
        }

        // Keyed by what was asked for: the wiki capitalizes names, and a caller should not have
        // to guess which spelling came back.
        return names
            .mapNotNull { name ->
                val user =
                    found[name] ?: found.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                user?.let { name to it }
            }
            .toMap()
    }

    override suspend fun current(): UserInfo {
        val response =
            transport
                .call(
                    ApiRequest.of(
                        "query",
                        "meta" to "userinfo",
                        "uiprop" to "groups|rights|editcount|registrationdate|blockinfo",
                    )
                )
                .throwOnError()

        val info =
            response["query"]?.jsonObject?.get("userinfo")?.jsonObject
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
    ): Flow<UserInfo> =
        continuation
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
        val flow =
            continuation
                .list(
                    ApiRequest.of(
                        "query",
                        "list" to "usercontribs",
                        "ucuser" to user,
                        "ucprop" to "ids|title|timestamp|comment|size|sizediff|flags|tags",
                        "ucnamespace" to
                            namespaces.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
                        "ucstart" to start?.let { MwTimestamp.format(it) },
                        "ucend" to end?.let { MwTimestamp.format(it) },
                        "ucshow" to show.toParam(),
                        "uctag" to tag,
                        "uclimit" to apiLimit(limit),
                    ),
                    "usercontribs",
                )
                .mapNotNull { activity.decodeContribution(it) }

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
            transport
                .call(
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
                    )
                )
                .also { it.raiseBadToken(TokenStore.USER_RIGHTS) }
                .throwOnError()
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
            transport
                .call(
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
                    )
                )
                .throwOnError()
        }
    }

    override suspend fun unblock(user: String, reason: String) {
        tokens.withFreshToken { token ->
            transport
                .call(
                    ApiRequest(
                        mapOf(
                            "action" to "unblock",
                            "user" to user,
                            "reason" to reason,
                            "assert" to "user",
                            "token" to token,
                        ),
                        RequestKind.WRITE,
                    )
                )
                .throwOnError()
        }
    }

    override fun blocks(
        users: Collection<String>,
        show: OptionSet,
        start: Instant?,
        end: Instant?,
        limit: Int?,
    ): Flow<BlockRecord> {
        val flow =
            continuation
                .list(
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
                )
                .map { activity.decodeBlockRecord(it) }

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
