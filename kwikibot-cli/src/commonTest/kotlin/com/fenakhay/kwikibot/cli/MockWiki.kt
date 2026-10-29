package com.fenakhay.kwikibot.cli

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.utils.io.ByteReadChannel
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.serialization.json.JsonPrimitive

/**
 * A wiki behind a [MockEngine]. It answers what opening a wiki asks for (the login, the user and the site)
 * and the pages `get` reads. Every request is kept in [requests], so a test can check where a command went
 * and as whom.
 *
 * Like a real wiki, it sees an anonymous user until a bot password logs in.
 *
 * @param password the bot password it accepts; any other is refused.
 * @param groups the groups a logged-in account is in.
 * @param pages the pages it has, by title.
 */
class MockWiki(
    private val password: String = "secret",
    private val groups: List<String> = listOf("bot", "user"),
    private val pages: Map<String, String> = emptyMap(),
) {
    /** One request: where it went, what it carried, and who it said it came from. */
    data class Request(val host: String, val parameters: Map<String, String>, val userAgent: String?)

    /** Every request the wiki was sent, in order. */
    @OptIn(ExperimentalAtomicApi::class)
    val requests: List<Request>
        get() = sent.load()

    @OptIn(ExperimentalAtomicApi::class) private val sent = AtomicReference(emptyList<Request>())

    @Volatile private var account: String? = null

    /** The engine a command reaches this wiki through. */
    val engine: MockEngine = MockEngine { request -> answer(request) }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val body = request.body.toByteArray().decodeToString()
        val parameters =
            (parseQueryString(request.url.encodedQuery).entries() + parseQueryString(body).entries())
                .associate { it.key to it.value.first() }
        sent.update { it + Request(request.url.host, parameters, request.headers[HttpHeaders.UserAgent]) }

        return when {
            parameters["meta"] == "tokens" -> json("""{"query":{"tokens":{"logintoken":"login+\\"}}}""")
            parameters["action"] == "login" -> login(parameters)
            parameters["meta"] == "userinfo" -> json(userinfo())
            parameters["meta"] == "siteinfo" -> json(SITE_INFO)
            "revisions" in parameters["prop"].orEmpty() -> json(page(parameters.getValue("titles")))
            else -> json("""{"error":{"code":"unexpected","info":${JsonPrimitive(parameters.toString())}}}""")
        }
    }

    private fun MockRequestHandleScope.login(parameters: Map<String, String>): HttpResponseData {
        if (parameters["lgpassword"] != password) {
            val reason = """{"code":"wrongpassword","text":"Incorrect password."}"""
            return json("""{"login":{"result":"Failed","reason":$reason}}""")
        }
        account = parameters.getValue("lgname").substringBefore('@')
        return json("""{"login":{"result":"Success","lguserid":7,"lgusername":"$account"}}""")
    }

    private fun userinfo(): String {
        val name = account ?: return """{"query":{"userinfo":{"id":0,"name":"192.0.2.1","groups":["*"]}}}"""
        val groupList = groups.joinToString(",") { "\"$it\"" }
        return """{"query":{"userinfo":{"id":7,"name":"$name","groups":[$groupList],"rights":["read"]}}}"""
    }

    private fun page(title: String): String {
        val text = pages[title] ?: return """{"query":{"pages":[{"ns":0,"title":"$title","missing":true}]}}"""
        return """{"query":{"pages":[{"pageid":1,"ns":0,"title":"$title","revisions":[{"revid":9001,
            "timestamp":"2026-08-31T21:43:26Z","slots":{"main":{"content":${JsonPrimitive(text)}}}}]}]}}"""
    }

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(
            ByteReadChannel(body),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private companion object {
        val SITE_INFO =
            """
            {"query":{
              "general":{"wikiid":"testwiki","sitename":"Test Wiki","lang":"en",
                         "server":"//test.example.org","articlepath":"/wiki/${'$'}1",
                         "mainpage":"Main Page","generator":"MediaWiki 1.47.0"},
              "namespaces":{"0":{"id":0,"name":"","case":"first-letter"}},
              "interwikimap":[]}}
            """
                .trimIndent()
    }
}
