package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readString

class BotMainTest {

    private val configured =
        BotConfig.parse(
            """
            [bot]
            name = "TestBot"
            version = "1.0"
            contact = "https://example.org/TestBot"

            [login]
            account = "TestBot"
            botName = "test"
            passwordEnv = "TEST_PASSWORD"
            """
                .trimIndent()
        )

    private val anonymousConfig = configured.copy(login = null)

    private val errors = Buffer()
    private var opened: Pair<WikiConfig, Credentials>? = null

    private fun environment(
        config: BotConfig? = configured,
        identity: Identity = Identity(name = "TestBot", id = 1, groups = setOf("bot")),
        password: String? = "secret",
        findConfig: () -> BotConfig? = { config },
        open: (suspend () -> Unit)? = null,
    ) =
        BotEnvironment(
            findConfig = findConfig,
            variables = { if (it == "TEST_PASSWORD") password else null },
            errors = errors,
            open = { _, wikiConfig, credentials, use ->
                opened = wikiConfig to credentials
                open?.invoke()
                use(FakeWiki(FakePageService(), identity = identity))
            },
        )

    private suspend fun start(
        vararg args: String,
        anonymous: Boolean = false,
        environment: BotEnvironment = environment(),
        block: suspend BotStart.() -> Int = { 0 },
    ): Int = startBot(args.toList(), anonymous, environment, block)

    @Test
    fun `the bot runs with the wiki and its flags and its own arguments and exits as it says`() = runTest {
        var started: BotStart? = null

        val status =
            start("--save", "--read-delay", "2s", "fix-pos", "--only=x") {
                started = this
                3
            }

        status shouldBe 3
        val bot = checkNotNull(started)
        bot.options.save shouldBe true
        bot.arguments shouldBe listOf("fix-pos", "--only=x")
        bot.wiki.identity.name shouldBe "TestBot"
        bot.config shouldBe configured
        opened!!.first.throttle.read shouldBe 2.seconds
        opened!!.second shouldBe Credentials.BotPassword("TestBot", "test", "secret")
        errors.text() shouldBe "logged in as TestBot (bot)\n"
    }

    @Test
    fun `a bad flag is a usage error and nothing is opened`() = runTest {
        start("--limit", "none") shouldBe 2

        errors.text() shouldContain "--limit"
        opened shouldBe null
    }

    @Test
    fun `no configuration an unreadable one or a password that is not set stops the start`() = runTest {
        start(environment = environment(config = null)) shouldBe 1
        errors.text() shouldContain "no kwikibot.toml found; looked in "

        start(environment = environment(findConfig = { error("no configuration at x.toml") })) shouldBe 1
        start(environment = environment(findConfig = { throw IllegalArgumentException("line 3") })) shouldBe 1
        start(environment = environment(findConfig = { throw IOException("denied") })) shouldBe 1
        start(environment = environment(password = null)) shouldBe 1

        errors.text().lines().drop(1).take(4) shouldBe
            listOf(
                "no configuration at x.toml",
                "kwikibot.toml is not valid: line 3",
                "cannot read kwikibot.toml: denied",
                "the environment variable TEST_PASSWORD is not set, and the configuration says the " +
                    "password for TestBot is in it",
            )
        opened shouldBe null
    }

    @Test
    fun `a failure finding the configuration that is not about the file is not passed off as one`() =
        runTest {
            assertFailsWith<UnsupportedOperationException> {
                start(
                    environment =
                        environment(findConfig = { throw UnsupportedOperationException("no access to home") })
                )
            }
            errors.text() shouldBe ""
            opened shouldBe null
        }

    @Test
    fun `a bot runs logged in unless it says it may not`() = runTest {
        start(environment = environment(config = anonymousConfig)) shouldBe 1
        errors.text() shouldContain "names no login, and this bot runs logged in"

        val anonymousUser = Identity(name = "127.0.0.1", id = 0)
        val ran = mutableListOf<Boolean>()
        start(anonymous = true, environment = environment(anonymousConfig, anonymousUser)) {
            ran += wiki.identity.isAnonymous
            0
        } shouldBe 0
        ran shouldBe listOf(true)
        errors.text() shouldContain "not logged in\n"
    }

    @Test
    fun `a saving bot runs logged in and in the bot group whatever it allows`() = runTest {
        start("--save", anonymous = true, environment = environment(anonymousConfig)) shouldBe 1

        val human = Identity(name = "Someone", id = 7, groups = setOf("user"))
        start("--save", environment = environment(identity = human)) shouldBe 1
        start(environment = environment(identity = human)) shouldBe 0

        val loggedOut = Identity(name = "127.0.0.1", id = 0)
        start(environment = environment(identity = loggedOut)) shouldBe 1

        errors.text() shouldContain "Someone is not in the bot group; not saving without --trial"
        errors.text() shouldContain "not logged in: the wiki sees an anonymous user"
    }

    @Test
    fun `a trial saves as an account outside the bot group and says so`() = runTest {
        val human = Identity(name = "Someone", id = 7, groups = setOf("user"))
        var saving: Boolean? = null

        start("--save", "--trial", "--limit", "20", environment = environment(identity = human)) {
            saving = options.save
            0
        } shouldBe 0

        saving shouldBe true
        errors.text() shouldBe
            "logged in as Someone (user)\ntrial: saving without the bot flag, at most 20 pages\n"
    }

    @Test
    fun `a trial by a flagged bot is an ordinary saving run`() = runTest {
        start("--trial", "--save", "--limit", "20") shouldBe 0

        errors.text() shouldBe "logged in as TestBot (bot)\n"
    }

    @Test
    fun `a trial without a limit or without saving does not start`() = runTest {
        val human = Identity(name = "Someone", id = 7, groups = setOf("user"))
        var ran = false

        start("--save", "--trial", environment = environment(identity = human)) {
            0.also { ran = true }
        } shouldBe 2
        start("--trial", "--limit", "20", environment = environment(identity = human)) {
            0.also { ran = true }
        } shouldBe 2

        ran shouldBe false
        errors.text().lines().take(2) shouldBe
            List(2) { "--trial needs --save and --limit, how many pages to try" }
    }

    @Test
    fun `a login the wiki refuses or a wiki out of reach is one line`() = runTest {
        start(environment = environment(open = { throw WikiError.Auth.LoginFailed("WrongPass") })) shouldBe 1
        start(
            environment =
                environment(open = { throw WikiError.Transport.Unreachable("https://example.org/w/api.php") })
        ) shouldBe 1

        errors.text().lines().take(2) shouldBe
            listOf(
                "could not log in: login failed: WrongPass",
                "could not reach the wiki: cannot reach https://example.org/w/api.php",
            )
    }

    @Test
    fun `an error opening the wiki that is not about the login or the connection is not passed off as one`() =
        runTest {
            assertFailsWith<WikiError.ReadOnly> {
                start(environment = environment(open = { throw WikiError.ReadOnly("maintenance") }))
            }
            errors.text() shouldBe ""
        }

    @Test
    fun `a failure in the bot's own work is the bot's to report`() = runTest {
        assertFailsWith<WikiError.Auth.NotLoggedIn> {
            start { throw WikiError.Auth.NotLoggedIn("editing") }
        }
        assertFailsWith<WikiError.Transport.Refused> {
            start { throw WikiError.Transport.Refused(403, "https://example.org/w/api.php", "") }
        }
    }

    @Test
    fun `a failure the bot lets through ends the process with its stack trace and status 1`() = runTest {
        val failure = WikiError.Auth.NotLoggedIn("editing")

        val status = exitStatus(emptyList(), anonymous = false, environment()) { throw failure }

        status shouldBe 1
        errors.text() shouldContain checkNotNull(failure.message)
        exitStatus(emptyList(), anonymous = false, environment()) { 4 } shouldBe 4
    }

    /** What the bot wrote as errors so far, leaving it there. */
    private fun Buffer.text(): String = copy().readString()
}
