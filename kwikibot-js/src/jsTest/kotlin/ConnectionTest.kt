import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory

class ConnectionTest {

    private val contact = "https://example.org/TestBot"

    private val quick = optionsOf<ThrottleOptions>("read" to 0.0, "write" to 0.0)

    private val noRetry = optionsOf<RetryOptions>("maxRetries" to 0)

    private fun settings(vararg more: Pair<String, Any?>): ConnectOptions =
        optionsOf("throttle" to quick, "retry" to noRetry, *more)

    private val mock = MockWiki()

    @Test
    fun `an OAuth2 token goes as a bearer header, with no login round trip`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond(
                """{"query":{"userinfo":{"id":7,"name":"Bot","groups":["*","user"],"rights":["edit"]},
                  "general":{"wikiid":"testwiki","sitename":"Test","lang":"en","timezone":"UTC",
                    "server":"//w.example.org","articlepath":"/wiki/${'$'}1","mainpage":"Main Page",
                    "generator":"MediaWiki 1.47.0"},
                    "namespaces":{"0":{"id":0,"name":"","case":"first-letter"}}}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val login = OAuth2("token-1", "Bot")

        val wiki = connectWith("w.example.org", contact, settings("login" to login), engine).await()

        seen.first().headers[HttpHeaders.Authorization] shouldBe "Bearer token-1"
        seen.none { it.url.parameters["action"] == "login" } shouldBe true
        wiki.user.name shouldBe "Bot"
        wiki.close()
        listOf(login.account, login.botName, login.username) shouldBe listOf("Bot", "", "Bot")
        OAuth2("t").account shouldBe ""
        shouldThrow<IllegalArgumentException> { OAuth2(" ") }
    }

    @Test
    fun `an OAuth2 token is a login wherever a bot password is taken`() = runTest {
        val login: BotPassword = OAuth2("token-1")

        (login is OAuth2) shouldBe true
        login.credentials.username shouldBe null
    }

    @Test
    fun `a bot password logs in through the same function`() = runTest {
        val wiki =
            connectWith(
                    "test.example.org",
                    contact,
                    settings("login" to BotPassword("Bot", "t", "secret")),
                    mock.engine,
                )
                .await()

        mock.requests.first { it["action"] == "login" }["lgname"] shouldBe "Bot@t"
        wiki.user.name shouldBe "Bot"
        wiki.close()
    }

    @Test
    fun `the environment holds a token, a bot password, or nothing`() {
        val token = credentialsFromEnvironment("X") { name -> if (name == "X_OAUTH_TOKEN") "t" else null }
        (token is OAuth2) shouldBe true

        val env = mapOf("Y_ACCOUNT" to "Bot", "Y_BOT_NAME" to "task", "Y_PASSWORD" to "p")
        val password = credentialsFromEnvironment("Y") { env[it] }
        listOf(password?.account, password?.botName) shouldBe listOf("Bot", "task")

        credentialsFromEnvironment("Z") { null } shouldBe null
        shouldThrow<IllegalStateException> {
            credentialsFromEnvironment("Y") { if (it == "Y_ACCOUNT") "Bot" else null }
        }
        credentialsFromEnvironment("KWIKIBOT_NO_SUCH_PREFIX") shouldBe null
    }

    @Test
    fun `a user agent has its parts, and connecting sends it`() = runTest {
        val agent = UserAgent("Mine", "2.0", contact)
        listOf(agent.tool, agent.version, agent.contact) shouldBe listOf("Mine", "2.0", contact)
        agent.headerValue shouldStartWith "Mine/2.0 ($contact)"
        agent.toString() shouldBe agent.headerValue
        shouldThrow<IllegalArgumentException> { UserAgent("Mine", "1", " ") }

        val seen = mutableListOf<String?>()
        val inner = mock.engine
        val engine = MockEngine { request ->
            seen += request.headers[HttpHeaders.UserAgent]
            inner.config.requestHandlers.first().invoke(this, request)
        }
        val wiki = connectWith("test.example.org", contact, settings("userAgent" to agent), engine).await()
        seen.first() shouldBe agent.headerValue
        wiki.close()
        LIBRARY_NAME shouldBe "kwikibot"
        LIBRARY_URL shouldContain "github.com"
        libraryVersion() shouldBe versionReport().lineSequence().first().removePrefix("kwikibot ")
    }

    @Test
    fun `the log level is set by name and refused otherwise`() {
        val before = logLevel()
        setLogLevel(literal("off"))
        logLevel() shouldBe "off"
        setLogLevel(literal("warn"))
        logLevel() shouldBe "warn"
        setLogLevel(before)
        shouldThrow<IllegalArgumentException> { setLogLevel(literal("loud")) }.message.orEmpty() shouldContain
            "'trace'"
    }

    @Test
    fun `every setting reaches the configuration`() {
        val heard = mutableListOf<String>()
        val config =
            configOf(
                contact,
                optionsOf(
                    "userAgent" to UserAgent("Mine", "2", contact),
                    "throttle" to optionsOf<ThrottleOptions>("read" to 5.0, "write" to 6.0),
                    "retry" to
                        optionsOf<RetryOptions>("maxRetries" to 1, "initialDelay" to 7.0, "maxDelay" to 8.0),
                    "maxlag" to 9,
                    "relogins" to 2,
                    "http" to
                        optionsOf<HttpOptions>(
                            "maxConcurrentRequests" to 4,
                            "maxIdleConnections" to 5,
                            "timeout" to 1000.0,
                        ),
                    "onWarning" to
                        { warning: ApiWarning ->
                            val _ = heard.add(warning.text)
                        },
                ),
            )

        config.userAgent.tool shouldBe "Mine"
        listOf(config.throttle.read.inWholeMilliseconds, config.throttle.write.inWholeMilliseconds) shouldBe
            listOf(5L, 6L)
        listOf(config.retry.maxRetries.toLong(), config.retry.initialDelay.inWholeMilliseconds) shouldBe
            listOf(1L, 7L)
        config.retry.maxDelay.inWholeMilliseconds shouldBe 8L
        listOf(config.maxlag, config.relogins) shouldBe listOf(9, 2)
        config.http.maxConcurrentRequests shouldBe 4
        config.http.maxIdleConnections shouldBe 5
        config.http.timeout.inWholeSeconds shouldBe 1L
    }

    @Test
    fun `maxlag can be left out with null, and defaults otherwise`() {
        configOf(contact, optionsOf("maxlag" to null)).maxlag shouldBe null
        configOf(contact, optionsOf()).maxlag shouldBe configOf(contact, null).maxlag
        (configOf(contact, null).maxlag != null) shouldBe true
    }

    @Test
    fun `a listener and a warning hear the wiki`() = runTest {
        val warned = mutableListOf<ApiWarning>()
        val responses = mutableListOf<String?>()
        mock.canned["categorymembers"] =
            """{"warnings":[{"module":"query","code":"deprecation","text":"old"}],"query":{"categorymembers":[]}}"""
        val wiki =
            connectWith(
                    "test.example.org",
                    contact,
                    settings(
                        "onWarning" to
                            { warning: ApiWarning ->
                                val _ = warned.add(warning)
                            },
                        "listener" to
                            optionsOf<ListenerOptions>(
                                "onResponse" to
                                    { params: Record<String, String>, _: Any? ->
                                        val _ = responses.add(params.toMap()["list"])
                                    }
                            ),
                    ),
                    mock.engine,
                )
                .await()

        wiki.categoryMembers("Lava").take()

        responses.contains("categorymembers") shouldBe true
        warned.single().module shouldBe "query"
        warned.single().text shouldBe "old"
        warned.single().code shouldBe "deprecation"
        warned.single().isDeprecation shouldBe true
        warned.single().toString() shouldContain "old"
        wiki.close()
    }

    @Test
    fun `a listener hears retries and pauses`() = runTest {
        var failures = 1
        val pauses = mutableListOf<Double>()
        val attempts = mutableListOf<Int>()
        val inner = mock.engine
        val engine = MockEngine { request ->
            if (request.url.parameters["list"] == "search" && failures-- > 0) {
                respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
            } else {
                inner.config.requestHandlers.first().invoke(this, request)
            }
        }
        val wiki =
            connectWith(
                    "test.example.org",
                    contact,
                    optionsOf(
                        "throttle" to quick,
                        "retry" to
                            optionsOf<RetryOptions>(
                                "maxRetries" to 1,
                                "initialDelay" to 1.0,
                                "maxDelay" to 1.0,
                            ),
                        "listener" to
                            optionsOf<ListenerOptions>(
                                "onRetry" to
                                    { _: Record<String, String>, attempt: Int, _: Double, _: String ->
                                        val _ = attempts.add(attempt)
                                    },
                                "onPenalty" to
                                    { wait: Double ->
                                        val _ = pauses.add(wait)
                                    },
                            ),
                    ),
                    engine,
                )
                .await()

        wiki.search("lava").take() shouldBe listOf("Volcano", "Volcanic ash")

        attempts shouldBe listOf(1)
        pauses shouldBe listOf(1000.0)
        wiki.close()
    }

    @Test
    fun `a listener hears a lost session restored`() = runTest {
        var lost = true
        val restored = mutableListOf<String>()
        val inner = mock.engine
        val engine = MockEngine { request ->
            if (request.url.parameters["list"] == "search" && lost) {
                lost = false
                respond(
                    """{"error":{"code":"assertuserfailed","info":"gone"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                inner.config.requestHandlers.first().invoke(this, request)
            }
        }
        val wiki =
            connectWith(
                    "test.example.org",
                    contact,
                    settings(
                        "login" to BotPassword("Bot", "t", "secret"),
                        "listener" to
                            optionsOf<ListenerOptions>(
                                "onRelogin" to
                                    { identity: Identity ->
                                        val _ = restored.add(identity.name)
                                    }
                            ),
                    ),
                    engine,
                )
                .await()

        wiki.search("lava").take().size shouldBe 2

        restored shouldBe listOf("Bot")
        wiki.close()
    }

    @Test
    fun `a cache written in JavaScript is asked first and given what the wiki said`() = runTest {
        val stored = mutableMapOf<String, Any?>()
        val cache: dynamic = js("({})")
        cache.get = { params: Record<String, String> -> params.toMap()["list"]?.let { stored[it] } }
        cache.put = { params: Record<String, String>, response: Any? ->
            params.toMap()["list"]?.let { stored[it] = response }
            null
        }
        mock.canned["categorymembers"] = """{"query":{"categorymembers":[{"ns":0,"title":"Lava"}]}}"""
        val wiki = connectWith("test.example.org", contact, settings("cache" to cache), mock.engine).await()

        wiki.categoryMembers("Lava").take() shouldBe listOf("Lava")
        val asked = mock.requests.count { it["list"] == "categorymembers" }
        wiki.categoryMembers("Lava").take() shouldBe listOf("Lava")

        mock.requests.count { it["list"] == "categorymembers" } shouldBe asked
        stored.keys.contains("categorymembers") shouldBe true
        wiki.close()
    }

    @Test
    fun `a disk cache answers a repeated read from its files`() = runTest {
        if (!hasFileSystem()) {
            shouldThrow<IllegalStateException> { DiskCache("anywhere") }
            return@runTest
        }
        val root = Path(SystemTemporaryDirectory, "kwikibot-cache-${js("Date.now()")}").toString()
        val cache = DiskCache(root, 60_000.0)
        cache.root shouldBe root
        mock.canned["categorymembers"] = """{"query":{"categorymembers":[{"ns":0,"title":"Lava"}]}}"""
        val wiki = connectWith("test.example.org", contact, settings("cache" to cache), mock.engine).await()

        wiki.categoryMembers("Lava").take() shouldBe listOf("Lava")
        val asked = mock.requests.count { it["list"] == "categorymembers" }
        wiki.categoryMembers("Lava").take() shouldBe listOf("Lava")
        mock.requests.count { it["list"] == "categorymembers" } shouldBe asked

        cache.clear()
        wiki.categoryMembers("Lava").take() shouldBe listOf("Lava")
        mock.requests.count { it["list"] == "categorymembers" } shouldBe asked + 1
        wiki.close()

        val params = recordOf("action" to "query", "list" to "x")
        cache.put(params, JSON.parse("""{"a":1}""")).await()
        (cache.get(params).await().asDynamic().a.unsafeCast<Any?>()) shouldBe 1
        cache.clear()
        cache.get(params).await() shouldBe null
    }

    @Test
    fun `a filesystem is asked for before a disk cache is made`() {
        shouldThrow<IllegalStateException> { requireFileSystem("a disk cache", available = false) }
    }

    @Test
    fun `families say where their wikis are and open by language`() = runTest {
        FAMILY_WIKTIONARY.kind shouldBe "wikimedia"
        listOf(FAMILY_WIKTIONARY.name, FAMILY_WIKTIONARY.domain) shouldBe
            listOf("wiktionary", "wiktionary.org")
        FAMILY_WIKTIONARY.endpoint("fr").server shouldBe "fr.wiktionary.org"
        FAMILY_COMMONS.kind shouldBe "single-site"
        listOf(FAMILY_COMMONS.server, FAMILY_COMMONS.scriptPath) shouldBe
            listOf("commons.wikimedia.org", "/w")
        FAMILY_COMMONS.endpoint("fr").server shouldBe "commons.wikimedia.org"
        listOf(
                FAMILY_WIKIPEDIA,
                FAMILY_WIKISOURCE,
                FAMILY_WIKIQUOTE,
                FAMILY_WIKIBOOKS,
                FAMILY_WIKINEWS,
                FAMILY_WIKIVERSITY,
                FAMILY_WIKIVOYAGE,
                FAMILY_WIKIDATA,
                FAMILY_META,
                FAMILY_MEDIAWIKI,
                FAMILY_TEST,
            )
            .forEach { family -> familyNamed(family.name)?.name shouldBe family.name }
        familyNamed("nope") shouldBe null
        val custom = customFamily("mine", "wiki.example.org", "")
        listOf(custom.name, custom.server, custom.scriptPath) shouldBe listOf("mine", "wiki.example.org", "")
        familyAt(endpointOf("a.example.org", "/x")).name shouldBe "a.example.org"
        familyAt(endpointOf("a.example.org"), "named").name shouldBe "named"

        val wiki =
            connectFamily("en", customFamily("t", "test.example.org"), contact, settings(), mock.engine)
                .await()
        wiki.endpoint.server shouldBe "test.example.org"
        wiki.close()
    }

    @Test
    fun `an endpoint is built, and read out of a page`() {
        val local = endpointOf("localhost:8080", "", secure = false)
        listOf(local.apiUrl, local.indexUrl, local.secure) shouldBe
            listOf("http://localhost:8080/api.php", "http://localhost:8080/index.php", false)
        local.articleUrl("Main Page") shouldBe "http://localhost:8080/wiki/Main_Page"
        wikimediaEndpoint("en", "wiktionary.org").server shouldBe "en.wiktionary.org"

        val html =
            """<link rel="EditURI" type="application/rsd+xml" href="//a.example.org/w/api.php?action=rsd">"""
        endpointFromHtml(html, "https://a.example.org/wiki/X")?.scriptPath shouldBe "/w"
        endpointFromHtml("<html></html>", "https://a.example.org/wiki/X") shouldBe null
    }

    @Test
    fun `script path and scheme reach the requests`() = runTest {
        val seen = mutableListOf<String>()
        val inner = mock.engine
        val engine = MockEngine { request ->
            seen += request.url.toString()
            inner.config.requestHandlers.first().invoke(this, request)
        }
        val wiki =
            connectWith(
                    "test.example.org",
                    contact,
                    settings("scriptPath" to "/wiki", "secure" to false),
                    engine,
                )
                .await()

        seen.first() shouldStartWith "http://test.example.org/wiki/api.php"
        wiki.close()
    }

    @Test
    fun `a client opens wikis that survive each other's closing`() = runTest {
        val client = createClient(contact, settings(), mock.engine)

        val first = client.wiki("test.example.org").await()
        val second = client.wikiOf("en", customFamily("t", "test.example.org")).await()

        first.close()
        second.siteName shouldBe "Test Wiki"
        second.close()
        client.close()
    }

    @Test
    fun `a wiki that cannot be opened rejects`() = runTest {
        val refusing = MockEngine { respond("no", HttpStatusCode.Forbidden) }

        val refused =
            shouldThrow<Throwable> { connectWith("x.example.org", contact, settings(), refusing).await() }

        describeError(refused)?.kind shouldBe "refused"
    }
}
