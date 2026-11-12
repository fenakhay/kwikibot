import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.net.RetryPolicy
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
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
import kotlin.js.Promise
import kotlin.time.Duration
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonPrimitive

/**
 * A French wiki in Paris time behind a [MockEngine]: it answers opening it, logging in, reading and saving
 * pages, the three listings, a page's history, month names and one Wikibase item. Every request is kept in
 * [requests].
 *
 * @param pages the pages it has, by title, each at revision 9001 until saved.
 * @param members the titles in Category:Volcanoes.
 */
class MockWiki(pages: Map<String, String> = emptyMap(), private val members: List<String> = emptyList()) {

    /** The parameters of every request, in order. */
    val requests: MutableList<Map<String, String>> = mutableListOf()

    /**
     * Answers given as they are, by the module asked for: a query's `list`, else its `prop`, else its `meta`;
     * any other request's `action`, and `""` for a request with none, such as a file's or an upload's. They
     * come before the built-in ones, apart from tokens and logging in.
     */
    val canned: MutableMap<String, String> = mutableMapOf()

    /** The extensions it says it has, by the name MediaWiki reports. Read when it is opened. */
    val extensions: MutableList<String> = mutableListOf()

    /** The body of every request, in order. */
    val bodies: MutableList<String> = mutableListOf()

    /** What was saved, by title. */
    val saved: MutableMap<String, String> = mutableMapOf()

    private val texts = pages.toMutableMap()
    private var revision = 9001L
    private var account: String? = null

    /** The engine the wiki is reached through. */
    val engine: MockEngine = MockEngine { request -> answer(request) }

    /** Opens this wiki through the facade, logged in with [login] when one is given. */
    suspend fun open(login: BotPassword? = null): Wiki =
        connect("test.example.org", login, "/w", CONFIG, engine).await()

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val body = request.body.toByteArray().decodeToString()
        bodies += body
        val parameters =
            (parseQueryString(request.url.encodedQuery).entries() + parseQueryString(body).entries())
                .associate { it.key to it.value.first() }
        requests += parameters

        return when {
            parameters["meta"] == "tokens" -> json(TOKENS)
            parameters["action"] == "login" -> json(login(parameters))
            module(parameters) in canned -> json(canned.getValue(module(parameters)))
            parameters["action"] == "edit" -> json(edit(parameters))
            parameters["action"] == "wbgetentities" -> json(ENTITY)
            parameters["meta"] == "userinfo" -> json(userinfo())
            parameters["meta"] == "siteinfo" -> json(siteInfo())
            parameters["meta"] == "allmessages" -> json(MONTHS)
            else -> json(query(parameters))
        }
    }

    private fun query(parameters: Map<String, String>): String =
        when {
            parameters["list"] == "categorymembers" -> listing("categorymembers", members)
            parameters["list"] == "search" -> listing("search", listOf("Volcano", "Volcanic ash"))
            parameters["list"] == "embeddedin" -> listing("embeddedin", listOf("Volcano"))
            "revisions" in parameters["prop"].orEmpty().split('|') ->
                if ("content" in parameters["rvprop"].orEmpty()) page(parameters.getValue("titles"))
                else HISTORY
            else -> """{"error":{"code":"unexpected","info":${JsonPrimitive(parameters.toString())}}}"""
        }

    private fun module(parameters: Map<String, String>): String =
        if (parameters["action"] == "query")
            parameters["list"] ?: parameters["prop"] ?: parameters["meta"].orEmpty()
        else parameters["action"].orEmpty()

    private fun login(parameters: Map<String, String>): String =
        if (parameters["lgpassword"] == "secret") {
            account = parameters.getValue("lgname").substringBefore('@')
            """{"login":{"result":"Success","lguserid":7,"lgusername":"$account"}}"""
        } else {
            """{"login":{"result":"Failed","reason":{"code":"wrongpassword","text":"Incorrect password."}}}"""
        }

    private fun siteInfo(): String {
        val names = extensions.joinToString(",") { """{"name":"$it"}""" }
        return SITE_INFO.replace(""""interwikimap":""", """"extensions":[$names],"interwikimap":""")
    }

    private fun userinfo(): String {
        val name = account ?: return """{"query":{"userinfo":{"id":0,"name":"192.0.2.1","groups":["*"]}}}"""
        val groups = """"groups":["*","bot","user"],"rights":["bot","edit"]"""
        return """{"query":{"userinfo":{"id":7,"name":"$name",$groups}}}"""
    }

    private fun edit(parameters: Map<String, String>): String {
        val title = parameters.getValue("title")
        val base = parameters["baserevid"]?.toLong()
        if (base != null && base != revision) {
            return """{"error":{"code":"editconflict","info":"Edit conflict."}}"""
        }
        val previous = revision
        revision += 1
        texts[title] = parameters.getValue("text")
        saved[title] = parameters.getValue("text")
        return """{"edit":{"result":"Success","pageid":1,"title":"$title",""" +
            """"oldrevid":$previous,"newrevid":$revision}}"""
    }

    private fun page(titles: String): String {
        val pages =
            titles.split('|').joinToString(",") { title ->
                val text = texts[title] ?: return@joinToString """{"ns":0,"title":"$title","missing":true}"""
                """{"pageid":1,"ns":0,"title":"$title","revisions":[{"revid":$revision,
                   "timestamp":"2026-08-31T21:43:26Z","slots":{"main":{"content":${JsonPrimitive(text)}}}}]}"""
            }
        return """{"query":{"pages":[$pages]}}"""
    }

    private fun listing(name: String, titles: List<String>): String =
        """{"query":{"$name":[${titles.joinToString(",") { """{"ns":0,"title":"$it"}""" }}]}}"""

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(
            ByteReadChannel(body),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )

    companion object {
        /** A configuration that never waits between requests nor retries a refusal. */
        val CONFIG: WikiConfig =
            WikiConfig(
                userAgent = UserAgent("TestBot", "1.0", "https://example.org/TestBot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO),
                retry = RetryPolicy.NONE,
            )

        private const val TOKENS =
            """{"query":{"tokens":{"logintoken":"l+\\","csrftoken":"c+\\","userrightstoken":"u+\\",""" +
                """"rollbacktoken":"r+\\","watchtoken":"w+\\"}}}"""

        private val SITE_INFO =
            """
            {"query":{
              "general":{"wikiid":"testwiki","sitename":"Test Wiki","lang":"fr","timezone":"Europe/Paris",
                         "server":"//test.example.org","articlepath":"/wiki/${'$'}1",
                         "mainpage":"Main Page","generator":"MediaWiki 1.47.0"},
              "namespaces":{
                "0":{"id":0,"name":"","case":"first-letter"},
                "2":{"id":2,"name":"User","canonical":"User","case":"first-letter"},
                "6":{"id":6,"name":"File","canonical":"File","case":"first-letter"},
                "10":{"id":10,"name":"Template","canonical":"Template","case":"first-letter"},
                "14":{"id":14,"name":"Category","canonical":"Category","case":"first-letter"},
                "104":{"id":104,"name":"Page","canonical":"Page","case":"first-letter"},
                "106":{"id":106,"name":"Index","canonical":"Index","case":"first-letter"}},
              "interwikimap":[{"prefix":"w","url":"https://en.wikipedia.org/wiki/${'$'}1"}]}}
            """
                .trimIndent()

        private val MONTHS =
            mapOf(
                    "january" to "janvier",
                    "february" to "février",
                    "march" to "mars",
                    "april" to "avril",
                    "may" to "mai",
                    "june" to "juin",
                    "july" to "juillet",
                    "august" to "août",
                    "september" to "septembre",
                    "october" to "octobre",
                    "november" to "novembre",
                    "december" to "décembre",
                )
                .entries
                .joinToString(",", """{"query":{"allmessages":[""", "]}}") { (key, name) ->
                    """{"name":"$key","content":"$name"},{"name":"$key-gen","content":"$name"}"""
                }

        private val HISTORY =
            """
            {"query":{"pages":[{"pageid":1,"ns":0,"title":"Volcano","revisions":[
              {"revid":9001,"parentid":9000,"user":"Alice","timestamp":"2026-08-31T21:43:26Z",
               "comment":"adding ash","size":120,"minor":true,"tags":["mobile edit"]},
              {"revid":9000,"parentid":0,"user":"Bob","timestamp":"2026-08-30T08:00:00Z","comment":"created",
               "size":80,"tags":[]}]}]}}
            """
                .trimIndent()

        private val ENTITY =
            """
            {"entities":{"Q42":{"type":"item","id":"Q42","lastrevid":7,
              "labels":{"en":{"language":"en","value":"Douglas Adams"}},
              "descriptions":{"en":{"language":"en","value":"English writer"}},
              "aliases":{"en":[{"language":"en","value":"DNA"}]},
              "claims":{"P31":[{"mainsnak":{"snaktype":"value","property":"P31","datatype":"wikibase-item",
                "datavalue":{"value":{"entity-type":"item","numeric-id":5,"id":"Q5"},"type":"wikibase-entityid"}},
                "type":"statement","id":"Q42${'$'}1","rank":"normal"}],
                "P20":[{"mainsnak":{"snaktype":"somevalue","property":"P20","datatype":"wikibase-item"},
                "type":"statement","id":"Q42${'$'}2","rank":"preferred"}]}}}}
            """
                .trimIndent()
    }
}

/** An options object with [entries], as a TypeScript author writes one. */
fun <T> optionsOf(vararg entries: Pair<String, Any?>): T {
    val options: dynamic = js("({})")
    entries.forEach { (name, value) -> options[name] = value }
    return options.unsafeCast<T>()
}

/** [value], a string or array of strings, as the union of string literals it is in TypeScript. */
internal fun <T> literal(value: Any): T = value.unsafeCast<T>()

/** [value] as a callback's answer, which in TypeScript it is as it stands. */
internal fun <T> answering(value: T): Awaitable<T> = value.unsafeCast<Awaitable<T>>()

/** [promise] as a callback's answer, which in TypeScript it is as it stands. */
internal fun <T> answering(promise: Promise<T>): Awaitable<T> = promise.unsafeCast<Awaitable<T>>()

/**
 * [value] as a callback's answer whatever it is, for what plain JavaScript can answer and TypeScript cannot.
 */
internal fun <T> untyped(value: Any?): Awaitable<T> = value.unsafeCast<Awaitable<T>>()

/** Every item of this async iterable, or the first [limit], ending the walk there. */
suspend fun <T> AsyncIterable<T>.take(limit: Int = Int.MAX_VALUE): List<T> {
    val iterator = asDynamic()[js("Symbol.asyncIterator")]()
    val items = mutableListOf<T>()
    while (items.size < limit) {
        val step = iterator.next().unsafeCast<Promise<dynamic>>().await()
        if (step.done == true) return items
        items += step.value.unsafeCast<T>()
    }
    iterator["return"]().unsafeCast<Promise<dynamic>>().await()
    return items
}
