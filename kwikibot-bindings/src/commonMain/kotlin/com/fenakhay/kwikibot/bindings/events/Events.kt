package com.fenakhay.kwikibot.bindings.events

import com.fenakhay.kwikibot.bindings.connect.userAgent
import com.fenakhay.kwikibot.client.model.EventStreams
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.UserAgent
import io.ktor.client.HttpClient

/**
 * Wikimedia's event streams over [http]: as this library for [contact], retried as the library retries, from
 * Wikimedia's own service, unless told.
 */
public fun eventStreamsWithDefaults(
    http: HttpClient,
    contact: String,
    userAgent: UserAgent? = null,
    retry: RetryPolicy? = null,
    baseUrl: String? = null,
): EventStreams =
    EventStreams(
        http,
        userAgent(contact, userAgent),
        retry ?: RetryPolicy(),
        baseUrl ?: EventStreams.WIKIMEDIA,
    )
