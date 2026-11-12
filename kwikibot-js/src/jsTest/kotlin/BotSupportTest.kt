import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BotSupportTest {

    private val toml =
        """
        maxlag = 2

        [bot]
        name = "MyBot"
        version = "2.0"
        contact = "https://example.org/me"

        [wiki]
        lang = "fr"
        family = "wikipedia"

        [throttle]
        read = "200ms"
        write = "5s"

        [login]
        account = "MyBot"
        botName = "task"
        passwordEnv = "MY_PASSWORD"

        [oauth]
        tokenEnv = "MY_TOKEN"
        username = "MyBot"
        optional = true

        [run]
        readConcurrency = 8
        writeConcurrency = 2
        readBatch = 50

        [http]
        maxConcurrentRequests = 2
        maxIdleConnections = 4
        timeout = "30s"

        [retry]
        maxRetries = 1
        initialDelay = "2s"
        maxDelay = "4s"
        relogins = 0
        """
            .trimIndent()

    @Test
    fun `a configuration reads every section`() {
        val config = parseBotConfig(toml)

        listOf(config.bot.name, config.bot.version, config.bot.contact) shouldBe
            listOf("MyBot", "2.0", "https://example.org/me")
        listOf(config.wiki.lang, config.wiki.family, config.wiki.server, config.wiki.scriptPath) shouldBe
            listOf("fr", "wikipedia", null, "/w")
        listOf(config.throttle.read, config.throttle.write) shouldBe listOf("200ms", "5s")
        listOf(
            config.login?.account,
            config.login?.botName,
            config.login?.passwordEnv,
            config.login?.optional,
        ) shouldBe listOf("MyBot", "task", "MY_PASSWORD", false)
        config.cache shouldBe null
        config.maxlag shouldBe 2
        listOf(config.oauth?.tokenEnv, config.oauth?.username, config.oauth?.optional) shouldBe
            listOf("MY_TOKEN", "MyBot", true)
        listOf(config.run.readConcurrency, config.run.writeConcurrency, config.run.readBatch) shouldBe
            listOf(8, 2, 50)
        listOf(
            config.http.maxConcurrentRequests,
            config.http.maxIdleConnections,
            config.http.timeout,
        ) shouldBe listOf(2, 4, "30s")
        listOf(
            config.retry.maxRetries,
            config.retry.initialDelay,
            config.retry.maxDelay,
            config.retry.relogins,
        ) shouldBe listOf(1, "2s", "4s", 0)

        config.language() shouldBe "fr"
        config.family().name shouldBe "wikipedia"
        config.endpoint().server shouldBe "fr.wikipedia.org"
    }

    @Test
    fun `a configuration says what a client is opened with`() {
        val options = parseBotConfig(toml).connectOptions()

        options.userAgent?.headerValue.orEmpty() shouldContain "MyBot/2.0"
        options.throttle?.read shouldBe 200.0
        options.throttle?.write shouldBe 5000.0
        options.retry?.maxRetries shouldBe 1
        options.retry?.initialDelay shouldBe 2000.0
        options.retry?.maxDelay shouldBe 4000.0
        options.maxlag shouldBe 2
        options.relogins shouldBe 0
        options.http?.maxConcurrentRequests shouldBe 2
        options.http?.maxIdleConnections shouldBe 4
        options.http?.timeout shouldBe 30_000.0
        options.login shouldBe null
    }

    @Test
    fun `a configuration reads its secrets from the environment it is given`() {
        val config = parseBotConfig(toml)

        val password = config.credentials { name -> if (name == "MY_PASSWORD") "secret" else null }
        listOf(password?.account, password?.botName) shouldBe listOf("MyBot", "task")

        (config.credentials { name -> if (name == "MY_TOKEN") "token" else null } as OAuth2).username shouldBe
            "MyBot"

        shouldThrow<IllegalStateException> { config.credentials { null } }.message.orEmpty() shouldContain
            "MY_PASSWORD"

        val none = parseBotConfig("[bot]\nname = \"B\"\ncontact = \"c\"\n")
        none.credentials { null } shouldBe null
    }

    @Test
    fun `a configuration can be made, and has a template and a search path`() {
        val config =
            createBotConfig(
                optionsOf(
                    "bot" to BotIdentity("Made", "c"),
                    "wiki" to WikiSelection("de", "wiktionary", "wiki.example.org", "/mw"),
                    "throttle" to ThrottleSettings("1s", "2s"),
                    "login" to LoginSettings("Made", "task"),
                    "cache" to CacheSettings("here", "1h"),
                    "maxlag" to 0,
                    "oauth" to OAuthSettings(),
                    "run" to RunSettings(readBatch = 3),
                    "http" to HttpSection(timeout = "9s"),
                    "retry" to RetrySettings(maxRetries = 9),
                )
            )

        config.bot.version shouldBe "1.0"
        config.endpoint().server shouldBe "wiki.example.org"
        config.endpoint().scriptPath shouldBe "/mw"
        config.throttle.write shouldBe "2s"
        config.login?.passwordEnv shouldBe "KWIKIBOT_PASSWORD"
        config.cache?.path shouldBe "here"
        config.maxlag shouldBe 0
        config.oauth?.tokenEnv shouldBe "KWIKIBOT_OAUTH_TOKEN"
        config.run.readBatch shouldBe 3
        config.http.timeout shouldBe "9s"
        config.retry.maxRetries shouldBe 9

        val plain = createBotConfig(optionsOf("bot" to BotIdentity("Plain", "c", "3")))
        plain.bot.version shouldBe "3"
        plain.wiki.family shouldBe "wiktionary"
        plain.maxlag shouldBe BOT_CONFIG_DEFAULT_MAXLAG
        plain.connectOptions().cache shouldBe null

        BOT_CONFIG_FILE_NAME shouldBe "kwikibot.toml"
        botConfigTemplate() shouldContain "[bot]"
        parseBotConfig(botConfigTemplate()).bot.name shouldBe "MyBot"
    }

    @Test
    fun `a configuration on disk is read in Node, and refused in a browser`() = runTest {
        if (!hasFileSystem()) {
            shouldThrow<IllegalStateException> { readBotConfig("x") }.message.orEmpty() shouldContain
                "Node only"
            shouldThrow<IllegalStateException> { findBotConfig() }.message.orEmpty() shouldContain "Node only"
            return@runTest
        }
        botConfigSearchPath().first() shouldBe BOT_CONFIG_FILE_NAME
        val path = temporaryPath("config.toml")
        writeTemporary(path, "[bot]\nname = \"OnDisk\"\ncontact = \"c\"\n")

        readBotConfig(path).bot.name shouldBe "OnDisk"
        findBotConfig(path)?.bot?.name shouldBe "OnDisk"
        shouldThrow<Throwable> { findBotConfig("$path.absent") }
        removeTemporary(path)
    }

    @Test
    fun `a configuration opens its wiki on a client`() = runTest {
        val mock = MockWiki()
        val config =
            parseBotConfig("[bot]\nname = \"B\"\ncontact = \"c\"\n[wiki]\nserver = \"test.example.org\"\n")
        val client = createClient("c", null, mock.engine)

        val wiki = config.connect(client).await()

        wiki.siteName shouldBe "Test Wiki"
        wiki.close()
        client.close()
    }

    @Test
    fun `the common flags are read and handed back`() {
        val parsed =
            parseCommonOptions(
                arrayOf(
                    "--save",
                    "--limit=3",
                    "--diff-log",
                    "d.txt",
                    "--skip-log",
                    "s.txt",
                    "--stop-page",
                    "Stop",
                    "--read-delay",
                    "100ms",
                    "--write-delay",
                    "2s",
                    "--http-connections",
                    "5",
                    "--contact",
                    "me",
                    "--state",
                    "dir",
                    "--resume",
                    "--trial",
                    "mine",
                )
            )

        val options = parsed.options
        parsed.rest.toList() shouldBe listOf("mine")
        listOf(options.save, options.resume, options.trial) shouldBe listOf(true, true, true)
        options.limit shouldBe 3
        listOf(options.diffLog, options.skipLog, options.state) shouldBe listOf("d.txt", "s.txt", "dir")
        options.stopPage shouldBe "Stop"
        listOf(options.readDelay, options.writeDelay) shouldBe listOf(100.0, 2000.0)
        options.httpConnections shouldBe 5
        options.contact shouldBe "me"

        COMMON_OPTIONS_USAGE shouldContain "--save"
        shouldThrow<IllegalArgumentException> { parseCommonOptions(arrayOf("--limit")) }
            .message
            .orEmpty() shouldContain "--limit"
    }

    @Test
    fun `the common flags change a client's options`() {
        val made =
            createCommonOptions(
                optionsOf(
                    "readDelay" to 50.0,
                    "httpConnections" to 7,
                    "contact" to "elsewhere",
                )
            )
        val base: ConnectOptions =
            optionsOf(
                "maxlag" to 1,
                "userAgent" to UserAgent("Mine", "1", "here"),
                "throttle" to optionsOf<ThrottleOptions>("write" to 9.0),
            )

        val merged = made.connectOptions(base)

        merged.maxlag shouldBe 1
        merged.userAgent?.contact shouldBe "elsewhere"
        merged.throttle?.read shouldBe 50.0
        merged.throttle?.write shouldBe 9.0
        merged.http?.maxConcurrentRequests shouldBe 7
        merged.http?.maxIdleConnections shouldBe 7
        createCommonOptions().connectOptions().maxlag shouldBe null
        base.throttle?.read shouldBe null

        shouldThrow<IllegalArgumentException> { createCommonOptions(optionsOf("resume" to true)) }
    }

    @Test
    fun `the common flags make a stop policy and a log`() = runTest {
        val mock = MockWiki(mapOf("Stop" to "false"))
        val wiki = mock.open()

        createCommonOptions(optionsOf("stopPage" to "Stop")).stopPolicy(wiki).mayContinue().await() shouldBe
            true
        createCommonOptions().stopPolicy(wiki).mayContinue().await() shouldBe true

        val log = createCommonOptions().runLog(wiki)
        wiki
            .botRun(
                optionsOf(
                    "source" to pageSourceOfTitles(arrayOf("Absent")),
                    "transform" to { _: Page -> null },
                    "onOutcome" to { outcome: PageOutcome -> log.record(outcome) },
                )
            )
            .await()
        if (!hasFileSystem()) {
            shouldThrow<IllegalStateException> {
                createCommonOptions(optionsOf("diffLog" to "d")).runLog(wiki)
            }
        }
        wiki.close()
    }

    @Test
    fun `a prompt with nobody there answers as told and counts`() = runTest {
        val prompt = NonInteractive(answer = true, textAnswer = "typed")
        val choices = arrayOf<Choice>(choice("a", "first", "one"), choice("b", "second", "two"))

        prompt.isInteractive shouldBe false
        prompt.confirm("Go?").await() shouldBe true
        prompt.choose("Which?", choices).await() shouldBe "one"
        prompt.text("What?").await() shouldBe "typed"
        prompt.asked shouldBe 3

        NonInteractive().text("What?", "given").await() shouldBe "given"
        shouldThrow<IllegalArgumentException> {
            prompt.choose("Which?", arrayOf(choice("ab", "x", "y"))).await()
        }
    }

    @Test
    fun `a terminal prompt writes the question and reads the answer`() = runTest {
        val written = mutableListOf<String>()
        val lines = ArrayDeque(listOf("maybe", "y", "b", "words", null))
        val prompt = TerminalPrompt({ written += it }, { answering(Promise.resolve(lines.removeFirst())) })
        val choices = arrayOf<Choice>(choice("a", "first", "one"), choice("b", "second", "two"))

        prompt.isInteractive shouldBe true
        prompt.confirm("Go?").await() shouldBe true
        written.first() shouldBe "Go? [y/N] "
        prompt.choose("Which?", choices).await() shouldBe "two"
        written.last() shouldBe "Which? (a = first, b = second) "
        prompt.text("What?", "given").await() shouldBe "words"
        prompt.text("What?", "given").await() shouldBe "given"

        TerminalPrompt(output = {}).isInteractive shouldBe true
        TerminalPrompt(input = { answering(null) }).isInteractive shouldBe true
        TerminalPrompt().isInteractive shouldBe true
    }

    @Test
    fun `a scripted prompt reads its answers, then its fallback`() = runTest {
        val prompt = ScriptedPrompt(arrayOf("y", "", "b", "words"), NonInteractive(answer = true))
        val choices = arrayOf<Choice>(choice("a", "first", "one"), choice("b", "second", "two"))

        prompt.isInteractive shouldBe false
        prompt.confirm("Go?").await() shouldBe true
        prompt.confirm("Go?", fallback = false).await() shouldBe false
        prompt.choose("Which?", choices).await() shouldBe "two"
        prompt.text("What?").await() shouldBe "words"
        prompt.confirm("Go?").await() shouldBe true
        ScriptedPrompt(emptyArray()).confirm("Go?").await() shouldBe false
    }

    @Test
    fun `a prompt of your own asks through your functions`() = runTest {
        val prompt =
            promptFrom(
                optionsOf(
                    "isInteractive" to false,
                    "confirm" to { _: String, fallback: Boolean -> Promise.resolve(!fallback) },
                    "choose" to { _: String, choices: Array<Choice> -> choices.last().value },
                    "text" to { question: String, fallback: String -> "$question $fallback" },
                )
            )

        prompt.isInteractive shouldBe false
        prompt.confirm("Go?").await() shouldBe true
        prompt
            .choose("Which?", arrayOf(choice("a", "first", "one"), choice("b", "second", "two")))
            .await() shouldBe "two"
        prompt.text("What?", "given").await() shouldBe "What? given"
    }

    @Test
    fun `a bot's start reports a bad flag with its exit status`() = runTest {
        val process: dynamic = js("typeof process === 'undefined' ? null : process")
        if (process == null) return@runTest
        val realExit = process.exit
        val realWrite = process.stderr.write
        val statuses = mutableListOf<Int>()
        process.exit = { status: Int -> statuses += status }
        process.stderr.write = { _: Any? -> true }
        try {
            botMain(arrayOf("--limit"), { _ -> untyped(0) }, anonymous = true)
            repeat(WAITS) { if (statuses.isEmpty()) realDelay() }
        } finally {
            process.exit = realExit
            process.stderr.write = realWrite
        }
        statuses shouldBe listOf(2)
    }

    private suspend fun realDelay() {
        Promise<Unit> { resolve, _ -> js("setTimeout")({ resolve(Unit) }, 10) }.await()
    }

    private fun choice(key: String, label: String, value: String): Choice =
        optionsOf("key" to key, "label" to label, "value" to value)

    private companion object {
        const val WAITS = 200
    }
}
