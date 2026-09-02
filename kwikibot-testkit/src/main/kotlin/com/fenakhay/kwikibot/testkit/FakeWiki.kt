package com.fenakhay.kwikibot.testkit

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.ExtensionService
import com.fenakhay.kwikibot.client.service.FileService
import com.fenakhay.kwikibot.client.service.ListService
import com.fenakhay.kwikibot.client.service.LogService
import com.fenakhay.kwikibot.client.service.MetaService
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.client.service.ProofreadService
import com.fenakhay.kwikibot.client.service.RenderService
import com.fenakhay.kwikibot.client.service.RevisionService
import com.fenakhay.kwikibot.client.service.UserService
import com.fenakhay.kwikibot.model.LangCode
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.model.title.InterwikiMap
import com.fenakhay.kwikibot.model.title.NamespaceMap
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.ApiRequest
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ParamInfo
import com.fenakhay.kwikibot.protocol.SiteInfo
import kotlinx.serialization.json.JsonObject

/**
 * A wiki that exists only in memory, for testing bots without a wiki.
 *
 * Pages are real: they come from a [FakePageService], so a test can read them, edit them and check what the
 * page ended up saying. Everything else is **not** implemented, and says so by throwing with the name of what
 * was called.
 *
 * That is deliberate. A fake that answers every query with an empty list makes a test pass while the bot does
 * nothing, which is the failure mode a fake is supposed to prevent. A test that needs listing or logs should
 * use a real service against `MockEngine`, or supply its own.
 *
 * ```
 * val wiki = FakeWiki("volcano" to "==English==")
 * wiki.pages.content(wiki.ref("volcano"))
 * ```
 */
public class FakeWiki(
    override val pages: PageService,
    override val id: WikiId = WikiId("testwiki"),
    namespaces: NamespaceMap = NamespaceMap.CANONICAL,
    override val identity: Identity =
        Identity(
            name = "TestBot",
            // Any non-zero id: zero is how the model says "anonymous", and a fake bot is not.
            id = 1,
            groups = setOf("bot"),
        ),
) : Wiki {

    public constructor(vararg texts: Pair<String, String>) : this(pages = FakePageService(texts.toMap()))

    override val info: SiteInfo =
        SiteInfo(
            id = id,
            siteName = "Test Wiki",
            language = LangCode("en"),
            server = "test.example.org",
            articlePath = "/wiki/$1",
            mainPage = "Main Page",
            generator = "MediaWiki 1.47.0",
            namespaces = namespaces,
            interwiki = InterwikiMap.EMPTY,
        )

    override val lists: ListService
        get() = notImplemented("lists")

    override val revisions: RevisionService
        get() = notImplemented("revisions")

    override val users: UserService
        get() = notImplemented("users")

    override val logs: LogService
        get() = notImplemented("logs")

    override val files: FileService
        get() = notImplemented("files")

    override val extensions: ExtensionService
        get() = notImplemented("extensions")

    override val proofread: ProofreadService
        get() = notImplemented("proofread")

    override val renderer: RenderService
        get() = notImplemented("renderer")

    override val meta: MetaService
        get() = notImplemented("meta")

    override val paramInfo: ParamInfo
        get() = ParamInfo(transport)

    /** A transport that refuses, so a test cannot accidentally reach the network through it. */
    override val transport: MediaWikiTransport =
        object : MediaWikiTransport {
            override val endpoint: ApiEndpoint = ApiEndpoint("test.example.org")

            override suspend fun call(request: ApiRequest): JsonObject =
                notImplemented("transport.call(${request.action})")
        }

    override val tokens: TokenStore = TokenStore(transport)

    private fun notImplemented(what: String): Nothing =
        throw NotImplementedError(
            "FakeWiki does not implement $what. Use a real service against MockEngine, or pass one " +
                "in — a fake that answered with an empty result would make this test pass while the " +
                "bot did nothing."
        )
}
