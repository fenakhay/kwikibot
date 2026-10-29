package com.fenakhay.kwikibot.net.transport

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import kotlinx.io.IOException
import okhttp3.ConnectionPool
import okhttp3.Dispatcher

internal actual fun defaultClient(
    settings: HttpSettings,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                // OkHttp allows five requests to one host at once by default. Every request of a run
                // goes to one host, so that would cap the run whatever concurrency it asks for.
                dispatcher(
                    Dispatcher().apply {
                        maxRequests = settings.maxConcurrentRequests
                        maxRequestsPerHost = settings.maxConcurrentRequests
                    }
                )
                connectionPool(
                    ConnectionPool(settings.maxIdleConnections, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)
                )
            }
        }
        configure()
    }

private const val KEEP_ALIVE_MINUTES = 5L

/** OkHttp reports a connection that fails as an `IOException`. */
internal actual fun isConnectionFailure(failure: Throwable): Boolean = failure is IOException
