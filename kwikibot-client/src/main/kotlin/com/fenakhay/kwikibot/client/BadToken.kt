package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.TokenStore
import com.fenakhay.kwikibot.protocol.ApiFailure
import kotlinx.serialization.json.JsonObject

/** The code a wiki answers with when the token it was sent has expired. */
private const val BAD_TOKEN = "badtoken"

/**
 * Turns a rejected token into the exception [TokenStore.withFreshToken] retries on.
 *
 * The transport hands error blocks back rather than throwing, so without this a stale token
 * reads as an ordinary refusal and the write is abandoned instead of retried. Every service that
 * writes needs it, which is why it does not live inside one of them.
 */
internal fun JsonObject.raiseBadToken(type: String = TokenStore.CSRF) {
    val failure = ApiFailure.from(this) ?: return
    if (failure.code == BAD_TOKEN) throw WikiError.Auth.BadToken(type)
}
