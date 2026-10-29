package com.fenakhay.kwikibot.protocol.decode

import com.fenakhay.kwikibot.model.MwTimestamp
import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.Expiry
import com.fenakhay.kwikibot.model.edit.Protection
import com.fenakhay.kwikibot.model.log.LogDetails
import com.fenakhay.kwikibot.model.log.LogEvent
import com.fenakhay.kwikibot.model.log.RecentChange
import com.fenakhay.kwikibot.model.page.Revision
import com.fenakhay.kwikibot.model.user.BlockInfo
import com.fenakhay.kwikibot.model.user.BlockRecord
import com.fenakhay.kwikibot.model.user.Contribution
import com.fenakhay.kwikibot.model.user.UserInfo
import kotlinx.serialization.json.JsonObject

/**
 * Decodes the things a wiki reports about its own activity: logs, recent changes, users.
 *
 * These share a shape — an actor, a time, a page, a comment, any of which may be missing because it was
 * suppressed — and they share the trap that goes with it: a hidden field is absent, not empty, so a decoder
 * that defaults to `""` turns a suppressed field into an absent one.
 */
public class ActivityDecoder(private val pages: PageDecoder) {

    /** Decodes one entry from `list=logevents`. */
    public fun decodeLogEvent(entry: JsonObject): LogEvent {
        val type = entry.string("type").orEmpty()
        return LogEvent(
            id = entry.long("logid") ?: 0L,
            type = type,
            action = entry.string("action").orEmpty(),
            page = entry.pageRef(),
            user = entry.string("user"),
            timestamp = entry.time("timestamp") ?: MwTimestamp.parse(EPOCH),
            comment = entry.string("comment"),
            tags = entry.array("tags").strings(),
            details = decodeDetails(type, entry.obj("params") ?: EMPTY),
        )
    }

    /** Decodes one entry from `list=recentchanges`. */
    public fun decodeRecentChange(entry: JsonObject): RecentChange {
        val type = entry.string("type").orEmpty()
        return RecentChange(
            id = entry.long("rcid") ?: 0L,
            type = type,
            page = entry.pageRef(),
            user = entry.string("user"),
            timestamp = entry.time("timestamp") ?: MwTimestamp.parse(EPOCH),
            comment = entry.string("comment"),
            revisionId = entry.long("revid")?.takeIf { it != 0L }?.let { RevisionId(it) },
            previousRevisionId = entry.long("old_revid")?.takeIf { it != 0L }?.let { RevisionId(it) },
            sizeChange = (entry.int("newlen") ?: 0) - (entry.int("oldlen") ?: 0),
            isMinor = entry.flag("minor"),
            isBot = entry.flag("bot"),
            isNew = entry.flag("new"),
            isPatrolled = entry.flag("patrolled"),
            // Recent changes mixes edits and log actions in one stream, and names the log
            // fields differently when it does: logtype, not type. Rebuilding the entry here
            // spares callers a second query, and spares them that trap.
            logEvent = if (type == "log") logEventOf(entry) else null,
            tags = entry.array("tags").strings(),
            // Present, true or false, when the query asked for it, which the wiki refuses to a session
            // that may not patrol. Absent otherwise, which is not the same as unpatrolled.
            patrolled = entry.boolean("patrolled"),
        )
    }

    /** Decodes one entry from `list=usercontribs`. */
    public fun decodeContribution(entry: JsonObject): Contribution? {
        val page = entry.pageRef() ?: return null
        return Contribution(
            page = page,
            revision =
                Revision(
                    id = RevisionId(entry.long("revid") ?: 0L),
                    parentId = entry.long("parentid")?.takeIf { it != 0L }?.let { RevisionId(it) },
                    timestamp = entry.time("timestamp") ?: MwTimestamp.parse(EPOCH),
                    user = entry.string("user"),
                    comment = entry.string("comment"),
                    isMinor = entry.flag("minor"),
                    size = entry.int("size") ?: 0,
                    tags = entry.array("tags").strings(),
                ),
            isNew = entry.flag("new"),
            isTop = entry.flag("top"),
            sizeChange = entry.int("sizediff") ?: 0,
        )
    }

    /**
     * Decodes one entry from `list=blocks`.
     *
     * The field names differ from the ones on a user row: a listed block reports `user`, `by` and `id` where
     * a user reports `blockedby` and `blockid`.
     */
    public fun decodeBlockRecord(entry: JsonObject): BlockRecord =
        BlockRecord(
            info =
                BlockInfo(
                    id = entry.long("id"),
                    by = entry.string("by"),
                    reason = entry.string("reason"),
                    since = entry.time("timestamp"),
                    expiry = entry.time("expiry"),
                    isPartial = entry.flag("partial"),
                ),
            // Hidden when the block itself is suppressed, which is why this is nullable.
            target = entry.string("user"),
            isAutomatic = entry.flag("automatic"),
        )

    /** Decodes one entry from `list=users`. */
    public fun decodeUser(entry: JsonObject): UserInfo =
        UserInfo(
            name = entry.string("name").orEmpty(),
            id = entry.long("userid")?.takeIf { it != 0L },
            groups = entry.array("groups").strings(),
            rights = entry.array("rights").strings(),
            editCount = entry.long("editcount") ?: 0L,
            registration = entry.time("registration"),
            block = entry.blockInfo(),
            // An IP address is not a username, so the API reports it as invalid rather than as a
            // user with no id. Both readings mean the same thing: the editor is not logged in.
            isAnonymous = entry.flag("invalid") || entry.containsKey("anon"),
            // A temporary account is auto-created for a logged-out editor and reports itself through
            // the "temp" group, so no name pattern is needed for a user row.
            isTemporary = TEMP_GROUP in entry.array("groups").strings(),
            isMissing = entry.flag("missing"),
            gender = entry.string("gender")?.takeIf { it != "unknown" },
            emailable = entry.flag("emailable"),
        )

    /**
     * Decodes `meta=userinfo`, which describes the logged-in account.
     *
     * A different shape from `list=users` — the name is under `name`, the block fields are the same, and the
     * rights are the ones this session actually has.
     */
    public fun decodeCurrentUser(entry: JsonObject): UserInfo =
        UserInfo(
            name = entry.string("name").orEmpty(),
            id = entry.long("id")?.takeIf { it != 0L },
            groups = entry.array("groups").strings(),
            rights = entry.array("rights").strings(),
            editCount = entry.long("editcount") ?: 0L,
            registration = entry.time("registrationdate"),
            block = entry.blockInfo(),
            isAnonymous = entry.containsKey("anon"),
            emailable = entry.flag("emailable"),
        )

    /**
     * The log entry inside a recent-changes row.
     *
     * The same information as a `list=logevents` entry under different names: the log type is `logtype`, the
     * action `logaction`, and the type-specific fields `logparams`.
     */
    private fun logEventOf(entry: JsonObject): LogEvent {
        val type = entry.string("logtype").orEmpty()
        return LogEvent(
            id = entry.long("logid") ?: 0L,
            type = type,
            action = entry.string("logaction").orEmpty(),
            page = entry.pageRef(),
            user = entry.string("user"),
            timestamp = entry.time("timestamp") ?: MwTimestamp.parse(EPOCH),
            comment = entry.string("comment"),
            tags = entry.array("tags").strings(),
            details = decodeDetails(type, entry.obj("logparams") ?: EMPTY),
        )
    }

    private fun decodeDetails(type: String, params: JsonObject): LogDetails {
        return when (type) {
            "move" -> decodeMove(params)
            "block" -> decodeBlock(params)
            "upload" -> LogDetails.Upload(sha1 = params.string("img_sha1"))
            "protect" -> decodeProtect(params)
            "rights" -> decodeRights(params)
            "patrol" -> decodePatrol(params)
            else -> otherDetails(params)
        }
    }

    private fun decodeBlock(params: JsonObject) =
        LogDetails.Block(
            duration = params.string("duration"),
            expiry = params.time("expiry"),
            flags = params.array("flags").strings(),
            // A block is sitewide unless it names what it covers, so an absent flag is not partial.
            isPartial = params.boolean("sitewide") == false,
        )

    private fun decodeProtect(params: JsonObject) =
        LogDetails.Protect(
            protections =
                params.array("details").objects().mapNotNull { fields ->
                    val action = fields.string("type") ?: return@mapNotNull null
                    Protection(
                        action = action,
                        level = fields.string("level").orEmpty(),
                        expiry = fields.string("expiry")?.let { Expiry.parse(it) } ?: Expiry.Never,
                        cascading = fields.flag("cascade"),
                    )
                },
            description = params.string("description"),
        )

    private fun decodeRights(params: JsonObject): LogDetails {
        val before = params.array("oldgroups").strings()
        val after = params.array("newgroups").strings()
        return LogDetails.Rights(added = after - before.toSet(), removed = before - after.toSet())
    }

    private fun decodePatrol(params: JsonObject) =
        LogDetails.Patrol(
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
                params.keys.mapNotNull { key -> params.string(key)?.let { key to it } }.toMap()
            )
        }

    /**
     * A move, or the fields the wiki sent when they name no target: none at all when the action was
     * suppressed, or a target that is not a title.
     */
    private fun decodeMove(params: JsonObject): LogDetails {
        val title = params.string("target_title")
        val namespace = params.int("target_ns") ?: 0
        val target = title?.let { pages.refOf(it, namespace) } ?: return otherDetails(params)

        return LogDetails.Move(
            target = target.title,
            suppressedRedirect = params.flag("suppressredirect"),
        )
    }

    private fun JsonObject.blockInfo(): BlockInfo? {
        if (!containsKey("blockid") && string("blockedby") == null) return null
        return BlockInfo(
            id = long("blockid"),
            by = string("blockedby"),
            reason = string("blockreason"),
            since = time("blockedtimestamp"),
            expiry = time("blockexpiry"),
            isPartial = flag("blockpartial"),
        )
    }

    private fun JsonObject.pageRef() = pages.refOf(this)

    private companion object {
        const val EPOCH = "1970-01-01T00:00:00Z"

        /** The group a wiki puts every auto-created temporary account in. */
        const val TEMP_GROUP = "temp"
        val EMPTY = JsonObject(emptyMap())
    }
}
