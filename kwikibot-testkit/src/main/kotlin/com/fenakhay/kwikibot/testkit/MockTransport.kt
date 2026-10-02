package com.fenakhay.kwikibot.testkit

import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A transport that answers from a function instead of a wiki.
 *
 * For testing code that calls the API directly, or a service the fakes do not model, without standing up an
 * HTTP stack: [handler] sees each request as parameters and returns the response a wiki would. Every request
 * is kept in [requests], in order, so a test can assert what was sent.
 *
 * ```
 * val transport = MockTransport { request ->
 *     MockTransport.json("""{"query":{"pages":[]}}""")
 * }
 * val wiki = FakeWiki(pages = FakePageService(), transport = transport)
 * ```
 *
 * @param endpoint where the transport claims to point.
 * @param handler the answer to each request. Throwing from it fails the call, as a transport error would.
 */
public class MockTransport(
    override val endpoint: ApiEndpoint = ApiEndpoint("test.example.org"),
    private val handler: suspend (ApiRequest) -> JsonObject,
) : MediaWikiTransport {

    /** Every request this transport was asked to send, in order. */
    public val requests: List<ApiRequest>
        get() = sent.toList()

    private val sent = CopyOnWriteArrayList<ApiRequest>()

    override suspend fun call(request: ApiRequest): JsonObject {
        sent += request
        return handler(request)
    }

    /** Writing answers. */
    public companion object {
        /** Parses [text] as the JSON object a wiki would answer with. */
        public fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    }
}
