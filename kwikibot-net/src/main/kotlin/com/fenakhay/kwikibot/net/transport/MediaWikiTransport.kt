package com.fenakhay.kwikibot.net.transport

import kotlinx.serialization.json.JsonObject

/**
 * Sends requests to one wiki's `api.php` and hands back the parsed response.
 *
 * The transport owns everything that is true of *every* call — pacing, replication lag, retries, the user
 * agent, credentials — and nothing about what any particular action means. Turning a response into domain
 * objects, and an `error` block into a typed failure, belongs a layer up.
 *
 * Implementations are safe to share between coroutines.
 */
public interface MediaWikiTransport {

    /** Where this transport is pointed. */
    public val endpoint: ApiEndpoint

    /**
     * Performs [request] and returns the decoded JSON response.
     *
     * Retries lag, rate limits and server errors according to the configured policy. A response carrying an
     * API `error` block is returned as-is rather than thrown: only the layer that knows the action can say
     * whether `missingtitle` is a failure or an answer.
     *
     * @throws com.fenakhay.kwikibot.model.WikiError.Transport if the request never produced a usable
     *   response, including when the retry budget runs out.
     */
    public suspend fun call(request: ApiRequest): JsonObject
}
