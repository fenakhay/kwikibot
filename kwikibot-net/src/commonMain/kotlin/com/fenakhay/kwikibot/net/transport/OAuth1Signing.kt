package com.fenakhay.kwikibot.net.transport

import com.fenakhay.kwikibot.net.auth.OAuth1Signer
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.request.forms.FormDataContent

/**
 * Signs every request the client sends to one of [hosts] with [signer], as an OAuth 1.0a login needs.
 *
 * The signature is made as the request goes out, after anything else has had its say about the URL and the
 * body, so it covers what is sent. Each request is signed when it is sent, and again for each redirect the
 * client follows to one of [hosts]: a request the transport tries again is a new request, with a new nonce
 * and timestamp. A request to any other host, a redirect there included, goes out with no signature at all.
 *
 * Only a form body, `application/x-www-form-urlencoded`, is signed. A multipart body, which is how files are
 * uploaded, is not, as RFC 5849 says; its parameters are then covered only as far as they are in the query.
 */
internal fun oauth1Signing(signer: OAuth1Signer, hosts: CredentialHosts): ClientPlugin<Unit> =
    scopedAuthorization("KwikibotOAuth1", hosts) { request ->
        val form = (request.body as? FormDataContent)?.formData
        signer.authorization(request.method.value, request.url.build(), form)
    }
