package com.fenakhay.kwikibot.net.auth

import io.ktor.http.Parameters
import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid
import org.kotlincrypto.macs.hmac.sha1.HmacSHA1

/**
 * Signs requests for an [Credentials.OAuth1] login, as RFC 5849 says: HMAC-SHA1 over the method, the URL and
 * every parameter, sent in an `Authorization: OAuth` header.
 *
 * Each call to [authorization] draws a nonce and reads the clock again, so no two requests, and no two
 * attempts at one request, carry the same signature.
 *
 * MediaWiki's OAuth extension checks the signature against the URL it was asked for, with `$wgServer` as its
 * host, and against the query and form parameters it received. So a wiki behind a proxy that rewrites the
 * host or the path refuses every request, whatever this sends.
 *
 * @param credentials the consumer and access token to sign with.
 * @param nonce a value never sent twice with the same timestamp. A secure random one unless given; a test
 *   fixes it to check a signature.
 * @param timestamp the seconds since the epoch, which the wiki checks is close to its own clock.
 * @param version the `oauth_version` sent, or `null` to send none, which RFC 5849 allows and the wiki reads
 *   as `1.0`.
 */
internal class OAuth1Signer(
    private val credentials: Credentials.OAuth1,
    private val nonce: () -> String = ::secureNonce,
    private val timestamp: () -> Long = { Clock.System.now().epochSeconds },
    private val version: String? = VERSION,
) {

    private val key: ByteArray =
        "${percentEncode(credentials.consumerSecret)}&${percentEncode(credentials.accessSecret)}"
            .encodeToByteArray()

    /**
     * The `Authorization` header for a request with [method] to [url], whose body, when it is
     * `application/x-www-form-urlencoded`, holds [form]. A body of any other kind, such as a multipart
     * upload, is not signed, as RFC 5849 says, and its [form] is `null`.
     */
    fun authorization(method: String, url: Url, form: Parameters? = null): String {
        val protocol = protocolParameters()
        val signed = url.parameters.pairs() + form?.pairs().orEmpty() + protocol
        val signature = signature(signatureBaseString(method, url, signed))
        return (protocol + (SIGNATURE to signature)).joinToString(", ", prefix = "OAuth ") { (name, value) ->
            "${percentEncode(name)}=\"${percentEncode(value)}\""
        }
    }

    /** The `oauth_` parameters this request is signed with, but for the signature itself. */
    private fun protocolParameters(): List<Pair<String, String>> = buildList {
        add("oauth_consumer_key" to credentials.consumerKey)
        add("oauth_nonce" to nonce())
        add("oauth_signature_method" to METHOD)
        add("oauth_timestamp" to timestamp().toString())
        add("oauth_token" to credentials.accessToken)
        version?.let { add("oauth_version" to it) }
    }

    /** The HMAC-SHA1 of [base], keyed with both secrets, in Base64. */
    fun signature(base: String): String = Base64.encode(HmacSHA1(key).doFinal(base.encodeToByteArray()))

    /** How RFC 5849 signs requests, without a consumer to sign for. */
    companion object {
        /** The only signature method this signs with. */
        const val METHOD: String = "HMAC-SHA1"

        /** The `oauth_version` sent unless told otherwise. */
        const val VERSION: String = "1.0"

        private const val SIGNATURE = "oauth_signature"

        private const val HEX = "0123456789ABCDEF"

        private const val BYTE = 0xFF

        private const val NIBBLE = 4

        private const val LOW_NIBBLE = 0x0F

        /**
         * The signature base string of RFC 5849 §3.4.1: the method, the URL without its query, and
         * [parameters] normalized, each percent-encoded and joined by `&`.
         *
         * @param method the HTTP method, in any case.
         * @param url where the request goes; its query is signed through [parameters], not here.
         * @param parameters every query parameter, every form parameter of the body, and the `oauth_`
         *   parameters but `oauth_signature`, with each name as often as it was sent.
         */
        fun signatureBaseString(method: String, url: Url, parameters: List<Pair<String, String>>): String =
            listOf(method.uppercase(), baseStringUri(url), normalized(parameters)).joinToString("&") {
                percentEncode(it)
            }

        /**
         * [url] as RFC 5849 §3.4.1.2 signs it: the scheme and host in lower case, the port only when it is
         * not the scheme's own, and the path as sent, without the query or a fragment.
         */
        fun baseStringUri(url: Url): String {
            val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
            val path = url.encodedPath.ifEmpty { "/" }
            return "${url.protocol.name.lowercase()}://${url.host.lowercase()}$port$path"
        }

        /**
         * [parameters] as RFC 5849 §3.4.1.3.2 normalizes them: each name and value percent-encoded, sorted by
         * name and then by value in byte order, and joined as `name=value` pairs with `&`.
         */
        fun normalized(parameters: List<Pair<String, String>>): String =
            parameters
                .map { (name, value) -> percentEncode(name) to percentEncode(value) }
                .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                .joinToString("&") { (name, value) -> "$name=$value" }

        /**
         * [text] encoded as RFC 5849 §3.6 says: every byte of its UTF-8 but the unreserved characters of RFC
         * 3986, `A`–`Z`, `a`–`z`, `0`–`9`, `-`, `.`, `_` and `~`, as `%` and two upper-case hex digits. A
         * space is `%20`, never `+`.
         */
        fun percentEncode(text: String): String = buildString {
            for (byte in text.encodeToByteArray()) {
                val code = byte.toInt() and BYTE
                if (code.toChar().isUnreserved()) {
                    append(code.toChar())
                } else {
                    append('%')
                    append(HEX[code shr NIBBLE])
                    append(HEX[code and LOW_NIBBLE])
                }
            }
        }

        private fun Char.isUnreserved(): Boolean =
            this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in UNRESERVED_MARKS

        private const val UNRESERVED_MARKS = "-._~"

        private fun Parameters.pairs(): List<Pair<String, String>> =
            entries().flatMap { (name, values) -> values.ifEmpty { listOf("") }.map { name to it } }
    }
}

/**
 * A nonce no one can guess: 122 random bits from the platform's secure random generator, which [Uuid.random]
 * draws from on every target this library builds for, as hex.
 */
private fun secureNonce(): String = Uuid.random().toHexString()
