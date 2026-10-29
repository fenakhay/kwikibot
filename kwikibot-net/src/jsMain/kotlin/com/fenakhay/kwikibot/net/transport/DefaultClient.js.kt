package com.fenakhay.kwikibot.net.transport

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js
import kotlinx.io.IOException

internal actual fun defaultClient(
    settings: HttpSettings,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Js) { configure() }

/**
 * fetch rejects a request it cannot send, which the Js engine passes on as an `Error`; its timeouts are an
 * `IOException`.
 */
internal actual fun isConnectionFailure(failure: Throwable): Boolean =
    failure is IOException || failure is Error
