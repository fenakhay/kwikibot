package com.fenakhay.kwikibot.net.transport

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlinx.io.IOException

internal actual fun defaultClient(
    settings: HttpSettings,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient =
    HttpClient(Darwin) {
        engine {
            configureSession { setHTTPMaximumConnectionsPerHost(settings.maxConcurrentRequests.toLong()) }
        }
        configure()
    }

/** Darwin reports a connection that fails as a `DarwinHttpRequestException`, an `IOException`. */
internal actual fun isConnectionFailure(failure: Throwable): Boolean = failure is IOException
