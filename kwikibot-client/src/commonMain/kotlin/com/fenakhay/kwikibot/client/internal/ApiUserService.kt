package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.internal.wire.Registry
import com.fenakhay.kwikibot.client.raiseBadToken
import com.fenakhay.kwikibot.client.service.EmailOutcome
import com.fenakhay.kwikibot.client.service.PreferenceKind
import com.fenakhay.kwikibot.client.service.UndeliverableReason
import com.fenakhay.kwikibot.client.service.UserService
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.title.Namespace
import com.fenakhay.kwikibot.model.user.BlockRecord
import com.fenakhay.kwikibot.model.user.Contribution
import com.fenakhay.kwikibot.model.user.UserInfo
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ApiFailure
import com.fenakhay.kwikibot.protocol.decode.ActivityDecoder
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.OptionSet
import com.fenakhay.kwikibot.protocol.decode.array
import com.fenakhay.kwikibot.protocol.decode.obj
import com.fenakhay.kwikibot.protocol.decode.objects
import com.fenakhay.kwikibot.protocol.decode.string
import com.fenakhay.kwikibot.protocol.throwOnError
import com.fenakhay.kwikibot.protocol.warnings
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
                        "usprop" to Registry.USERS.joined,
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
                        "uiprop" to Registry.CURRENT_USER.joined,
                    )
                )
                .throwOnError()

        val info = response.obj("query")?.obj("userinfo") ?: return UserInfo(name = "", isAnonymous = true)
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
                    "auprop" to Registry.ALL_USERS.joined,
                    "aulimit" to limitParam(limit),
                ),
                "allusers",
            )
            .map { activity.decodeUser(it) }
            .limitedTo(limit)

    override fun contributions(
        user: String,
        namespaces: Set<Namespace>,
        start: Instant?,
        end: Instant?,
        show: OptionSet,
        tag: String?,
        limit: Int?,
    ): Flow<Contribution> =
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
                    "uclimit" to limitParam(limit),
                ),
                "usercontribs",
            )
            .mapNotNull { activity.decodeContribution(it) }
            .limitedTo(limit)

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
    ): Flow<BlockRecord> =
        continuation
            .list(
                ApiRequest.of(
                    "query",
                    "list" to "blocks",
                    "bkusers" to users.takeIf { it.isNotEmpty() }?.joinToString("|"),
                    "bkshow" to show.toParam(),
                    "bkstart" to start?.let { MwTimestamp.format(it) },
                    "bkend" to end?.let { MwTimestamp.format(it) },
                    "bkprop" to Registry.BLOCKS.joined,
                    "bklimit" to limitParam(limit),
                ),
                "blocks",
            )
            .map { activity.decodeBlockRecord(it) }
            .limitedTo(limit)

    override suspend fun preferences(): Map<String, String> {
        val response =
            transport
                .call(
                    ApiRequest(
                        mapOf(
                            "action" to "query",
                            "meta" to "userinfo",
                            "uiprop" to Registry.PREFERENCES.joined,
                        ),
                        cacheable = false,
                    )
                )
                .throwOnError()

        val options = response.obj("query")?.obj("userinfo")?.obj("options") ?: return emptyMap()
        return options.mapValues { (_, value) -> value.asPreference() }
    }

    override suspend fun setPreferences(changes: Map<String, String?>): Map<String, String> {
        for ((name, value) in changes) {
            require(name.isNotEmpty() && '=' !in name && SEPARATOR !in name) {
                "a preference cannot be called '$name'"
            }
            require(value == null || SEPARATOR !in value) {
                "the value of $name holds U+001F, which MediaWiki takes as a separator"
            }
        }

        val refused = mutableMapOf<String, String>()
        for (batch in changes.entries.chunked(MAX_CHANGES)) {
            val entries = batch.map { (name, value) -> if (value == null) name else "$name=$value" }
            val response = options { put("change", multiValue(entries)) }
            refused += refusals(response, batch.map { it.key })
        }
        return refused
    }

    override suspend fun resetPreferences(kinds: Set<PreferenceKind>) {
        val _ = options {
            put("reset", "1")
            if (kinds.isNotEmpty()) put("resetkinds", kinds.joinToString("|") { it.apiValue })
        }
    }

    override suspend fun email(
        target: String,
        subject: String,
        text: String,
        ccMe: Boolean,
    ): EmailOutcome {
        val response = tokens.withFreshToken { token ->
            transport
                .call(
                    ApiRequest(
                        buildMap {
                            put("action", "emailuser")
                            put("target", target)
                            put("subject", subject)
                            put("text", text)
                            if (ccMe) put("ccme", "1")
                            put("assert", "user")
                            put("token", token)
                        },
                        RequestKind.WRITE,
                    )
                )
                .also { it.raiseBadToken() }
        }

        ApiFailure.from(response)?.let { failure ->
            return undeliverable(failure) ?: throw emailRefusal(failure)
        }

        val result = response.obj("emailuser")
        if (result?.string("result") == FAILURE) {
            val error = result.array("errors").objects().firstOrNull()
            throw WikiError.Api(
                error?.string("code") ?: FAILURE.lowercase(),
                error?.string("text").orEmpty(),
                "emailuser",
            )
        }
        return EmailOutcome.Sent
    }

    private suspend fun options(params: MutableMap<String, String>.() -> Unit): JsonObject =
        tokens.withFreshToken { token ->
            transport
                .call(
                    ApiRequest(
                        buildMap {
                            put("action", "options")
                            params()
                            put("assert", "user")
                            put("token", token)
                        },
                        RequestKind.WRITE,
                    )
                )
                .also { it.raiseBadToken() }
                .throwOnError()
        }

    /** [entries] as one multi-valued parameter, behind U+001F when any of them holds the usual `|`. */
    private fun multiValue(entries: List<String>): String =
        if (entries.any { '|' in it }) entries.joinToString(SEPARATOR, prefix = SEPARATOR)
        else entries.joinToString("|")

    /**
     * Which of [names] the wiki refused in [response], by the warning that names each.
     *
     * A warning names the preference in its text and nowhere else, so the longest name standing in it as a
     * whole word is the one it is about.
     */
    private fun refusals(response: JsonObject, names: List<String>): Map<String, String> =
        response
            .warnings()
            .filter { it.code in REFUSALS }
            .mapNotNull { warning ->
                names
                    .filter { warning.text.namesPreference(it) }
                    .maxByOrNull { it.length }
                    ?.let { it to warning.text }
            }
            .toMap()

    private fun String.namesPreference(name: String): Boolean {
        var at = indexOf(name)
        while (at >= 0) {
            if (!getOrNull(at - 1).isNameCharacter() && !getOrNull(at + name.length).isNameCharacter())
                return true
            at = indexOf(name, at + 1)
        }
        return false
    }

    private fun Char?.isNameCharacter(): Boolean =
        this != null && (isLetterOrDigit() || this == '-' || this == '_')

    /** A preference as text, a boolean written as the `1` or `0` a ticked or unticked box is kept as. */
    private fun JsonElement.asPreference(): String =
        when {
            this is JsonNull -> ""
            this !is JsonPrimitive -> toString()
            isString -> content
            else ->
                when (booleanOrNull) {
                    true -> "1"
                    false -> "0"
                    null -> content
                }
        }

    /**
     * What to raise for an email the wiki refused to send, the rate limit as the transport would raise it.
     */
    private fun refusal(failure: ApiFailure): WikiError =
        if (failure.code == RATE_LIMITED) WikiError.Transport.RateLimited(null) else failure.toWikiError()

    /**
     * [refusal] for an email, naming `sendemail` when the wiki's bare `permissiondenied` came from the
     * session lacking it, as a bot password without the `sendemail` grant does.
     */
    private suspend fun emailRefusal(failure: ApiFailure): WikiError =
        if (failure.code == PERMISSION_DENIED && transport.lacksRight(SEND_EMAIL)) {
            WikiError.Auth.MissingRight(SEND_EMAIL)
        } else {
            refusal(failure)
        }

    private fun undeliverable(failure: ApiFailure): EmailOutcome.Undeliverable? {
        val reason =
            when (failure.code) {
                "emailnotarget",
                "notarget",
                "baduser_target" -> UndeliverableReason.NO_SUCH_USER
                "noemailtext",
                "noemail" -> UndeliverableReason.NO_ADDRESS
                "nowikiemailtext",
                "nowikiemail" -> UndeliverableReason.NOT_ACCEPTED
                else -> return null
            }
        return EmailOutcome.Undeliverable(reason, failure.code, failure.text)
    }

    private companion object {
        const val DEFAULT_BATCH = 50

        const val PERMISSION_DENIED = "permissiondenied"

        const val SEND_EMAIL = "sendemail"

        /** As many values as MediaWiki takes in one multi-valued parameter from any account. */
        const val MAX_CHANGES = 50

        /** What MediaWiki reads a multi-valued parameter as split by when it starts with it. */
        const val SEPARATOR = "\u001f"

        const val RATE_LIMITED = "ratelimited"
        const val FAILURE = "Failure"

        /** The warnings `action=options` gives for a change it did not save. */
        val REFUSALS = setOf("validationfailed", "global-option-ignored")
    }
}
