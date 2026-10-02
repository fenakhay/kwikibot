package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.client.internal.wire.MediaWikiBehaviour
import com.fenakhay.kwikibot.client.service.AbuseFilterProperty
import com.fenakhay.kwikibot.client.service.ExtensionService
import com.fenakhay.kwikibot.client.service.ParseProperty
import com.fenakhay.kwikibot.client.service.WatchMode
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.net.RequestKind
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.TransportListener
import com.fenakhay.kwikibot.protocol.ApiWarning
import com.fenakhay.kwikibot.protocol.ApiWarningListener
import com.fenakhay.kwikibot.testkit.FailOnDeprecation
import com.fenakhay.kwikibot.wikitext.Wikitext
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The library against a MediaWiki release it does not otherwise meet.
 *
 * [LiveWikiTest] reads Wikimedia, which runs the newest build. Third-party wikis run older releases, and
 * those are where a respelled value goes wrong: the new spelling does not exist yet, and the old one must be
 * sent instead. CI starts each supported release in Docker with `.github/mediawiki/start.sh` and points this
 * test at it through `KWIKI_MEDIAWIKI`. Without that variable it is skipped.
 *
 * The wiki is a throwaway, so this test also writes to it, and it is stricter than [LiveWikiTest]: any
 * warning fails the request that drew it, since every value sent should be one the release takes.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "KWIKI_MEDIAWIKI", matches = "https?://.+")
class LocalWikiTest {

    /** Every request the wiki answered, to check which spelling went out. */
    private val sent = CopyOnWriteArrayList<ApiRequest>()

    private val strict =
        object : ApiWarningListener {
            private val failing =
                FailOnDeprecation(
                    others = { request, warning ->
                        throw AssertionError(
                            "action=${request.action} drew a warning: $warning (${warning.code})"
                        )
                    }
                )

            override fun onWarning(request: ApiRequest, warning: ApiWarning) =
                failing.onWarning(request, warning)

            override fun onResponse(request: ApiRequest, response: JsonObject) {
                sent += request
                super.onResponse(request, response)
            }
        }

    private fun onLocalWiki(
        listener: TransportListener = TransportListener.NONE,
        block: suspend (Wiki) -> Unit,
    ): Unit = runBlocking {
        val url = URI(System.getenv("KWIKI_MEDIAWIKI"))
        val endpoint =
            ApiEndpoint(
                server = url.authority,
                scriptPath = url.path.orEmpty().trimEnd('/'),
                secure = url.scheme == "https",
            )
        val config =
            WikiConfig(
                userAgent = UserAgent("kwikibot-localtest", "0.1.0", "https://github.com/fenakhay/kwikibot"),
                throttle = Throttle(Duration.ZERO, Duration.ZERO),
                onWarning = strict,
                listener = listener,
            )
        // The defaults are the ones .github/mediawiki/start.sh sets up.
        val credentials =
            Credentials.BotPassword(
                account = environment("KWIKI_MEDIAWIKI_ACCOUNT") ?: "Admin",
                botName = environment("KWIKI_MEDIAWIKI_BOT") ?: "kwikibot",
                password = environment("KWIKI_MEDIAWIKI_BOT_PASSWORD") ?: "kwikibotkwikibotkwikibotkwikibot",
            )

        WikiClient(config, credentials).use { client -> block(client.wiki(endpoint)) }
    }

    @Test
    fun `the release is one this library supports, and the session is logged in`(): Unit =
        onLocalWiki { wiki ->
            println("testing against ${wiki.info.generator}")

            wiki.info.hasVersion(MediaWikiBehaviour.OLDEST_SUPPORTED) shouldBe true
            wiki.identity.isAnonymous shouldBe false
        }

    @Test
    @Suppress("DEPRECATION")
    fun `headings come back in whichever spelling of the table of contents the release takes`(): Unit =
        onLocalWiki { wiki ->
            val text = "==Alpha==\nText.\n===Beta===\nMore.\n==Gamma==\nEnd."
            val context = wiki.ref("Sandbox")

            val current = wiki.renderer.resolveText(text, context, setOf(ParseProperty.TOC_DATA)).sections
            val deprecated = wiki.renderer.resolveText(text, context, setOf(ParseProperty.SECTIONS)).sections

            current.map { it.heading } shouldBe listOf("Alpha", "Beta", "Gamma")
            current.map { it.level } shouldBe listOf(2, 3, 2)
            current.map { it.tocLevel } shouldBe listOf(1, 2, 1)
            deprecated shouldBe current

            val takesTocData = "tocdata" in wiki.paramInfo.values("parse", "prop").orEmpty()
            parsed().map { it.params["prop"] }.distinct() shouldBe
                listOf(if (takesTocData) "tocdata" else "sections")
        }

    @Test
    fun `a page written, edited and read back says what was written`(): Unit = onLocalWiki { wiki ->
        val page = wiki.ref("Kwikibot test ${UUID.randomUUID()}")

        val created =
            wiki.pages
                .edit(page) {
                    text = "==First==\nOne."
                    summary = "kwikibot local test"
                    watchlist = WatchMode.NO_CHANGE
                }
                .shouldBeInstanceOf<EditOutcome.Saved>()

        wiki.pages
            .edit(page) {
                appendText = "\n==Second==\nTwo."
                summary = "kwikibot local test"
                baseRevision = created.revision
                watchlist = WatchMode.WATCH
            }
            .shouldBeInstanceOf<EditOutcome.Saved>()

        wiki.pages.content(page)?.text shouldBe "==First==\nOne.\n==Second==\nTwo."
        wiki.renderer.sections(page).map { it.heading } shouldBe listOf("First", "Second")
        wiki.revisions.history(page).toList().size shouldBe 2
    }

    @Test
    fun `every activity list takes the properties it asks for`(): Unit = onLocalWiki { wiki ->
        wiki.pages.edit(wiki.ref("Kwikibot test ${UUID.randomUUID()}")) {
            text = "Something for the lists to find."
            summary = "kwikibot local test"
            watchlist = WatchMode.WATCH
        }

        wiki.logs.recentChanges(limit = 5).toList().isNotEmpty() shouldBe true
        wiki.logs.watchlistChanges(limit = 5).toList().isNotEmpty() shouldBe true
        wiki.logs.events(limit = 5).toList().isNotEmpty() shouldBe true
        wiki.revisions.allRevisions(limit = 5).toList().isNotEmpty() shouldBe true
        wiki.users.allUsers(limit = 5).toList().isNotEmpty() shouldBe true
        wiki.users.info(listOf("Admin")).keys shouldBe setOf("Admin")
        wiki.users.blocks(limit = 5).toList()
    }

    @Test
    fun `abuse filters are asked for in the spelling the release takes`(): Unit = onLocalWiki { wiki ->
        assumeTrue(wiki.extensions.has(ExtensionService.ABUSE_FILTER), "AbuseFilter is not installed")

        wiki.extensions.abuseFilters(properties = AbuseFilterProperty.DEFAULT).toList()

        val takesFlags = "flags" in wiki.paramInfo.values("query+abusefilters", "prop").orEmpty()
        val asked = sent.single { it.params["list"] == "abusefilters" }.params["abfprop"].orEmpty().split('|')
        if (takesFlags) {
            ("flags" in asked) shouldBe true
        } else {
            asked.containsAll(listOf("status", "private", "protected")) shouldBe true
        }
    }

    @Test
    fun `an update never creates a page and a creation never overwrites one`(): Unit = onLocalWiki { wiki ->
        val missing = wiki.ref("Kwikibot test ${UUID.randomUUID()}")
        val existing = wiki.ref("Kwikibot test ${UUID.randomUUID()}")
        wiki.pages.edit(existing) {
            text = "Here already."
            summary = "kwikibot local test"
        }

        wiki.pages
            .edit(missing) {
                text = "Should not exist."
                summary = "kwikibot local test"
                noCreate = true
            }
            .shouldBeInstanceOf<EditOutcome.Refused>()
        wiki.pages.exists(missing) shouldBe false

        wiki.pages
            .edit(existing) {
                text = "Should not replace it."
                summary = "kwikibot local test"
                createOnly = true
            }
            .shouldBeInstanceOf<EditOutcome.Refused>()
        wiki.pages.content(existing)?.text shouldBe "Here already."
    }

    @Test
    fun `a session the wiki ends is logged back into, and the request goes through`(): Unit =
        onLocalWiki(listener = relogins) { wiki ->
            val page = wiki.ref("Kwikibot test ${UUID.randomUUID()}")

            // Ends the session from under the client, as a wiki can at any time.
            val token = wiki.tokens.token()
            wiki.transport.call(ApiRequest(mapOf("action" to "logout", "token" to token), RequestKind.WRITE))

            wiki.pages
                .edit(page) {
                    text = "Written after the session was lost."
                    summary = "kwikibot local test"
                }
                .shouldBeInstanceOf<EditOutcome.Saved>()
            restored.size shouldBe 1
            wiki.pages.content(page)?.revisionId.shouldNotBeNull()
        }

    @Test
    fun `many texts expand in one request, each as it would alone`(): Unit = onLocalWiki { wiki ->
        val texts = listOf("{{uc:alpha}}", "* {{lc:BETA}}", "{{#if:x|yes|no}}", "plain")

        val alone = texts.map { wiki.pages.expandText(it) }
        sent.clear()
        val together = wiki.pages.expandTexts(texts)

        together.map { it.text } shouldBe alone
        sent.count { it.action == "expandtemplates" } shouldBe 1
    }

    @Test
    fun `redirects to a template are every name it is used under`(): Unit = onLocalWiki { wiki ->
        val name = "Kwikibot test ${UUID.randomUUID()}"
        val template = wiki.ref("Template:$name")
        val redirect = wiki.ref("Template:$name redirect")
        wiki.pages.edit(template) {
            text = "{{{1}}}"
            summary = "kwikibot local test"
        }
        wiki.pages.edit(redirect) {
            text = "#REDIRECT [[Template:$name]]"
            summary = "kwikibot local test"
        }

        wiki.pages.redirectsTo(listOf(template)).getValue(template).map { it.title } shouldBe
            listOf(redirect.title)
        val names = wiki.templateNames(name)
        names shouldBe setOf("Template:$name", "Template:$name redirect")

        val code = Wikitext.parse("{{$name|a}} {{$name redirect|b}} {{other}}", wiki.parseOptions())
        code.templates(names, wiki.titleRules()).map { it.rawValue("1") } shouldBe listOf("a", "b")
    }

    @Test
    fun `a fresh read is the page as it is now`(): Unit = onLocalWiki { wiki ->
        val page = wiki.ref("Kwikibot test ${UUID.randomUUID()}")
        wiki.pages.edit(page) {
            text = "Before."
            summary = "kwikibot local test"
        }
        wiki.pages.contents(listOf(page))
        wiki.pages.edit(page) {
            text = "After."
            summary = "kwikibot local test"
        }

        wiki.pages.freshContents(listOf(page)).getValue(page).text shouldBe "After."
    }

    @Test
    fun `the wiki says how it parses and names pages`(): Unit = onLocalWiki { wiki ->
        val options = wiki.parseOptions()
        val rules = wiki.titleRules()

        options.extensionTags.containsAll(listOf("pre", "nowiki")) shouldBe true
        options.protocols.contains("https://") shouldBe true
        rules.key("Template:Foo_bar") shouldBe "Foo bar"
        rules.isParserFunction("#if:x") shouldBe true
        rules.isParserFunction("PAGENAME") shouldBe true
    }

    private val restored = CopyOnWriteArrayList<String>()

    private val relogins =
        object : TransportListener {
            override fun onRelogin(identity: Identity) {
                restored += identity.name
            }
        }

    private fun parsed(): List<ApiRequest> = sent.filter { it.action == "parse" }

    /** A setting, where the build passes an unset one as empty. */
    private fun environment(name: String): String? = System.getenv(name)?.ifEmpty { null }
}
