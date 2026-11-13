import com.fenakhay.kwikibot.net.auth.Credentials
import io.kotest.matchers.collections.shouldContain as shouldContainItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class SecretsTest {

    private val password = "pw-very-private"
    private val token = "tok-very-private"
    private val contact = "https://example.org/me"

    private fun jsonOf(value: Any?): String =
        try {
            JSON.stringify(value)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Throwable) {
            ""
        }

    /** Every way of looking at [value] named here, as text, with what it was looked at by. */
    private fun views(value: dynamic): Map<String, String> {
        val spread = js("Object").assign(js("({})"), value)
        val views =
            mutableMapOf(
                "JSON.stringify" to jsonOf(value),
                "Object.keys" to js("Object").keys(value).toString(),
                "Object.values" to jsonOf(js("Object").values(value)),
                "spread" to jsonOf(spread),
                "String" to js("String")(value).toString(),
            )
        if (hasFileSystem()) {
            val util = js("process.getBuiltinModule('util')")
            val nested: dynamic = js("({ depth: null })")
            val hidden: dynamic = js("({ depth: null, showHidden: true })")
            views["util.inspect"] = util.inspect(value).toString()
            views["util.inspect deep"] = util.inspect(value, nested).toString()
            views["util.inspect hidden"] = util.inspect(value, hidden).toString()
            views["util.inspect of a spread"] = util.inspect(spread, nested).toString()
            views["console.log"] = logged(value)
        }
        return views
    }

    /** What the real `console.log` writes for [value], on a console writing to a string. */
    private fun logged(value: dynamic): String {
        val stream = js("process.getBuiltinModule('stream')")
        val written = StringBuilder()
        val writing: dynamic = js("({})")
        writing.write = { chunk: dynamic, _: dynamic, done: dynamic ->
            written.append(chunk.toString())
            done()
        }
        val out = js("new stream.Writable(writing)")
        val console = js("process.getBuiltinModule('console')")
        val logger = js("new console.Console(out)")
        logger.log(value)
        return written.toString()
    }

    private fun assertHides(value: dynamic, secret: String, hidden: Boolean = true) {
        views(value)
            .filterKeys { hidden || it != "util.inspect hidden" }
            .forEach { (how, seen) ->
                val at = seen.indexOf(secret)
                if (at >= 0) {
                    val around = seen.substring((at - 200).coerceAtLeast(0), at)
                    throw AssertionError("seen through $how, after: $around")
                }
            }
    }

    private val oauthAnswer =
        """{"query":{"userinfo":{"id":7,"name":"Acc","groups":["*","user"],"rights":["edit"]},
          "general":{"wikiid":"testwiki","sitename":"Test","lang":"en","timezone":"UTC",
            "server":"//w.example.org","articlepath":"/wiki/${'$'}1","mainpage":"Main Page",
            "generator":"MediaWiki 1.47.0"},
            "namespaces":{"0":{"id":0,"name":"","case":"first-letter"}}}}"""

    private fun oauthEngine(seen: MutableList<String?> = mutableListOf()) = MockEngine { request ->
        seen += request.headers[HttpHeaders.Authorization]
        respond(oauthAnswer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun `credentials the library reads for itself are seen by nothing that looks at them`() {
        val environment =
            mapOf(
                "BOT_ACCOUNT" to "Acc",
                "BOT_BOT_NAME" to "task",
                "BOT_PASSWORD" to password,
                "TOK_OAUTH_TOKEN" to token,
            )

        val botPassword = Credentials.fromEnvironment("BOT", environment::get)
        val oauth = Credentials.fromEnvironment("TOK", environment::get)

        assertHides(botPassword, password, hidden = false)
        assertHides(oauth, token, hidden = false)
        (botPassword as Credentials.BotPassword).password shouldBe password
        (oauth as Credentials.OAuth2).accessToken shouldBe token
    }

    @Test
    fun `a bot password is seen by nothing that looks at it`() {
        val login = BotPassword("Acc", "task", password)

        assertHides(login, password)
        login.account shouldBe "Acc"
        login.botName shouldBe "task"
    }

    @Test
    fun `an OAuth2 token is seen by nothing that looks at it`() {
        val login = OAuth2(token, "Acc")

        assertHides(login, token)
        login.account shouldBe "Acc"
        login.username shouldBe "Acc"
    }

    @Test
    fun `a cookie's value is seen by nothing that looks at it and its properties still work`() {
        val auth = sparqlAuthCookie("wcqsSession", token)

        assertHides(auth, token)
        auth.kind.unsafeCast<String>() shouldBe "cookie"
        auth.name shouldBe "wcqsSession"
        auth.value shouldBe token
    }

    @Test
    fun `the Commons query service's cookie is seen by nothing that looks at it`() {
        val auth = sparqlAuthWcqs(token)

        assertHides(auth, token)
        auth.value shouldBe token
    }

    @Test
    fun `no credentials at all still say so`() {
        val auth = sparqlAuthNone()

        auth.kind.unsafeCast<String>() shouldBe "none"
        auth.value shouldBe null
        views(auth).getValue("JSON.stringify") shouldBe "{}"
    }

    @Test
    fun `a wiki logged in with a bot password shows nothing of it`() = runTest {
        val mock = MockWiki(password = password)
        val wiki = mock.open(BotPassword("Acc", "task", password))

        mock.requests.any { it["lgpassword"] == password } shouldBe true
        assertHides(wiki, password)
        wiki.close()
    }

    @Test
    fun `a wiki logged in with a token shows nothing of it`() = runTest {
        val wiki =
            connectWith(
                    "test.example.org",
                    contact,
                    optionsOf("login" to OAuth2(token, "Acc")),
                    oauthEngine(),
                )
                .await()

        assertHides(wiki, token)
        wiki.close()
    }

    @Test
    fun `a query client holding a cookie shows nothing of it`() {
        val client =
            sparqlClient(
                contact,
                optionsOf("auth" to sparqlAuthWcqs(token)),
                MockEngine { respond("", HttpStatusCode.OK) },
            )

        assertHides(client, token)
        client.close()
    }

    private class Heard {
        val params = mutableListOf<Record<String, String>>()
        val responses = mutableListOf<Record<String, JsonValue>>()
        val retries = mutableListOf<Record<String, String>>()
    }

    private fun listening(heard: Heard): ListenerOptions =
        optionsOf(
            "onResponse" to
                { params: Record<String, String>, response: Record<String, JsonValue> ->
                    heard.params += params
                    heard.responses += response
                },
            "onRetry" to
                { params: Record<String, String>, _: Int, _: Double, _: String ->
                    heard.retries += params
                },
        )

    private fun options(heard: Heard, vararg more: Pair<String, Any?>): ConnectOptions =
        optionsOf(
            "throttle" to optionsOf<ThrottleOptions>("read" to 0.0, "write" to 0.0),
            "retry" to optionsOf<RetryOptions>("maxRetries" to 1, "initialDelay" to 1.0, "maxDelay" to 1.0),
            "listener" to listening(heard),
            *more,
        )

    private val secretNames =
        listOf(
            "password",
            "lgpassword",
            "oldpassword",
            "newpassword",
            "retype",
            "token",
            "csrftoken",
            "logintoken",
            "CentralAuthToken",
            "authorization",
            "Cookie",
        )

    private fun secretParams(): Record<String, ApiValue> {
        val params: dynamic = js("({ action: 'probe', keep: 'visible' })")
        secretNames.forEach { params[it] = "hush-$it" }
        return params.unsafeCast<Record<String, ApiValue>>()
    }

    private fun assertRedacted(params: Record<String, String>) {
        val plain: dynamic = params
        secretNames.forEach { name -> plain[name].toString() shouldBe "[redacted]" }
        plain.keep.toString() shouldBe "visible"
        JSON.stringify(params) shouldNotContain "hush-"
    }

    @Test
    fun `onResponse hears no secret parameter, and the wiki is still sent it`() = runTest {
        val mock = MockWiki()
        mock.canned["probe"] = """{"probe":{"ok":true}}"""
        val heard = Heard()
        val wiki = connectWith("test.example.org", contact, options(heard), mock.engine).await()

        wiki.call(secretParams()).await()

        assertRedacted(heard.params.last())
        mock.requests.last()["lgpassword"] shouldBe "hush-lgpassword"
        mock.requests.last()["csrftoken"] shouldBe "hush-csrftoken"
        wiki.close()
    }

    @Test
    fun `onRetry hears no secret parameter`() = runTest {
        val mock = MockWiki()
        mock.canned["probe"] = """{"probe":{"ok":true}}"""
        var failed = false
        val engine = MockEngine { request ->
            val isProbe =
                request.url.parameters["action"] == "probe" || !failed && request.method.value == "POST"
            if (isProbe && !failed) {
                failed = true
                respond("busy", HttpStatusCode.ServiceUnavailable)
            } else {
                mock.engine.config.requestHandlers.single()(this, request)
            }
        }
        val heard = Heard()
        val wiki = connectWith("test.example.org", contact, options(heard), engine).await()
        failed = false

        wiki.call(secretParams()).await()

        (heard.retries.size > 0) shouldBe true
        assertRedacted(heard.retries.last())
        wiki.close()
    }

    @Test
    fun `logging in is heard with its password and token redacted`() = runTest {
        val mock = MockWiki()
        val heard = Heard()

        val wiki =
            connectWith(
                    "test.example.org",
                    contact,
                    options(heard, "login" to BotPassword("Acc", "task", password)),
                    MockWiki(password = password).engine,
                )
                .await()

        val login = heard.params.single { it.asDynamic().action == "login" }
        login.asDynamic().lgpassword.toString() shouldBe "[redacted]"
        login.asDynamic().lgtoken.toString() shouldBe "[redacted]"
        JSON.stringify(heard.params.toTypedArray()) shouldNotContain password
        wiki.close()
    }

    @Test
    fun `an answer's tokens are heard redacted, and the caller still gets them`() = runTest {
        val mock = MockWiki()
        val heard = Heard()
        val wiki = connectWith("test.example.org", contact, options(heard), mock.engine).await()

        val token = wiki.tokens.token().await()

        (token.isNotEmpty() && token != "[redacted]") shouldBe true
        val all = JSON.stringify(heard.responses.toTypedArray())
        all shouldContain "[redacted]"
        all shouldNotContain token
        wiki.close()
    }

    @Test
    fun `an OAuth2 token and its header are never heard`() = runTest {
        val seenHeaders = mutableListOf<String?>()
        val engine = oauthEngine(seenHeaders)
        val heard = Heard()

        val wiki =
            connectWith(
                    "test.example.org",
                    contact,
                    options(heard, "login" to OAuth2(token, "Acc")),
                    engine,
                )
                .await()

        seenHeaders shouldContainItem "Bearer $token"
        JSON.stringify(heard.params.toTypedArray()) shouldNotContain token
        JSON.stringify(heard.responses.toTypedArray()) shouldNotContain token
        wiki.close()
    }

    @Test
    fun `a response cache never sees what carries a secret`() = runTest {
        val seen = mutableListOf<String>()
        val cache =
            optionsOf<ResponseCache>(
                "get" to
                    { params: Record<String, String> ->
                        seen += JSON.stringify(params)
                        null
                    },
                "put" to
                    { params: Record<String, String>, _: Record<String, JsonValue> ->
                        seen += JSON.stringify(params)
                        null
                    },
            )
        val wiki =
            connectWith(
                    "test.example.org",
                    contact,
                    optionsOf(
                        "login" to BotPassword("Acc", "task", password),
                        "cache" to cache,
                    ),
                    MockWiki(password = password).engine,
                )
                .await()

        seen.joinToString() shouldNotContain password
        wiki.close()
    }

    @Test
    fun `an answer naming no secret is heard as it came`() = runTest {
        val mock = MockWiki()
        mock.canned["probe"] = """{"probe":{"nested":[{"title":"Lava"}],"tokens":{"csrftoken":"x"}}}"""
        val heard = Heard()
        val wiki = connectWith("test.example.org", contact, options(heard), mock.engine).await()

        val answer = wiki.call(optionsOf<Record<String, ApiValue>>("action" to "probe")).await()

        val redacted = JSON.stringify(heard.responses.last())
        redacted shouldContain "Lava"
        redacted shouldNotContain "\"x\""
        JSON.stringify(answer) shouldContain "\"x\""
        wiki.close()
    }
}
