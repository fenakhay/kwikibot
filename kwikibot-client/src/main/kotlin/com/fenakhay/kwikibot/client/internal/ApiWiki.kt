package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.internal.wire.Capabilities
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
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.net.transport.TransportListener
import com.fenakhay.kwikibot.protocol.ParamInfo
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.decode.ActivityDecoder
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import com.fenakhay.kwikibot.wikitext.ParseOptions
import com.fenakhay.kwikibot.wikitext.TitleRules
import io.ktor.client.HttpClient

internal class ApiWiki(
    override val info: SiteInfo,
    override val identity: Identity,
    override val transport: MediaWikiTransport,
    override val tokens: TokenStore,
    private val http: HttpClient,
    private val endpoint: ApiEndpoint,
    private val userAgent: UserAgent,
    private val onWarning: TransportListener = TransportListener.NONE,
) : Wiki {

    override val id: WikiId
        get() = info.id

    private val decoder = PageDecoder(info.id, info.namespaces)

    /** As many titles per query as the account may name, which for a bot is ten times the default. */
    private val batch = identity.batchLimit

    override val pages: PageService =
        ApiPageService(
            transport = transport,
            tokens = tokens,
            decoder = decoder,
            namespaces = info.namespaces,
            batchSize = batch,
        )

    override val lists: ListService =
        ApiListService(
            transport = transport,
            decoder = decoder,
            namespaces = info.namespaces,
        )

    override val revisions: RevisionService =
        ApiRevisionService(
            transport = transport,
            tokens = tokens,
            decoder = decoder,
            namespaces = info.namespaces,
            batchSize = batch,
        )

    override val users: UserService =
        ApiUserService(
            transport = transport,
            tokens = tokens,
            activity = ActivityDecoder(decoder),
            batchSize = batch,
        )

    override val logs: LogService =
        ApiLogService(
            transport = transport,
            activity = ActivityDecoder(decoder),
            namespaces = info.namespaces,
            identity = identity,
        )

    override val paramInfo: ParamInfo = ParamInfo(transport)

    /** Which spelling of each respelled value this wiki takes, read from [paramInfo] when first needed. */
    private val capabilities = Capabilities(paramInfo, info.server)

    override val renderer: RenderService =
        ApiRenderService(
            transport = transport,
            decoder = decoder,
            namespaces = info.namespaces,
            capabilities = capabilities,
        )

    override val meta: MetaService = ApiMetaService(transport, tokens)

    override val proofread: ProofreadService =
        ApiProofreadService(
            transport = transport,
            decoder = decoder,
            namespaces = info.namespaces,
            info = info,
            batchSize = batch,
        )

    override val extensions: ExtensionService =
        ApiExtensionService(
            transport = transport,
            tokens = tokens,
            decoder = decoder,
            namespaces = info.namespaces,
            info = info,
            capabilities = capabilities,
            batchSize = batch,
        )

    override val files: FileService =
        ApiFileService(
            transport = transport,
            tokens = tokens,
            decoder = decoder,
            namespaces = info.namespaces,
            http = http,
            endpoint = endpoint,
            userAgent = userAgent,
            onWarning = onWarning,
            assertion = "user".takeUnless { identity.isAnonymous },
            batchSize = batch,
        )

    private val parserSettings = ParserSettings(transport)

    override suspend fun parseOptions(): ParseOptions = parserSettings.options()

    override suspend fun titleRules(): TitleRules = parserSettings.rules()

    override fun toString(): String = "Wiki(${info.id} as ${identity.name})"
}
