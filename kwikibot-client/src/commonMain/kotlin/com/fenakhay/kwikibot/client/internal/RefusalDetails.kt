package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.model.RevisionId
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.protocol.decode.Continuation
import com.fenakhay.kwikibot.protocol.decode.array
import com.fenakhay.kwikibot.protocol.decode.long
import com.fenakhay.kwikibot.protocol.decode.objects
import com.fenakhay.kwikibot.protocol.decode.string
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject

private const val EDIT_ACTION = "edit"
private const val CREATE_ACTION = "create"

/**
 * This refusal with what the wiki's error leaves out: the revision a conflict lost to, and the level a page
 * is protected at. Asked for only after a refusal, so a save costs nothing extra. A refusal is the answer
 * whether or not this can be found out, so failing to find it out changes nothing.
 */
internal suspend fun EditOutcome.Refused.withDetails(
    continuation: Continuation,
    namespaces: NamespaceMap,
): EditOutcome.Refused {
    if (this !is EditOutcome.Conflict && this !is EditOutcome.Protected) return this

    val entry =
        runCatching {
            val request =
                ApiRequest.of(
                    "query",
                    "prop" to "info",
                    "inprop" to "protection",
                    "titles" to namespaces.format(ref.title),
                )
            continuation.pages(request.copy(cacheable = false)).toList().firstOrNull()
        }
            .getOrElse {
                if (it is CancellationException) throw it
                null
            } ?: return this

    return when (this) {
        is EditOutcome.Conflict ->
            copy(currentRevision = entry.long("lastrevid")?.takeIf { it != 0L }?.let { RevisionId(it) })
        is EditOutcome.Protected -> copy(level = entry.protectionLevel())
    }
}

/** The level that stops an edit: the one on editing, or for a page yet to exist, the one on creating it. */
private fun JsonObject.protectionLevel(): String? {
    val protections = array("protection").objects()
    val found =
        protections.firstOrNull { it.string("type") == EDIT_ACTION }
            ?: protections.firstOrNull { it.string("type") == CREATE_ACTION }
    return found?.string("level")?.takeIf { it.isNotEmpty() }
}
