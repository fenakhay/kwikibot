package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.internal.wire.Registry
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.decode.array
import com.fenakhay.kwikibot.protocol.decode.obj
import com.fenakhay.kwikibot.protocol.decode.strings
import com.fenakhay.kwikibot.protocol.throwOnError
import kotlin.coroutines.cancellation.CancellationException

/**
 * Whether the session is known not to hold [right]. Not knowing is not lacking it.
 *
 * MediaWiki refuses an action the session has no right to with a bare `permissiondenied`, whose message names
 * the groups that hold the right rather than the right itself, and blames the account when what it lacks is a
 * bot password's grant. Asking for the session's rights is what tells the caller which right is missing.
 */
internal suspend fun MediaWikiTransport.lacksRight(right: String): Boolean {
    val rights = runCatching {
        call(
                ApiRequest.of("query", "meta" to "userinfo", "uiprop" to Registry.CURRENT_RIGHTS.joined)
                    .copy(cacheable = false)
            )
            .throwOnError()
            .obj("query")
            ?.obj("userinfo")
            ?.array("rights")
            ?.strings()
    }
        .getOrElse {
            if (it is CancellationException) throw it
            null
        }
    return rights != null && right !in rights
}
