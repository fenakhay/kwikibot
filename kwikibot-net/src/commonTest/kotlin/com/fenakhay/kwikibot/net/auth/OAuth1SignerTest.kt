package com.fenakhay.kwikibot.net.auth

import com.fenakhay.kwikibot.net.auth.OAuth1Signer.Companion.baseStringUri
import com.fenakhay.kwikibot.net.auth.OAuth1Signer.Companion.normalized
import com.fenakhay.kwikibot.net.auth.OAuth1Signer.Companion.percentEncode
import com.fenakhay.kwikibot.net.auth.OAuth1Signer.Companion.signatureBaseString
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.parametersOf
import kotlin.test.Test

/**
 * The signature RFC 5849 describes, checked against the RFC's own worked examples and against signatures
 * Python's `oauthlib` 3.2.2 made for the same requests.
 */
class OAuth1SignerTest {

    private val photos =
        Credentials.OAuth1("dpf43f3p2l4k3l03", "kd94hf93k423kf44", "nnch734d00sl2jdk", "pfkkdhi9sl3r4s00")

    private fun Credentials.OAuth1.signer(
        nonce: String,
        timestamp: Long,
        version: String? = OAuth1Signer.VERSION,
    ) = OAuth1Signer(this, { nonce }, { timestamp }, version)

    /** The parameters of an `Authorization: OAuth` header, decoded. */
    private fun parameters(header: String): Map<String, String> =
        header.removePrefix("OAuth ").split(", ").associate { pair ->
            val (name, quoted) = pair.split("=", limit = 2)
            name to quoted.removeSurrounding("\"").decodeURLQueryComponent()
        }

    @Test
    fun `the protected resource request of RFC 5849 section 1_2 gets the signature the RFC gives`() {
        val url = Url("http://photos.example.net/photos?file=vacation.jpg&size=original")
        val header = photos.signer("chapoH", 137131202, version = null).authorization("GET", url)

        parameters(header) shouldBe
            mapOf(
                "oauth_consumer_key" to "dpf43f3p2l4k3l03",
                "oauth_nonce" to "chapoH",
                "oauth_signature_method" to "HMAC-SHA1",
                "oauth_timestamp" to "137131202",
                "oauth_token" to "nnch734d00sl2jdk",
                "oauth_signature" to "MdpQcU8iPSUjWoN/UDMsK2sui9I=",
            )
        header shouldNotContain "kd94hf93k423kf44"
        header shouldNotContain "pfkkdhi9sl3r4s00"
    }

    @Test
    fun `the base string of RFC 5849 section 1_2 is the one the RFC gives`() {
        val url = Url("http://photos.example.net/photos?file=vacation.jpg&size=original")
        val oauth =
            listOf(
                "oauth_consumer_key" to "dpf43f3p2l4k3l03",
                "oauth_token" to "nnch734d00sl2jdk",
                "oauth_signature_method" to "HMAC-SHA1",
                "oauth_timestamp" to "137131202",
                "oauth_nonce" to "chapoH",
            )

        signatureBaseString(
            "GET",
            url,
            url.parameters.entries().map { it.key to it.value.single() } + oauth,
        ) shouldBe
            "GET&http%3A%2F%2Fphotos.example.net%2Fphotos&file%3Dvacation.jpg%26" +
                "oauth_consumer_key%3Ddpf43f3p2l4k3l03%26oauth_nonce%3DchapoH%26" +
                "oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D137131202%26" +
                "oauth_token%3Dnnch734d00sl2jdk%26size%3Doriginal"
    }

    @Test
    fun `the request of RFC 5849 section 3_4_1_1 has the base string the RFC gives`() {
        val credentials = Credentials.OAuth1("9djdj82h48djs9d2", "unused", "kkk9d7dh3k39sjv7", "unused")
        val url = Url("http://example.com/request?b5=%3D%253D&a3=a&c%40=&a2=r%20b")
        val form = parametersOf("c2" to listOf(""), "a3" to listOf("2 q"))
        val expected =
            "POST&http%3A%2F%2Fexample.com%2Frequest&a2%3Dr%2520b%26a3%3D2%2520q%26a3%3Da%26" +
                "b5%3D%253D%25253D%26" +
                "c%2540%3D%26c2%3D%26oauth_consumer_key%3D9djdj82h48djs9d2%26oauth_nonce%3D7d8f3e4a%26" +
                "oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D137131201%26oauth_token%3Dkkk9d7dh3k39sjv7"

        val header =
            credentials.signer("7d8f3e4a", 137131201, version = null).authorization("post", url, form)

        val signed = parameters(header)
        val oauth = signed.filterKeys { it != "oauth_signature" }.toList()
        val query =
            url.parameters.entries().flatMap { (name, values) ->
                values.ifEmpty { listOf("") }.map { name to it }
            }
        signatureBaseString(
            "POST",
            url,
            query + form.entries().map { it.key to it.value.single() } + oauth,
        ) shouldBe expected
        signed.getValue("oauth_signature") shouldBe credentials.signer("", 0).signature(expected)
    }

    @Test
    fun `signatures match the ones oauthlib makes for the same requests`() {
        val edge = Credentials.OAuth1("key~with space", "cs&é", "tok", "ts/+=")
        val signer = edge.signer("n0nce", 1700000000)

        val post =
            signer.authorization(
                "POST",
                Url("https://Wiki.Example.ORG:443/w/api.php?title=Caf%C3%A9%20~x&a=2&a=1&empty="),
                parametersOf(
                    "a" to listOf("3"),
                    "text" to listOf("héllo wörld 😀"),
                    "summary" to listOf(""),
                    "tilde" to listOf("~"),
                ),
            )
        parameters(post).getValue("oauth_signature") shouldBe "j++aYX3/0nZDS8+l04FFn0ra4kc="
        parameters(post).getValue("oauth_consumer_key") shouldBe "key~with space"
        post.contains("oauth_consumer_key=\"key~with%20space\"") shouldBe true

        val local = signer.authorization("GET", Url("http://localhost:18080/api.php?action=query"))
        parameters(local).getValue("oauth_signature") shouldBe "dSF6uQIjkF66r4tdgd6fkKkz1S8="

        val photo =
            photos
                .signer("chapoH", 137131202)
                .authorization("GET", Url("http://photos.example.net/photos?file=vacation.jpg&size=original"))
        parameters(photo).getValue("oauth_signature") shouldBe "1IAE9RzK+DqSqVTdQ/0zWANXVzs="
        parameters(photo).getValue("oauth_version") shouldBe "1.0"
    }

    @Test
    fun `parameters are encoded as RFC 3986 unreserved characters and nothing else`() {
        percentEncode("a b") shouldBe "a%20b"
        percentEncode("~-._") shouldBe "~-._"
        percentEncode("AZaz09") shouldBe "AZaz09"
        percentEncode("+&=*/%") shouldBe "%2B%26%3D%2A%2F%25"
        percentEncode("é") shouldBe "%C3%A9"
        percentEncode("😀") shouldBe "%F0%9F%98%80"
        percentEncode("") shouldBe ""
    }

    @Test
    fun `parameters are sorted by name then by value in byte order`() {
        normalized(listOf("b" to "1", "a" to "2", "a" to "1", "A" to "z", "a" to "")) shouldBe
            "A=z&a=&a=1&a=2&b=1"
        normalized(listOf("a" to "b c", "a" to "b+c", "a" to "b~c")) shouldBe "a=b%20c&a=b%2Bc&a=b~c"
        normalized(listOf("x" to "é", "x" to "z")) shouldBe "x=%C3%A9&x=z"
        normalized(listOf("a_b" to "1", "a" to "2", "a-b" to "3")) shouldBe "a=2&a-b=3&a_b=1"
        normalized(emptyList()) shouldBe ""
    }

    @Test
    fun `a name in both the query and the form body is signed with both values`() {
        val url = Url("https://wiki.example.org/w/api.php?origin=a&title=Q")
        val signed =
            signatureBaseString(
                "POST",
                url,
                listOf("origin" to "a", "title" to "Q", "title" to "F", "empty" to ""),
            )

        signed shouldBe
            "POST&https%3A%2F%2Fwiki.example.org%2Fw%2Fapi.php&empty%3D%26origin%3Da%26title%3DF%26title%3DQ"

        val header =
            photos
                .signer("n", 1)
                .authorization("POST", url, parametersOf("title" to listOf("F"), "empty" to listOf()))
        parameters(header).getValue("oauth_signature") shouldBe
            photos
                .signer("", 0)
                .signature(
                    signatureBaseString(
                        "POST",
                        url,
                        listOf("origin" to "a", "title" to "Q", "title" to "F", "empty" to "") +
                            parameters(header).filterKeys { it != "oauth_signature" }.toList(),
                    )
                )
    }

    @Test
    fun `the URL is signed without its query in lower case with a port only when not the default`() {
        baseStringUri(Url("HTTP://Example.COM:80/r%20v/X?id=123")) shouldBe "http://example.com/r%20v/X"
        baseStringUri(Url("https://www.example.net:8080/?q=1")) shouldBe "https://www.example.net:8080/"
        baseStringUri(Url("https://wiki.example.org:443/w/api.php")) shouldBe
            "https://wiki.example.org/w/api.php"
        baseStringUri(Url("http://localhost:18080/w/api.php")) shouldBe "http://localhost:18080/w/api.php"
        baseStringUri(Url("https://wiki.example.org")) shouldBe "https://wiki.example.org/"
    }

    @Test
    fun `every signature draws a nonce and reads the clock again`() {
        var nonces = 0
        var ticks = 0L
        val signer = OAuth1Signer(photos, { "nonce-${nonces++}" }, { 1000 + ticks++ })
        val url = Url("https://wiki.example.org/w/api.php?action=query")

        val first = parameters(signer.authorization("GET", url))
        val second = parameters(signer.authorization("GET", url))

        first.getValue("oauth_nonce") shouldBe "nonce-0"
        second.getValue("oauth_nonce") shouldBe "nonce-1"
        second.getValue("oauth_timestamp") shouldBe "1001"
        second.getValue("oauth_signature") shouldNotBe first.getValue("oauth_signature")
    }

    @Test
    fun `the default nonce is random and the default clock is the time now`() {
        val signer = OAuth1Signer(photos)
        val url = Url("https://wiki.example.org/w/api.php")

        val first = parameters(signer.authorization("GET", url))
        val second = parameters(signer.authorization("GET", url))

        first.getValue("oauth_nonce") shouldNotBe second.getValue("oauth_nonce")
        first.getValue("oauth_nonce").length shouldBe 32
        (first.getValue("oauth_timestamp").toLong() > 1_700_000_000L) shouldBe true
        first.getValue("oauth_version") shouldBe "1.0"
    }
}
