package com.fenakhay.kwikibot.client

import com.fenakhay.kwikibot.model.WikiError
import com.fenakhay.kwikibot.model.edit.EditOutcome
import com.fenakhay.kwikibot.model.file.UploadOutcome
import com.fenakhay.kwikibot.net.Throttle
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Credentials
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.KtorTransport
import com.fenakhay.kwikibot.net.transport.TransportListener
import com.fenakhay.kwikibot.net.transport.WikiHttpClient
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.HttpHeaders
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * An OAuth 1.0a login against a real MediaWiki, with the OAuth extension checking every signature.
 *
 * Opt-in, like [LocalWikiTest]: `.github/mediawiki/start.sh` starts the wiki, `.github/mediawiki/oauth.sh`
 * installs the extension and prints an owner-only consumer for `Admin` as `KWIKI_MEDIAWIKI_OAUTH_` variables,
 * and this runs once those and `KWIKI_MEDIAWIKI` are set.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "KWIKI_MEDIAWIKI_OAUTH_CONSUMER_KEY", matches = ".+")
class LocalOAuth1Test {

    private val sent = CopyOnWriteArrayList<ApiRequest>()

    private val listener =
        object : TransportListener {
            override fun onResponse(request: ApiRequest, response: JsonObject) {
                sent += request
            }
        }

    private val credentials =
        Credentials.fromEnvironment("KWIKI_MEDIAWIKI").shouldBeInstanceOf<Credentials.OAuth1>()

    private val url = URI(System.getenv("KWIKI_MEDIAWIKI"))

    private val endpoint = ApiEndpoint(url.authority, url.path.orEmpty().trimEnd('/'), url.scheme == "https")

    private val config =
        WikiConfig(
            userAgent = UserAgent("kwikibot-localtest", "0.1.0", "https://github.com/fenakhay/kwikibot"),
            throttle = Throttle(Duration.ZERO, Duration.ZERO),
            listener = listener,
        )

    private fun onLocalWiki(login: Credentials = credentials, block: suspend (Wiki) -> Unit): Unit =
        runBlocking {
            WikiClient(config, login).use { client -> block(client.wiki(endpoint)) }
        }

    @Test
    fun `a signed session is the consumer's owner, with no login sent`(): Unit = onLocalWiki { wiki ->
        wiki.identity.name shouldBe "Admin"
        wiki.identity.isAnonymous shouldBe false
        sent.none { it.action == "login" } shouldBe true
        sent.any { it.params["meta"] == "userinfo" } shouldBe true
    }

    @Test
    fun `a signed edit with a form body full of awkward characters is saved and read back`(): Unit =
        onLocalWiki { wiki ->
            val page = wiki.ref("Kwikibot OAuth ${UUID.randomUUID()}")
            val text = "Café ~ au lait + 1 = 2 & 50% 😀\n[[Link|piped]] {{tl}}"

            wiki.pages
                .edit(page) {
                    this.text = text
                    summary = "kwikibot OAuth 1.0a live test: a+b c~d"
                }
                .shouldBeInstanceOf<EditOutcome.Saved>()

            wiki.pages.content(page)?.text shouldBe text
            wiki.revisions.history(page).let { history ->
                var user: String? = null
                history.collect { user = it.user }
                user shouldBe "Admin"
            }
        }

    @Test
    fun `a read long enough to be posted is signed with its form body`(): Unit = onLocalWiki { wiki ->
        val page = wiki.ref("Kwikibot OAuth read ${UUID.randomUUID()}")
        wiki.pages
            .edit(page) {
                text = "here"
                summary = "kwikibot OAuth 1.0a live test"
            }
            .shouldBeInstanceOf<EditOutcome.Saved>()
        val titles = (1..40).map { "Kwikibot missing page number $it with a title long enough" }

        val found = wiki.pages.contents(titles.map { wiki.ref(it) } + page)

        found.values.map { it.text } shouldBe listOf("here")
        sent.any { it.params["titles"].orEmpty().length > KtorTransport.MAX_GET_LENGTH } shouldBe true
    }

    @Test
    fun `a signed upload goes out as multipart with its body left out of the signature`(): Unit =
        runBlocking {
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
            image.setRGB(3, 3, UUID.randomUUID().hashCode())
            val file = Files.createTempFile("kwikibot-oauth", ".png")
            Files.write(file, ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
            val http = WikiHttpClient.create(credentials = credentials)
            val signedMultipart = CopyOnWriteArrayList<String>()
            http.plugin(HttpSend).intercept { request ->
                val call = execute(request)
                if (request.body is MultiPartFormDataContent) {
                    signedMultipart += call.request.headers[HttpHeaders.Authorization].orEmpty()
                }
                call
            }

            WikiClient(config, credentials, httpClient = http).use { client ->
                val wiki = client.wiki(endpoint)
                val target = wiki.ref("File:Kwikibot OAuth ${UUID.randomUUID()}.png")

                wiki.files
                    .upload(
                        kotlinx.io.files.Path(file.toString()),
                        target,
                        comment = "kwikibot OAuth 1.0a upload",
                    )
                    .shouldBeInstanceOf<UploadOutcome.Uploaded>()

                wiki.files.latest(target)?.size shouldBe Files.size(file)
            }
            http.close()
            signedMultipart.isNotEmpty() shouldBe true
            signedMultipart.forEach { it shouldStartWith "OAuth " }
        }

    @Test
    fun `a wrong secret is refused by the wiki, so the signature is what it checks`() {
        assertFailsWith<WikiError> {
            onLocalWiki(credentials.copy(accessSecret = credentials.accessSecret.reversed())) {}
        }
    }
}
