package com.fenakhay.kwikibot.net.transport

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl
import kotlinx.io.IOException

internal actual fun defaultClient(
    settings: HttpSettings,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Curl) { configure() }

/**
 * Curl reports a request that fails as an `IllegalStateException` naming libcurl's error, and its timeouts as
 * an `IOException`.
 */
internal actual fun isConnectionFailure(failure: Throwable): Boolean =
    failure is IOException || failure is IllegalStateException
