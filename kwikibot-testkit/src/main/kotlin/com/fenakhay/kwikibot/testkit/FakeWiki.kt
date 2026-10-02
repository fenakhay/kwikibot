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
import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.TitleRules
import kotlinx.serialization.json.JsonObject

/**
 * A wiki that exists only in memory, for testing bots without a wiki.
 *
 * Pages are real: they come from a [FakePageService], so a test can read them, edit them and check what the
 * page ended up saying. Every other service is whatever the test hands in. Anything not handed in is not
 * implemented, and says so by throwing with the name of what was called.
 *
 * That is deliberate. A fake that answers every query with an empty list makes a test pass while the bot does
 * nothing, which is the failure mode a fake is supposed to prevent. A test that needs listing or logs passes
 * a service of its own, or a real one built on a [MockTransport].
 *
 * ```
 * val wiki = FakeWiki("volcano" to "==English==")
 * wiki.pages.content(wiki.ref("volcano"))
 * ```
 *
 * @param pages the pages, usually a [FakePageService].
 * @param id the wiki's database name.
 * @param namespaces the namespaces titles are parsed against.
 * @param identity who the fake says is logged in: a bot, by default.
 * @param lists the listing service, or `null` to refuse every call to it.
 * @param revisions the history service, or `null` to refuse.
 * @param users the account service, or `null` to refuse.
 * @param logs the log service, or `null` to refuse.
 * @param renderer the rendering service, or `null` to refuse.
 * @param meta the site-information service, or `null` to refuse.
 * @param transport what [Wiki.transport], [paramInfo] and [tokens] talk to. `null`, the default, is a
 *   transport that refuses, so a test cannot reach the network by accident.
 */
public class FakeWiki(
    override val pages: PageService,
    override val id: WikiId = WikiId("testwiki"),
    namespaces: NamespaceMap = NamespaceMap.CANONICAL,
    override val identity: Identity = TEST_BOT,
    lists: ListService? = null,
    revisions: RevisionService? = null,
    users: UserService? = null,
    logs: LogService? = null,
    renderer: RenderService? = null,
    meta: MetaService? = null,
    transport: MediaWikiTransport? = null,
) : Wiki {

    public constructor(vararg texts: Pair<String, String>) : this(pages = FakePageService(texts.toMap()))

    /** The constructor 1.1 compiled against, kept so code built then still links. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        pages: PageService,
        id: WikiId = WikiId("testwiki"),
        namespaces: NamespaceMap = NamespaceMap.CANONICAL,
        identity: Identity = TEST_BOT,
    ) : this(pages, id, namespaces, identity, lists = null)

    /** The services the test handed in, kept apart from the overrides that fall back to refusing. */
    private val supplied = Supplied(lists, revisions, users, logs, renderer, meta)

    private class Supplied(
        val lists: ListService?,
        val revisions: RevisionService?,
        val users: UserService?,
        val logs: LogService?,
        val renderer: RenderService?,
        val meta: MetaService?,
    )

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
        get() = supplied.lists ?: notImplemented("lists")

    override val revisions: RevisionService
        get() = supplied.revisions ?: notImplemented("revisions")

    override val users: UserService
        get() = supplied.users ?: notImplemented("users")

    override val logs: LogService
        get() = supplied.logs ?: notImplemented("logs")

    override val files: FileService
        get() = notImplemented("files")

    override val extensions: ExtensionService
        get() = notImplemented("extensions")

    override val proofread: ProofreadService
        get() = notImplemented("proofread")

    override val renderer: RenderService
        get() = supplied.renderer ?: notImplemented("renderer")

    override val meta: MetaService
        get() = supplied.meta ?: notImplemented("meta")

    override val paramInfo: ParamInfo
        get() = ParamInfo(transport)

    /**
     * The transport the test supplied, or one that refuses, so a test cannot reach the network by accident.
     */
    override val transport: MediaWikiTransport =
        transport
            ?: object : MediaWikiTransport {
                override val endpoint: ApiEndpoint = ApiEndpoint("test.example.org")

                override suspend fun call(request: ApiRequest): JsonObject =
                    notImplemented("transport.call(${request.action})")
            }

    override val tokens: TokenStore = TokenStore(this.transport)

    /** How Wikimedia's wikis parse, which is what a fake wiki stands in for. */
    override suspend fun parseOptions(): ParseOptions = ParseOptions.DEFAULT

    /** MediaWiki's own naming rules. */
    override suspend fun titleRules(): TitleRules = TitleRules.DEFAULT

    private fun notImplemented(what: String): Nothing =
        throw NotImplementedError(
            "FakeWiki does not implement $what. Pass one in, or use a real service on a MockTransport — a " +
                "fake that answered with an empty result would make this test pass while the bot did nothing."
        )

    private companion object {
        /** Any non-zero id: zero is how the model says "anonymous", and a fake bot is not. */
        val TEST_BOT = Identity(name = "TestBot", id = 1, groups = setOf("bot"))
    }
}
