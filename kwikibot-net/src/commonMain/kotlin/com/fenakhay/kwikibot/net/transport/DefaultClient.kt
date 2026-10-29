package com.fenakhay.kwikibot.net.transport

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * A client on this platform's own HTTP engine, its connections set up from [settings], with [configure]
 * applied after. The client owns the engine and closes it with itself.
 */
internal expect fun defaultClient(
    settings: HttpSettings,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient

/**
 * Whether [failure], thrown while a request was sent or its answer read, is this platform's engine failing to
 * reach the server, which is worth trying again. Ktor's engines share no type for it: OkHttp and Darwin throw
 * an `IOException`, Curl and WinHTTP an `IllegalStateException`, and fetch a plain `Error`.
 */
internal expect fun isConnectionFailure(failure: Throwable): Boolean
