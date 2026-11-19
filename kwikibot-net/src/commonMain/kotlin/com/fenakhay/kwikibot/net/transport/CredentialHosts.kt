package com.fenakhay.kwikibot.net.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.util.AttributeKey
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The hosts a client made by [WikiHttpClient] sends its login to: those of the wikis a transport on it
 * reaches.
 *
 * A client is handed to whatever needs one, and a bot may give the same client to a service that is not a
 * wiki, such as PetScan or a stream. An OAuth token or signature sent there would be the account's credential
 * in the hands of whoever runs that host, so it goes only where the client is a wiki's: each [KtorTransport]
 * built on the client adds its endpoint's host and port, and nothing else is ever added.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CredentialHosts {
    private val hosts = AtomicReference(emptySet<String>())

    /** Lets requests to [endpoint]'s host and port carry the login. */
    fun allow(endpoint: ApiEndpoint) {
        val host = keyOf(Url(endpoint.apiUrl))
        while (true) {
            val current = hosts.load()
            if (host in current || hosts.compareAndSet(current, current + host)) return
        }
    }

    /** Whether a request to [url] may carry the login. */
    operator fun contains(url: Url): Boolean = keyOf(url) in hosts.load()

    private fun keyOf(url: Url): String =
        "${url.protocol.name.lowercase()}://${url.host.lowercase()}:${url.port}"
}

/** Where a client [WikiHttpClient] made keeps the hosts it sends its login to. */
internal val CREDENTIAL_HOSTS = AttributeKey<CredentialHosts>("kwikibot.CREDENTIAL_HOSTS")

/** Lets this client's login go to [endpoint], if it is a client [WikiHttpClient] made with one. */
internal fun HttpClient.allowCredentials(endpoint: ApiEndpoint) {
    attributes.getOrNull(CREDENTIAL_HOSTS)?.allow(endpoint)
}

/**
 * Puts [authorization]'s header on each request to one of [hosts], as it is sent and again for each redirect
 * followed to one of them. Any other request goes out as it was, so a header a caller set for another service
 * is kept, and a redirect to another host loses this one, as Ktor drops `Authorization` when a redirect
 * changes host.
 */
internal fun scopedAuthorization(
    name: String,
    hosts: CredentialHosts,
    authorization: (HttpRequestBuilder) -> String,
): ClientPlugin<Unit> =
    createClientPlugin(name) {
        on(Send) { request ->
            if (request.url.build() in hosts)
                request.headers[HttpHeaders.Authorization] = authorization(request)
            proceed(request)
        }
    }
