package com.fenakhay.kwikibot.cli

import com.fenakhay.kwikibot.net.UserAgent
import com.github.ajalt.clikt.command.test
import com.github.ajalt.clikt.testing.CliktCommandTestResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class WikiCommandTest {

    private val directory = Files.createTempDirectory("kwikibot-cli")
    private val wiki = MockWiki(pages = mapOf("Volcano" to "==English==\n{{en-noun}}"))
    private var searchPath = listOf<Path>(directory.resolve("absent.toml"))

    private val configured =
        """
        [bot]
        name = "TestBot"
        version = "2.0"
        contact = "https://example.org/TestBot"

        [wiki]
        lang = "de"
        family = "wikipedia"

        [login]
        account = "TestBot"
        botName = "task"
        passwordEnv = "TEST_PASSWORD"
        """
            .trimIndent()

    /** Writes [text] as a configuration file and puts it on the search path, after one that is absent. */
    private fun configuration(text: String = configured, name: String = "kwikibot.toml"): Path {
        val file = directory.resolve(name)
        file.writeText(text)
        searchPath = searchPath + listOf(file)
        return file
    }

    private suspend fun kwikibot(vararg argv: String, password: String? = null): CliktCommandTestResult =
        kwikibot(CliEnvironment(searchPath = { searchPath }, engine = wiki.engine))
            .test(argv.toList(), envvars = listOfNotNull(password?.let { "TEST_PASSWORD" to it }).toMap())

    private val hosts: List<String>
        get() = wiki.requests.map { it.host }.distinct()

    private val logins: List<Map<String, String>>
        get() = wiki.requests.map { it.parameters }.filter { it["action"] == "login" }

    @Test
    fun `whoami with no configuration opens the wiki the flags name, anonymously`() = runTest {
        val result =
            kwikibot("whoami", "--contact", "https://example.org/me", "--lang", "fr", "--family", "wikipedia")

        result.statusCode shouldBe 0
        result.stdout shouldBe
            """
            wiki:   testwiki (Test Wiki, MediaWiki 1.47.0)
            user:   192.0.2.1 (anonymous)
            groups: *
            bot:    false

            """
                .trimIndent()
        hosts shouldBe listOf("fr.wikipedia.org")
        wiki.requests.map { it.userAgent }.toSet() shouldBe
            setOf(UserAgent("kwikibot", UserAgent.LIBRARY_VERSION, "https://example.org/me").headerValue)
        logins.shouldBeEmpty()
    }

    @Test
    fun `without a configuration or a contact nothing is opened`() = runTest {
        val result = kwikibot("whoami")

        result.statusCode shouldBe 1
        result.stderr shouldBe
            "no configuration found and no --contact given; run 'kwikibot init-config' or pass --contact\n"
        wiki.requests.shouldBeEmpty()
    }

    @Test
    fun `the configuration on the search path names the wiki, the user agent and the login`() = runTest {
        configuration()

        val result = kwikibot("whoami", password = "secret")

        result.statusCode shouldBe 0
        result.stdout shouldBe
            """
            wiki:   testwiki (Test Wiki, MediaWiki 1.47.0)
            user:   TestBot
            groups: bot, user
            bot:    true

            """
                .trimIndent()
        hosts shouldBe listOf("de.wikipedia.org")
        wiki.requests.map { it.userAgent }.toSet() shouldBe
            setOf(UserAgent("TestBot", "2.0", "https://example.org/TestBot").headerValue)
        logins.map { it["lgname"] to it["lgpassword"] } shouldBe listOf("TestBot@task" to "secret")
    }

    @Test
    fun `flags win over the configuration`() = runTest {
        configuration()

        val result =
            kwikibot(
                "whoami",
                "--contact=https://example.org/me",
                "--lang=fr",
                "--account=Other",
                "--bot-name=check",
                "--password=secret",
            )

        result.statusCode shouldBe 0
        result.stdout.lines()[1] shouldBe "user:   Other"
        hosts shouldBe listOf("fr.wikipedia.org")
        wiki.requests.map { it.userAgent }.toSet() shouldBe
            setOf(UserAgent("kwikibot", UserAgent.LIBRARY_VERSION, "https://example.org/me").headerValue)
        logins.map { it["lgname"] } shouldBe listOf("Other@check")
    }

    @Test
    fun `a language or family flag keeps the rest of the wiki the configuration names`() = runTest {
        configuration()

        kwikibot("whoami", "--family", "wikisource", password = "secret").statusCode shouldBe 0

        hosts shouldBe listOf("de.wikisource.org")
    }

    @Test
    fun `with no configuration and no flag naming one, the wiki is English Wiktionary`() = runTest {
        kwikibot("whoami", "--contact", "https://example.org/me").statusCode shouldBe 0

        hosts shouldBe listOf("en.wiktionary.org")
    }

    @Test
    fun `a server the configuration names is where the wiki is`() = runTest {
        configuration(
            """
            [bot]
            name = "TestBot"
            contact = "https://example.org/TestBot"

            [wiki]
            server = "wiki.example.org"
            scriptPath = ""
            """
                .trimIndent()
        )

        kwikibot("whoami").statusCode shouldBe 0

        hosts shouldBe listOf("wiki.example.org")
    }

    @Test
    fun `--config is read in place of the search path`() = runTest {
        configuration()
        val other =
            directory.resolve("other.toml").apply {
                writeText(configured.replace("lang = \"de\"", "lang = \"nl\""))
            }

        kwikibot("whoami", "--config", other.toString(), password = "secret").statusCode shouldBe 0

        hosts shouldBe listOf("nl.wikipedia.org")
    }

    @Test
    fun `a configuration that is missing, invalid or unreadable is one line, and nothing is opened`() =
        runTest {
            val missing = directory.resolve("missing.toml")
            val invalid = directory.resolve("invalid.toml").apply { writeText("[bot]\nname = \"TestBot\"\n") }
            val unreadable = Files.createDirectory(directory.resolve("directory.toml"))

            val results =
                listOf(missing, invalid, unreadable).map { kwikibot("whoami", "--config", it.toString()) }

            results.map { it.statusCode } shouldBe listOf(1, 1, 1)
            results[0].stderr shouldBe "no configuration at $missing\n"
            results[1].stderr shouldStartWith "$invalid is not valid: "
            results[2].stderr shouldStartWith "cannot read $unreadable: "
            results.forEach { it.stderr.lines().size shouldBe 2 }
            wiki.requests.shouldBeEmpty()
        }

    @Test
    fun `a configuration naming what does not exist is a message, and nothing is opened`() = runTest {
        configuration(configured.replace("family = \"wikipedia\"", "family = \"wikiplanet\""))

        val unknownInFile = kwikibot("whoami", password = "secret")
        val unknownFlag = kwikibot("whoami", "--family", "wikimoon", password = "secret")

        unknownInFile.statusCode shouldBe 1
        unknownInFile.stderr shouldBe
            "unknown family 'wikiplanet'; name a Wikimedia project or set wiki.server\n"
        unknownFlag.statusCode shouldBe 1
        unknownFlag.stderr shouldBe "unknown family: wikimoon\n"
        wiki.requests.shouldBeEmpty()
    }

    @Test
    fun `a configuration with a value that cannot be used is a message, and nothing is opened`() = runTest {
        configuration("$configured\n\n[throttle]\nread = \"soon\"\n")

        val result = kwikibot("whoami", password = "secret")

        result.statusCode shouldBe 1
        result.stderr shouldBe "Invalid duration string format: 'soon'.\n"
        wiki.requests.shouldBeEmpty()
    }

    @Test
    fun `a password the environment does not hold is a message, and nothing is opened`() = runTest {
        configuration()

        val result = kwikibot("whoami")

        result.statusCode shouldBe 1
        result.stderr shouldBe
            "the environment variable TEST_PASSWORD is not set, and the configuration says the " +
                "password for TestBot is in it\n"
        wiki.requests.shouldBeEmpty()
    }

    @Test
    fun `a login the wiki refuses is a message`() = runTest {
        configuration()

        val result = kwikibot("whoami", password = "wrong")

        result.statusCode shouldBe 1
        result.stderr shouldBe "login failed: Incorrect password.\n"
        result.stdout shouldBe ""
    }

    @Test
    fun `login reports the account it logged in as`() = runTest {
        configuration()

        val result = kwikibot("login", password = "secret")

        result.statusCode shouldBe 0
        result.stdout shouldBe "logged in to testwiki as TestBot\ngroups: bot, user\n"
    }

    @Test
    fun `login fails when nothing was configured to log in with`() = runTest {
        val result = kwikibot("login", "--contact", "https://example.org/me")

        result.statusCode shouldBe 1
        result.stdout shouldBe ""
        result.stderr shouldBe "not logged in: no credentials were configured\n"
    }

    @Test
    fun `get prints a page's wikitext`() = runTest {
        val result = kwikibot("get", "Volcano", "--contact", "https://example.org/me")

        result.statusCode shouldBe 0
        result.stdout shouldBe "==English==\n{{en-noun}}\n"
        wiki.requests.single { it.parameters["titles"] != null }.parameters["titles"] shouldBe "Volcano"
    }

    @Test
    fun `get of a page that does not exist fails`() = runTest {
        val result = kwikibot("get", "Nope", "--contact", "https://example.org/me")

        result.statusCode shouldBe 1
        result.stdout shouldBe ""
        result.stderr shouldBe "no such page: Nope\n"
    }
}
