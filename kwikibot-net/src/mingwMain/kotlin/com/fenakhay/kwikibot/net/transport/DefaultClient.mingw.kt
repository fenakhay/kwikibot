package com.fenakhay.kwikibot.net.transport

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.winhttp.WinHttp
import kotlinx.io.IOException

internal actual fun defaultClient(
    settings: HttpSettings,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(WinHttp) { configure() }

/**
 * WinHTTP reports a request that fails as an `IllegalStateException` naming the Windows error, and its
 * timeouts as an `IOException`.
 */
internal actual fun isConnectionFailure(failure: Throwable): Boolean =
    failure is IOException || failure is IllegalStateException
