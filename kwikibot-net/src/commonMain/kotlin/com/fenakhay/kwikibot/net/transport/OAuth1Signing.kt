package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.auth.OAuth1Signer
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.authority
import io.ktor.util.AttributeKey

/**
 * Signs every request the client sends with [signer], as an OAuth 1.0a login needs.
 *
 * The signature is made as the request goes out, after anything else has had its say about the URL and the
 * body, so it covers what is sent. Each request is signed when it is sent, and again for each redirect the
 * client follows to the same host: a request the transport tries again is a new request, with a new nonce and
 * timestamp. A redirect to another host goes out with no signature at all, as a bearer header would not be
 * sent there either.
 *
 * Only a form body, `application/x-www-form-urlencoded`, is signed. A multipart body, which is how files are
 * uploaded, is not, as RFC 5849 says; its parameters are then covered only as far as they are in the query.
 */
internal fun oauth1Signing(signer: OAuth1Signer): ClientPlugin<Unit> =
    createClientPlugin("KwikibotOAuth1") {
        on(Send) { request ->
            val url = request.url.build()
            val first = request.attributes.getOrNull(SIGNED_FOR)
            if (first == null) request.attributes.put(SIGNED_FOR, url.authority)
            if (first == null || first == url.authority) {
                val form = (request.body as? FormDataContent)?.formData
                request.headers[HttpHeaders.Authorization] =
                    signer.authorization(request.method.value, url, form)
            }
            proceed(request)
        }
    }

/** The host and port a request was first sent to, which is the only one its redirects are signed for. */
private val SIGNED_FOR = AttributeKey<String>("kwikibot.OAUTH1_SIGNED_FOR")
