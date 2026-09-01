package com.fenakhay.kwikibot.protocol

import com.fenakhay.kwikibot.model.BlockInfo
import com.fenakhay.kwikibot.model.BlockRecord
import com.fenakhay.kwikibot.model.Contribution
import com.fenakhay.kwikibot.model.Expiry
import com.fenakhay.kwikibot.model.LogDetails
import com.fenakhay.kwikibot.model.LogEvent
import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.Protection
import com.fenakhay.kwikibot.model.RecentChange
import com.fenakhay.kwikibot.model.Revision
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.UserInfo
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Decodes the things a wiki reports about its own activity: logs, recent changes, users.
 *
 * These share a shape — an actor, a time, a page, a comment, any of which may be missing because
 * it was suppressed — and they share the trap that goes with it: a hidden field is absent, not
 * empty, so a decoder that defaults to `""` turns a suppressed field into an absent one.
 */
public class ActivityDecoder(private val pages: PageDecoder) {

    /** Decodes one entry from `list=logevents`. */
    public fun decodeLogEvent(entry: JsonObject): LogEvent {
        val type = entry.text("type").orEmpty()
        return LogEvent(
            id = entry.long("logid") ?: 0L,
            type = type,
            action = entry.text("action").orEmpty(),
            page = entry.pageRef(),
            user = entry.text("user"),
            timestamp = entry.time("timestamp") ?: MwTimestamp.parse(EPOCH),
            comment = entry.text("comment"),
            tags = entry.strings("tags"),
            details = decodeDetails(type, entry.params(type)),
        )
    }

    /** Decodes one entry from `list=recentchanges`. */
    public fun decodeRecentChange(entry: JsonObject): RecentChange {
        val type = entry.text("type").orEmpty()
        return RecentChange(
            id = entry.long("rcid") ?: 0L,
            type = type,
            page = entry.pageRef(),
            user = entry.text("user"),
            timestamp = entry.time("timestamp") ?: MwTimestamp.parse(EPOCH),
            comment = entry.text("comment"),
            revisionId = entry.long("revid")?.takeIf { it != 0L }?.let { RevisionId(it) },
            previousRevisionId = entry.long("old_revid")?.takeIf { it != 0L }
                ?.let { RevisionId(it) },
            sizeChange = (entry.int("newlen") ?: 0) - (entry.int("oldlen") ?: 0),
            isMinor = entry.flag("minor"),
            isBot = entry.flag("bot"),
            isNew = entry.flag("new"),
            isPatrolled = entry.flag("patrolled"),
            // Recent changes mixes edits and log actions in one stream, and names the log
            // fields differently when it does: logtype, not type. Rebuilding the entry here
            // spares callers a second query, and spares them that trap.
            logEvent = if (type == "log") logEventOf(entry) else null,
            tags = entry.strings("tags"),
        )
    }

    /** Decodes one entry from `list=usercontribs`. */
    public fun decodeContribution(entry: JsonObject): Contribution? {
        val page = entry.pageRef() ?: return null
        return Contribution(
            page = page,
            revision = Revision(
                id = RevisionId(entry.long("revid") ?: 0L),
                parentId = entry.long("parentid")?.takeIf { it != 0L }?.let { RevisionId(it) },
                timestamp = entry.time("timestamp") ?: MwTimestamp.parse(EPOCH),
                user = entry.text("user"),
                comment = entry.text("comment"),
                isMinor = entry.flag("minor"),
                size = entry.int("size") ?: 0,
                tags = entry.strings("tags"),
            ),
            isNew = entry.flag("new"),
            isTop = entry.flag("top"),
            sizeChange = entry.int("sizediff") ?: 0,
        )
    }

    /**
     * Decodes one entry from `list=blocks`.
     *
     * The field names differ from the ones on a user row: a listed block reports `user`, `by`
     * and `id` where a user reports `blockedby` and `blockid`.
     */
    public fun decodeBlockRecord(entry: JsonObject): BlockRecord = BlockRecord(
        info = BlockInfo(
            id = entry.long("id"),
            by = entry.text("by"),
            reason = entry.text("reason"),
            since = entry.time("timestamp"),
            expiry = entry.text("expiry")
                ?.takeUnless { MwTimestamp.isNever(it) }
                ?.let { MwTimestamp.parseOrNull(it) },
            isPartial = entry.flag("partial"),
        ),
        // Hidden when the block itself is suppressed, which is why this is nullable.
        target = entry.text("user"),
        isAutomatic = entry.flag("automatic"),
    )

    /** Decodes one entry from `list=users`. */
    public fun decodeUser(entry: JsonObject): UserInfo = UserInfo(
        name = entry.text("name").orEmpty(),
        id = entry.long("userid")?.takeIf { it != 0L },
        groups = entry.strings("groups"),
        rights = entry.strings("rights"),
        editCount = entry.long("editcount") ?: 0L,
        registration = entry.time("registration"),
        block = entry.blockInfo(),
        // An IP address is not a username, so the API reports it as invalid rather than as a
        // user with no id. Both readings mean the same thing: the editor is not logged in.
        isAnonymous = entry.flag("invalid") || entry.containsKey("anon"),
        // A temporary account is auto-created for a logged-out editor and reports itself through
        // the "temp" group, so no name pattern is needed for a user row.
        isTemporary = TEMP_GROUP in entry.strings("groups"),
        isMissing = entry.flag("missing"),
        gender = entry.text("gender")?.takeIf { it != "unknown" },
        emailable = entry.flag("emailable"),
    )

    /**
     * Decodes `meta=userinfo`, which describes the logged-in account.
     *
     * A different shape from `list=users` — the name is under `name`, the block fields are the
     * same, and the rights are the ones this session actually has.
     */
    public fun decodeCurrentUser(entry: JsonObject): UserInfo = UserInfo(
        name = entry.text("name").orEmpty(),
        id = entry.long("id")?.takeIf { it != 0L },
        groups = entry.strings("groups"),
        rights = entry.strings("rights"),
        editCount = entry.long("editcount") ?: 0L,
        registration = entry.time("registrationdate"),
        block = entry.blockInfo(),
        isAnonymous = entry.containsKey("anon"),
        emailable = entry.flag("emailable"),
    )

    /**
     * The log entry inside a recent-changes row.
     *
     * The same information as a `list=logevents` entry under different names: the log type is
     * `logtype`, the action `logaction`, and the type-specific fields `logparams`.
     */
    private fun logEventOf(entry: JsonObject): LogEvent {
        val type = entry.text("logtype").orEmpty()
        return LogEvent(
            id = entry.long("logid") ?: 0L,
            type = type,
            action = entry.text("logaction").orEmpty(),
            page = entry.pageRef(),
            user = entry.text("user"),
            timestamp = entry.time("timestamp") ?: MwTimestamp.parse(EPOCH),
            comment = entry.text("comment"),
            tags = entry.strings("tags"),
            details = decodeDetails(type, entry["logparams"]?.jsonObject ?: EMPTY),
        )
    }

    /**
     * The type-specific fields of a log entry.
     *
     * `formatversion=2` nests them under `params`; older responses put them under a key named
     * after the log type.
     */
    private fun JsonObject.params(type: String): JsonObject =
        this["params"]?.jsonObject ?: this[type]?.jsonObject ?: EMPTY

    private fun decodeDetails(type: String, params: JsonObject): LogDetails {
        return when (type) {
            "move" -> decodeMove(params)
            "block" -> decodeBlock(params)
            "upload" -> LogDetails.Upload(sha1 = params.text("img_sha1"))
            "protect" -> decodeProtect(params)
            "rights" -> decodeRights(params)
            "patrol" -> decodePatrol(params)
            else -> otherDetails(params)
        }
    }

    private fun decodeBlock(params: JsonObject) = LogDetails.Block(
        duration = params.text("duration"),
        expiry = params.text("expiry")
            ?.takeUnless { MwTimestamp.isNever(it) }
            ?.let { MwTimestamp.parseOrNull(it) },
        flags = params.strings("flags"),
        // A block is sitewide unless it names what it covers, so an absent flag is not partial.
        isPartial = params["sitewide"]?.jsonPrimitive?.booleanOrNull == false,
    )

    private fun decodeProtect(params: JsonObject) = LogDetails.Protect(
        protections = params["details"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val fields = entry.jsonObject
            val action = fields.text("type") ?: return@mapNotNull null
            Protection(
                action = action,
                level = fields.text("level").orEmpty(),
                expiry = fields.text("expiry")?.let { Expiry.parse(it) } ?: Expiry.Never,
                cascading = fields.flag("cascade"),
            )
        },
        description = params.text("description"),
    )

    private fun decodeRights(params: JsonObject): LogDetails {
        val before = params.strings("oldgroups")
        val after = params.strings("newgroups")
        return LogDetails.Rights(added = after - before.toSet(), removed = before - after.toSet())
    }

    private fun decodePatrol(params: JsonObject) = LogDetails.Patrol(
        revision = RevisionId(params.long("curid") ?: 0L),
        previous = params.long("previd")?.takeIf { it != 0L }?.let { RevisionId(it) },
        automatic = params.flag("auto"),
    )

    /**
     * The fields of a log type this library does not model.
     *
     * Kept rather than dropped, so a caller that knows what they mean can still read them.
     */
    private fun otherDetails(params: JsonObject): LogDetails =
        if (params.isEmpty()) {
            LogDetails.None
        } else {
            LogDetails.Unknown(
                params.entries.mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.let { key to it.content }
                }.toMap(),
            )
        }

    private fun decodeMove(params: JsonObject): LogDetails {
        val title = params.text("target_title") ?: params.text("new_title")
        val namespace = params.int("target_ns") ?: params.int("new_ns") ?: 0
        val target = title?.let { pages.refOf(it, namespace) }
            ?: return LogDetails.Unknown(mapOf("target_title" to title.orEmpty()))

        return LogDetails.Move(
            target = target.title,
            suppressedRedirect = params.flag("suppressredirect") ||
                params.flag("suppressedredirect"),
        )
    }

    private fun JsonObject.blockInfo(): BlockInfo? {
        if (!containsKey("blockid") && text("blockedby") == null) return null
        return BlockInfo(
            id = long("blockid"),
            by = text("blockedby"),
            reason = text("blockreason"),
            since = time("blockedtimestamp"),
            expiry = text("blockexpiry")
                ?.takeUnless { MwTimestamp.isNever(it) }
                ?.let { MwTimestamp.parseOrNull(it) },
            isPartial = flag("blockpartial"),
        )
    }

    private fun JsonObject.pageRef() = pages.refOf(this)

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.time(key: String): Instant? =
        this[key]?.jsonPrimitive?.content?.let { MwTimestamp.parseOrNull(it) }

    private fun JsonObject.strings(key: String): List<String> =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    /**
     * Whether a presence flag is set.
     *
     * `formatversion=2` sends `true`, older responses send an empty string, and both mean the
     * same thing; only an explicit `false` counts as unset.
     */
    private fun JsonObject.flag(key: String): Boolean {
        val value: JsonElement = this[key] ?: return false
        val primitive = value as? JsonPrimitive ?: return true
        return primitive.booleanOrNull ?: true
    }

    private companion object {
        const val EPOCH = "1970-01-01T00:00:00Z"

        /** The group a wiki puts every auto-created temporary account in. */
        const val TEMP_GROUP = "temp"
        val EMPTY = JsonObject(emptyMap())
    }
}
