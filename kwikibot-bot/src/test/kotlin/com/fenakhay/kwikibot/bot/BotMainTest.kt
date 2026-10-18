package com.fenakhay.kwikibot.bot

import com.fenakhay.kwikibot.client.WikiConfig
import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.testkit.FakePageService
import com.fenakhay.kwikibot.testkit.FakeWiki
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

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

    private val errors = StringBuilder()
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
    fun `the bot runs with the wiki, its flags and its own arguments, and exits as it says`() = runTest {
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
        errors.toString() shouldBe "logged in as TestBot (bot)\n"
    }

    @Test
    fun `a bad flag is a usage error and nothing is opened`() = runTest {
        start("--limit", "none") shouldBe 2

        errors.toString() shouldContain "--limit"
        opened shouldBe null
    }

    @Test
    fun `no configuration, an unreadable one or a password that is not set stops the start`() = runTest {
        start(environment = environment(config = null)) shouldBe 1
        errors.toString() shouldContain "no kwikibot.toml found; looked in "

        start(environment = environment(findConfig = { error("no configuration at x.toml") })) shouldBe 1
        start(environment = environment(findConfig = { throw IllegalArgumentException("line 3") })) shouldBe 1
        start(environment = environment(findConfig = { throw IOException("denied") })) shouldBe 1
        start(environment = environment(password = null)) shouldBe 1

        errors.lines().drop(1).take(4) shouldBe
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
            assertFailsWith<SecurityException> {
                start(
                    environment = environment(findConfig = { throw SecurityException("no access to home") })
                )
            }
            errors.toString() shouldBe ""
            opened shouldBe null
        }

    @Test
    fun `a bot runs logged in unless it says it may not`() = runTest {
        start(environment = environment(config = anonymousConfig)) shouldBe 1
        errors.toString() shouldContain "names no login, and this bot runs logged in"

        val anonymousUser = Identity(name = "127.0.0.1", id = 0)
        val ran = mutableListOf<Boolean>()
        start(anonymous = true, environment = environment(anonymousConfig, anonymousUser)) {
            ran += wiki.identity.isAnonymous
            0
        } shouldBe 0
        ran shouldBe listOf(true)
        errors.toString() shouldContain "not logged in\n"
    }

    @Test
    fun `a saving bot runs logged in and in the bot group, whatever it allows`() = runTest {
        start("--save", anonymous = true, environment = environment(anonymousConfig)) shouldBe 1

        val human = Identity(name = "Someone", id = 7, groups = setOf("user"))
        start("--save", environment = environment(identity = human)) shouldBe 1
        start(environment = environment(identity = human)) shouldBe 0

        val loggedOut = Identity(name = "127.0.0.1", id = 0)
        start(environment = environment(identity = loggedOut)) shouldBe 1

        errors.toString() shouldContain "Someone is not in the bot group; not saving"
        errors.toString() shouldContain "not logged in: the wiki sees an anonymous user"
    }

    @Test
    fun `a login the wiki refuses or a wiki out of reach is one line`() = runTest {
        start(environment = environment(open = { throw WikiError.Auth.LoginFailed("WrongPass") })) shouldBe 1
        start(
            environment =
                environment(open = { throw WikiError.Transport.Unreachable("https://example.org/w/api.php") })
        ) shouldBe 1

        errors.lines().take(2) shouldBe
            listOf(
                "could not log in: login failed: WrongPass",
                "could not reach the wiki: cannot reach https://example.org/w/api.php",
            )
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
}
